/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.compbridlnk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands the platform's mod list the update checks Fabric mods provide.
 *
 * <p>NeoForge's update indicator reads an update URL out of each mod's FML metadata, which Fabric mods do
 * not have, so their rows would never show an update. Everything they do have lives in Mod Menu: a mod can
 * bring its own {@code UpdateChecker}, another mod can provide checkers for it, and for everyone else Mod
 * Menu performs its own lookup by jar hash. This link asks Mod Menu to run exactly those checks - so the
 * two mod lists agree - and hands every result that reports an available update to the platform's
 * {@code ModListUpdateChecks} registry, which the mod list screen consults for rows without an FML
 * container.
 *
 * <p>The checks are network work running on Mod Menu's own executor, so the results are collected on a
 * daemon thread that reads the update info Mod Menu stores per mod once it is there, until every mod has
 * been looked at or a few minutes have passed.
 *
 * <p>All of it is reflective: Mod Menu is not on this project's compile class path, and its classes only
 * exist when Mod Menu itself is installed, which the link checks first. The link never throws.
 */
public final class ModMenuUpdateLink {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat");

    private static final String MODMENU = "com.terraformersmc.modmenu.ModMenu";
    private static final String CHECKER_UTIL = "com.terraformersmc.modmenu.util.UpdateCheckerUtil";
    private static final String MOD = "com.terraformersmc.modmenu.util.mod.Mod";
    private static final String UPDATE_INFO = "com.terraformersmc.modmenu.api.UpdateInfo";
    private static final String COMPONENT = "net.minecraft.network.chat.Component";
    private static final String REGISTRY = "net.neoforged.neoforge.client.gui.modlist.ModListUpdateChecks";
    private static final String CHECK_RESULT = "net.neoforged.fml.VersionChecker$CheckResult";
    private static final String STATUS = "net.neoforged.fml.VersionChecker$Status";
    private static final String COMPARABLE_VERSION = "org.apache.maven.artifact.versioning.ComparableVersion";

    private ModMenuUpdateLink() {}

    /** Runs the Fabric mods' update checks and hands what they report to the platform's mod list. */
    public static void linkModMenuUpdateChecks() {
        // The target loader first: Mod Menu and its registry live with the Fabric mods, and another
        // loader's copy of the same class would answer with a registry that stays empty forever (the
        // payload probe ran into the same class-view split).
        var loaders = new java.util.ArrayList<ClassLoader>();
        try {
            loaders.add(net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader());
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] No Fabric target class loader to try for the update link", t);
        }
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(ModMenuUpdateLink.class.getClassLoader());
        loaders.removeIf(java.util.Objects::isNull);

        try {
            var modMenuClass = loadFirst(loaders, MODMENU);
            if (modMenuClass == null) {
                return; // Mod Menu is not installed; there are no update sources to hand over.
            }
            var registry = loadFirst(loaders, REGISTRY);
            var modInterface = loadFirst(loaders, MOD);
            var updateInfoClass = loadFirst(loaders, UPDATE_INFO);
            var checkResultClass = loadFirst(loaders, CHECK_RESULT);
            var statusClass = loadFirst(loaders, STATUS);
            var comparableClass = loadFirst(loaders, COMPARABLE_VERSION);
            if (registry == null || modInterface == null || updateInfoClass == null
                    || checkResultClass == null || statusClass == null || comparableClass == null) {
                LOGGER.debug("[NeoFobric] The platform side of the update link is not reachable");
                return;
            }

            var modsField = modMenuClass.getField("MODS");
            var getUpdateInfo = modInterface.getMethod("getUpdateInfo");
            var isUpdateAvailable = updateInfoClass.getMethod("isUpdateAvailable");
            var getDownloadLink = updateInfoClass.getMethod("getDownloadLink");
            var getUpdateMessage = updateInfoClass.getMethod("getUpdateMessage");
            var getUpdateChannel = updateInfoClass.getMethod("getUpdateChannel");
            var getMessageString = loadFirst(loaders, COMPONENT).getMethod("getString");
            var register = registry.getMethod("register", String.class, checkResultClass);
            var resultCtor = checkResultClass.getConstructor(statusClass, comparableClass, java.util.Map.class, String.class);
            var channelCtor = comparableClass.getConstructor(String.class);
            var outdated = java.lang.Enum.valueOf(uncheckedCast(statusClass), "OUTDATED");
            var betaOutdated = java.lang.Enum.valueOf(uncheckedCast(statusClass), "BETA_OUTDATED");

            var checkerUtil = loadFirst(loaders, CHECKER_UTIL);

            var collector = new Thread(() -> collect(checkerUtil, modsField, getUpdateInfo, isUpdateAvailable,
                    getDownloadLink, getUpdateMessage, getUpdateChannel, getMessageString, register, resultCtor,
                    channelCtor, outdated, betaOutdated), "NeoFobric mod menu update link");
            collector.setDaemon(true);
            collector.start();
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not link the Mod Menu update checks", t);
        }
    }

    /** Collects the results Mod Menu's checks produce until every mod has been looked at. */
    private static void collect(Class<?> checkerUtil, java.lang.reflect.Field modsField,
            java.lang.reflect.Method getUpdateInfo, java.lang.reflect.Method isUpdateAvailable,
            java.lang.reflect.Method getDownloadLink, java.lang.reflect.Method getUpdateMessage,
            java.lang.reflect.Method getUpdateChannel, java.lang.reflect.Method getMessageString,
            java.lang.reflect.Method register, java.lang.reflect.Constructor<?> resultCtor,
            java.lang.reflect.Constructor<?> channelCtor, Object outdated, Object betaOutdated) {
        // Ask Mod Menu to run its checks (mods' own checkers, provided checkers, jar-hash lookups); the
        // results land on its mod wrappers, which this thread reads. Off the render thread: the call can
        // sit in network work for tens of seconds.
        try {
            checkerUtil.getMethod("checkForUpdates").invoke(null);
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not start the Mod Menu update checks", t);
        }
        java.util.Set<String> handled = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var collected = 0;
        var firstRound = true;
        var directLookupDone = false;
        // The bridge's own fallback material: what Modrinth needs to answer "which of these jars is
        // behind" - the loaded jars' hashes, mapped back to their mod ids.
        var hashesByHash = new java.util.LinkedHashMap<String, String>();
        for (var entry : net.neoforged.fabricbridge.discovery.FabricJarRegistry.getEntries().entrySet()) {
            var hash = sha1(entry.getKey());
            if (hash != null) {
                hashesByHash.put(hash, entry.getValue().modId());
            }
        }
        for (var round = 0; round < 200 && !Thread.currentThread().isInterrupted(); round++) {
            try {
                if (round > 0) {
                    Thread.sleep(3000L);
                }
                var mods = (java.util.Map<String, ?>) modsField.get(null);
                if (firstRound) {
                    firstRound = false;
                    LOGGER.info("[NeoFobric] Mod Menu's update checker covers {} mod(s)", mods.size());
                }
                // Mod Menu's own pass stays silent in some setups; the direct Modrinth lookup asks for the
                // same thing by jar hash and covers the mods it may never get around to.
                if (!directLookupDone && round >= 2) {
                    directLookupDone = true;
                    collected += checkOnModrinth(hashesByHash, handled, register, resultCtor, channelCtor,
                            outdated, betaOutdated);
                }
                for (var entry : mods.entrySet()) {
                    if (handled.contains(entry.getKey())) {
                        continue;
                    }
                    Object info;
                    try {
                        info = getUpdateInfo.invoke(entry.getValue());
                    } catch (Throwable t) {
                        // A wrapper without update support at all (a dummy parent, say) is done as far as
                        // this link is concerned.
                        handled.add(entry.getKey());
                        continue;
                    }
                    if (info == null) {
                        continue; // This mod's check has not finished yet; look again next round.
                    }
                    handled.add(entry.getKey());
                    try {
                        if (!Boolean.TRUE.equals(isUpdateAvailable.invoke(info))) {
                            continue; // Checked, and up to date.
                        }
                        var link = String.valueOf(getDownloadLink.invoke(info));
                        var channel = String.valueOf(getUpdateChannel.invoke(info));
                        var message = getUpdateMessage.invoke(info);
                        var messageText = message == null ? "" : String.valueOf(getMessageString.invoke(message));
                        // The list only draws OUTDATED and BETA_OUTDATED; the channel picks the one.
                        var status = "release".equalsIgnoreCase(channel) ? outdated : betaOutdated;
                        var target = channelCtor.newInstance(channel);
                        register.invoke(null, entry.getKey(), resultCtor.newInstance(status, target,
                                java.util.Map.of(target, messageText), link));
                        collected++;
                        LOGGER.info("[NeoFobric] Fabric mod {} has an update available ({})", entry.getKey(), channel);
                    } catch (Throwable t) {
                        LOGGER.debug("[NeoFobric] Could not hand over the update result of {}", entry.getKey(), t);
                    }
                }
                if (!mods.isEmpty() && handled.size() >= mods.size()) {
                    LOGGER.info("[NeoFobric] Fabric mod update checks finished: {} mod(s) checked, {} update(s) available",
                            handled.size(), collected);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Update link collector round failed", t);
            }
        }
        LOGGER.info("[NeoFobric] Fabric mod update checks timed out with {} mod(s) checked, {} update(s) available",
                handled.size(), collected);
    }

    /**
     * Asks Modrinth directly which of the loaded jars have a newer version, the way Mod Menu's own hash
     * lookup would, and registers one result per mod that does.
     */
    private static int checkOnModrinth(java.util.Map<String, String> hashesByHash, java.util.Set<String> handled,
            java.lang.reflect.Method register, java.lang.reflect.Constructor<?> resultCtor,
            java.lang.reflect.Constructor<?> channelCtor, Object outdated, Object betaOutdated) {
        if (hashesByHash.isEmpty()) {
            return 0;
        }
        try {
            // The game ships Gson; going through it keeps the bridge free of compile-time dependencies.
            var gsonClass = Class.forName("com.google.gson.Gson");
            var gson = gsonClass.getConstructor().newInstance();
            var toJson = gsonClass.getMethod("toJson", Object.class);
            var fromJson = gsonClass.getMethod("fromJson", String.class, Class.class);

            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("hashes", new java.util.ArrayList<>(hashesByHash.keySet()));
            body.put("algorithm", "sha1");
            body.put("loaders", java.util.List.of("fabric", "quilt"));
            body.put("game_versions", java.util.List.of(minecraftVersion()));
            body.put("version_types", java.util.List.of("release", "beta", "alpha"));

            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.modrinth.com/v2/version_files/update"))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "NeoFobric mod list update link (NeoForge with the Fabric bridge)")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(String.valueOf(toJson.invoke(gson, body))))
                    .build();
            var response = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.debug("[NeoFobric] The Modrinth update lookup answered {}", response.statusCode());
                return 0;
            }
            @SuppressWarnings("unchecked")
            var parsed = (java.util.Map<String, java.util.Map<String, Object>>) fromJson.invoke(gson, response.body(), java.util.Map.class);
            var registered = 0;
            for (var entry : parsed.entrySet()) {
                var modId = hashesByHash.get(entry.getKey());
                if (modId == null || handled.contains(modId)) {
                    continue;
                }
                var version = entry.getValue();
                // The endpoint answers with the latest compatible version, which is frequently the one the
                // jar already is; the latest version's file list carrying our own hash proves that.
                if (versionCarriesHash(version, entry.getKey())) {
                    handled.add(modId);
                    continue;
                }
                handled.add(modId);
                var versionNumber = String.valueOf(version.get("version_number"));
                var versionType = String.valueOf(version.get("version_type"));
                var link = "https://modrinth.com/project/" + version.get("project_id") + "/version/" + version.get("id");
                var status = "release".equalsIgnoreCase(versionType) ? outdated : betaOutdated;
                var target = channelCtor.newInstance(versionNumber);
                register.invoke(null, modId, resultCtor.newInstance(status, target,
                        java.util.Map.of(target, "Update available: " + versionNumber), link));
                LOGGER.info("[NeoFobric] Fabric mod {} has {} available on Modrinth", modId, versionNumber);
                registered++;
            }
            LOGGER.info("[NeoFobric] Direct Modrinth lookup: {} of {} loaded jar(s) have an update", registered, hashesByHash.size());
            return registered;
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] The direct Modrinth update lookup failed", t);
            return 0;
        }
    }

    /** {@return whether the given version's file list carries this hash, i.e. the installed jar is it} */
    private static boolean versionCarriesHash(java.util.Map<String, Object> version, String hash) {
        if (!(version.get("files") instanceof java.util.List<?> files)) {
            return false;
        }
        for (var file : files) {
            if (file instanceof java.util.Map<?, ?> map && map.get("hashes") instanceof java.util.Map<?, ?> hashes
                    && hash.equals(String.valueOf(hashes.get("sha1")))) {
                return true;
            }
        }
        return false;
    }

    /** {@return the jar's SHA-1, or null when it cannot be read} */
    private static String sha1(java.nio.file.Path file) {
        try (var in = java.nio.file.Files.newInputStream(file)) {
            var digest = java.security.MessageDigest.getInstance("SHA-1");
            var buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            var hex = new StringBuilder();
            for (var b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not hash {}", file, t);
            return null;
        }
    }

    /** {@return the running Minecraft version as Modrinth names it} */
    private static String minecraftVersion() {
        try {
            var sharedConstants = Class.forName("net.minecraft.SharedConstants");
            var version = sharedConstants.getMethod("getCurrentVersion").invoke(null);
            return String.valueOf(version.getClass().getMethod("name").invoke(version));
        } catch (Throwable t) {
            return "26.2";
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Class<? extends Enum> uncheckedCast(Class<?> enumClass) {
        return (Class<? extends Enum>) enumClass;
    }

    private static Class<?> loadFirst(java.util.List<ClassLoader> loaders, String className) {
        for (var loader : loaders) {
            try {
                return Class.forName(className, false, loader);
            } catch (Throwable absent) {
                // Try the next loader.
            }
        }
        return null;
    }
}
