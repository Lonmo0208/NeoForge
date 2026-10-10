/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.internal;

import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForgeMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(NeoForgeMod.MOD_ID)
public class LogoFileWarningsHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogoFileWarningsHandler.class);

    private static final boolean HIDE_WARNING_SCREEN = Boolean.getBoolean("neoforge.warnings.logofile.hide");

    public LogoFileWarningsHandler() {
        // Skip if in production or if the property to skip the warnings is set
        if (FMLEnvironment.isProduction() || HIDE_WARNING_SCREEN) return;

        ModList.get().getMods().forEach(info -> {
            // See DefaultModDisplayInfo#banner()
            var logoFile = info.getLogoFile()
                    .or(() -> info.getOwningFile().getConfig().getConfigElement("logoFile"));

            // If logoFile is not set, there is nothing to warn about
            if (logoFile.isEmpty()) return;

            var bannerFile = info.getConfig().getConfigElement("bannerFile")
                    .or(() -> info.getOwningFile().getConfig().getConfigElement("bannerFile"));
            // See DefaultModDisplayInfo#icon()
            var iconFile = info.getConfig().getConfigElement("iconFile")
                    .or(() -> info.getOwningFile().getConfig().getConfigElement("iconFile"));

            // Skip warning if bannerFile or iconFile is present, since it means the developer consciously kept the old property (for multi-version compat)
            if (bannerFile.isEmpty() && iconFile.isEmpty()) {
                // NeoFobric: a log line, not a loading issue. `logoFile` still works - FML reads it as a
                // fallback for the mod's display image - so nobody can act on the notice, while a
                // NeoFobric instance routinely carries third-party mods that use the old property (the
                // NeoForge builds of Sodium and Iris both do). Reporting it as a loading issue raised the
                // warning screen and blocked the game for information that changes nothing; the notice
                // stays available in the log at debug level.
                LOGGER.debug("Mod {} uses the deprecated `logoFile` property; change to `bannerFile` and/or (for square icons) `iconFile`",
                        info.getModId());
            }
        });
    }
}
