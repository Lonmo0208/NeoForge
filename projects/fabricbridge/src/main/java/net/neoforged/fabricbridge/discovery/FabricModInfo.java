/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.discovery;

import java.util.ArrayList;
import java.util.List;

/**
 * The subset of {@code fabric.mod.json} that the bridge needs to understand.
 *
 * @param modId          the mod id
 * @param version        the mod version
 * @param environment    the {@code environment} declaration: {@code *}, {@code client} or {@code server}
 * @param commonMixins   mixin configs declared under {@code mixins} (applied on both sides)
 * @param clientMixins   mixin configs declared under {@code client}
 * @param serverMixins   mixin configs declared under {@code server}
 * @param nestedJarPaths nested jar entries ({@code jars}) declared by this mod
 * @param accessWidener  the classTweaker/access-widener file declared via {@code accessWidener}, or null
 * @param displayName    the {@code name} declaration, or the mod id when the mod does not name itself
 * @param description    the {@code description} declaration, or an empty string
 * @param authors        the {@code authors} declaration names, empty when absent
 * @param license        the {@code license} declaration, or an empty string
 * @param websiteUrl     the {@code contact.homepage} declaration, or null
 * @param issuesUrl      the {@code contact.issues} declaration, or null
 * @param iconPath       the {@code icon} declaration (a path relative to the mod root), or null
 * @param bundledBy      the mod id of the mod whose jar carries this one (jar-in-jar), or an empty
 *                       string for a jar the player put in the mods folder themselves
 */
public record FabricModInfo(
        String modId,
        String version,
        String environment,
        List<String> commonMixins,
        List<String> clientMixins,
        List<String> serverMixins,
        List<String> nestedJarPaths,
        String accessWidener,
        String displayName,
        String description,
        List<String> authors,
        String license,
        String websiteUrl,
        String issuesUrl,
        String iconPath,
        String bundledBy) {

    /**
     * {@return the same mod, marked as carried inside {@code parentModId}'s jar}
     */
    public FabricModInfo withBundledBy(String parentModId) {
        return new FabricModInfo(modId, version, environment, commonMixins, clientMixins, serverMixins,
                nestedJarPaths, accessWidener, displayName, description, authors, license, websiteUrl,
                issuesUrl, iconPath, parentModId);
    }

    /**
     * {@return the mixin configs of this mod that apply on the given side}
     */
    public List<String> mixinConfigsFor(boolean client) {
        var sideConfigs = client ? clientMixins : serverMixins;
        if (commonMixins.isEmpty()) {
            return sideConfigs;
        }
        if (sideConfigs.isEmpty()) {
            return commonMixins;
        }
        var combined = new ArrayList<String>(commonMixins.size() + sideConfigs.size());
        combined.addAll(commonMixins);
        combined.addAll(sideConfigs);
        return List.copyOf(combined);
    }
}
