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

        ModLoader.gatherAndInitializeMods(syncExecutor, ModWorkManager.parallelExecutor(), periodicTask);

        ModLoader.runInitTask("Registry initialization", syncExecutor, periodicTask, () -> {
            RegistryManager.postNewRegistryEvent();
            GameData.unfreezeData();
            GameData.postRegisterEvents();
            // NeoFobric: run the Fabric Loader entrypoints while the registries are still open, so
            // Fabric mods can register their content just like they do in a pure Fabric environment.
            net.neoforged.fabricbridge.FabricBridgeHooks.runFabricEntrypoints();
            // NeoFobric: Fabric's default attribute registry does not use NeoForge's
            // EntityAttributeCreationEvent; it replaces the (immutable) vanilla default attribute map with a
            // mutable copy - its mixin does that at the tail of DefaultAttributes' static initialiser - and
            // writes there. DefaultAttributes#getSupplier reads NeoForge's own map first, and that map was
            // filled by the attribute pass above, before any of this ran, so every attribute a Fabric mod
            // registered stayed invisible ("Pig does not have test attribute"). Repeating the pass copies
            // them over, exactly as if they had gone through the event. It is safe to repeat: the handlers
            // only put into or combine with the existing suppliers.
            net.neoforged.neoforge.common.CommonHooks.modifyAttributes();
            // NeoFobric: the Fabric client entrypoints are the one part of Fabric mod initialisation that
            // cannot run here - they need the game instance, and their insertion point sits inside the
            // Minecraft constructor - yet Fabric mods do register content from them (particle types are
            // the classic case). Fabric's own registry sync solves the same problem by moving the
            // registry freeze to after the mod initialisers, so on a client run the bridge takes the
            // freeze over and runs it as soon as the client entrypoints are through. Datagen and the
            // dedicated server keep the freeze here.
            if (datagen || FMLEnvironment.getDist() != Dist.CLIENT
                    || !net.neoforged.fabricbridge.FabricBridgeHooks.deferRegistryFreeze(GameData::freezeData)) {
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
