/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.client.gui.modlist;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.VersionChecker;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/// Update check results the loader bridge registers for the mods it loaded.
///
/// The rows of Fabric mods in the mod list have no FML container, so [VersionChecker] never checks them.
/// The bridge runs what each Fabric mod declares for Mod Menu - its own update checker or the jar-hash
/// lookup Mod Menu performs for every mod - and hands outcomes that report an available update over here;
/// the mod list's update indicator then shows exactly what Mod Menu would have shown.
///
/// Only results that pass [VersionCheckResultSupplier]'s filter are registered, so the display logic does
/// not have to re-check them.
@ApiStatus.Internal
public final class ModListUpdateChecks {
    private static final Map<String, VersionChecker.CheckResult> RESULTS = new ConcurrentHashMap<>();

    private ModListUpdateChecks() {}

    /// Registers the update check result a Fabric mod's checker reported, keyed by its mod id.
    ///
    /// Called by the bridge, which reaches this method reflectively because it compiles without the
    /// platform.
    public static void register(String modId, VersionChecker.CheckResult result) {
        RESULTS.put(modId, result);
    }

    /// {@return the registered result for this mod id, or `null` when the bridge has none}
    public static VersionChecker.@Nullable CheckResult get(String modId) {
        return RESULTS.get(modId);
    }
}
