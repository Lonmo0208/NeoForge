/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.net.URI;
import java.util.Map;

import net.neoforged.fml.jarcontents.CompositeJarContents;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.jarmoduleinfo.JarModuleInfo;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers Fabric mods in the mods folder and hands them to FML as a single game library.
 *
 * <p><strong>Why one single module?</strong> FML loads all game content through a JPMS
 * {@code ModuleLayer}, where each jar becomes its own module and two modules may not export the
 * same package. Fabric mods routinely share package namespaces across jars (Fabric API's modules
 * are full of overlaps), which kills module resolution with a {@code ResolutionException}. The
 * Fabric ecosystem has no such restriction because Knot uses a flat classpath, so we replicate
 * that by merging every discovered Fabric jar - top-level and nested alike - into one composite
 * module. Inside a single module, overlapping packages are perfectly legal.
 *
 * <p>This locator runs at the highest priority, before FML's own mods folder locator, and claims
 * every Fabric jar via {@code ILaunchContext#addLocated}, so the built-in locators skip them.
 */
public final class BridgeFabricModLocator implements IModFileCandidateLocator {

    public BridgeFabricModLocator() {
        // Belt-and-braces for the same option FabricBootstrapProcessor sets: FML loads this locator
        // through ServiceLoader while it scans for candidates - earlier than the class-processor
        // pipeline is even built - so the property is guaranteed to be in place before any mixin
        // environment can read it. See FabricBootstrapProcessor's static block for the rationale.
        if (!"SAFE".equals(System.getProperty("mixin.initialiserInjectionMode"))) {
            System.setProperty("mixin.initialiserInjectionMode", "SAFE");
            LOGGER.info("[NeoFobric] mixin.initialiserInjectionMode=SAFE (else Fabric instance field initialisers land inside NeoForge loop bodies)");
        }
    }
    private static final Logger LOGGER = LoggerFactory.getLogger(BridgeFabricModLocator.class);

    private static final int MAX_NESTING_DEPTH = 10;

    /**
     * Marker file identifying a classpath entry that exposes the Minecraft classes.
     */
    private static final String GAME_CONTENT_MARKER = "net/minecraft/server/MinecraftServer.class";

    /**
     * Marker for entries that only carry the client half of the Minecraft classes. In dev the main
     * and client sourcesets are compiled into separate folders, and only the common one contains
     * {@link #GAME_CONTENT_MARKER} - but client-only classes (for example BakedQuad) are exactly the
     * ones the jar patcher has to resolve to realign client mixins, so a client-only entry counts as
     * game content too.
     */
    private static final String CLIENT_GAME_CONTENT_MARKER = "net/minecraft/client/Minecraft.class";

    /**
     * Module name of the composite module holding all Fabric mod classes.
     */
    public static final String FABRIC_MODULE_NAME = "neofobric.fabric.mods";

    /**
     * Classpath resources identifying Minecraft dependency jars that Fabric mods mixin into.
     * Each marker locates a jar that gets integrated into the composite game library.
     */
    private static final String[] EXTRA_LIBRARY_MARKERS = {
            // datafixerupper, mixin-ed by fabric-dimensions-v1. (MixinExtras is not listed here:
            // it carries an FMLModType manifest attribute, so FML's InDevJarLocator already loads
            // it as its own game library when present on the classpath, and the production JiJ
            // expansion covers installed clients.)
            "com/mojang/datafixers/DSL.class"
    };

    @Override
    public int getPriority() {
        // Run before FML's built-in locators, so we claim the Fabric jars before the mods
        // folder locator tries to load them as NeoForge mods.
        // Priority 1000 is the highest system priority; FML sorts locators by descending priority.
        return 1000;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        var modsDir = FMLPaths.MODSDIR.get();
        if (!Files.isDirectory(modsDir)) {
            return;
        }

        if (net.neoforged.fabricbridge.FabricBridge.isDisabled()) {
            // A/B diagnosis switch (-Dneofobric.disabled=true): leave the Fabric jars to FML's own
            // locators so the same NeoForge build runs without the bridge.
            LOGGER.warn("[NeoFobric] Bridge disabled via neofobric.disabled - Fabric mods are not bridged");
            return;
        }

        // Fabric mod paths in discovery order (top-level jars, then nested jars as they are
        // extracted from their parents).
        var fabricJars = new LinkedHashMap<Path, FabricModInfo>();

        try (var files = Files.list(modsDir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(path -> examineJar(path, fabricJars, 0));
        } catch (IOException e) {
            LOGGER.error("[NeoFobric] Failed to list the mods folder", e);
            return;
        }

        if (fabricJars.isEmpty()) {
            return;
        }

        // Claim every Fabric jar so no other locator (mods folder, game locator, ...) picks it up.
        for (var path : fabricJars.keySet()) {
            context.addLocated(path);
        }

        // Merge all Fabric content into one JPMS module to avoid split-package conflicts.
        var contentsList = new ArrayList<JarContents>(fabricJars.size());
        for (var path : fabricJars.keySet()) {
            try {
                contentsList.add(JarContents.ofPath(path));
            } catch (Exception e) {
                LOGGER.error("[NeoFobric] Failed to open Fabric mod jar {}", path, e);
                // Un-claim so the file is reported by the regular discovery pipeline instead.
                // (addLocated returns false if already present; there is no remove, but
                // re-adding a path is a no-op, so just log and continue.)
            }
        }
        if (contentsList.isEmpty()) {
            return;
        }

        // Pull Minecraft dependency jars that Fabric mods mixin into (duck-type interfaces,
        // redirects) into the composite module as well. Knot controls the entire classpath in a
        // pure Fabric environment, so those jars are mixin-able there; on FML they are loaded by
        // the system classloader, outside the mixin transformation pipeline, so mixins into them
        // silently fail (and blow up later on casts to the duck interfaces). The game layer has
        // priority over the parent classloader, so once the jar is part of it, its classes are
        // transformed like any other game content.
        for (var marker : EXTRA_LIBRARY_MARKERS) {
            var libraryJar = locateSystemJarContaining(marker);
            if (libraryJar != null) {
                try {
                    contentsList.add(JarContents.ofPath(libraryJar));
                    context.addLocated(libraryJar);
                    LOGGER.info("[NeoFobric] Integrated {} into the Fabric game library for mixin support", libraryJar.getFileName());
                } catch (Exception e) {
                    LOGGER.error("[NeoFobric] Failed to integrate {} into the Fabric game library", libraryJar, e);
                }
            }
        }

        // Realign Mixin injector handlers before the game layer is built: FML's mixin service reads
        // mixin class bytes straight from the jar entries, so the rewrite has to exist as a jar by
        // now. Fabric mods are not on the classpath yet, so the resolution view is built from the
        // game content the bootstrap classpath already exposes (the classes folder in dev, the
        // patched Minecraft jars in production) - without it no mixin target resolves and nothing
        // gets realigned. The view only ever covers game content, and it is closed before the jars
        // are written so no handle is held on them.
        var gameContent = classpathGameContent();
        // Original jar path -> realigned copy. Only jars that actually needed a rewrite appear here.
        var realignedJars = new java.util.HashMap<Path, Path>();
        if (!gameContent.isEmpty()) {
            // Every copy is regenerated this pass, so the cache starts empty - which also stops a
            // removed mod from leaving a copy of itself behind forever.
            clearRealignedCache(modsDir);
            var realigned = 0;
            // One resolver for the whole pass: the jars are opened once and held, and lookups are
            // memoized, instead of reopening the game jar (and walking the mods folder on a miss)
            // for every single target lookup.
            var fabricPaths = new ArrayList<Path>(fabricJars.keySet());
            try (var resolver = new MixinJarPatcher.TargetResolver(gameContent, fabricPaths)) {
                // Colliding accessor members are planned before any jar is written: a call site can live
                // in a different jar than the accessor it calls.
                var accessorRenames = MixinJarPatcher.scanAccessorCollisions(fabricPaths, resolver);
                for (var path : fabricPaths) {
                    var effective = MixinJarPatcher.patchJar(path, resolver, accessorRenames);
                    if (!effective.equals(path)) {
                        realignedJars.put(path, effective);
                        realigned++;
                    }
                }
            }
            if (realigned > 0) {
                LOGGER.info("[NeoFobric] Realigned Mixin handlers in {} Fabric jar(s)", realigned);
            }
        }

        // Point the game library at the realigned copies where one was produced. The Fabric jars
        // occupy the first fabricJars.size() positions of contentsList, in fabricJars order.
        var gameLibraryContents = new ArrayList<JarContents>(contentsList.size());
        var fabricPaths = new ArrayList<Path>(fabricJars.keySet());
        for (var i = 0; i < contentsList.size(); i++) {
            var content = contentsList.get(i);
            var effective = i < fabricPaths.size() ? realignedJars.get(fabricPaths.get(i)) : null;
            if (effective == null) {
                gameLibraryContents.add(content);
                continue;
            }
            try {
                gameLibraryContents.add(JarContents.ofPath(effective));
            } catch (Exception e) {
                LOGGER.error("[NeoFobric] Failed to open the realigned copy {} - falling back to the original", effective, e);
                gameLibraryContents.add(content);
            }
        }

        var composite = new CompositeJarContents(gameLibraryContents);
        var moduleInfo = new FabricCompositeModuleInfo();
        IModFile modFile;
        try {
            modFile = IModFile.create(composite, moduleInfo, JarModsDotTomlModFileReader::manifestParser,
                    IModFile.Type.GAMELIBRARY, ModFileDiscoveryAttributes.DEFAULT);
        } catch (Exception e) {
            LOGGER.error("[NeoFobric] Failed to create the composite Fabric game library", e);
            return;
        }

        pipeline.addModFile(modFile);
        for (var entry : fabricJars.entrySet()) {
            // The registry must point at the jar that actually carries the mixin configs the bridge
            // will read back, i.e. the realigned copy when there is one.
            var effective = realignedJars.getOrDefault(entry.getKey(), entry.getKey());
            FabricJarRegistry.register(effective, entry.getValue());
            FabricJarRegistry.markGameLayerPath(effective);
        }

        // Publish what the Mods screen should show. NeoForge's own screen knows only FML's mod list,
        // which the composite game library deliberately is not part of (single-module JPMS merge), so
        // the display metadata travels through this file instead of through FML structures. Written
        // on every launch, so a mod removed from mods/ cannot leave a ghost row behind.
        writeModsMenuFile(fabricJars, realignedJars);

        LOGGER.info("[NeoFobric] Merged {} Fabric jar(s) into the composite game library", fabricJars.size());
    }

    /**
     * Writes the display metadata of every discovered Fabric mod to
     * {@code .neofobric/mods-menu.json}, which NeoForge's mod list screen reads back
     * (see {@code ModListContributions}). Also raises the {@code neofobric.active} flag the screen
     * gates the file read on, so a launch with the bridge disabled never shows a stale list.
     */
    private static void writeModsMenuFile(Map<Path, FabricModInfo> fabricJars, Map<Path, Path> realignedJars) {
        System.setProperty("neofobric.active", "true");
        var file = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve(".neofobric").resolve("mods-menu.json");
        try {
            Files.createDirectories(file.getParent());
            var out = new StringBuilder(4096);
            out.append("{\"mods\":[");
            var first = true;
            var written = 0;
            var seenIds = new java.util.HashSet<String>();
            for (var entry : fabricJars.entrySet()) {
                var info = entry.getValue();
                // Two mods can carry the same nested jar (fabric-api bundles its modules, and sodium
                // bundles the same ones again), which would list the module twice. The first occurrence
                // is the one whose classes win in the composite module, so it is the one shown.
                if (!seenIds.add(info.modId())) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                written++;
                var effective = realignedJars.getOrDefault(entry.getKey(), entry.getKey());
                out.append("{\"id\":").append(jsonString(info.modId()))
                        .append(",\"name\":").append(jsonString(info.displayName()))
                        .append(",\"version\":").append(jsonString(info.version()))
                        .append(",\"description\":").append(jsonString(info.description()))
                        .append(",\"authors\":").append(jsonString(String.join(", ", info.authors())))
                        .append(",\"license\":").append(jsonString(info.license()))
                        .append(",\"website\":").append(jsonString(info.websiteUrl()))
                        .append(",\"issues\":").append(jsonString(info.issuesUrl()))
                        .append(",\"icon\":").append(jsonString(info.iconPath()))
                        .append(",\"root\":").append(jsonString(effective.toString()))
                        .append(",\"bundledBy\":").append(jsonString(info.bundledBy()))
                        .append('}');
            }
            out.append("]}");
            Files.writeString(file, out.toString(), java.nio.charset.StandardCharsets.UTF_8);
            LOGGER.info("[NeoFobric] Published {} Fabric mod(s) to the mod list screen manifest", written);
        } catch (IOException e) {
            // Display only: a missing manifest means the mod list simply does not show Fabric mods.
            LOGGER.warn("[NeoFobric] Failed to write the mod list screen manifest {}", file, e);
        }
    }

    /** JSON string literal with the control characters the metadata can realistically carry. */
    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        var out = new StringBuilder(value.length() + 8);
        out.append('"');
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /**
     * Deletes the bridge's generated copies under {@code <mods>/.neofobric/}.
     *
     * <p>They are regenerated during this same pass; wiping them first keeps a mod the player
     * removed from leaving its copy behind, and clears copies from older bridge versions that were
     * written next to their originals (the suffix check in {@link #examineJar} covers those).
     */
    private static void clearRealignedCache(Path modsDir) {
        var cacheDir = modsDir.resolve(MixinJarPatcher.CACHE_DIR_NAME);
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        try (var entries = Files.list(cacheDir)) {
            var removed = 0;
            for (var entry : (Iterable<Path>) entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(MixinJarPatcher.PATCHED_SUFFIX))::iterator) {
                try {
                    if (Files.deleteIfExists(entry)) {
                        removed++;
                    }
                } catch (IOException e) {
                    LOGGER.debug("[NeoFobric] Could not remove the old realigned copy {}", entry, e);
                }
            }
            if (removed > 0) {
                LOGGER.debug("[NeoFobric] Cleared {} realigned cop(y/ies) from {}", removed, cacheDir);
            }
        } catch (IOException e) {
            LOGGER.debug("[NeoFobric] Failed to list {}", cacheDir, e);
        }
    }

    /**
     * Examines a jar file: if it is a Fabric mod, records it and recursively examines its nested jars.
     */
    private static void examineJar(Path path, Map<Path, FabricModInfo> fabricJars, int depth) {
        examineJar(path, fabricJars, depth, "");
    }

    /**
     * @param bundledBy the mod id of the jar this one came out of, or an empty string for a jar in the
     *            mods folder itself
     */
    private static void examineJar(Path path, Map<Path, FabricModInfo> fabricJars, int depth, String bundledBy) {
        if (depth > MAX_NESTING_DEPTH || fabricJars.containsKey(path)) {
            return;
        }

        // The bridge's own realigned copies look exactly like Fabric mod jars (same metadata, same
        // suffix-eligible name), so without this they were discovered as a second mod: every patched
        // jar was loaded twice, scanned twice and patched twice, and the duplicate class entries went
        // into the game layer. The copy is a disposable artifact that patchJar regenerates from the
        // original during this same pass, so the stale one is removed rather than re-examined. Old
        // bridge versions wrote them next to their originals, which is why both the file itself and
        // its old-format sibling are cleaned up.
        if (path.getFileName().toString().endsWith(MixinJarPatcher.PATCHED_SUFFIX)) {
            try {
                Files.deleteIfExists(path);
                LOGGER.info("[NeoFobric] Removed the stale realigned copy {} (regenerated from its original)", path.getFileName());
            } catch (IOException e) {
                LOGGER.warn("[NeoFobric] Could not remove the stale realigned copy {} - ignoring it", path, e);
            }
            return;
        }
        deleteQuietly(path.resolveSibling(path.getFileName() + MixinJarPatcher.PATCHED_SUFFIX));

        FabricModInfo info;
        try (var jar = JarContents.ofPath(path)) {
            byte[] metadata = jar.readFile(FabricJarRegistry.FABRIC_MOD_JSON);
            if (metadata == null) {
                return; // Not a Fabric mod.
            }
            info = FabricModJsonParser.parse(metadata);
            if (info == null) {
                return;
            }
        } catch (Exception e) {
            LOGGER.debug("[NeoFobric] Failed to read {} while checking for Fabric metadata", path, e);
            return;
        }

        if (!bundledBy.isEmpty()) {
            info = info.withBundledBy(bundledBy);
        }
        fabricJars.put(path, info);
        LOGGER.info("[NeoFobric] Found Fabric mod {} {} ({})", info.modId(), info.version(), path);

        // Extract nested jars to the cache and examine them as well.
        for (var nestedJar : info.nestedJarPaths()) {
            try {
                var extracted = extractNestedJar(path, info, nestedJar);
                examineJar(extracted, fabricJars, depth + 1, info.modId());
            } catch (Exception e) {
                LOGGER.error("[NeoFobric] Failed to process nested jar {} of Fabric mod {}", nestedJar, info.modId(), e);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("[NeoFobric] Could not delete {}", path, e);
        }
    }

    private static Path extractNestedJar(Path parentPath, FabricModInfo parentInfo, String nestedJar) throws IOException {
        byte[] nestedBytes;
        try (var jar = JarContents.ofPath(parentPath)) {
            nestedBytes = jar.readFile(nestedJar);
        }
        if (nestedBytes == null) {
            throw new IOException("Nested jar " + nestedJar + " does not exist in " + parentPath);
        }

        var fileName = nestedJar;
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }

        var targetDir = getCacheDirectory().resolve(parentInfo.modId());
        Files.createDirectories(targetDir);
        var target = targetDir.resolve(fileName);

        // Re-extract when the source jar changes so upstream mod updates are picked up. The size is
        // deliberately not compared: the cached copy may carry a Mixin handler realignment, which
        // changes its length without the source having changed. An updated source jar is newer than
        // the cache, so it still triggers a fresh extraction and a fresh realignment pass.
        if (!Files.exists(target)
                || Files.getLastModifiedTime(parentPath).toMillis() > Files.getLastModifiedTime(target).toMillis()) {
            // Defaults are CREATE + TRUNCATE_EXISTING + WRITE, i.e. a replace.
            Files.write(target, nestedBytes);
        }
        return target;
    }

    /**
     * {@return the classpath entries that currently expose the Minecraft classes}
     *
     * <p>Used to resolve Mixin targets while the game layer is not built yet. In dev that is the
     * Minecraft classes folder NeoForm compiles into, in production the patched Minecraft jar;
     * both are on the bootstrap classpath. Entries are verified by probing for a Minecraft class
     * instead of being trusted by name, so nothing is loaded from a wrong location.
     */
    private static java.util.List<Path> classpathGameContent() {
        var candidates = new java.util.LinkedHashSet<Path>();
        for (var entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                candidates.add(Path.of(entry));
            }
        }
        try {
            for (var url : ((java.net.URLClassLoader) ClassLoader.getSystemClassLoader()).getURLs()) {
                if ("file".equals(url.getProtocol())) {
                    candidates.add(Path.of(url.toURI()));
                }
            }
        } catch (Throwable ignored) {
            // Not a URLClassLoader (or the URL is not a file) - the class path property is enough.
        }

        var found = new ArrayList<Path>();
        for (var candidate : candidates) {
            if (exposesGameContent(candidate)) {
                found.add(candidate);
            }
        }
        LOGGER.info("[NeoFobric] Game content roots for mixin target resolution (in resolution order): {}", found);
        return found;
    }

    /**
     * {@return whether a classpath entry carries the Minecraft classes} In dev this is the folder of
     * extracted classes, in production the patched Minecraft jar - probing for one class covers both
     * without guessing at file names. A dev entry holding only the client sourceset is accepted too
     * (see {@link #CLIENT_GAME_CONTENT_MARKER}).
     */
    private static boolean exposesGameContent(Path candidate) {
        try {
            if (Files.isDirectory(candidate)) {
                return Files.isRegularFile(candidate.resolve(GAME_CONTENT_MARKER))
                        || Files.isRegularFile(candidate.resolve(CLIENT_GAME_CONTENT_MARKER));
            }
            if (Files.isRegularFile(candidate)) {
                try (var jar = JarContents.ofPath(candidate)) {
                    return jar.readFile(GAME_CONTENT_MARKER) != null || jar.readFile(CLIENT_GAME_CONTENT_MARKER) != null;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[NeoFobric] Failed to probe classpath entry {}", candidate, e);
        }
        return false;
    }

    /**
     * {@return the directory extracted nested jars are cached in}
     */
    public static Path getCacheDirectory() {
        return FMLPaths.GAMEDIR.get().resolve(".neofobric").resolve("processedMods");
    }

    /**
     * {@return the jar on the system classpath that contains the given resource, or null if the
     * resource is absent or not served from a jar file}
     */
    private static Path locateSystemJarContaining(String resourcePath) {
        try {
            var url = ClassLoader.getSystemResource(resourcePath);
            if (url == null) {
                return null;
            }

            var spec = url.toURI().getSchemeSpecificPart();
            int bang = spec.indexOf('!');
            if (bang >= 0) {
                spec = spec.substring(0, bang);
            }
            if (spec.startsWith("file:")) {
                var path = Path.of(URI.create(spec));
                return Files.isRegularFile(path) ? path : null;
            }
            return null;
        } catch (Exception e) {
            LOGGER.debug("[NeoFobric] Failed to locate the system jar containing {}", resourcePath, e);
            return null;
        }
    }

    /**
     * A {@link JarModuleInfo} describing the composite module that contains all Fabric mods.
     * Scanned like an automatic module: every package with classes is part of the module and all
     * {@code META-INF/services} entries are collected as service providers.
     */
    private static final class FabricCompositeModuleInfo implements JarModuleInfo {
        @Override
        public String name() {
            return FABRIC_MODULE_NAME;
        }

        @Override
        public String version() {
            return null;
        }

        @Override
        public java.lang.module.ModuleDescriptor createDescriptor(JarContents contents) {
            var builder = java.lang.module.ModuleDescriptor.newAutomaticModule(name());
            JarModuleInfo.scanAutomaticModule(contents, builder);
            // 复合模块必须登记每个 Fabric jar 的包。FML 的 ModuleClassLoader 按"包名 -> 模块"查表来
            // 定位类，包没登记就直接抛 ClassNotFoundException —— 这正是 Fabric 入口点全部加载失败的原因
            // （Minecraft 自己的包登记在别的模块里，所以只有 mod 的类受影响）。自动模块扫描只覆盖
            // 一部分内容，所以这里对全部内容补扫一遍，重复的包名并集去重即可：所有 Fabric jar 本来就
            // 合并在这一个模块里，包共享是这里的既定设计。
            var packages = new java.util.HashSet<String>();
            contents.visitContent((path, resource) -> collectPackage(packages, path));
            for (var modulePackage : builder.build().packages()) {
                packages.add(modulePackage);
            }
            builder.packages(packages);
            return builder.build();
        }

        /**
         * 从一个资源路径推出它所属的包名。只认类文件：{@code a/b/C.class} 的包是 {@code a.b}，
         * {@code module-info.class} 与 {@code package-info.class} 这一类没有包语义的条目会被
         * 目录深度判断挡掉，不需要依赖 FML 内部的包名合法性判断。
         */
        private static void collectPackage(java.util.Set<String> packages, String path) {
            if (!path.endsWith(".class")) {
                return;
            }
            var lastSlash = path.lastIndexOf('/');
            if (lastSlash < 1) {
                return;
            }
            var directory = path.substring(0, lastSlash);
            // "a/b/C.class" -> "a.b"；单目录段且无点的名字不是包路径（例如模块描述符所在目录）。
            if (!directory.contains("/")) {
                return;
            }
            packages.add(directory.replace('/', '.'));
        }
    }
}
