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

    /**
     * Hands the bridge's Fabric-channel test to the platform's payload decode path.
     *
     * <p>Reflection keeps the platform source free of a compile-time dependency on the bridge module; both
     * classes are in the game layer at runtime. A missing hook is not fatal - the ordinary fallback keeps
     * answering as before, Fabric channels just lose the hand-off.
     */
    private static void installFabricChannelDecodeHook() {
        try {
            var hooks = Class.forName(
                    "net.neoforged.neoforge.network.registration.FabricChannelDecodeHooks");
            hooks.getMethod("setFabricChannelTest", java.util.function.Predicate.class)
                    .invoke(null, (java.util.function.Predicate<Object>) FabricBridgeHooks::letFabricDecode);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not install the Fabric channel decode hook", t);
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

            // Before the loader logs anything of its own: its builtin handler buffers instead of printing,
            // so without this the "Loading N mod(s)" block stays invisible until the shutdown hook dumps it
            // into fabricloader.log - the one line a Fabric user looks for, missing from console and
            // latest.log. Log.init replays what the buffer already holds, so nothing logged before this
            // point is lost.
            BridgedLogHandler.install();

            try {
                FabricLoaderImpl.INSTANCE.load();
                FabricLoaderImpl.INSTANCE.freeze();
            } catch (net.fabricmc.loader.impl.FormattedException e) {
                // The loader could not resolve the installed mods (a missing dependency being the everyday
                // case). Fabric's own launcher would show its error window here; report it the same way
                // and never continue with a half-booted loader.
                reportFabricLoadFailure(e);
                throw e;
            }
            booted = true;
            installFabricChannelDecodeHook();

            // Read every mod's access widener / class tweaker into Fabric's own ClassTweaker. Knot does
            // this as part of its launch and nothing else does, so without this call the rules stay
            // empty and any mod that widens or un-finals a game class fails later - iris declares
            // "extendable class net/minecraft/client/OptionInstance" and dies defining its subclass
            // with "cannot inherit from final class". NeoFobricClassTweakerProcessor applies the rules
            // to the game classes as FML transforms them.
            try {
                FabricLoaderImpl.INSTANCE.loadClassTweakers();
                registerEnumExtensionTargets();
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Failed to read the Fabric access wideners; mods that widen game classes will misbehave", t);
            }

            LOGGER.info("[NeoFobric] Fabric Loader {} started with {} Fabric mod(s) in {} ms",
                    FabricLoaderImpl.VERSION, FabricLoaderImpl.INSTANCE.getAllMods().size(), System.currentTimeMillis() - start);
        }
    }

    /**
     * Reports a Fabric mod-set failure - the loader could not resolve the installed mods, a missing
     * dependency being the everyday case - the way fabric-loader reports it from its own launcher: the
     * loader's error window on a client, its readable message plus a clean exit on a server.
     *
     * <p>The bridge boots the loader directly (no Knot {@code launch()}), so Fabric's own error path
     * ({@code FabricLauncherBase#handleFormattedException}, protected to Fabric's launcher hierarchy)
     * never runs. Without this, a mod set that does not resolve never loads a single Fabric mod and the
     * failure unwinds into FML's bootstrap - either as a crash report with the missing mod's name buried
     * in a stack trace, or as a logged-and-swallowed early boot whose later symptoms point anywhere but
     * at the missing dependency. The few lines below are that handler, on public API only, so a Fabric
     * failure looks the same here as it does in a Fabric-only install.
     */
    private static void reportFabricLoadFailure(net.fabricmc.loader.impl.FormattedException exc) {
        var actual = exc.getMessage() != null ? exc : exc.getCause();
        LOGGER.error("[NeoFobric] Fabric Loader cannot start with this mod set: {}", exc.getMainText(), actual);
        var provider = FabricLoaderImpl.INSTANCE.tryGetGameProvider();
        if (provider == null || !provider.displayCrash(actual, exc.getDisplayedText())) {
            // Blocks on the client until the window is closed, then exits the JVM; on a server (or with
            // nogui) no window opens and the log line above is the report, followed by the same exit.
            net.fabricmc.loader.impl.gui.FabricGuiEntry.displayError(exc.getDisplayedText(), actual, true);
        } else {
            System.exit(1);
        }
    }

    /**
     * Marks every game enum a Fabric mod extends, so the platform's {@code IExtensibleEnum} marker can step
     * aside before Mixin prepares any config.
     *
     * <p>The class tweakers are the authoritative list - a mod that extends an enum declares it there, and the
     * loader has just read them all into Fabric's own instance, which is available long before any game class is
     * transformed. Collecting the same set from the mixins instead (which the jar patcher also does, as a
     * fallback) races with FML's own class loading: an enum whose extension is noticed only after something has
     * already defined the class keeps its marker, FML refuses the extension, and the mod's enum constants are
     * missing (WilderWild's grass colour modifier then fails to parse out of its own biome JSON).
     */
    private static void registerEnumExtensionTargets() {
        var extensions = FabricLoaderImpl.INSTANCE.getClassTweaker().getAllEnumExtensions();
        for (var target : extensions.keySet()) {
            net.neoforged.fabricbridge.transform.NeoFobricClassTweakerProcessor.registerEnumExtensionTarget(target);
        }
        if (!extensions.isEmpty()) {
            LOGGER.info("[NeoFobric] {} game enum(s) are extended by Fabric mods, from the class tweakers", extensions.size());
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

            // On a client the game instance is already in place - attachGameInstance prepares the loader
            // before the entrypoints, the way Fabric does - and passing it through keeps this call from
            // clobbering it with null. On the server and datagen paths nothing has set an instance yet,
            // which is also what setServerGameInstance expects to find when the server object appears.
            loader.prepareModInit(gameDir, loader.getGameInstance());
            // Arm the content-layer acceptance check before mods run: it watches whether the Fabric
            // events a bridge change could have silently broken still fire, and pokes the network once
            // per world join (see BridgeSelfCheck's javadoc).
            BridgeSelfCheck.install();
            invokeIsolated("preLaunch", net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint.class,
                    net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint::onPreLaunch);
            invokeIsolated("main", net.fabricmc.api.ModInitializer.class, net.fabricmc.api.ModInitializer::onInitialize);
            // The payload round-trip probe registers its own throwaway channels through Fabric's networking
            // API right where the mods' own registrations happened (see BridgePayloadProbe).
            BridgePayloadProbe.install();

            if (FMLEnvironmentHolder.dist() != Dist.CLIENT) {
                invokeIsolated("server", net.fabricmc.api.DedicatedServerModInitializer.class,
                        net.fabricmc.api.DedicatedServerModInitializer::onInitializeServer);
            } else {
                // Client entrypoints need a usable Minecraft instance, which does not exist on the
                // paths that run the main set here (datagen, and servers never reach this branch).
                // On a client run the whole block runs from the client window instead
                // (FabricBridgeHooks#attachGameInstance), main set first, then these.
                LOGGER.info("[NeoFobric] Deferring Fabric client entrypoints until the client is set up");
            }
            // Armed for both branches: everything this method runs has run now, and deferring the client
            // set is a decision it has already made - that set runs from attachGameInstance under its own
            // guard. Left unarmed on the client path, a second call (a second game instance in one process)
            // would replay every mod's onInitialize.
            entrypointsInvoked = true;

            LOGGER.info("[NeoFobric] Fabric entrypoints invoked");
            // The mod initializers are the stage that registers payloads and channel receivers, so this is
            // the first moment the networking inventory can say anything (see its javadoc).
            BridgeSelfCheck.logNetworkingInventory();
            // Every Fabric storage the mod set registers exists by now, so the NeoForge capabilities can be
            // offered to Fabric's lookups from here on (see FabricTransferHooks).
            FabricTransferHooks.install();
            // The moment the Fabric entrypoints are done is the moment to ask whether what they register into
            // the game's own registries landed (-Dneofobric.registryProbe).
            BridgeSelfCheck.registryProbe("fabric entrypoints done");
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
            BridgePayloadProbe.installClient();
            LOGGER.info("[NeoFobric] Fabric client entrypoints invoked");
            // Second reading of the inventory: the client entrypoints are where the clientbound payloads and
            // receivers get registered, so this line is the one that shows them.
            BridgeSelfCheck.logNetworkingInventory();
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
        net.neoforged.fabricbridge.compbridlnk.SodiumCompat.pinBeforeIndigo(containers);
        for (var container : containers) {
            try {
                var modId = container.getProvider().getMetadata().getId();
                if (net.neoforged.fabricbridge.compbridlnk.SodiumCompat.yieldsRendererToSodium(key, modId)) {
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
