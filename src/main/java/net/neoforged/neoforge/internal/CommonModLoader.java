/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.internal;

import java.util.concurrent.Executor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.ModWorkManager;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;
import net.neoforged.fml.event.lifecycle.InterModProcessEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.RegistryManager;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal class for handling the steps of mod loading that are common for client, data and server runs.
 *
 * <p><ul>
 * <li>Client runs {@link #begin} and {@link #load} at different timings, see {@code ClientModLoader}.</li>
 * <li>Server runs both consecutively.</li>
 * <li>Datagen only runs {@link #begin}.</li>
 * </ul>
 */
@ApiStatus.Internal
public abstract class CommonModLoader {
    private static boolean registriesLoaded = false;

    public static boolean areRegistriesLoaded() {
        return registriesLoaded;
    }

    protected static void begin(Runnable periodicTask, boolean datagen) {
        var syncExecutor = ModWorkManager.syncExecutor();

        // NeoFobric: Fabric loader constructs mods on the calling thread. Some Fabric mods mutate
        // shared maps from their constructors without synchronisation - amecs' key-modifier layer
        // registers every KeyMapping in a plain HashMap - so NeoForge's parallel construction turns
        // that into a ConcurrentModificationException, surfaces as ExceptionInInitializerError from
        // whichever mod happened to create a KeyMapping, and aborts the whole launch. Serialise mod
        // construction whenever Fabric mods are present; it costs a little startup time, not correctness.
        var constructionExecutor = net.neoforged.fabricbridge.FabricBridge.hasFabricMods()
                ? syncExecutor
                : ModWorkManager.parallelExecutor();
        ModLoader.gatherAndInitializeMods(syncExecutor, constructionExecutor, periodicTask);

        ModLoader.runInitTask("Registry initialization", syncExecutor, periodicTask, () -> {
            // NeoFobric: open the built-in registries before the NewRegistry events fire. A Fabric mod's
            // mixin can reach the built-in registries from inside those events - wover registers its
            // structure type from a mixin on RegistryDataLoader's static initialiser, which the data pack
            // registry event triggers - and Fabric never freezes them, so the upstream order (events, then
            // unfreeze) turns that into a hard crash on a dedicated server: "Registry is already frozen
            // (trying to add key ResourceKey[minecraft:worldgen/structure_type / wover:random_nbt_structure])".
            GameData.unfreezeData();
            RegistryManager.postNewRegistryEvent();
            GameData.postRegisterEvents();
            // NeoFobric: run the Fabric Loader entrypoints while the registries are still open, so
            // Fabric mods can register their content just like they do in a pure Fabric environment.
            // On a normal client run the whole Fabric side moves to the client window instead (the
            // Minecraft constructor - see FabricBridgeHooks#deferFabricWindow): a Fabric main entrypoint
            // may use the client (voxy-extra reads Minecraft.getInstance() in its class initialiser),
            // and Fabric itself invokes both entrypoint sets from that constructor. Datagen and the
            // dedicated server keep everything here, where the freeze cannot be deferred past.
            if (datagen || FMLEnvironment.getDist() != Dist.CLIENT
                    || !net.neoforged.fabricbridge.FabricBridgeHooks.deferFabricWindow(
                            () -> {
                                // NeoFobric: the Fabric entrypoints just ran inside the still-open window;
                                // Architectury's listeners (only just registered on their shell mods' buses)
                                // get their RegisterEvent pass here, before the freeze below closes it.
                                GameData.postRegisterEventsForShellMods();
                                GameData.freezeData();
                            },
                            net.neoforged.neoforge.common.CommonHooks::rebaseAttributesAfterFabricEntrypoints)) {
                net.neoforged.fabricbridge.FabricBridgeHooks.runFabricEntrypoints();
                // NeoFobric: the same late pass on the paths that run the entrypoints here (datagen and
                // the dedicated server) - the registries are still open right up to freezeData below.
                GameData.postRegisterEventsForShellMods();
                // NeoFobric: Fabric's default attribute registry does not use NeoForge's
                // EntityAttributeCreationEvent; it replaces the (immutable) vanilla default attribute map with a
                // mutable copy - its mixin does that at the tail of DefaultAttributes' static initialiser - and
                // writes there. DefaultAttributes#getSupplier reads NeoForge's own map first, and that map was
                // filled by the attribute pass above, before the Fabric entrypoints ran, so every attribute a
                // Fabric mod registered stayed invisible ("Pig does not have test attribute"). Rebasing copies
                // them over, and re-applying the modification event re-layers what NeoForge mods asked for on
                // top. The creation event is deliberately not repeated - it refuses a second entry for the
                // same entity, which is how a NeoForge mod's mob (bagus_lib's) died here. On a client this
                // rebase runs from the client window instead, directly behind the entrypoints.
                net.neoforged.neoforge.common.CommonHooks.rebaseAttributesAfterFabricEntrypoints();
                // NeoFobric: on a client the freeze is taken over with the entrypoints (deferFabricWindow)
                // and runs once both entrypoint sets are through; datagen and the dedicated server freeze
                // right here, as they always did.
                GameData.freezeData();
            }
            registriesLoaded = true;
        });

        if (!datagen) {
            ModLoader.runInitTask("Config loading", syncExecutor, periodicTask, () -> {
                if (FMLEnvironment.getDist() == Dist.CLIENT) {
                    ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.CLIENT, FMLPaths.CONFIGDIR.get());
                }
                ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get());
            });
        }

        NeoForge.EVENT_BUS.start();
    }

    protected static void load(Runnable periodicTask) {
        Executor syncExecutor = ModWorkManager.syncExecutor();
        Executor parallelExecutor = ModWorkManager.parallelExecutor();

        ModLoader.dispatchParallelEvent("Common setup", syncExecutor, parallelExecutor, periodicTask, FMLCommonSetupEvent::new);
        ModLoader.dispatchParallelEvent("Sided setup", syncExecutor, parallelExecutor, periodicTask,
                FMLEnvironment.getDist().isClient() ? FMLClientSetupEvent::new : FMLDedicatedServerSetupEvent::new);

        ModLoader.runInitTask("Registration events", syncExecutor, periodicTask, RegistrationEvents::init);

        ModLoader.dispatchParallelEvent("Enqueue IMC", syncExecutor, parallelExecutor, periodicTask, InterModEnqueueEvent::new);
        ModLoader.dispatchParallelEvent("Process IMC", syncExecutor, parallelExecutor, periodicTask, InterModProcessEvent::new);
        ModLoader.dispatchParallelEvent("Complete loading of %d mods".formatted(ModList.get().size()), syncExecutor, parallelExecutor, periodicTask, FMLLoadCompleteEvent::new);

        ModLoader.runInitTask("Network registry lock", syncExecutor, periodicTask, NetworkRegistry::setup);
    }
}
