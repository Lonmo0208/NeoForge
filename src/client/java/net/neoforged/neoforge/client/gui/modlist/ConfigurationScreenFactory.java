/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.client.gui.modlist;

import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.FabricConfigHooks;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/// Configuration screen factory used by the mod list screen. For internal use only.
@FunctionalInterface
@ApiStatus.Internal
interface ConfigurationScreenFactory {
    // unary operator takes in previous screen (to return to later)
    @Nullable
    UnaryOperator<Screen> create(ModDisplayInfo displayInfo);

    ConfigurationScreenFactory DEFAULT = displayInfo -> {
        final ModContainer container = ModList.get().getModContainerById(displayInfo.id()).orElse(null);
        if (container != null) {
            final IConfigScreenFactory factory = container.getCustomExtension(IConfigScreenFactory.class).orElse(null);
            if (factory == null) return null;

            return parentScreen -> factory.createScreen(container, parentScreen);
        }

        // The rows the bridge contributes for Fabric mods have no FML container. Their configuration screen
        // is the one the mod declares for Mod Menu - the bridge registers that factory, see
        // ModListConfigScreens - and for the mods that registered their configuration with the platform's
        // config system through FabricConfigHooks it is the platform's own screen, which is also what their
        // Mod Menu entry opens.
        final var contributed = ModListConfigScreens.get(displayInfo.id());
        if (contributed != null) {
            return parentScreen -> contributed.apply(Minecraft.getInstance(), parentScreen);
        }
        if (!hasRegisteredConfiguration(displayInfo.id())) {
            return null;
        }
        final ModContainer synthetic = FabricConfigHooks.containerForModId(displayInfo.id());
        return parentScreen -> new ConfigurationScreen(synthetic, parentScreen);
    };

    /// {@return whether a configuration the platform knows is registered under this mod id}
    ///
    /// Fabric mods reach the platform's config system through [FabricConfigHooks], which registers their
    /// configurations under a synthetic container keyed by the mod id; when such a config exists, the
    /// platform's configuration screen can show it.
    private static boolean hasRegisteredConfiguration(String modId) {
        for (final ModConfig.Type type : ModConfig.Type.values()) {
            for (final ModConfig config : ModConfigs.getConfigSet(type)) {
                if (config.getModId().equals(modId)) {
                    return true;
                }
            }
        }
        return false;
    }
}
