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
                if ("END_CLIENT_TICK".equals(label) || label.endsWith(".END_CLIENT_TICK")) {
                    progress();
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

    private static void report(Object minecraft) {
        var silent = new ArrayList<String>();
        var wired = 0;
        for (var entry : fired.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                wired++;
            } else {
                silent.add(entry.getKey());
            }
        }

        var canary = "not sent";
        try {
            var connection = minecraft.getClass().getMethod("getConnection").invoke(minecraft);
            
            var player = minecraft.getClass().getField("player").get(minecraft);
            if (canarySent) {
                canary = connection != null && player != null
                        ? "ok (" + (ticksInWorld - canarySentAtTick) + " ticks after sending, still connected)"
                        : "FAILED (disconnected after the canary payload)";
            }
        } catch (Throwable t) {
            canary = "unavailable";
        }

        LOGGER.info("[NeoFobric] Self-check: events {}/{} wired, network canary {}",
                wired, fired.size(), canary);
        if (!silent.isEmpty()) {
            LOGGER.warn("[NeoFobric] Self-check silent event(s): {}. A Fabric event that never fires means the call site "
                    + "NeoForge moved is still missed - see docs/NEOFOBRIC_HANDOFF.md ('调用点搬家').", String.join(", ", silent));
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
