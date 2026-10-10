/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.discovery;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks the Fabric mods the bridge discovered during FML's mod discovery phase.
 *
 * <p>Discovery runs on a background thread while the early loading screen is shown; the results
 * are consumed later on the main thread (class processor linking and loader boot), after FML's
 * off-thread discovery has been joined, so the concurrent collections here are a safety net.
 */
public final class FabricJarRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricJarRegistry.class);

    public static final String FABRIC_MOD_JSON = "fabric.mod.json";

    private static final Map<Path, FabricModInfo> FABRIC_JARS = new ConcurrentHashMap<>();
    /**
     * Fabric jar paths that are part of the FML game content layer (either a jar in the mods
     * folder or a nested jar extracted to the bridge cache). fabric-loader's own classpath
     * management ({@code addToClassPath}) is a no-op for these.
     */
    private static final Set<Path> GAME_LAYER_PATHS = ConcurrentHashMap.newKeySet();

    private FabricJarRegistry() {}

    public static boolean hasFabricMods() {
        return !FABRIC_JARS.isEmpty();
    }

    public static void register(Path path, FabricModInfo info) {
        FABRIC_JARS.put(path, info);
    }

    public static FabricModInfo get(Path path) {
        return FABRIC_JARS.get(path);
    }

    public static void markGameLayerPath(Path path) {
        GAME_LAYER_PATHS.add(path);
    }

    public static boolean isGameLayerPath(Path path) {
        return GAME_LAYER_PATHS.contains(path);
    }

    public static List<Path> getPaths() {
        return List.copyOf(FABRIC_JARS.keySet());
    }

    /**
     * { all registered Fabric mods as an unmodifiable snapshot of path to metadata}
     */
    public static java.util.SequencedMap<Path, FabricModInfo> getEntries() {
        return java.util.Collections.unmodifiableSequencedMap(new java.util.LinkedHashMap<>(FABRIC_JARS));
    }
}
