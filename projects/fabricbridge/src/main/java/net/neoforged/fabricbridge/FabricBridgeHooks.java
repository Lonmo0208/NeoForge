/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.minecraft.Hooks;

/**
 * The hand-off points between NeoForge/Minecraft and the fabric-loader runtime.
 *
 * <p>{@link #runFabricEntrypoints()} is called by NeoForge's mod loader
 * ({@code CommonModLoader#begin}) inside the registry-initialization window, where the registries
 * are open, so Fabric mods can register content exactly like they do in a pure Fabric
 * environment. On a client run that window is stretched over the client entrypoints with
 * {@link #deferRegistryFreeze(Runnable)} and closed again in {@link #attachGameInstance(Object)}.
 * The remaining methods are invoked from small runtime patches the bridge applies to Minecraft
 * classes (game instance hand-off and server branding).
 */
public final class FabricBridgeHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricBridgeHooks.class);

    /** NeoForge's {@code GameData.freezeData}, held here while the registry window stays open. */
    private static volatile Runnable deferredRegistryFreeze;

    /**
     * The fluid attributes Fabric's fluid-behaviour registry declares, keyed by the fluid itself. Built on
     * first use; {@link #fabricFluidBehavior(Object)}.
     */
    private static volatile java.util.Map<Object, FabricFluidBehavior> fabricFluidBehaviors;

    private FabricBridgeHooks() {}

    /**
     * {@return whether the bridge has discovered Fabric mods and should inject itself into the game}
     */
    public static boolean bridgeActive() {
        return FabricBridge.hasFabricMods();
    }

    /**
     * Boots the fabric-loader runtime (if needed) and invokes the Fabric entrypoints
     * ({@code preLaunch}, {@code main} and the side entrypoint). Called while the registries are
     * unfrozen, right after NeoForge's own registration events.
     */
    public static void runFabricEntrypoints() {
        if (!bridgeActive()) {
            return;
        }

        FabricBridge.bootIfNeeded();
        FabricBridge.invokeEntrypoints();
        runHooksTheGameReachesTooEarly();
        traceFabricPaths();
    }

    private static void traceFabricPaths() {
        if (!Boolean.getBoolean("neofobric.traceTags")) {
            return;
        }

        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader", false, modsLoader);
            var getInstance = fabricLoader.getMethod("getInstance").invoke(null);
            var optionalContainer = fabricLoader.getMethod("getModContainer", String.class).invoke(getInstance, "minecraft");
            var modContainer = optionalContainer.getClass().getMethod("orElseThrow").invoke(optionalContainer);
            var getRootPaths = Class.forName("net.fabricmc.loader.api.ModContainer", false, modsLoader).getMethod("getRootPaths");
            var findPath = Class.forName("net.fabricmc.loader.api.ModContainer", false, modsLoader).getMethod("findPath", String.class);
            LOGGER.info("[NeoFobric] minecraft mod content roots: {}", getRootPaths.invoke(modContainer));
            LOGGER.info("[NeoFobric] minecraft mod content roots (walked now): {}", BridgedGameProvider.minecraftPaths());
            for (var file : new String[] {
                    "data/minecraft/tags/block/diamond_ores.json",
                    "data/c/tags/block/ores.json",
                    "data/c/tags/block/ores/diamond.json" }) {
                LOGGER.info("[NeoFobric] findPath({}) = {}", file, findPath.invoke(modContainer, file));
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not trace the minecraft mod's paths", e);
        }
    }

    /**
     * Re-runs the Fabric API hooks the game reaches before the Fabric entrypoints have registered their
     * listeners.
     *
     * <p>Fabric API hangs some of its bulk registration work off moments FML gets to while it is still loading
     * mods. fabric-object-builder-api-v1 emits its {@code ModifyDefaultAttribute} event from the head of
     * {@code BuiltInRegistries.freeze()}, and the vanilla bootstrap - which FML runs before the mod
     * initialisers - reaches that freeze long before these entrypoints, so the listener list is still empty at
     * that moment and every modification a Fabric mod registers is skipped ("Pig does not have test
     * attribute"). Replaying the hook now that the listeners exist applies them.
     *
     * <p>Must run after the entrypoints and before NeoForge's attribute pass
     * ({@code CommonHooks.modifyAttributes}), which copies the vanilla map - the one Fabric writes into - over.
     * Repeating it is harmless: the event rebuilds a supplier from the one already registered.
     */
    private static void runHooksTheGameReachesTooEarly() {
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var registry = Class.forName("net.fabricmc.fabric.impl.object.builder.FabricDefaultAttributeRegistryImpl", false, modsLoader);
            registry.getMethod("invokeModify").invoke(null);
        } catch (ClassNotFoundException e) {
            // fabric-object-builder-api-v1 is not in this mod set
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not replay Fabric API's default-attribute modification", e);
        }
    }

    /**
     * Takes the registry freeze over for a client run, so the Fabric client entrypoints - which can
     * only run from inside the Minecraft constructor - still see open registries.
     *
     * <p>Registering content from a client entrypoint is normal on the Fabric side (particle types
     * are the classic case, Fabric API's own particle test mod does it), but NeoForge closes the
     * registries while the game is still loading, long before any client entrypoint can run.
     * Fabric's own registry sync arrives at the same place from the other side: it moves the
     * registry freeze to after the mod initialisers. The bridge therefore takes the freeze here and
     * runs it as soon as the client entrypoints are through ({@link #attachGameInstance}), keeping
     * the registries open in the meantime.
     *
     * <p>The action is NeoForge's own {@code GameData.freezeData}, passed as a callback to keep
     * this module free of compile-time neoforge dependencies; being a lambda of NeoForge's class
     * loader, it also freezes the registries the game actually uses, whatever class loader the
     * bridge itself was defined in.
     *
     * @param freezeAction what NeoForge would have run at the end of its registry window
     * @return whether the freeze was taken over; {@code false} means the caller keeps it and must
     *         freeze as it always did
     */
    public static boolean deferRegistryFreeze(Runnable freezeAction) {
        if (!bridgeActive() || Boolean.getBoolean("neofobric.noClientEntrypoints")) {
            return false;
        }

        deferredRegistryFreeze = freezeAction;
        LOGGER.info("[NeoFobric] Registries stay open until the Fabric client entrypoints have run");
        return true;
    }

    /**
     * Runs the freeze that {@link #deferRegistryFreeze(Runnable)} held back, if there is one. Called
     * once the client entrypoints are through; harmless when the freeze was never deferred.
     */
    private static void closeDeferredRegistryFreeze() {
        var freezeAction = deferredRegistryFreeze;
        if (freezeAction == null) {
            return;
        }

        deferredRegistryFreeze = null;
        freezeAction.run();
        LOGGER.info("[NeoFobric] Registries frozen after the Fabric client entrypoints");
    }

    /**
     * {@return whether this class belongs to a mod that was written for Fabric}
     *
     * <p>Fabric mods live in the game content layer the bridge registers for them
     * ({@link net.neoforged.fabricbridge.discovery.BridgeFabricModLocator#FABRIC_MODULE_NAME}), which is a
     * module of its own inside the game class loader - the one reliable mark that survives a class being
     * handed around as a {@code Class<?>}.
     */
    public static boolean isFabricModClass(Class<?> clazz) {
        var module = clazz.getModule();
        return module != null
                && net.neoforged.fabricbridge.discovery.BridgeFabricModLocator.FABRIC_MODULE_NAME.equals(module.getName());
    }

    /**
     * Names of Fabric's payload type registries, one per protocol and side, inside
     * {@code PayloadTypeRegistryImpl}. A payload type only exists in the registry of the side that
     * registered it, so an absent one (the client registries on a server, say) is simply skipped.
     */
    private static final String[] FABRIC_PAYLOAD_TYPE_REGISTRIES = {
            "SERVERBOUND_CONFIGURATION", "CLIENTBOUND_CONFIGURATION", "SERVERBOUND_PLAY", "CLIENTBOUND_PLAY"
    };

    /** {@code (registry, get(Identifier))} pairs of the registries above, resolved once. */
    private static volatile java.util.List<java.util.function.Predicate<String>> fabricChannelTests;

    /**
     * Fabric's own channel-registration channels. Fabric moves its channel declarations over these two ids,
     * and the class tweaker renames the constants to exactly these values so they no longer collide with
     * NeoForge's {@code minecraft:register}/{@code minecraft:unregister} negotiation (different wire formats
     * under the same ids). Fabric does register payload types for them, so the lookup below would find them
     * anyway - they are listed here as well so the bridge does not depend on that staying true.
     */
    private static final java.util.Set<String> FABRIC_CONTROL_CHANNELS =
            java.util.Set.of("minecraft:fabric_register", "minecraft:fabric_unregister");

    /**
     * {@return whether this channel is one of Fabric's modded payload types}
     *
     * <p>NeoForge refuses to let a mod send a custom payload to a peer that has not negotiated the channel
     * in <em>its</em> registration system, and it dies with
     * {@code "Payload x may not be sent to the client!"}. Fabric has no such membership test: a mod
     * registers its payload type with {@code PayloadTypeRegistry}, then it may send - the peer decodes it as
     * long as it registered the same type, and a receiver is not required for sending at all (Fabric API's
     * context test sends a payload whose handler is registered per connection, which no declaration ever
     * mentions). The set of registered payload types is therefore the right question to ask, and
     * {@code PayloadTypeRegistry} answers it on both sides. Fabric API asserts a payload type before it
     * accepts a receiver for it, so this set covers the receivers too.
     *
     * @param channelName the channel id as {@code namespace:path}
     */
    public static boolean isFabricChannel(String channelName) {
        if (!bridgeActive() || channelName == null) {
            return false;
        }

        if (FABRIC_CONTROL_CHANNELS.contains(channelName)) {
            return true;
        }

        if (channelName.startsWith("minecraft:")) {
            // Vanilla's own channels: Fabric API refuses to let a mod register a payload type in the
            // minecraft namespace, so nothing else can be in here.
            return false;
        }

        for (var isFabricType : fabricChannelTests()) {
            if (isFabricType.test(channelName)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<java.util.function.Predicate<String>> fabricChannelTests() {
        var resolved = fabricChannelTests;
        if (resolved != null) {
            return resolved;
        }

        var tests = new java.util.ArrayList<java.util.function.Predicate<String>>(FABRIC_PAYLOAD_TYPE_REGISTRIES.length);
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var payloadTypeRegistry = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl", false, modsLoader);
            var identifier = Class.forName("net.minecraft.resources.Identifier", false, modsLoader);
            var parseIdentifier = identifier.getMethod("tryParse", String.class);
            var get = payloadTypeRegistry.getMethod("get", identifier);

            for (var fieldName : FABRIC_PAYLOAD_TYPE_REGISTRIES) {
                java.lang.Object registry;
                try {
                    registry = payloadTypeRegistry.getField(fieldName).get(null);
                } catch (NoSuchFieldException e) {
                    continue;
                }
                if (registry == null) {
                    continue;
                }
                tests.add(name -> {
                    try {
                        var id = parseIdentifier.invoke(null, name);
                        return id != null && get.invoke(registry, id) != null;
                    } catch (Throwable t) {
                        return false;
                    }
                });
            }
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not reach Fabric's payload type registries for the channel lookup", t);
        }

        resolved = java.util.List.copyOf(tests);
        fabricChannelTests = resolved;
        return resolved;
    }

    /**
     * Hands the constructed game instance to the fabric-loader runtime and, on the client, runs the
     * Fabric client entrypoints. Injected into the client {@code Minecraft} constructor.
     *
     * <p>The client entrypoints have to run here rather than in {@link #runFabricEntrypoints()}:
     * that happens during NeoForge's registry window, where no game instance exists yet, while the
     * entrypoints legitimately use the client (Sodium's config builder reads
     * {@code Minecraft.getInstance().getWindow()}). They equally cannot wait until after the
     * constructor, because Sodium's {@code Minecraft#setFullscreen} mixin reads the client config
     * <em>during</em> the same constructor - so the entrypoint that registers it must already have
     * run. This insertion point sits after the game instance is assigned and before the window is
     * created, which satisfies both.
     *
     * <p>Because NeoForge's registries stay open until exactly this point on a client run
     * ({@link #deferRegistryFreeze}), the freeze is run here, directly after the entrypoints.
     */
    public static void attachGameInstance(Object gameInstance) {
        if (!bridgeActive()) {
            return;
        }

        if (!Boolean.getBoolean("neofobric.noPrepareModInit")) {
            try {
                FabricBridge.bootIfNeeded();
                FabricLoaderImpl.INSTANCE.prepareModInit(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get(), gameInstance);
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Failed to attach the game instance to Fabric Loader", t);
            }
        }

        if (!Boolean.getBoolean("neofobric.noClientEntrypoints")) {
            try {
                FabricBridge.invokeClientEntrypoints();
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Failed to run the Fabric client entrypoints", t);
            }
        }

        // The client entrypoints are the last Fabric-side chance to register content, so this is where
        // the registry window that runFabricEntrypoints left open closes again (see deferRegistryFreeze).
        closeDeferredRegistryFreeze();
    }

    /**
     * Hands the dedicated server instance to the fabric-loader runtime. Injected into the server
     * main class where the server object is constructed.
     */
    public static void setServerGameInstance(Object gameInstance) {
        if (!bridgeActive()) {
            return;
        }

        try {
            FabricLoaderImpl.INSTANCE.setGameInstance(gameInstance);
        } catch (IllegalStateException e) {
            // Already set; harmless.
            LOGGER.debug("[NeoFobric] Game instance was already set", e);
        } catch (Throwable t) {
            LOGGER.error("[NeoFobric] Failed to set the server game instance on Fabric Loader", t);
        }
    }

    /**
     * Appends {@code fabric} to the game's brand. Injected into
     * {@code MinecraftServer#getServerModName} and {@code ClientBrandRetriever#getClientModName},
     * mirroring fabric-loader's {@code BrandingPatch}.
     */
    public static String insertBranding(String brand) {
        if (!bridgeActive()) {
            return brand;
        }
        return Hooks.insertBranding(brand);
    }

    /** Guards the fallback path so the redirect only consults Fabric once per lookup. */
    private static final ThreadLocal<Boolean> IN_CODEC_LOOKUP = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * {@return Fabric's codec for this payload id, or NeoForge's own lookup when Fabric has none}
     *
     * <p>Fabric and NeoForge both claim {@code minecraft:register}, but with different payload classes -
     * Fabric's {@code RegistrationPayload} versus NeoForge's {@code MinecraftRegisterPayload}. Without
     * this, NeoForge's encoder resolves the id to its own class and the cast fails with
     * {@code ClassCastException}, killing the connection in the configuration phase so that no world can
     * be entered, singleplayer included.
     *
     * <p>The fallback re-enters {@code NetworkRegistry#getCodec}, which has been redirected here; the
     * flag makes that second entry run NeoForge's original logic. Both sides are reached reflectively so
     * this class keeps no compile-time dependency on either, and any failure just falls back to NeoForge.
     */
    public static Object fabricPayloadCodec(Object id, Object protocol, Object flow) {
        if (bridgeActive() && !IN_CODEC_LOOKUP.get()) {
            IN_CODEC_LOOKUP.set(Boolean.TRUE);
            try {
                var fromFabric = lookupFabricCodec(id, protocol, flow);
                if (fromFabric != null) {
                    return fromFabric;
                }
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Fabric payload codec lookup failed for {}", id, t);
            } finally {
                IN_CODEC_LOOKUP.set(Boolean.FALSE);
            }
            // Not a Fabric payload: hand the lookup back to NeoForge, which now runs unredirected.
            return lookupNeoForgeCodec(id, protocol, flow);
        }
        if (bridgeActive() && IN_CODEC_LOOKUP.get()) {
            return lookupNeoForgeCodec(id, protocol, flow);
        }
        return null;
    }

    private static Object lookupNeoForgeCodec(Object id, Object protocol, Object flow) {
        try {
            return Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry")
                    .getMethod("getCodec",
                            Class.forName("net.minecraft.resources.Identifier"),
                            Class.forName("net.minecraft.network.ConnectionProtocol"),
                            Class.forName("net.minecraft.network.protocol.PacketFlow"))
                    .invoke(null, id, protocol, flow);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object lookupFabricCodec(Object id, Object protocol, Object flow) throws Exception {
        var registries = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl");
        var protocolName = String.valueOf(protocol);
        var flowName = String.valueOf(flow);

        String field;
        if (protocolName.endsWith("CONFIGURATION")) {
            field = flowName.endsWith("SERVERBOUND") ? "SERVERBOUND_CONFIGURATION" : "CLIENTBOUND_CONFIGURATION";
        } else if (protocolName.endsWith("PLAY")) {
            field = flowName.endsWith("SERVERBOUND") ? "SERVERBOUND_PLAY" : "CLIENTBOUND_PLAY";
        } else {
            return null;
        }

        var registry = registries.getField(field).get(null);
        var typeAndCodec = registry.getClass()
                .getMethod("get", Class.forName("net.minecraft.resources.Identifier"))
                .invoke(registry, id);
        if (typeAndCodec == null) {
            return null;
        }
        var codec = typeAndCodec.getClass().getMethod("codec").invoke(typeAndCodec);
        // Widen the payload type: it was registered as StreamCodec<B, T>, while NeoForge asks for
        // StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload>.
        return codec.getClass()
                .getMethod("map", java.util.function.Function.class, java.util.function.Function.class)
                .invoke(codec, java.util.function.Function.identity(), java.util.function.Function.identity());
    }

    /**
     * What Fabric's fluid-behaviour registry says about one fluid.
     *
     * <p>Fabric declares a fluid's physics as a {@code FluidBehavior} registered against a fluid tag, and keeps
     * it to itself: nothing in Minecraft reads it. NeoForge reads the same physics off a {@code FluidType}, so
     * a fluid a Fabric mod registered is described here for NeoForge to build that type from.
     *
     * @param behavior             Fabric's own behaviour object, handed back to {@link #moveInFabricFluid} when
     *                             the physics it describes has to run
     * @param tag                  the fluid tag that behaviour was registered against, likewise handed back
     * @param waterLike            whether the fluid was registered as water-like, in which case NeoForge's own
     *                             water type describes it exactly and is reused
     * @param canSwim              whether entities can swim in it
     * @param canDrown             whether entities drown in it
     * @param canPushEntity        whether its current pushes entities along
     * @param supportsBoating      whether boats float on it
     * @param motionScale          how strongly that current pulls, as Fabric spells it
     * @param fallDistanceModifier what it multiplies an entity's fall distance by
     */
    public record FabricFluidBehavior(Object behavior, Object tag, boolean waterLike, boolean canSwim, boolean canDrown,
            boolean canPushEntity, boolean supportsBoating, double motionScale, float fallDistanceModifier) {}

    /**
     * {@return what Fabric's fluid-behaviour registry declares for this fluid, or {@code null} when it
     * declares nothing about it}
     *
     * <p>The lookup is built once from the registry's fluid tags, because Fabric keys the declaration by tag
     * while NeoForge keys everything by fluid: each tracked tag's behavior is copied onto every fluid the tag
     * contains. Fluids whose behavior is a plain {@code FluidBehavior} implementation - anything not built
     * through Fabric's own builder - cannot be described in these terms and are reported as unknown, which
     * leaves them on NeoForge's empty type as before.
     */
    public static FabricFluidBehavior fabricFluidBehavior(Object fluid) {
        var resolved = fabricFluidBehaviors;
        if (resolved == null) {
            resolved = collectFabricFluidBehaviors();
            // An empty result is kept out of the cache: fluid tags are bound by the first data-pack load, and a
            // lookup that ran before that (or while the registry was being filled) must not be remembered.
            if (!resolved.isEmpty()) {
                fabricFluidBehaviors = resolved;
            }
        }
        return resolved.get(fluid);
    }

    private static java.util.Map<Object, FabricFluidBehavior> collectFabricFluidBehaviors() {
        var byFluid = new java.util.IdentityHashMap<Object, FabricFluidBehavior>();
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var registry = Class.forName("net.fabricmc.fabric.api.registry.fluid.EntityFluidInteractionRegistry", false, modsLoader);
            var tagKey = Class.forName("net.minecraft.tags.TagKey", false, modsLoader);
            var getFluidBehavior = registry.getMethod("getFluidBehavior", tagKey);
            var waterLike = Class.forName("net.fabricmc.fabric.api.registry.fluid.FluidBehavior", false, modsLoader)
                    .getField("WATER_LIKE").get(null);
            var fluidRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, modsLoader)
                    .getField("FLUID").get(null);
            var getTagOrEmpty = fluidRegistry.getClass().getMethod("getTagOrEmpty", tagKey);
            var holderValue = Class.forName("net.minecraft.core.Holder", false, modsLoader).getMethod("value");

            for (var tag : (Iterable<?>) registry.getMethod("getCustomInteractableFluids").invoke(null)) {
                var behavior = getFluidBehavior.invoke(null, tag);
                if (behavior == null) {
                    continue;
                }
                var described = describeFabricFluidBehavior(behavior, tag, behavior == waterLike);
                if (described == null) {
                    continue;
                }

                for (var holder : (Iterable<?>) getTagOrEmpty.invoke(fluidRegistry, tag)) {
                    byFluid.put(holderValue.invoke(holder), described);
                }
            }
        } catch (ClassNotFoundException e) {
            // fabric-content-registries-v0 is not in this mod set
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not read Fabric's fluid behaviour registry", e);
        }
        return byFluid;
    }

    private static FabricFluidBehavior describeFabricFluidBehavior(Object behavior, Object tag, boolean waterLike) {
        var type = behavior.getClass();
        try {
            return new FabricFluidBehavior(
                    behavior,
                    tag,
                    waterLike,
                    (boolean) type.getMethod("allowSwimming").invoke(behavior),
                    (boolean) type.getMethod("drowning").invoke(behavior),
                    (double) type.getMethod("flowingPushScale").invoke(behavior) != 0,
                    (boolean) type.getMethod("allowBoats").invoke(behavior),
                    (double) type.getMethod("flowingPushScale").invoke(behavior),
                    (float) type.getMethod("fallDistanceMultiplier").invoke(behavior));
        } catch (NoSuchMethodException e) {
            // Not one of Fabric's own behaviours (its builder's record); nothing to translate.
            return null;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not read a Fabric fluid behaviour", e);
            return null;
        }
    }

    /**
     * Runs the movement Fabric declared for one of its fluids.
     *
     * <p>NeoForge hands movement in a modded fluid to the fluid type ({@code FluidType#move}), and everything a
     * Fabric mod declared about that movement - its gravity multiplier, its speed and slowdown, whether mobs
     * float in it - lives in its behaviour, so the two are joined here: a fluid NeoForge built a type for runs
     * Fabric's method, and the entity is moved exactly as it would be on Fabric.
     *
     * <p>{@code isFalling} and {@code oldY} are the two values Fabric's method takes that NeoForge's hook does
     * not pass. They are recomputed here from the entity at the same point {@code LivingEntity#travelInFluid}
     * computes its own, which is where the hook is called from, so the method sees what it would see there.
     *
     * @return whether Fabric handled the movement
     */
    public static boolean moveInFabricFluid(Object behavior, Object tag, Object entity, Object movementVector,
            double gravity, boolean isFalling, double oldY) {
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var behaviorInterface = Class.forName("net.fabricmc.fabric.api.registry.fluid.FluidBehavior", false, modsLoader);
            // Resolved on the interface rather than on the implementation: Fabric's behaviours are often
            // package-private records or lambdas, and a public method of a non-public class cannot be called
            // from here - only the interface is reachable.
            var travelInFluid = behaviorInterface.getMethod("travelInFluid",
                    Class.forName("net.minecraft.tags.TagKey", false, modsLoader),
                    Class.forName("net.minecraft.world.entity.LivingEntity", false, modsLoader),
                    Class.forName("net.minecraft.world.phys.Vec3", false, modsLoader),
                    double.class,
                    boolean.class,
                    double.class);
            travelInFluid.invoke(behavior, tag, entity, movementVector, gravity, isFalling, oldY);
            return true;
        } catch (ClassNotFoundException e) {
            // fabric-content-registries-v0 is not in this mod set
            return false;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not run a Fabric fluid behaviour for {}", entity, e);
            return false;
        }
    }

    /**
     * {@return the state a Fabric mod registered as this state's stripped form, or {@code null} when the
     * Fabric side has nothing registered for it}
     *
     * <p>Fabric keeps a stripping transformer per block instead of a plain block, because a registration can
     * ask for the original state's properties to be copied. Its own call site sits in
     * {@code AxeItem#getStripped}, a method NeoForge's tree no longer calls, so it has to be asked for the
     * result explicitly.
     */
    public static Object strippedStateFromFabric(Object state) {
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var registry = Class.forName("net.fabricmc.fabric.impl.content.registry.StrippableBlockRegistryImpl", false, modsLoader);
            var blockState = Class.forName("net.minecraft.world.level.block.state.BlockState", false, modsLoader);
            return registry.getMethod("getStrippedBlockState", blockState).invoke(null, state);
        } catch (ClassNotFoundException e) {
            // fabric-content-registries-v0 is not in this mod set
            return null;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not ask Fabric's strippable-block registry for {}", state, e);
            return null;
        }
    }

    /**
     * Applies Fabric API's fuel events to a fuel table NeoForge assembled from its own data map, in Fabric's
     * own order.
     *
     * <p>Fabric fires them from inside {@code FuelValues#vanillaBurnTimes}: the build callbacks add fuels,
     * then vanilla excludes the non-flammable wood tag, then the exclusion callbacks run. NeoForge builds
     * that table from the furnace-fuel data map instead (which is why the exclusions the Fabric side expects
     * are replayed here too), and a Fabric mod has no way to write a data map at runtime - so without this
     * its fuels would never burn and its exclusions never apply.
     *
     * <p>The base smelt time is passed in because Fabric's callbacks scale their values by it and the data
     * map path has no equivalent parameter of its own.
     */
    public static void applyFabricFuelEvents(Object builder, Object registries, Object features, int baseSmeltTime) {
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var events = Class.forName("net.fabricmc.fabric.api.registry.FuelValueEvents", false, modsLoader);
            var context = Class.forName("net.fabricmc.fabric.impl.content.registry.FuelRegistryEventsContextImpl", false, modsLoader)
                    .getConstructor(
                            Class.forName("net.minecraft.core.HolderLookup$Provider", false, modsLoader),
                            Class.forName("net.minecraft.world.flag.FeatureFlagSet", false, modsLoader),
                            int.class)
                    .newInstance(registries, features, baseSmeltTime);

            callEvent(events.getField("BUILD").get(null), "build", builder, context);
            builder.getClass().getMethod("remove", Class.forName("net.minecraft.tags.TagKey", false, modsLoader))
                    .invoke(builder, Class.forName("net.minecraft.tags.ItemTags", false, modsLoader)
                            .getField("NON_FLAMMABLE_WOOD").get(null));
            callEvent(events.getField("EXCLUSIONS").get(null), "buildExclusions", builder, context);
        } catch (ClassNotFoundException e) {
            // fabric-content-registries-v0 is not in this mod set
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not apply Fabric API's fuel events", e);
        }
    }

    private static void callEvent(Object event, String methodName, Object... args) throws ReflectiveOperationException {
        var invoker = event.getClass().getMethod("invoker").invoke(event);
        // Resolved on the callback interface rather than on the implementation: Fabric's events are backed by
        // lambdas, and a public method of a lambda's (non-public) class cannot be called from here.
        for (var type : invoker.getClass().getInterfaces()) {
            for (var method : type.getMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                    method.invoke(invoker, args);
                    return;
                }
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    /** Renders one HUD layer; Fabric's elements are run around it. */
    @FunctionalInterface
    public interface HudRenderCallback {
        void render(Object graphics, Object deltaTracker);
    }
    /** A chain of Fabric HUD elements, ready to render around one layer. */
    @FunctionalInterface
    public interface FabricHudElements {
        void render(Object graphics, Object deltaTracker);
    }

    /**
     * {@return Fabric's own identifier for one of its HUD element ids, or {@code null} when it has no constant
     * with that value}
     *
     * <p>Resolved through Fabric's constants rather than parsed from the string on purpose: the registry is
     * keyed by identity, so a freshly parsed identifier finds nothing.
     */
    private static Object fabricElementId(String id, ClassLoader modsLoader, Class<?> identifierType) throws ReflectiveOperationException {
        var constants = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements", false, modsLoader);
        for (var field : constants.getFields()) {
            var value = field.get(null);
            if (value != null && identifierType.isInstance(value) && value.toString().equals(id)) {
                return value;
            }
        }
        return null;
    }

    /**
     * {@return the Fabric HUD elements registered around one vanilla HUD layer, run around {@code vanilla}, or
     * {@code null} when the Fabric side has nothing registered there}
     *
     * <p>Fabric's HUD API is organised around the vanilla layers: a mod attaches its element before or after
     * one of them, or replaces it, and Fabric wraps the vanilla render call to run them. That wrapping is done
     * by a mixin into {@code Hud#extractRenderState}, which no longer calls the layers individually - NeoForge
     * renders them through {@code GuiLayerManager} - so this side of the bridge hands the same information
     * over one layer at a time instead, and the caller passes the layer's own render in as the element Fabric
     * registered its own around.
     *
     * <p>The vanilla render is wrapped in a proxy implementing Fabric's element interface, so a replacement
     * registered through Fabric's API composes with it the same way it does on the Fabric side.
     */
    public static FabricHudElements fabricHudElements(String fabricElementId, HudRenderCallback vanilla) {
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var identifierType = Class.forName("net.minecraft.resources.Identifier", false, modsLoader);
            var elementType = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement", false, modsLoader);
            var layerType = Class.forName("net.fabricmc.fabric.impl.client.rendering.hud.HudLayer", false, modsLoader);
            var registry = Class.forName("net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl", false, modsLoader);
            var id = fabricElementId(fabricElementId, modsLoader, identifierType);
            if (id == null) {
                return null;
            }
            var root = registry.getMethod("getRoot", identifierType).invoke(null, id);
            if (root == null) {
                return null;
            }

            var vanillaElement = java.lang.reflect.Proxy.newProxyInstance(modsLoader, new Class<?>[] { elementType },
                    (proxy, method, args) -> {
                        if (method.getName().equals("extractRenderState") && args != null && args.length == 2) {
                            vanilla.render(args[0], args[1]);
                        }
                        return null;
                    });

            // A root starts out holding just the vanilla layer, and holds one layer per registered element -
            // so a root whose single layer still hands back exactly the element it was given, and is not
            // removed, has nothing to run. Resolved on the interface: the layers are anonymous classes.
            var layers = (java.util.List<?>) root.getClass().getMethod("layers").invoke(root);
            if (layers.size() == 1) {
                var onlyLayer = layers.get(0);
                var isRemoved = (boolean) layerType.getMethod("isRemoved").invoke(onlyLayer);
                var passedThrough = layerType.getMethod("element", elementType).invoke(onlyLayer, vanillaElement);
                if (!isRemoved && passedThrough == vanillaElement) {
                    return null;
                }
            }

            var extractRenderState = root.getClass().getMethod("extractRenderState",
                    Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor", false, modsLoader),
                    Class.forName("net.minecraft.client.DeltaTracker", false, modsLoader),
                    elementType);
            return (graphics, deltaTracker) -> {
                try {
                    extractRenderState.invoke(root, graphics, deltaTracker, vanillaElement);
                } catch (ReflectiveOperationException e) {
                    LOGGER.warn("[NeoFobric] Could not render Fabric's HUD elements for {}", fabricElementId, e);
                }
            };
        } catch (ClassNotFoundException e) {
            // fabric-rendering-v1 is not in this mod set
            return null;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not read Fabric's HUD element registry", e);
            return null;
        }
    }
}
