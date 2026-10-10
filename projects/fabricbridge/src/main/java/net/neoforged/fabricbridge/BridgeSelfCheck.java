/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A built-in acceptance check for the bridge's content layer: it registers the same mix of Fabric
 * events the manual probe used to, watches whether they actually fire while the game runs, sends one
 * canary payload over the network, and prints a one-line verdict.
 *
 * <p>Why this exists: the failures this checks for are invisible in logs. When NeoForge moves a call
 * site a Fabric event is injected at, the injection is silently skipped and the event simply never
 * fires - the block-break AFTER event did that for months. And when a play-phase payload reaches the
 * wrong handler, the player is disconnected with an error that names neither the payload nor the
 * bridge; that is how a newly added NeoForge mod (Jade) made its world unenterable. Both classes are
 * only detectable by "register a listener, do the thing, see whether it fires", which is exactly what
 * this check does on every start.
 *
 * <p>Everything is done through reflection: this module is compiled without fabric-api on its
 * classpath (the bridge runs on the bootstrap layer), so the event holders and payload classes are
 * looked up from the game layer at runtime. The check never changes game state - the canary payload
 * is a vanilla {@code minecraft:brand} packet the server ignores - and it can be turned off with
 * {@code -Dneofobric.selfCheck=false}.
 */
public final class BridgeSelfCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger(BridgeSelfCheck.class);

    /** label -> whether the listener fired (null = registration failed, the event holder was missing). */
    private static final Map<String, Boolean> fired = new LinkedHashMap<>();
    private static final List<String> unavailable = new ArrayList<>();

    private static volatile ClassLoader gameLoader;
    private static volatile boolean installed;
    private static volatile int ticksInWorld;
    private static volatile boolean canarySent;
    private static volatile int canarySentAtTick = -1;
    private static volatile boolean reported;
    /** Dedicated servers have no client tick to report from, so their verdict rides the server tick instead. */
    private static volatile int ticksWithPlayer;
    /** The labels whose events only ever fire on a client - everything else is the server side's business. */
    private static final java.util.Set<String> CLIENT_SIDE_EVENTS =
            java.util.Set.of("ClientLifecycle.CLIENT_STARTED", "ClientTick.END_CLIENT_TICK");

    private BridgeSelfCheck() {}

    /**
     * Logs how many entrypoints each stage has, once, at boot.
     *
     * <p>This is the cheap answer to "is a whole loader feature missing": fabric-api's server-side game
     * tests are declared through the {@code fabric-gametest} entrypoint, and a count of zero there means the
     * test server runs vanilla's single smoke test and nothing else, with no warning anywhere.
     */
    private static void logEntrypointInventory() {
        var inventory = new StringBuilder("[NeoFobric] Fabric entrypoints:");
        for (var key : new String[] { "preLaunch", "main", "client", "server", "fabric-gametest", "fabric-client-gametest" }) {
            try {
                var count = net.fabricmc.loader.impl.FabricLoaderImpl.INSTANCE
                        .getEntrypointContainers(key, Object.class).size();
                inventory.append(' ').append(key).append('=').append(count);
            } catch (Throwable t) {
                inventory.append(' ').append(key).append("=?");
            }
        }
        LOGGER.info(inventory.toString());
    }

    /**
     * Logs what Fabric's networking layer ended up holding: the payload types mods declared per direction,
     * and how many global channel receivers are registered on each side.
     *
     * <p>This is the networking half of the entrypoint inventory, and it belongs after the entrypoints have
     * run - at boot the payloads are not registered yet, so every count would read zero. Callers invoke it
     * once the mod-initializer stage is done ({@code FabricBridge#invokeEntrypoints}) and again after the
     * client stage, so the second line shows what the client entrypoints added.
     *
     * <p>What it proves: a mod whose networking setup silently never ran - the failure mode that cost a
     * whole day on Jade, where the packets only blew up at play time - shows up here as a smaller count or
     * a zero, instead of as a stack trace in the middle of a session.
     */
    public static void logNetworkingInventory() {
        if (!enabled() || !FabricBridge.hasFabricMods()) {
            return;
        }
        if (gameLoader == null) {
            try {
                gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            } catch (Throwable t) {
                gameLoader = Thread.currentThread().getContextClassLoader();
            }
        }
        var parts = new ArrayList<String>();

        // The per-direction payload type counts live in an internal map; the loader's own API has no count,
        // and a missing field here (a fabric-api release that renames it) must not break anything.
        for (var field : new String[] { "SERVERBOUND_PLAY", "CLIENTBOUND_PLAY", "SERVERBOUND_CONFIGURATION", "CLIENTBOUND_CONFIGURATION" }) {
            try {
                var registryClass = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl", false, gameLoader);
                var registry = registryClass.getField(field).get(null);
                var packetTypes = registryClass.getDeclaredField("packetTypes");
                packetTypes.setAccessible(true);
                parts.add(field.toLowerCase(java.util.Locale.ROOT) + "=" + ((java.util.Map<?, ?>) packetTypes.get(registry)).size());
            } catch (Throwable t) {
                parts.add(field.toLowerCase(java.util.Locale.ROOT) + "=?");
            }
        }

        for (var side : new String[] { "serverbound", "clientbound" }) {
            var holderName = "serverbound".equals(side)
                    ? "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking"
                    : "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking";
            try {
                var holder = Class.forName(holderName, false, gameLoader);
                var receivers = (java.util.Set<?>) holder.getMethod("getGlobalReceivers").invoke(null);
                parts.add(side + " receivers=" + receivers.size());
            } catch (Throwable t) {
                parts.add(side + " receivers=?");
            }
        }

        LOGGER.info("[NeoFobric] Fabric networking: {}", String.join(", ", parts));
    }

    private static boolean enabled() {
        return !"false".equals(System.getProperty("neofobric.selfCheck"));
    }

    /**
     * Registers the acceptance listeners once. Safe to call repeatedly; only files if Fabric mods are
     * loaded (its event classes come from fabric-api).
     */
    public static void install() {
        if (installed || !enabled() || !FabricBridge.hasFabricMods()) {
            return;
        }
        synchronized (BridgeSelfCheck.class) {
            if (installed) {
                return;
            }
            installed = true;
            // The event holders live in the game layer (fabric-api modules), so resolve them through
            // the launcher's target loader - the bridge itself sits on the bootstrap layer and cannot
            // see them.
            try {
                gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            } catch (Throwable t) {
                gameLoader = null;
            }
            if (gameLoader == null) {
                gameLoader = Thread.currentThread().getContextClassLoader();
            }

            logEntrypointInventory();

            // Server side: lifecycle, ticks, world/chunk/entity/block-entity loading, player join,
            // command registration and loot table loading all happen naturally when a world opens.
            register("ServerLifecycle.SERVER_STARTED", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STARTED");
            register("ServerTick.END_SERVER_TICK", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents", "END_SERVER_TICK");
            register("ServerLevel.LOAD", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents", "LOAD");
            register("ServerChunk.CHUNK_LOAD", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents", "CHUNK_LOAD");
            register("ServerEntity.ENTITY_LOAD", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents", "ENTITY_LOAD");
            register("ServerBlockEntity.BLOCK_ENTITY_LOAD", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents", "BLOCK_ENTITY_LOAD");
            register("ServerPlayConnection.JOIN", "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents", "JOIN");
            register("CommandRegistration.EVENT", "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback", "EVENT");
            register("LootTable.ALL_LOADED", "net.fabricmc.fabric.api.loot.v3.LootTableEvents", "ALL_LOADED");

            // Client side: enough to prove the client event wiring and to drive the run ticks below.
            register("ClientLifecycle.CLIENT_STARTED", "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents", "CLIENT_STARTED");
            register("ClientTick.END_CLIENT_TICK", "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents", "END_CLIENT_TICK");

            LOGGER.info("[NeoFobric] Self-check armed: {} event(s) registered, {} unavailable",
                    fired.size(), unavailable.size());
        }
    }

    /**
     * Registers a proxy listener for one Fabric event field. The listener records that it fired; the
     * client-tick one additionally drives the reporting (it is the only event that fires often enough
     * to time the checks from without another hook).
     */
    private static void register(String label, String holderClassName, String fieldName) {
        try {
            var holder = Class.forName(holderClassName, false, gameLoader);
            var field = holder.getField(fieldName);
            var event = field.get(null);
            var listenerType = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];

            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> label + " self-check listener";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                        default -> null;
                    };
                }
                fired.put(label, Boolean.TRUE);
                if (label.endsWith(".END_CLIENT_TICK")) {
                    progress();
                } else if (label.endsWith(".END_SERVER_TICK")) {
                    progressServer();
                } else if (label.endsWith(".CLIENT_STARTED")) {
                    componentProbe("client started");
                } else if (label.endsWith(".ALL_LOADED")) {
                    // A loot table of Twilight Forest's fails to parse with "Unknown registry key
                    // minecraft:entity_sub_predicate_type: minecraft:type_specific", which means the static
                    // registry was missing vanilla's own entry when the tables were decoded. This probe
                    // answers whether it is there at that moment (see -Dneofobric.registryProbe).
                    registryProbe("loot tables loaded");
                }
                return defaultValue(method.getReturnType());
            };

            var proxy = Proxy.newProxyInstance(gameLoader, new Class<?>[] { listenerType }, handler);
            var eventInterface = Class.forName("net.fabricmc.fabric.api.event.Event", false, gameLoader);
            eventInterface.getMethod("register", Object.class).invoke(event, proxy);
            fired.put(label, Boolean.FALSE);
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            unavailable.add(label);
        } catch (Throwable t) {
            unavailable.add(label);
            LOGGER.debug("[NeoFobric] Self-check could not register {}", label, t);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) return Boolean.FALSE;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return (char) 0;
        return null;
    }

    /**
     * The dedicated-server half of {@link #progress()}: no client tick exists there and the canary is a
     * client-to-server packet, so once a player has been online long enough for chunks, entities and loot
     * tables to load, the wiring verdict is reported without it. A client's integrated server keeps the
     * client path, which counts the same way and knows the canary's outcome.
     */
    private static void progressServer() {
        if (reported || gameLoader == null || !isDedicatedServer()) {
            return;
        }
        try {
            if (!Boolean.TRUE.equals(fired.get("ServerPlayConnection.JOIN"))) {
                return;
            }
            if (++ticksWithPlayer < 200) {
                return;
            }
            reported = true;
            report(null);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Self-check server progress failed", t);
        }
    }

    /** {@return whether this JVM is a dedicated server} Never throws: the check must not be able to break a run. */
    private static boolean isDedicatedServer() {
        try {
            return net.neoforged.fml.loading.FMLEnvironment.getDist() == net.neoforged.api.distmarker.Dist.DEDICATED_SERVER;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Runs from the client tick listener: counts ticks spent with a player in a world, sends the
     * network canary shortly after joining and prints the verdict once enough ticks have passed for
     * everything (chunks, entities, loot tables) to have loaded.
     */
    private static void progress() {
        if (reported || gameLoader == null) {
            return;
        }
        try {
            var minecraft = Class.forName("net.minecraft.client.Minecraft", false, gameLoader);
            var instance = minecraft.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return;
            }
            var player = minecraft.getField("player").get(instance);
            if (player == null) {
                return;
            }
            ticksInWorld++;
            if (ticksInWorld == 1 || ticksInWorld == 200) {
                registryProbe("client tick " + ticksInWorld);
            }

            // The transfer bridge's probe rides the same tick: it needs a loaded world for the same reason
            // the rest of the checks do, and it runs once (it is what the run is here to report).
            FabricTransferHooks.probeInWorldOnce();

            schematicProgressProbe(instance);
            schematicUploadDriver(instance);

            if (!canarySent && ticksInWorld == 20) {
                canarySent = true;
                canarySentAtTick = ticksInWorld;
                sendCanary(instance);
            }

            if (ticksInWorld == 200) {
                reported = true;
                report(instance);
            }
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Self-check progress failed", t);
        }
    }

    /**
     * Sends one vanilla {@code minecraft:brand} payload from the client. It travels the exact path a
     * NeoForge-native mod's payload takes (serverbound custom payload in the play phase), and the
     * server ignores its content - so the only observable outcome is whether the connection survives,
     * which is what the report checks. Before the bridge handled play-phase payload routing, any such
     * payload disconnected the player with "Unknown addon".
     */
    private static void sendCanary(Object minecraft) {
        try {
            var packetClass = Class.forName("net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket", false, gameLoader);
            var brandClass = Class.forName("net.minecraft.network.protocol.common.custom.BrandPayload", false, gameLoader);
            var brand = brandClass.getConstructor(String.class).newInstance("neofobric/self-check");
            var packet = packetClass.getConstructor(Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload", false, gameLoader)).newInstance(brand);

            var connection = minecraft.getClass().getMethod("getConnection").invoke(minecraft);
            if (connection == null) {
                canarySent = false;
                return;
            }
            connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet", false, gameLoader)).invoke(connection, packet);
            LOGGER.debug("[NeoFobric] Self-check canary payload sent");
        } catch (Throwable t) {
            canarySent = false;
            LOGGER.debug("[NeoFobric] Self-check could not send the canary payload", t);
        }
    }

    /**
     * Prints whether a static registry holds vanilla's own entry at a given moment, for the loot table that
     * fails to decode with {@code Unknown registry key minecraft:entity_sub_predicate_type: type_specific}.
     * Enabled with {@code -Dneofobric.registryProbe=true}; silent otherwise.
     */
    static void registryProbe(String where) {
        if (!Boolean.getBoolean("neofobric.registryProbe")) {
            return;
        }
        try {
            var registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, gameLoader);
            var registry = registries.getField("ENTITY_SUB_PREDICATE_TYPE").get(null);
            var id = Class.forName("net.minecraft.resources.Identifier", false, gameLoader)
                    .getMethod("parse", String.class).invoke(null, "minecraft:type_specific");
            var size = (int) registry.getClass().getMethod("size").invoke(registry);
            var found = registry.getClass().getMethod("getOptional", id.getClass()).invoke(registry, id);
            var present = found instanceof java.util.Optional<?> optional && optional.isPresent();
            LOGGER.info("[NeoFobric] registry probe at {}: ENTITY_SUB_PREDICATE_TYPE size={} has type_specific={}",
                    where, size, present);
            // The world load of a Fabric mod's data fails with "Unknown registry key in ResourceKey[
            // minecraft:root / minecraft:worldgen/carver]: betterend:end_round_cave" and "Unbound values in
            // registry ... configured_carver", so this asks whether the code that registers a carver landed
            // at all.
            var carvers = registries.getField("CARVER").get(null);
            var carverId = Class.forName("net.minecraft.resources.Identifier", false, gameLoader)
                    .getMethod("parse", String.class).invoke(null, "betterend:end_round_cave");
            var carverSize = (int) carvers.getClass().getMethod("size").invoke(carvers);
            var carverFound = carvers.getClass().getMethod("getOptional", carverId.getClass()).invoke(carvers, carverId);
            var keys = (java.util.Set<?>) carvers.getClass().getMethod("keySet").invoke(carvers);
            LOGGER.info("[NeoFobric] registry probe at {}: CARVER size={} has betterend:end_round_cave={} keys={}",
                    where, carverSize,
                    carverFound instanceof java.util.Optional<?> optional && optional.isPresent(),
                    keys.stream().map(String::valueOf).limit(12).sorted().toList());
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] registry probe failed at {}", where, t);
        }
    }

    /**
     * Asks whether the vanilla component codec knows a mod's own component type, which is what a mod's
     * {@code @Mixin} into {@code ComponentSerialization#bootstrap} registers.
     *
     * <p>Why this exists: Create writes a recipe's assembly steps into the result's {@code minecraft:lore} as
     * a component whose argument is its own {@code {"ingredient": "#tag"}} type. It can encode that type
     * either way, but decoding needs the type registered - so when the registration is missing, Create's
     * {@code Recipe.CODEC.parse(...).getOrThrow()} throws instead of logging, and the whole world load
     * fails with "Failed to load level data or datapacks". Nothing in the log says which of the two
     * halves is missing, and {@code FuzzyCodec} throws away the reason as well, so this probe decodes an
     * empty component: only a registered content type with two optional fields can accept it.
     *
     * <p>Enabled with {@code -Dneofobric.componentProbe=true}; silent otherwise. No registries are touched,
     * so it can run before a world exists.
     */
    static void componentProbe(String where) {
        if (!Boolean.getBoolean("neofobric.componentProbe")) {
            return;
        }
        try {
            // A config Mixin has not prepared yet is one whose mixins no already-loaded class can have
            // received - the count is 0 when the bridge's forced preparation did its job.
            LOGGER.info("[NeoFobric] component probe at {}: Mixin has {} config(s) still unvisited", where,
                    org.spongepowered.asm.mixin.Mixins.getUnvisitedCount());
            var codec = Class.forName("net.minecraft.network.chat.ComponentSerialization", false, gameLoader)
                    .getField("CODEC").get(null);
            var ops = Class.forName("com.mojang.serialization.JsonOps", false, gameLoader).getField("INSTANCE").get(null);
            var parse = Class.forName("com.mojang.serialization.Codec", false, gameLoader)
                    .getMethod("parse", Class.forName("com.mojang.serialization.DynamicOps", false, gameLoader), Object.class);
            var jsonParser = Class.forName("com.google.gson.JsonParser", false, gameLoader)
                    .getMethod("parseString", String.class);
            for (var text : new String[] { "{}", "{\"text\":\"probe\"}", "{\"ingredient\":\"#create:track_nuggets\"}" }) {
                var json = jsonParser.invoke(null, text);
                LOGGER.info("[NeoFobric] component probe at {}: {} -> {}", where, text,
                        describeDataResult(parse.invoke(codec, ops, json)));
            }
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] component probe failed at {}", where, t);
        }
    }

    private static String describeDataResult(Object result) {
        try {
            if ((boolean) result.getClass().getMethod("isSuccess").invoke(result)) {
                var value = result.getClass().getMethod("result").invoke(result);
                var contents = value instanceof java.util.Optional<?> optional && optional.isPresent()
                        ? optional.get().getClass()
                        : value;
                return "ok (" + contents + ")";
            }
            var error = result.getClass().getMethod("error").invoke(result);
            return "failed: " + (error instanceof java.util.Optional<?> optional ? optional.map(String::valueOf).orElse("?") : error);
        } catch (Throwable t) {
            return "unreadable: " + t;
        }
    }

    /** The last line the schematic-table probe printed, so it only logs when something really changes. */
    private static volatile String schematicProbeLast;

    /**
     * Answers where Create's schematic-table upload progress gets lost: the screen holds a rendered bar whose
     * width follows {@code chasingProgress}, which follows the block entity's {@code uploadingProgress} - and
     * the block entity value arrives over the platform's block-entity sync. Reading both on every tick says
     * whether the value never arrives (sync) or arrives and is not drawn (the bar itself).
     *
     * <p>On by default, but silent unless a schematic table screen is open: it then prints a line whenever one
     * of the numbers changes, and a heartbeat every five seconds, so "the screen is open and nothing moves" is
     * visible too. Turn it off with {@code -Dneofobric.schematicProbe=false}.
     */
    static void schematicProgressProbe(Object minecraft) {
        if (Boolean.getBoolean("neofobric.noSchematicProbe")) {
            return;
        }
        try {
            var screen = member(minecraft, "screen");
            if (screen == null || !screen.getClass().getName().contains("SchematicTable")) {
                schematicProbeLast = null;
                schematicProbeHeartbeat = 0;
                return;
            }
            var line = new StringBuilder("[NeoFobric] schematic probe:")
                    .append(" progress=").append(member(screen, "progress"))
                    .append(" chasing=").append(member(screen, "chasingProgress"))
                    .append(" lastChasing=").append(member(screen, "lastChasingProgress"));
            var menu = member(screen, "menu");
            var holder = menu == null ? null : member(menu, "contentHolder");
            line.append(" holder=").append(holder == null ? "null" : holder.getClass().getSimpleName());
            if (holder != null) {
                line.append(" isUploading=").append(member(holder, "isUploading"))
                        .append(" uploadingProgress=").append(member(holder, "uploadingProgress"))
                        .append(" uploadingSchematic=").append(member(holder, "uploadingSchematic"))
                        .append(" sendUpdate=").append(member(holder, "sendUpdate"));
                // If the screen's block entity is not the one the client level holds, no sync can ever reach
                // the copy it reads from - which would look exactly like "the bar never fills".
                var level = member(minecraft, "level");
                var pos = member(holder, "worldPosition");
                if (level != null && pos != null) {
                    try {
                        var fromLevel = invoke(level, "getBlockEntity", pos);
                        line.append(" sameInstanceAsLevel=").append(fromLevel == holder);
                    } catch (Throwable t) {
                        line.append(" sameInstanceAsLevel=?");
                    }
                }
            }
            var text = line.toString();
            if (!text.equals(schematicProbeLast) || ++schematicProbeHeartbeat >= 100) {
                schematicProbeLast = text;
                schematicProbeHeartbeat = 0;
                LOGGER.info(text);
            }
        } catch (Throwable t) {
            // A diagnostic must never be able to break a run.
            LOGGER.debug("[NeoFobric] schematic probe failed", t);
        }
    }

    private static int schematicProbeHeartbeat;
    /** Set by the upload driver so the probe also reports the client-side block entity, screen or not. */
    private static volatile Object schematicProbePos;

    /**
     * Drives one schematic upload from the server side, so the progress path can be observed without anyone
     * clicking through the blueprint table's GUI.
     *
     * <p>Enabled with {@code -Dneofobric.schematicTest=true}: it puts a schematic table next to the player,
     * feeds Create's own {@code ServerSchematicLoader} the bytes of a real schematic in the game's
     * {@code schematics} directory, and lets the probe report what the client sees. Everything goes through
     * Create's public API, so what it exercises is exactly what a real upload exercises - only the file
     * chooser and the button are skipped.
     */
    static void schematicUploadDriver(Object minecraft) {
        if (!Boolean.getBoolean("neofobric.schematicTest")) {
            return;
        }
        try {
            // Everything that touches a server level, a server player or an upload has to run on the server
            // thread - doing it from the client tick (this method's own thread) is what stalled an earlier run.
            if (schematicTestStep <= 1) {
                var server = member(minecraft, "singleplayerServer");
                if (server == null) {
                    server = invoke(minecraft, "getSingleplayerServer");
                }
                if (server == null) {
                    return;
                }
                var serverThread = server;
                invoke(serverThread, "execute", (Runnable) () -> runSchematicTestServerSteps(serverThread));
                return;
            }
            switch (schematicTestStep) {
                case 2 -> {
                    if (++schematicTestWait < 100) {
                        return;
                    }
                    LOGGER.info("[NeoFobric] schematic test: verdict - server side {} | client side {}",
                            schematicTestSummary(), clientBlockEntitySummary());
                    schematicTestStep = 99;
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            schematicTestStep = 99;
            LOGGER.warn("[NeoFobric] schematic test driver failed", t);
        }
    }

    private static void runSchematicTestServerSteps(Object server) {
        if (schematicTestStep > 1) {
            return;
        }
        try {
            switch (schematicTestStep) {
                case 0 -> {
                    var players = invoke(server, "getPlayerList");
                    var list = players == null ? null : invoke(players, "getPlayers");
                    var player = list instanceof java.util.List<?> l && !l.isEmpty() ? l.get(0) : null;
                    if (player == null) {
                        return;
                    }
                    schematicTestPlayer = player;
                    var level = invoke(player, "level");
                    var file = schematicTestFile();
                    if (level == null || file == null) {
                        LOGGER.warn("[NeoFobric] schematic test: no level or no schematic file to send");
                        schematicTestStep = 99;
                        return;
                    }
                    var pos = invoke(player, "blockPosition");
                    var posClass = Class.forName("net.minecraft.core.BlockPos", false, gameLoader);
                    var tablePos = posClass
                            .getConstructor(int.class, int.class, int.class)
                            .newInstance((int) invoke(pos, "getX") + 1, (int) invoke(pos, "getY"),
                                    (int) invoke(pos, "getZ"));
                    // Look for a table the player already stands next to; otherwise put one there. The block
                    // comes from the registry by name, so no mod class is named anywhere in this driver.
                    var be = findSchematicTable(level, pos, posClass);
                    if (be == null) {
                        var block = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, gameLoader)
                                .getField("BLOCK").get(null);
                        var id = Class.forName("net.minecraft.resources.Identifier", false, gameLoader)
                                .getMethod("parse", String.class).invoke(null, "create:schematic_table");
                        var value = invoke(block, "getValue", id);
                        var valueName = String.valueOf(value);
                        if (value == null || valueName.contains("air") || valueName.contains("Air")) {
                            LOGGER.warn("[NeoFobric] schematic test: create:schematic_table is not registered ({})",
                                    valueName);
                            schematicTestStep = 99;
                            return;
                        }
                        var state = invoke(value, "defaultBlockState");
                        invoke(level, "setBlockAndUpdate", tablePos, state);
                        be = invoke(level, "getBlockEntity", tablePos);
                    }
                    LOGGER.info("[NeoFobric] schematic test: table at {} -> {}", tablePos,
                            be == null ? "no block entity" : be.getClass().getName());
                    schematicProbePos = tablePos;
                    schematicTestBe = be;
                    schematicTestBytes = java.nio.file.Files.readAllBytes(file);
                    LOGGER.info("[NeoFobric] schematic test: sending {} bytes of {} as {}", schematicTestBytes.length,
                            file.getFileName(), schematicTestName);
                    var loader = Class.forName("com.zurrtum.create.Create", false, gameLoader)
                            .getField("SCHEMATIC_RECEIVER").get(null);
                    invoke(loader, "handleNewUpload", player, schematicTestName, (long) schematicTestBytes.length, tablePos);
                    schematicTestStep = 1;
                }
                case 1 -> {
                    var loader = Class.forName("com.zurrtum.create.Create", false, gameLoader)
                            .getField("SCHEMATIC_RECEIVER").get(null);
                    var chunkSize = 1024;
                    while (schematicTestOffset < schematicTestBytes.length) {
                        var end = Math.min(schematicTestOffset + chunkSize, schematicTestBytes.length);
                        var chunk = java.util.Arrays.copyOfRange(schematicTestBytes, schematicTestOffset, end);
                        invoke(loader, "handleWriteRequest", schematicTestPlayer, schematicTestName, chunk);
                        schematicTestOffset = end;
                    }
                    LOGGER.info("[NeoFobric] schematic test: all {} bytes written; block entity now says {}",
                            schematicTestBytes.length, schematicTestSummary());
                    schematicTestStep = 2;
                }
                case 2 -> {
                    if (++schematicTestWait < 100) {
                        return;
                    }
                    LOGGER.info("[NeoFobric] schematic test: verdict - server side {} | client side {}",
                            schematicTestSummary(), clientBlockEntitySummary());
                    schematicTestStep = 99;
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            schematicTestStep = 99;
            LOGGER.warn("[NeoFobric] schematic test driver failed", t);
        }
    }

    private static int schematicTestStep;
    private static int schematicTestOffset;
    private static int schematicTestWait;
    private static Object schematicTestPlayer;
    private static Object schematicTestBe;
    private static byte[] schematicTestBytes = new byte[0];
    private static final String schematicTestName = "neofobric-probe.nbt";

    /** {@return the newest schematic in the game directory, or null} */
    private static java.nio.file.Path schematicTestFile() {
        try {
            var dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("schematics");
            if (!java.nio.file.Files.isDirectory(dir)) {
                return null;
            }
            try (var stream = java.nio.file.Files.list(dir)) {
                return stream.filter(p -> p.getFileName().toString().endsWith(".nbt"))
                        .min(java.util.Comparator.comparingLong(p -> -p.toFile().lastModified()))
                        .orElse(null);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@return a schematic table block entity within a few blocks of the player, or null} */
    private static Object findSchematicTable(Object level, Object pos, Class<?> posClass) {
        try {
            var x = (int) invoke(pos, "getX");
            var y = (int) invoke(pos, "getY");
            var z = (int) invoke(pos, "getZ");
            var constructor = posClass.getConstructor(int.class, int.class, int.class);
            for (var dx = -8; dx <= 8; dx++) {
                for (var dy = -4; dy <= 4; dy++) {
                    for (var dz = -8; dz <= 8; dz++) {
                        var candidate = constructor.newInstance(x + dx, y + dy, z + dz);
                        var be = invoke(level, "getBlockEntity", candidate);
                        if (be != null && be.getClass().getName().contains("SchematicTableBlockEntity")) {
                            LOGGER.info("[NeoFobric] schematic test: using the table already at {}", candidate);
                            return be;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] schematic test: no table found nearby", t);
        }
        return null;
    }

    private static String schematicTestSummary() {
        var be = schematicTestBe;
        return be == null ? "<none>"
                : "isUploading=" + member(be, "isUploading") + " progress=" + member(be, "uploadingProgress")
                        + " sendUpdate=" + member(be, "sendUpdate");
    }

    /**
     * {@return what the <em>client</em> block entity at the test position says}
     *
     * <p>This is the value the bar on the screen is drawn from, so comparing it with the server side is the
     * whole question: equal means the sync works and the bar's own drawing is at fault, different means the
     * update never arrives.
     */
    private static String clientBlockEntitySummary() {
        try {
            var minecraft = Class.forName("net.minecraft.client.Minecraft", false, gameLoader)
                    .getMethod("getInstance").invoke(null);
            var level = member(minecraft, "level");
            var pos = schematicProbePos;
            if (level == null || pos == null) {
                return "<no client level>";
            }
            var be = invoke(level, "getBlockEntity", pos);
            if (be == null) {
                return "<no block entity on the client>";
            }
            return "sameInstanceAsServer=" + (be == schematicTestBe)
                    + " isUploading=" + member(be, "isUploading")
                    + " progress=" + member(be, "uploadingProgress")
                    + " username=" + member(be, "uploadingSchematic");
        } catch (Throwable t) {
            return "<unreadable: " + t + ">";
        }
    }

    /**
     * Calls a method by name and arguments: the first method whose parameter count matches and whose
     * parameter types accept the arguments wins. Kept deliberately dumb - an earlier version tried to take
     * explicit parameter types as leading arguments and got the two mixed up.
     */
    private static Object invoke(Object owner, String name, Object... args) throws ReflectiveOperationException {
        if (owner == null) {
            throw new NoSuchMethodException(name + " on null");
        }
        for (var type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (var method : type.getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                    continue;
                }
                if (!accepts(method.getParameterTypes(), args)) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(owner, args);
            }
        }
        throw new NoSuchMethodException(name + "/" + args.length);
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        for (var i = 0; i < types.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (types[i].isPrimitive()) {
                if (!(args[i] instanceof Number || args[i] instanceof Boolean)) {
                    return false;
                }
                continue;
            }
            if (!types[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    /** Reads a field from an object or any of its superclasses - the Create screens keep theirs private. */
    private static Object member(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException | IllegalAccessException | RuntimeException e) {
                // keep walking
            }
        }
        return null;
    }

    private static void report(Object minecraft) {        var silent = new ArrayList<String>();
        var wired = 0;
        for (var entry : fired.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                wired++;
            } else {
                silent.add(entry.getKey());
            }
        }

        // A client talking to a server it does not host never sees the server side's events, and that is
        // not the silent wiring this check is looking for - keep the two apart or the warning below cries
        // wolf on every multiplayer join.
        var remoteOnly = new ArrayList<String>();
        if (minecraft != null) {
            try {
                var hosts = (boolean) minecraft.getClass().getMethod("hasSingleplayerServer").invoke(minecraft);
                if (!hosts) {
                    silent.removeIf(label -> {
                        if (CLIENT_SIDE_EVENTS.contains(label)) {
                            return false;
                        }
                        remoteOnly.add(label);
                        return true;
                    });
                }
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Self-check could not tell whether this client hosts its server", t);
            }
        }

        var canary = minecraft == null ? "not applicable (dedicated server)" : "not sent";
        try {
            if (minecraft != null) {
                var connection = minecraft.getClass().getMethod("getConnection").invoke(minecraft);

                var player = minecraft.getClass().getField("player").get(minecraft);
                if (canarySent) {
                    canary = connection != null && player != null
                            ? "ok (" + (ticksInWorld - canarySentAtTick) + " ticks after sending, still connected)"
                            : "FAILED (disconnected after the canary payload)";
                }
            }
        } catch (Throwable t) {
            canary = "unavailable";
        }

        LOGGER.info("[NeoFobric] Self-check: events {}/{} wired{}, network canary {}",
                wired, fired.size(),
                remoteOnly.isEmpty() ? "" : " (" + remoteOnly.size() + " of the rest belong to the server joined)",
                canary);
        if (!silent.isEmpty()) {
            LOGGER.warn("[NeoFobric] Self-check silent event(s): {}. A Fabric event that never fires means the call site "
                    + "NeoForge moved is still missed - see docs/NEOFOBRIC_HANDOFF.md ('调用点搬家').", String.join(", ", silent));
        }
        if (!remoteOnly.isEmpty()) {
            LOGGER.info("[NeoFobric] Self-check: {} event(s) belong to the server this client joined, so they are not "
                    + "expected to fire here: {}", remoteOnly.size(), String.join(", ", remoteOnly));
        }
        if (!unavailable.isEmpty()) {
            LOGGER.info("[NeoFobric] Self-check skipped event(s) whose holder class is absent: {}", String.join(", ", unavailable));
        }

        // The game's log config does not carry this logger's output into latest.log (the same appender
        // behaviour that swallowed the manual probe's output), so the verdict is also written to a file
        // of our own, next to the mod list manifest - that one is always readable after a run.
        try {
            var file = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve(".neofobric").resolve("self-check.txt");
            java.nio.file.Files.createDirectories(file.getParent());
            var text = new StringBuilder();
            text.append("NeoFobric self-check ").append(java.time.LocalDateTime.now()).append('\n')
                    .append("events: ").append(wired).append('/').append(fired.size()).append(" wired\n");
            for (var entry : fired.entrySet()) {
                text.append("  ").append(Boolean.TRUE.equals(entry.getValue()) ? "fired  " : "SILENT ").append(' ').append(entry.getKey()).append('\n');
            }
            for (var label : unavailable) {
                text.append("  absent   ").append(label).append('\n');
            }
            text.append("network canary: ").append(canary).append('\n');
            java.nio.file.Files.writeString(file, text.toString(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Self-check could not write its report file", t);
        }
    }
}
