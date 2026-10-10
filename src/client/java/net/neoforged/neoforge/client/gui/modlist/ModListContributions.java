/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.client.gui.modlist;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/// Display-only mod list rows contributed by a loader bridge, e.g. Fabric mods running through
/// NeoForge's Fabric bridge.
///
/// Such mods are not part of FML's mod list - they are loaded as one game-library module and never
/// get containers - so [ModListScreen] cannot discover them through [net.neoforged.fml.ModList].
/// The bridge publishes what a player should see into a small JSON manifest while it discovers the
/// mods, and this class reads that manifest back.
///
/// The manifest is written on every launch, so a mod the player removed cannot leave a ghost row
/// behind. A launch without the bridge leaves the {@code neofobric.active} system property unset,
/// which suppresses the read entirely - otherwise a manifest from an earlier launch would list mods
/// that are not running.
///
/// Manifest location: {@code <gameDir>/.neofobric/mods-menu.json}, shape:
/// <pre>{@code
/// { "mods": [ { "id": "...", "name": "...", "version": "...", "description": "...",
///               "authors": "...", "license": "...", "website": "...", "issues": "...",
///               "icon": "assets/example/icon.png", "root": "C:/.../example.jar" } ] }
/// }</pre>
/// Every field except {@code id} is optional; {@code icon} is a path inside {@code root}.
@ApiStatus.Internal
final class ModListContributions {
    private static final Logger LOGGER = LogUtils.getLogger();

    /// Set by the bridge during mod discovery. A launch that never sets it shows no contributed rows.
    private static final String ACTIVE_FLAG = "neofobric.active";

    private static final Path MANIFEST = FMLPaths.GAMEDIR.get().resolve(".neofobric").resolve("mods-menu.json");

    /// One contributed row: what to show, and the id of the mod whose jar carries it ({@code ""} when
    /// it is a jar the player installed directly, in which case the row is top-level).
    record Contributed(ModDisplayInfo info, String bundledBy) {}

    private static volatile List<Contributed> cache;

    private ModListContributions() {}

    /// {@return the contributed rows, in manifest order} Read once per launch.
    static List<Contributed> contributions() {
        var cached = cache;
        if (cached != null) {
            return cached;
        }
        synchronized (ModListContributions.class) {
            if (cache == null) {
                cache = read();
            }
            return cache;
        }
    }

    private static List<Contributed> read() {
        if (!Boolean.getBoolean(ACTIVE_FLAG) || !Files.isRegularFile(MANIFEST)) {
            return List.of();
        }
        try (var reader = Files.newBufferedReader(MANIFEST, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("mods")) {
                return List.of();
            }
            var entries = new ArrayList<Contributed>();
            for (var element : root.getAsJsonObject().getAsJsonArray("mods")) {
                var entry = readEntry(element);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            LOGGER.debug("Read {} contributed mod list row(s) from {}", entries.size(), MANIFEST);
            return List.copyOf(entries);
        } catch (IOException | RuntimeException e) {
            // Display only: a broken manifest means the screen simply does not list these mods.
            LOGGER.warn("Failed to read the contributed mod list manifest {}", MANIFEST, e);
            return List.of();
        }
    }

    private static @Nullable Contributed readEntry(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }
        var object = element.getAsJsonObject();
        var id = string(object, "id");
        if (id == null || id.isBlank()) {
            return null;
        }
        var name = orElse(string(object, "name"), id);
        var iconPath = string(object, "icon");
        var root = string(object, "root");
        ImageResource icon = iconPath == null || root == null ? null : ImageResource.jarEntry(Path.of(root), iconPath);
        var info = new ExternalMod(
                id,
                Component.literal(name),
                orElse(string(object, "version"), ""),
                Component.literal(orElse(string(object, "authors"), "")),
                Component.literal(orElse(string(object, "description"), "")),
                Component.literal(orElse(string(object, "license"), "")),
                icon,
                uri(string(object, "website")),
                uri(string(object, "issues")));
        return new Contributed(info, orElse(string(object, "bundledBy"), ""));
    }

    private static @Nullable String string(JsonObject object, String member) {
        var value = object.get(member);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String orElse(@Nullable String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static @Nullable URI uri(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /// One contributed row. Every component is a literal: the metadata comes from another loader's
    /// manifest, where translation keys would point at language files the resource manager never
    /// loaded.
    private record ExternalMod(
            String id,
            Component displayName,
            String version,
            Component authors,
            Component description,
            Component license,
            @Nullable ImageResource icon,
            @Nullable URI displayUrl,
            @Nullable URI issuesUrl) implements ModDisplayInfo {
        @Override
        public Component credits() {
            return Component.empty();
        }

        @Override
        public @Nullable ImageResource banner() {
            return null;
        }
    }
}
