/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.client.gui.modlist;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.common.FabricConfigHooks;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/// Configuration screens the loader bridge registers for the mods it loaded.
///
/// The rows of Fabric mods in the mod list have no FML container, so the usual extension point cannot
/// supply their configuration screen. The bridge knows what each Fabric mod declares for Mod Menu and
/// hands that factory over here; the mod list screen's config button then opens exactly the screen Mod
/// Menu would have opened. Mods that registered their configuration with the platform's config system
/// through [FabricConfigHooks] are handled by the screen itself and do not need a registration here.
///
/// Display only: a mod without a registration simply keeps its config button inactive.
@ApiStatus.Internal
public final class ModListConfigScreens {
    private static final Map<String, BiFunction<Minecraft, Screen, Screen>> SCREENS = new ConcurrentHashMap<>();

    private ModListConfigScreens() {}

    /// Registers the configuration screen factory a Fabric mod declares, keyed by its mod id.
    ///
    /// `factory` receives the game instance and the screen to return to; called by the bridge, which
    /// reaches this method reflectively because it compiles without the platform.
    public static void register(String modId, BiFunction<Minecraft, Screen, Screen> factory) {
        SCREENS.put(modId, factory);
    }

    /// {@return the registered screen factory for this mod id, or `null`}
    static @Nullable BiFunction<Minecraft, Screen, Screen> get(String modId) {
        return SCREENS.get(modId);
    }
}
