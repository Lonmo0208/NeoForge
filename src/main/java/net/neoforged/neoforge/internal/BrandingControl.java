/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.fml.ModList;
import net.neoforged.fml.i18n.FMLTranslations;
import net.neoforged.neoforge.common.NeoForgeVersion;
import org.jspecify.annotations.Nullable;

public class BrandingControl {
    public static final String BRANDING_NAME = "NeoForge";
    private static final String BRANDING_ID = "neoforge";

    @Nullable
    private static String forgeStatusLine;

    @Nullable
    private static List<String> brandings;
    @Nullable
    private static List<String> brandingsNoMC;
    @Nullable
    private static List<String> overCopyrightBrandings;

    private static void computeBranding() {
        if (brandings == null) {
            ImmutableList.Builder<String> brd = ImmutableList.builder();
            brd.add("Minecraft " + SharedConstants.getCurrentVersion().name());

            final String brandingName = BRANDING_NAME + ' ' + NeoForgeVersion.getVersion();
            final int modCount = ModList.get().size();
            // When the Fabric bridge is active its mods run alongside FML's, but ModList only knows
            // FML's side - the count shown here would silently ignore every Fabric mod. The bridge
            // publishes its count as a system property, so the line can report the total and the
            // split. The property is only set while the bridge actually loaded mods, which keeps the
            // plain line for a Fabric-mod-free (or bridge-disabled) game.
            final Integer fabricMods = fabricModCount();
            if (fabricMods == null) {
                brd.add(FMLTranslations.parseMessage("fml.menu.branding", brandingName, modCount));
            } else {
                brd.add(FMLTranslations.parseMessage("fml.menu.branding.bridged", brandingName, modCount + fabricMods, modCount, fabricMods));
            }

            brandings = brd.build();
            brandingsNoMC = brandings.subList(1, brandings.size());
        }
    }

    /**
     * {@return the number of Fabric mods the bridge loaded, or null when it loaded none}
     */
    @Nullable
    private static Integer fabricModCount() {
        final String property = System.getProperty("neofobric.fabricModCount");

        if (property == null || property.isBlank())
            return null;

        try {
            return Integer.parseInt(property.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> getBrandings(boolean includeMC, boolean reverse) {
        computeBranding();
        if (includeMC) {
            return reverse ? Lists.reverse(brandings) : brandings;
        } else {
            return reverse ? Lists.reverse(brandingsNoMC) : brandingsNoMC;
        }
    }

    public static void setForgeStatusLine(@Nullable String forgeStatusLine) {
        BrandingControl.forgeStatusLine = forgeStatusLine;
    }

    private static void computeOverCopyrightBrandings() {
        if (overCopyrightBrandings == null) {
            ImmutableList.Builder<String> brd = ImmutableList.builder();
            if (forgeStatusLine != null) brd.add(forgeStatusLine);
            overCopyrightBrandings = brd.build();
        }
    }

    public static void forEachLine(boolean includeMC, boolean reverse, BiConsumer<Integer, String> lineConsumer) {
        final List<String> brandings = getBrandings(includeMC, reverse);
        IntStream.range(0, brandings.size()).boxed().forEachOrdered(idx -> lineConsumer.accept(idx, brandings.get(idx)));
    }

    public static void forEachAboveCopyrightLine(BiConsumer<Integer, String> lineConsumer) {
        computeOverCopyrightBrandings();
        IntStream.range(0, overCopyrightBrandings.size()).boxed().forEachOrdered(idx -> lineConsumer.accept(idx, overCopyrightBrandings.get(idx)));
    }

    public static String getClientBranding() {
        return BRANDING_ID;
    }

    public static String getServerBranding() {
        return BRANDING_ID;
    }

    public static ResourceManagerReloadListener resourceManagerReloadListener() {
        return BrandingControl::onResourceManagerReload;
    }

    private static void onResourceManagerReload(ResourceManager resourceManager) {
        brandings = null;
        brandingsNoMC = null;
    }
}
