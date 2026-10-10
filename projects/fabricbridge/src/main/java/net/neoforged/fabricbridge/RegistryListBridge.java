/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges the datapack registry lists of the two loaders for the world-load call.
 *
 * <p>fabric-registry-sync replaces the registry list handed to {@code RegistryDataLoader} with its own
 * list so the modded dynamic registries it knows about get loaded. On a pure Fabric game that list is
 * "vanilla worldgen registries + modded ones", so replacing is equivalent to merging. Here it is not:
 * NeoForge keeps its own datapack registries (biome modifiers, data maps, ...) in the list it passes -
 * replacing drops them, and the server then dies at startup with
 * {@code Missing registry: neoforge:biome_modifier}.
 *
 * <p>The patched Fabric handler calls this with the list it was handed as the base and Fabric's list as
 * additions: everything NeoForge contributed stays, every Fabric registry that is not already there is
 * appended, and duplicates (the same vanilla registry present in both) collapse by registry key.
 * Reflection only - this runs on the bootstrap layer and cannot see the game classes.
 */
public final class RegistryListBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryListBridge.class);

    private RegistryListBridge() {}

    /**
     * @param base the registry list the game passed to the loader (NeoForge's, including its extras)
     * @param additions Fabric's list (vanilla + modded dynamic registries)
     * @return base plus every addition whose registry key is not already present, in that order
     */
    public static List<Object> merge(List<Object> base, List<Object> additions) {
        if (base == null || base.isEmpty()) {
            return additions == null ? List.of() : additions;
        }
        if (additions == null || additions.isEmpty()) {
            return base;
        }

        Map<Object, Object> byKey = new LinkedHashMap<>();
        for (var entry : base) {
            var key = registryKey(entry);
            byKey.put(key == null ? entry : key, entry);
        }

        var added = 0;
        for (var entry : additions) {
            var key = registryKey(entry);
            if (key != null && byKey.containsKey(key)) {
                continue;
            }
            if (key != null) {
                byKey.put(key, entry);
            } else {
                byKey.put(entry, entry);
            }
            added++;
        }

        if (added == 0) {
            return base;
        }
        LOGGER.debug("[NeoFobric] Kept {} registry(ies) NeoForge added to the datapack registry list while merging in {} Fabric entr(ies)",
                base.size(), added);
        return new ArrayList<>(byKey.values());
    }

    /**
     * {@return the registry key of a {@code RegistryDataLoader.RegistryData<?>} entry, or null when it
     * cannot be read} Read reflectively because the bridge cannot reference game classes.
     */
    private static Object registryKey(Object registryData) {
        try {
            return registryData.getClass().getMethod("key").invoke(registryData);
        } catch (Throwable t) {
            return null;
        }
    }
}
