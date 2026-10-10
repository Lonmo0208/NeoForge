/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fabricbridge.discovery.FabricJarRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boots the fabric-loader runtime inside the running NeoForge instance.
 *
 * <p>The loader is booted lazily, once the FML game content layer (which contains all Fabric mod
 * classes) exists, and its classpath management is routed through {@link BridgedLauncher}: Fabric
 * mod jars are already part of the game layer, so {@code addToClassPath} only has to recognise
 * them. Everything else (discovery, dependency resolution, language adapters, entrypoints) is
 * plain fabric-loader behaviour driven by a minimal {@link BridgedGameProvider} that describes the
 * already-running Minecraft instance instead of launching one.
 */
public final class FabricBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricBridge.class);

    private static final Object BOOT_LOCK = new Object();
    private static volatile boolean booted;
    private static volatile boolean entrypointsInvoked;
    private static volatile boolean clientEntrypointsInvoked;

    private FabricBridge() {}

    /**
     * {@return whether the bridge should act at all}
     *
     * <p>{@code -Dneofobric.disabled=true} switches the whole bridge off without touching the jars.
     * It exists for A/B diagnosis: running the same NeoForge build with and without the bridge
     * separates a bridge defect from an upstream problem, which a "remove the mods" test cannot do
     * because that also removes the Fabric mods themselves.
     */
    public static boolean isDisabled() {
        return Boolean.getBoolean("neofobric.disabled");
    }

    public static boolean hasFabricMods() {
        return !isDisabled() && FabricJarRegistry.hasFabricMods();
    }

    public static boolean isBooted() {
        return booted;
    }

    /**
     * Registers the bridged launcher and game provider with fabric-loader, so its static state is
     * usable from the earliest Minecraft hooks (e.g. registry-sync's server Main mixin). Safe to call
     * repeatedly; the full loader boot happens later in {@link #bootIfNeeded()}.
     *
     * <p>The game provider is installed here, as early as the class-processor link, because
     * installing it is also what gives fabric-loader its game directory - and Mixin reads a config's
     * plugin the moment it first selects that config, which is at the transition of the first game
     * class, long before the loader boot below. A plugin whose static initializer asks fabric-loader
     * for the game directory (iris's {@code IrisMixinPlugin} reads {@code options.txt} to decide
     * between its Vulkan and OpenGL mixin sets) otherwise dies with
     * {@code IllegalStateException: invoked too early?}; Mixin then drops the plugin and applies
     * <em>every</em> mixin of the config, so both mutually exclusive sets ran and the duplicate
     * keybind registration killed the client on startup.
     */
    public static void ensureLauncherRegistered() {
        if (net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher() == null) {
            net.fabricmc.loader.impl.launch.FabricLauncherBase.setLauncher(
                    new BridgedLauncher(toEnvType(FMLEnvironmentHolder.dist())));
        }
        if (FabricLoaderImpl.INSTANCE.tryGetGameProvider() == null) {
            FabricLoaderImpl.INSTANCE.setGameProvider(new BridgedGameProvider());
        }
    }

    public static void bootIfNeeded() {
        if (booted || !hasFabricMods()) {
            return;
        }

        synchronized (BOOT_LOCK) {
            if (booted) {
                return;
            }

            var start = System.currentTimeMillis();
            ensureLauncherRegistered();

            FabricLoaderImpl.INSTANCE.load();
            FabricLoaderImpl.INSTANCE.freeze();
            booted = true;

            // Read every mod's access widener / class tweaker into Fabric's own ClassTweaker. Knot does
            // this as part of its launch and nothing else does, so without this call the rules stay
            // empty and any mod that widens or un-finals a game class fails later - iris declares
            // "extendable class net/minecraft/client/OptionInstance" and dies defining its subclass
            // with "cannot inherit from final class". NeoFobricClassTweakerProcessor applies the rules
            // to the game classes as FML transforms them.
            try {
                FabricLoaderImpl.INSTANCE.loadClassTweakers();
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Failed to read the Fabric access wideners; mods that widen game classes will misbehave", t);
            }

            LOGGER.info("[NeoFobric] Fabric Loader {} started with {} Fabric mod(s) in {} ms",
                    FabricLoaderImpl.VERSION, FabricLoaderImpl.INSTANCE.getAllMods().size(), System.currentTimeMillis() - start);
        }
    }

    /**
     * Invokes the Fabric entrypoints exactly once. Called after {@link #bootIfNeeded()}.
     */
    public static void invokeEntrypoints() {
        if (entrypointsInvoked) {
            return;
        }
        synchronized (BOOT_LOCK) {
            if (entrypointsInvoked) {
                return;
            }

            var loader = FabricLoaderImpl.INSTANCE;
            var gameDir = FMLPaths.GAMEDIR.get();

            loader.prepareModInit(gameDir, null);
            invokeIsolated("preLaunch", net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint.class,
                    net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint::onPreLaunch);
            invokeIsolated("main", net.fabricmc.api.ModInitializer.class, net.fabricmc.api.ModInitializer::onInitialize);

            if (FMLEnvironmentHolder.dist() != Dist.CLIENT) {
                invokeIsolated("server", net.fabricmc.api.DedicatedServerModInitializer.class,
                        net.fabricmc.api.DedicatedServerModInitializer::onInitializeServer);
                entrypointsInvoked = true;
            } else {
                // Client entrypoints need a usable Minecraft instance and window, which do not exist
                // yet; FabricBridgeHooks#onClientInitialized triggers them from the client's first
                // screen instead. The registry window is over by then, so Fabric mods that register
                // content in their client entrypoint would be too late - the same limitation Fabric
                // mods have when they register content outside of ModInitializer.
                LOGGER.info("[NeoFobric] Deferring Fabric client entrypoints until the client is set up");
            }

            LOGGER.info("[NeoFobric] Fabric entrypoints invoked");
        }
    }

    /**
     * Invokes the Fabric client entrypoints exactly once, from the client's first screen.
     */
    public static void invokeClientEntrypoints() {
        if (!hasFabricMods()) {
            return;
        }
        synchronized (BOOT_LOCK) {
            if (clientEntrypointsInvoked) {
                return;
            }
            clientEntrypointsInvoked = true;
            invokeIsolated("client", net.fabricmc.api.ClientModInitializer.class,
                    net.fabricmc.api.ClientModInitializer::onInitializeClient);
            LOGGER.info("[NeoFobric] Fabric client entrypoints invoked");
        }
    }

    /**
     * Invokes every entrypoint of the given stage, isolating failures: one broken entrypoint is
     * logged and skipped instead of aborting the whole loading process. Fabric API modules with
     * NeoForge-incompatible internals (e.g. parts of the rendering stack) then degrade to
     * "feature unavailable" instead of taking the game down.
     */
    private static <T> void invokeIsolated(String key, Class<T> type, java.util.function.Consumer<? super T> invoker) {
        var containers = new java.util.ArrayList<>(FabricLoaderImpl.INSTANCE.getEntrypointContainers(key, type));
        pinSodiumBeforeIndigo(containers);
        for (var container : containers) {
            try {
                var modId = container.getProvider().getMetadata().getId();
                if (yieldsRendererToSodium(key, modId)) {
                    LOGGER.info("[NeoFobric] Skipping the '{}' entrypoint of '{}' - Sodium registers the"
                            + " FRAPI renderer, and the registry accepts only one", key, modId);
                    continue;
                }
                invoker.accept(container.getEntrypoint());
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Entrypoint '{}' of Fabric mod '{}' failed and was skipped",
                        key, container.getProvider().getMetadata().getId(), t);
            }
        }
    }

    /**
     * Runs Sodium's entrypoints before Indigo's.
     *
     * <p>Fabric's renderer registry accepts exactly one rendering plug-in and throws
     * {@code UnsupportedOperationException} at the second registration, so on an instance carrying both
     * Sodium and Indigo the winner is simply whichever client entrypoint fabric-loader happens to return
     * first - an order this bridge does not control, and one that has been observed to flip between
     * otherwise identical launches. The two possible winners are not equally good: with Indigo holding
     * the plug-in slot, Sodium's {@code ExtendedBlockModelFeatureRenderer} still runs and replays
     * Indigo-built meshes into Sodium's own quad emitter, and Indigo's {@code MeshViewImpl} casts that
     * emitter to its own type - the first frame dies with
     * {@code ClassCastException: MutableQuadViewWrapper cannot be cast to MutableQuadViewImpl}. With
     * Sodium first, Indigo's registration fails exactly as it always has on those launches and
     * everything else keeps working, which is the configuration every successful run used.
     *
     * <p>Only reorders when both are present, so no other mod's entrypoint order changes.
     */
    private static <T> void pinSodiumBeforeIndigo(java.util.List<net.fabricmc.loader.api.entrypoint.EntrypointContainer<T>> containers) {
        var hasSodium = false;
        var hasIndigo = false;
        for (var container : containers) {
            var modId = container.getProvider().getMetadata().getId();
            hasSodium |= "sodium".equals(modId);
            hasIndigo |= "fabric-renderer-indigo".equals(modId);
        }
        if (!hasSodium || !hasIndigo) {
            return;
        }
        java.util.List<net.fabricmc.loader.api.entrypoint.EntrypointContainer<T>> ordered = new java.util.ArrayList<>(containers.size());
        for (var container : containers) {
            if ("sodium".equals(container.getProvider().getMetadata().getId())) {
                ordered.add(container);
            }
        }
        for (var container : containers) {
            if (!"sodium".equals(container.getProvider().getMetadata().getId())) {
                ordered.add(container);
            }
        }
        containers.clear();
        containers.addAll(ordered);
        LOGGER.info("[NeoFobric] Pinned Sodium's entrypoint ahead of the Fabric renderer to keep the"
                + " one renderer plug-in slot");
    }

    /**
     * {@return whether this entrypoint should stand down so Sodium can take the renderer slot}
     *
     * <p>Fabric's renderer API keeps exactly one plug-in, and whoever registers first wins. Indigo
     * registers from its client entrypoint, which runs before Sodium's, so with both installed Sodium's
     * FRAPI integration was rejected with "Attempted to register a second rendering plug-in" and the
     * mod never took over block rendering. Blocks then only appeared once something forced a rebuild -
     * breaking a block re-rendered its own chunk - and items drew through a pipeline that left them
     * unlit and dark.
     *
     * <p>Only applies when Sodium is present; on its own, Indigo is the renderer and must register.
     */
    private static boolean yieldsRendererToSodium(String key, String modId) {
        // Off by default: Indigo is not only a renderer, it is also the model baker, so standing it down
        // left every block and item model without a texture - the game drew black-and-magenta placeholders.
        // Kept behind a flag because taking the renderer slot is still the right move once Indigo's model
        // loading is routed through NeoForge's.
        if (!Boolean.getBoolean("neofobric.indigoYieldsToSodium")) {
            return false;
        }
        if (!"client".equals(key) || !"fabric-renderer-indigo".equals(modId)) {
            return false;
        }
        try {
            return FabricLoaderImpl.INSTANCE.isModLoaded("sodium");
        } catch (Throwable t) {
            return false;
        }
    }

    public static EnvType toEnvType(Dist dist) {
        return dist == Dist.CLIENT ? EnvType.CLIENT : EnvType.SERVER;
    }

    /**
     * Indirection so this class can be loaded before {@code FMLEnvironment} is touched by mods.
     */
    private static final class FMLEnvironmentHolder {
        static Dist dist() {
            return net.neoforged.fml.loading.FMLEnvironment.getDist();
        }
    }
}
