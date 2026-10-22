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
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
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

    /** A plain mod id, as both loaders spell them. */
    private static final java.util.regex.Pattern MOD_ID = java.util.regex.Pattern.compile("[a-z0-9_.-]+");

    /**
     * {@return the mod id when it is usable as a cache directory name, otherwise null}
     *
     * <p>Mod ids arrive from a jar's own {@code fabric.mod.json}, so they are attacker-controlled
     * input: an id like {@code ../../Downloads} would walk out of the cache directory the moment it
     * is resolved into a path.
     */
    private static String safeCacheId(String modId) {
        return modId != null && MOD_ID.matcher(modId).matches() ? modId : null;
    }

    /**
     * {@return the plain file name of a nested jar entry, or null when it is not one}
     *
     * <p>The same trust question as {@link #safeCacheId}: an entry name is split on both separators
     * (a zip may use either), must come back a plain name, and must not carry {@code ..} or the
     * drive/stream separator {@code :} into a write.
     */
    private static String safeNestedFileName(String nestedJar) {
        var name = nestedJar == null ? "" : nestedJar;
        var slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf(':') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('/') >= 0) {
            return null;
        }
        return name;
    }

    /** The mod ids the NeoForge mods in the mods folder provide, for the current discovery pass. */
    private static volatile java.util.Set<String> fmlProvidedModIds = java.util.Set.of();

    /** The Fabric jars skipped because an FML mod provides the same id; claimed in {@code findCandidates}. */
    private static final java.util.List<Path> fmlProvidedJars = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Packages the FML side of the game layer will provide, so the Fabric composite module must not claim
     * them. Filled while the mods folder is scanned: every jar that is not a Fabric mod (an FML mod, or a
     * jar with no metadata at all) contributes the packages of its classes - recursively, because these
     * jars carry more jars: a NeoForge mod's JiJ modules, and the wrapper artifacts with which a mod hands
     * its own dependencies to the loader. c2me's OpenCL wrapper is the case this exists for: it nests
     * lwjgl-zstd, lwjgl-opencl and friends under {@code META-INF/jars/}, and c2me's language provider adds
     * them to FML's file set, so they become automatic modules of FML's layer ({@code lwjgl.zstd}). A
     * Fabric mod that bundles a copy of the same library - voxy ships lwjgl-zstd for the dedicated servers
     * that have no LWJGL of their own - would put the same package into the Fabric module too, and the JDK
     * refuses to build the layer:
     * <pre>Modules neofobric.fabric.mods and lwjgl.zstd export package org.lwjgl.util.zstd to module jei</pre>
     * Whichever copy the FML side supplies wins; the classes stay visible to both loaders' mods through the
     * shared layer, and a run without the c2me wrapper keeps the Fabric copy because nothing claims the
     * package then.
     */
    private static final java.util.Set<String> fmlSidePackages = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * {@return the mod ids every NeoForge mod in the folder provides}
     *
     * <p>Read before the Fabric jars are examined, because a Fabric jar can only be skipped once it is known
     * that the platform provides the same mod.
     */
    /**
     * {@return the id in the form both loaders agree on}
     *
     * <p>NeoForge normalises mod ids to lower case with hyphens turned into underscores, while Fabric keeps
     * the id as written. Both are the same mod: the NeoForge build of Cloth Config is {@code cloth_config}
     * where the Fabric one is {@code cloth-config}, and without this the lookup below misses the pair, the
     * Fabric jar is claimed as well, and the two module layers fail to resolve because both export
     * {@code me.shedaniel.*} (ResolutionException: Module cloth_config contains package ...).
     */
    private static String normalizeModId(String id) {
        return id.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
    }

    private static java.util.Set<String> readForgeModIds(Path modsDir) {
        var ids = new java.util.HashSet<String>();
        try (var jars = Files.list(modsDir)) {
            for (var jarPath : (Iterable<Path>) jars::iterator) {
                if (Files.isRegularFile(jarPath)
                        && jarPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                    for (var mod : readForgeModsDotToml(jarPath)) {
                        if (!mod[0].isEmpty()) {
                            ids.add(normalizeModId(mod[0]));
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.debug("[NeoFobric] Could not list the mods folder to collect NeoForge mod ids", e);
        }
        return ids;
    }

    /**
     * Marker for entries that only carry the client half of the Minecraft classes. In dev the main
     * and client sourcesets are compiled into separate folders, and only the common one contains
     * {@link #GAME_CONTENT_MARKER} - but client-only classes (for example BakedQuad) are exactly the
     * ones the jar patcher has to resolve to realign client mixins, so a client-only entry counts as
     * game content too.
     */
    private static final String CLIENT_GAME_CONTENT_MARKER = "net/minecraft/client/Minecraft.class";

    /**
     * Metadata files that mark a jar as a NeoForge mod. Such a jar is never claimed by the bridge, so a
     * multi-loader build (the NeoForge Sodium/Iris jars carry both this and a {@code fabric.mod.json})
     * stays with FML.
     */
    private static final java.util.List<String> FML_MOD_METADATA = java.util.List.of(
            "META-INF/neoforge.mods.toml", "META-INF/mods.toml");

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

        // NeoFobric: mod ids the NeoForge mods in this folder provide. A Fabric jar that provides one of them
        // is skipped (see examineJar) - keeping both builds puts the same packages into the FML module layer
        // and into the Fabric one, and the JDK refuses to resolve that (Sodium and Iris both died with
        // "Modules neofobric.fabric.mods and sodium export package net.caffeinemc.mods.sodium.api.util").
        fmlProvidedJars.clear();
        fmlSidePackages.clear();
        fmlProvidedModIds = readForgeModIds(modsDir);

        try (var files = Files.list(modsDir)) {
            var listingStarted = System.nanoTime();
            files.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(path -> examineJar(path, fabricJars, 0));
            LOGGER.info("[NeoFobric] Scanned the mods folder in {} ms: {} Fabric jar(s), {} left to FML",
                    (System.nanoTime() - listingStarted) / 1_000_000L, fabricJars.size(), fmlProvidedJars.size());
        } catch (IOException e) {
            LOGGER.error("[NeoFobric] Failed to list the mods folder", e);
            return;
        }

        if (fabricJars.isEmpty() && fmlProvidedJars.isEmpty()) {
            return;
        }

        // Claim every Fabric jar so no other locator (mods folder, game locator, ...) picks it up. The jars
        // skipped for an FML counterpart are claimed too: FML would otherwise report the Fabric metadata it
        // cannot use as a broken file.
        for (var path : fabricJars.keySet()) {
            context.addLocated(path);
        }
        for (var path : fmlProvidedJars) {
            context.addLocated(path);
        }

        // NeoFobric: FML mods whose requirements only a Fabric mod can satisfy. Iris requires Sodium, and in
        // a mixed pack the Sodium that loads is the Fabric build - FML alone would refuse the whole launch
        // ("Mod iris requires sodium ... which is missing"). Those requirements are relaxed in a rewritten
        // copy of the mod file, which claims the original and takes its place in the pipeline.
        relaxFabricSatisfiableRequirements(context, pipeline, modsDir, fabricJars);

        // NeoFobric: Architectury's NeoForge build resolves a mod's event bus through FML's mod list
        // (`EventBusesHooks.getModEventBus(modId)` -> `ModList.get().getModContainerById(id)`), and a
        // Fabric mod has no entry there - every `DeferredRegister.register()` in a Fabric mod then dies
        // with `NoSuchElementException: No value present` before a single entry is registered.
        // MoreSpearEnchantments is the everyday case: its seven enchantment effect types never register,
        // and the moment a world's data pack decodes those enchantments the world refuses to open
        // (the "当前选中的数据包中出现了错误" screen). Each Architectury-using Fabric mod gets a shell FML
        // mod - its id and version, no code - so the lookup finds a container with an event bus, and
        // Architectury's direct registration path then runs inside the registry window the bridge keeps
        // open for the Fabric entrypoints.
        registerArchitecturyShellMods(pipeline, modsDir, fabricJars);

        // Packages the platform (Minecraft, NeoForge, FML and the libraries they bring) already
        // provides must not be merged into the composite module: a JPMS module may not claim a
        // package another module of the same layer owns, and FML then refuses to start with
        //   Modules neofobric.fabric.mods and neoforge export package net.neoforged.neoforge.common
        // Multi-loader libraries are exactly that case - ForgeConfigAPIPort's Fabric build
        // reimplements the whole net.neoforged config API, and both it and Create Fly bring
        // nightconfig with them. The platform copy is the authoritative implementation on NeoForge,
        // so the Fabric duplicate is shadowed (and reported) instead of being merged.
        var platformPackages = platformProvidedPackages(fabricJars.keySet());

        // A package more than one Fabric jar provides is resolved class by class from the last jar that has it,
        // so a jar bundling an older copy of a module overwrites the newer one piecemeal. That is how fabric-api's
        // ScreenEvents lost afterForeground: the recipe-book mixin was fabric-api's 5.2.1 while the class it calls
        // came from the 5.0.3 copy ModMenu bundles. The newest provider of each duplicated package keeps it, and
        // the older copies are hidden by the same per-jar filter that shadows platform packages.
        var duplicatePackages = duplicateFabricPackages(fabricJars);

        // Merge all Fabric content into one JPMS module to avoid split-package conflicts.
        // contentsList stays index-aligned with fabricPaths, so the realigning pass below can swap
        // individual entries for their rewritten copies.
        var fabricPaths = new ArrayList<Path>(fabricJars.size());
        var contentsList = new ArrayList<JarContents>(fabricJars.size());
        var contentFilters = new ArrayList<JarContents.PathFilter>(fabricJars.size());
        var shadowedPackages = new java.util.concurrent.ConcurrentHashMap<Path, java.util.Set<String>>();
        for (var path : fabricJars.keySet()) {
            try {
                var content = JarContents.ofPath(path);
                fabricPaths.add(path);
                contentsList.add(content);
                var duplicatesHere = duplicatePackages.get(path);
                var shadowed = duplicatesHere == null ? platformPackages
                        : platformPackages.isEmpty() ? duplicatesHere
                                : union(duplicatesHere, platformPackages);
                var danglingServices = danglingServiceFiles(path, platformPackages);
                if (!danglingServices.isEmpty()) {
                    LOGGER.info("[NeoFobric] Hid {} service file(s) whose providers live in a"
                                    + " platform-provided package from {}: {}",
                            danglingServices.size(), path.getFileName(), danglingServices);
                }
                contentFilters.add(shadowed.isEmpty() && danglingServices.isEmpty()
                        ? null
                        : platformShadowFilter(path, shadowed, shadowedPackages, danglingServices));
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
        for (var entry : shadowedPackages.entrySet()) {
            LOGGER.info("[NeoFobric] Shadowed {} platform-provided package(s) from {}: {}",
                    entry.getValue().size(), entry.getKey().getFileName(), entry.getValue());
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
                    contentFilters.add(null);
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
        // Written from every patching thread at once.
        var realignedJars = new java.util.concurrent.ConcurrentHashMap<Path, Path>();
        if (!gameContent.isEmpty()) {
            // Every copy is regenerated this pass, so the cache starts empty - which also stops a
            // removed mod from leaving a copy of itself behind forever.
            clearRealignedCache(modsDir);
            var realigned = 0;
            // One resolver for the whole pass: the jars are opened once and held, and lookups are
            // memoized, instead of reopening the game jar (and walking the mods folder on a miss)
            // for every single target lookup.
            try (var resolver = new MixinJarPatcher.TargetResolver(gameContent, fabricPaths)) {
                // Colliding accessor members are planned before any jar is written: a call site can live
                // in a different jar than the accessor it calls.
                var accessorRenames = MixinJarPatcher.scanAccessorCollisions(fabricPaths, resolver);
                // The patched copy of a jar depends on this whole set (the accessor plan is global), so the
                // set is part of every jar's patch-cache key.
                MixinJarPatcher.noteModSet(fabricPaths);
                var patchingStarted = System.nanoTime();
                var patchedJars = new java.util.concurrent.ConcurrentHashMap<Path, Path>();
                // The jars are independent of each other (the accessor collisions were planned across all of
                // them before this), and patching one is pure bytecode work on that jar, so they go through
                // every core. One jar at a time left eleven of this machine's twelve cores idle during the
                // longest phase of startup.
                fabricPaths.parallelStream().forEach(path -> {
                    var jarStarted = System.nanoTime();
                    try {
                        var effective = MixinJarPatcher.patchJar(path, resolver, accessorRenames);
                        if (!effective.equals(path)) {
                            realignedJars.put(path, effective);
                            patchedJars.put(path, effective);
                        }
                        LOGGER.info("[NeoFobric] Patched {} in {} ms", path.getFileName(),
                                (System.nanoTime() - jarStarted) / 1_000_000L);
                    } catch (Throwable t) {
                        LOGGER.error("[NeoFobric] Failed to patch {}", path.getFileName(), t);
                    }
                });
                LOGGER.info("[NeoFobric] Patched {} Fabric jar(s) in {} ms wall clock on {} threads",
                        fabricPaths.size(), (System.nanoTime() - patchingStarted) / 1_000_000L,
                        Runtime.getRuntime().availableProcessors());
                realigned = patchedJars.size();
                // A patched nested jar has to travel inside its parent as well: fabric-loader unpacks the
                // copies it finds in the parent jar, so a nested mod whose classes were rewritten would
                // otherwise be loaded in its original shape (that is how the platform-namespace shading
                // failed to reach the classpath).
                injectPatchedNestedJars(fabricJars, realignedJars);
            }
            if (realigned > 0) {
                LOGGER.info("[NeoFobric] Realigned Mixin handlers in {} Fabric jar(s)", realigned);
            }
        }

        // Point the game library at the realigned copies where one was produced, keeping the
        // filters aligned with their delegates.
        var gameLibraryContents = new ArrayList<JarContents>(contentsList.size());
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

        // A composite is only needed when it actually combines something or filters something:
        // CompositeJarContents rejects a single unfiltered delegate.
        var filtered = contentFilters.stream().anyMatch(java.util.Objects::nonNull);
        var composite = gameLibraryContents.size() == 1 && !filtered
                ? gameLibraryContents.getFirst()
                : new CompositeJarContents(gameLibraryContents, filtered ? contentFilters : null);
        var moduleInfo = new FabricCompositeModuleInfo(platformPackages);
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

        // Fabric-loader's own discovery walks the mods folder and takes every jar that carries a
        // fabric.mod.json as a Fabric mod. A multi-loader NeoForge jar sitting there (the NeoForge
        // builds of Sodium and Iris do) would then be read as a Fabric mod too, and its Fabric
        // dependency list resolved against a fabric side that is not installed. The mods folder it
        // scans is redirected to a view that links the Fabric jars only, which leaves exactly the same
        // set discoverable there as before minus the jars that belong to FML.
        if (FabricJarRegistry.hasForeignMods()) {
            redirectFabricModsFolder(modsDir, fabricJars, realignedJars);
        }

        // Boot fabric-loader here, not in the FML registration window: discovery has just finished and
        // the mods folder redirect above is in place, which is everything the loader's own mod scan
        // needs. The mod list has to exist before the game layer transforms its first class, because
        // that is when mixin config plugins are constructed - and they legitimately ask the loader for
        // their own mod container. Create's plugin does it through
        // com.zurrtum.create.Create.<clinit> (FabricLoader.getModContainer("create").orElseThrow()),
        // which turned the too-late boot into ExceptionInInitializerError and no startup at all.
        try {
            net.neoforged.fabricbridge.FabricBridge.bootIfNeeded();
        } catch (Throwable t) {
            LOGGER.error("[NeoFobric] Early fabric-loader boot failed - the bridge retries during the"
                    + " registration window, mixin config plugins that ask for their mod container"
                    + " before that will not find it", t);
        }

        LOGGER.info("[NeoFobric] Merged {} Fabric jar(s) into the composite game library", fabricJars.size());
    }

    /**
     * Points fabric-loader at a mods folder holding links to the Fabric jars only, so the NeoForge jars
     * in the real folder cannot be discovered as Fabric mods through that scan either.
     *
     * <p>Only <em>top-level</em> Fabric jars are linked: the nested jars were never reachable through
     * this scan (they live in the bridge cache, not in the mods folder), and leaving that set unchanged
     * is the whole point of the redirect.
     */
    private static void redirectFabricModsFolder(Path modsDir, Map<Path, FabricModInfo> fabricJars,
            java.util.Map<Path, Path> realignedJars) {
        var view = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve(".neofobric").resolve("fabricMods");
        try {
            if (Files.isDirectory(view)) {
                try (var entries = Files.list(view)) {
                    for (var entry : (Iterable<Path>) entries::iterator) {
                        deleteQuietly(entry);
                    }
                }
            } else {
                Files.createDirectories(view);
            }
            var linked = 0;
            for (var path : fabricJars.keySet()) {
                if (!modsDir.equals(path.toAbsolutePath().normalize().getParent())) {
                    continue;
                }
                var link = view.resolve(path.getFileName().toString());
                // Link the rewritten copy when there is one: fabric-loader unpacks nested jars from whatever it
                // finds here, so a parent that only carries a rewritten nested jar has to be the rewritten one.
                var source = realignedJars.getOrDefault(path, path);
                try {
                    Files.createLink(link, source);
                } catch (Exception linkUnavailable) {
                    Files.copy(source, link, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                // fabric-loader may offer it back through addToClassPath; it is game content already.
                FabricJarRegistry.markGameLayerPath(link);
                linked++;
            }
            System.setProperty("fabric.modsFolder", view.toString());
            exposePlatformMods(modsDir, view, fabricJars);
            LOGGER.info("[NeoFobric] Pointed fabric-loader's mods folder at {} ({} Fabric jar(s)); the NeoForge jars in {} stay FML's",
                    view, linked, modsDir);
        } catch (IOException e) {
            LOGGER.error("[NeoFobric] Failed to prepare the Fabric-only mods folder {} - NeoForge jars that ship a fabric.mod.json may be read as Fabric mods",
                    view, e);
        }
    }

    /**
     * Writes the display metadata of every discovered Fabric mod to
     * {@code .neofobric/mods-menu.json}, which NeoForge's mod list screen reads back
     * (see {@code ModListContributions}). Also raises the {@code neofobric.active} flag the screen
     * gates the file read on, so a launch with the bridge disabled never shows a stale list.
     */
    private static void writeModsMenuFile(Map<Path, FabricModInfo> fabricJars, Map<Path, Path> realignedJars) {
        System.setProperty("neofobric.active", "true");
        if (!fabricJars.isEmpty()) {
            // Fabric mods routinely add synced data to vanilla entities through a mixin; NeoForge's
            // verifyEntityDataAccessorRegistration rejects that outright. The bridge sets this flag so
            // the platform logs a warning instead, keeping those mods loadable when both peers run the
            // same Fabric mod set.
            System.setProperty("neofobric.allowForeignEntityData", "true");
        }
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
                        .append(",\"icon\":").append(jsonString(iconInJar(effective, info.iconPath())))
                        .append(",\"root\":").append(jsonString(effective.toString()))
                        .append(",\"bundledBy\":").append(jsonString(info.bundledBy()))
                        .append('}');
            }
            out.append("]}");
            Files.writeString(file, out.toString(), java.nio.charset.StandardCharsets.UTF_8);
            // The main menu's branding line reads this to show both loaders' mods in one count:
            // NeoForge counts its own through ModList, and this is the number of Fabric mods the
            // bridge loaded (one per mod id - nested jars that ship the same module twice collapse,
            // matching what the mod list screen shows).
            System.setProperty("neofobric.fabricModCount", Integer.toString(written));
            LOGGER.info("[NeoFobric] Published {} Fabric mod(s) to the mod list screen manifest", written);
        } catch (IOException e) {
            // Display only: a missing manifest means the mod list simply does not show Fabric mods.
            LOGGER.warn("[NeoFobric] Failed to write the mod list screen manifest {}", file, e);
        }
    }

    /**
     * {@return the icon path when the jar actually carries that entry, else null}
     *
     * <p>The mod list screen resolves a contributed icon against the jar and logs a warning per missing
     * one; the wover modules, for example, declare per-module icons their jars never ship, which would be
     * sixteen warnings on every open of the screen. An icon that is not there is simply not advertised,
     * and the screen draws its placeholder instead.
     */
    private static String iconInJar(Path jarPath, String iconPath) {
        if (iconPath == null || iconPath.isEmpty()) {
            return null;
        }
        try (var zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            return zip.getEntry(iconPath) != null ? iconPath : null;
        } catch (Exception e) {
            LOGGER.debug("[NeoFobric] Could not check the icon {} of {}", iconPath, jarPath, e);
            return null;
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
            // Which loader a jar belongs to follows its metadata, newest first: NeoForge, then Fabric, and
            // only then the Forge era.
            //
            // A jar that declares itself a NeoForge mod belongs to FML, even when it also carries a
            // fabric.mod.json: the multi-loader builds of Sodium and Iris ship both files, and claiming such
            // a jar takes it away from FML entirely - the NeoForge mod never initialises, while fabric-loader
            // resolves the Fabric half of the metadata and dies on the sibling it names ("Mod 'Iris' (iris)
            // requires any 0.9.x version of sodium, which is missing", because the installed Sodium is the
            // NeoForge build).
            //
            // A jar nested inside a Fabric mod is the exception: Fabric's JiJ puts it on the parent's
            // classpath, so a multi-loader library there (SkinShuffle nests SpruceUI) has to stay on the
            // Fabric side even though it also declares neoforge.mods.toml - handing it to FML puts its
            // classes in another module, and the Fabric parent cannot see them (NoClassDefFoundError:
            // dev/lambdaurora/spruceui/screen/SpruceScreen).
            byte[] metadata = jar.readFile(FabricJarRegistry.FABRIC_MOD_JSON);
            if (jar.readFile("META-INF/neoforge.mods.toml") != null && (bundledBy.isEmpty() || metadata == null)) {
                LOGGER.info("[NeoFobric] Leaving {} to FML - it declares META-INF/neoforge.mods.toml",
                        path.getFileName());
                FabricJarRegistry.markForeignModPath(path);
                noteFmlSidePackages(path);
                return;
            }
            // Below NeoForge sits Fabric. A Fabric build that also ships a stray legacy mods.toml is still a
            // Fabric mod - Create: Ultimate Factory's carries one with modLoader="javafml" and
            // loaderVersion="[47,)" left over from a Forge build - and leaving it to FML means FML skips the
            // jar as an old Forge build while the bridge never picks it up either, so the mod never loads.
            if (metadata == null && jar.readFile("META-INF/mods.toml") != null) {
                LOGGER.info("[NeoFobric] Leaving {} to FML - it declares META-INF/mods.toml and no fabric.mod.json",
                        path.getFileName());
                FabricJarRegistry.markForeignModPath(path);
                noteFmlSidePackages(path);
                return;
            }
            if (metadata == null) {
                // Not a Fabric mod. A jar with no metadata can still put its content into the FML layer (a
                // mod's language provider adds the wrapper jars it nests - see fmlSidePackages), so its
                // packages count as FML-side content too.
                noteFmlSidePackages(path);
                return;
            }
            info = FabricModJsonParser.parse(metadata);
            if (info == null) {
                LOGGER.warn("[NeoFobric] {} carries a fabric.mod.json that could not be parsed - the jar is ignored", path.getFileName());
                return;
            }
        } catch (Exception e) {
            LOGGER.warn("[NeoFobric] Could not read {} while checking for Fabric metadata - the jar is ignored", path, e);
            return;
        }

        if (fmlProvidedModIds.contains(normalizeModId(info.modId()))) {
            // NeoFobric: both loaders ship this mod, so only one build may be loaded. The Fabric one is the one
            // to go: FML's dependencies and mixin gating are written against the NeoForge build, and skipping it
            // here is what keeps the two module layers from exporting the same packages (Sodium).
            LOGGER.info("[NeoFobric] Skipping {} - the NeoForge mod {} provides the same mod id, and its build is the one loaded",
                    path.getFileName(), info.modId());
            FabricJarRegistry.markForeignModPath(path);
            fmlProvidedJars.add(path);
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

    /**
     * Makes the platform's NeoForge mods visible to fabric-loader.
     *
     * <p>Fabric mods decide features by asking {@code FabricLoader.isModLoaded(...)} - Create's client
     * mixin plugin is the canonical example: with sodium loaded it adds its Sodium render helper, with
     * iris loaded it adds {@code IrisPipelinesMixin}, which registers Create's FRAPI pipelines
     * ("ponder:pipeline/*") with Iris so shader packs can map them to their programs. In this setup
     * sodium and iris are the <em>NeoForge</em> builds and are not Fabric mods, so the question
     * answered "no": Iris never learned those pipelines and fell back to the vanilla program for them
     * ("Missing program ponder:pipeline/entity_block_solid in override list"), which under a shader
     * pack renders the affected blocks unshaded - visible as transparent-with-shadows geometry.
     *
     * <p>The fix is not to fake containers: every NeoForge mod found in the platform mods folder gets
     * a one-file jar with a {@code fabric.mod.json} carrying its real id, version and name, written
     * into the redirected Fabric mods folder. fabric-loader's own discovery then resolves it like any
     * other mod, so {@code isModLoaded}, {@code getModContainer} and version-range dependency checks
     * all see the platform mods with their true metadata.
     *
     * <p>The loader-side ids ({@code neoforge}, {@code minecraft}, {@code forge}, ...) are left alone:
     * a Fabric mod asking about them would take loader-specific code paths that this bridge does not
     * provide, whereas sodium/iris-style content mods are exactly the ported counterpart the bridge
     * stands on. Ids already claimed by a real Fabric jar are skipped - the real mod wins.
     */
    private static void exposePlatformMods(Path modsDir, Path view, Map<Path, FabricModInfo> fabricJars) {
        var claimed = new java.util.HashSet<String>();
        for (var info : fabricJars.values()) {
            claimed.add(info.modId());
        }
        for (var info : FabricJarRegistry.getEntries().values()) {
            claimed.add(info.modId());
        }

        var blocked = java.util.Set.of("minecraft", "neoforge", "forge", "fml", "fabricloader", "java", "javafml", "lowcodefml");
        var exposed = new ArrayList<String>();
        try (var jars = Files.list(modsDir)) {
            for (var jarPath : (Iterable<Path>) jars::iterator) {
                if (!Files.isRegularFile(jarPath) || !jarPath.toString().endsWith(".jar")) {
                    continue;
                }
                for (var mod : readForgeModsDotToml(jarPath)) {
                    var id = mod[0];
                    if (id.isEmpty() || blocked.contains(id) || claimed.contains(id)) {
                        continue;
                    }
                    // NeoFobric: an id that is still not a plain mod id (an unparsed annotation, a stray
                    // character) is skipped instead of being used as a file name - one malformed mods.toml must
                    // not take the whole locator down, which is exactly what happened: an id of
                    // `"bagus_lib" #mandatory` threw InvalidPathException out of the discovery pass and the
                    // game never started.
                    if (!MOD_ID.matcher(id).matches()) {
                        LOGGER.warn("[NeoFobric] Ignoring the mod id '{}' in {} - it is not a plain mod id",
                                id, jarPath.getFileName());
                        continue;
                    }
                    var version = sanitizeFabricVersion(mod[1]);
                    var name = mod[2].isEmpty() ? id : mod[2];
                    var target = view.resolve("neofobric-platform-" + id + ".jar");
                    try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(target))) {
                        out.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
                        out.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version
                                + "\",\"name\":\"" + escapeJson(name)
                                + "\",\"environment\":\"*\",\"custom\":{\"neofobric\":\"platform-mod\"}}")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        out.closeEntry();
                    }
                    exposed.add(id + " " + version);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[NeoFobric] Failed to expose the NeoForge mods in {} to fabric-loader", modsDir, e);
            return;
        }
        if (!exposed.isEmpty()) {
            LOGGER.info("[NeoFobric] Exposed {} NeoForge mod(s) to fabric-loader (mods probing isModLoaded see them): {}",
                    exposed.size(), String.join(", ", exposed));
        }
    }

    /**
     * {@return {id, version, displayName} of every {@code [[mods]]} entry in the jar's
     *         {@code neoforge.mods.toml}, or an empty list when the jar has none}
     *
     * <p>Deliberately a section-aware line scan rather than a TOML parser: only {@code modId} /
     * {@code version} / {@code displayName} lines inside {@code [[mods]]} are read, so the
     * {@code modId = ...} lines of {@code [[dependencies.x]]} blocks (which name other mods, not this
     * jar's) are never mistaken for a mod.
     */
    private static java.util.List<String[]> readForgeModsDotToml(Path jarPath) {
        var mods = new ArrayList<String[]>();
        try (var zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            var entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                return mods;
            }
            var text = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String id = null;
            String version = "";
            String displayName = "";
            var inModsSection = false;
            for (var rawLine : text.split("\n")) {
                var line = rawLine.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[")) {
                    if (inModsSection && id != null) {
                        mods.add(new String[] { id, version, displayName });
                    }
                    inModsSection = line.startsWith("[[mods]]");
                    if (!inModsSection) {
                        id = null;
                        version = "";
                        displayName = "";
                    }
                    continue;
                }
                if (!inModsSection) {
                    continue;
                }
                var eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                var key = line.substring(0, eq).strip();
                var value = tomlValue(line.substring(eq + 1).strip());
                switch (key) {
                    case "modId" -> id = value;
                    case "version" -> version = value;
                    case "displayName" -> displayName = value;
                    default -> { }
                }
            }
            if (inModsSection && id != null) {
                mods.add(new String[] { id, version, displayName });
            }
        } catch (Exception e) {
            LOGGER.debug("[NeoFobric] Could not read neoforge.mods.toml of {}", jarPath, e);
        }
        return mods;
    }

    /** Suffix of the rewritten copy of an FML mod whose unsatisfiable-in-FML requirements were relaxed. */
    private static final String RELAXED_SUFFIX = ".neofobric-fml.jar";

    /**
     * Relaxes the mod-file requirements that only a Fabric mod can satisfy.
     *
     * <p>FML resolves a mod's dependencies against FML mods alone, so a NeoForge mod that requires a library
     * whose only build on disk is the Fabric one is refused outright - in a mixed pack Iris requires
     * Sodium, and Sodium is the Fabric build there. The requirement is turned into an optional one in a
     * freshly written copy of the mod file (same container, same classes, only that entry changed): the
     * bridge claims the original path so FML's folder locator skips it, and hands the copy to the discovery
     * pipeline instead. The classes keep working because the Fabric build answers for the same code at
     * runtime, and the loader layout link moves the mod's own references onto that build
     * (see compbridlnk.SodiumLoaderLayoutLink).
     *
     * <p>Only requirements whose id no FML mod provides and a loading Fabric mod does are touched; anything
     * else stays exactly as the mod declared it. The declared version range is not evaluated - the
     * substitution is logged so a mismatch is visible.
     */
    private static void relaxFabricSatisfiableRequirements(ILaunchContext context, IDiscoveryPipeline pipeline,
            Path modsDir, Map<Path, FabricModInfo> fabricJars) {
        if (fabricJars.isEmpty()) {
            return;
        }
        var fabricIds = new java.util.HashMap<String, FabricModInfo>();
        for (var info : fabricJars.values()) {
            fabricIds.putIfAbsent(normalizeModId(info.modId()), info);
        }
        var loadedFabricIds = java.util.Set.copyOf(fabricIds.keySet());
        try (var jars = Files.list(modsDir)) {
            for (var jarPath : (Iterable<Path>) jars::iterator) {
                var fileName = jarPath.getFileName().toString();
                if (!Files.isRegularFile(jarPath) || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                var relaxed = planRequirementRelaxations(jarPath, fabricIds);
                if (relaxed == null) {
                    continue;
                }
                var copy = writeRelaxedCopy(modsDir, jarPath, relaxed, loadedFabricIds);
                if (copy == null) {
                    continue;
                }
                context.addLocated(jarPath);
                pipeline.addPath(copy, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
                for (var substitution : relaxed.substitutions()) {
                    LOGGER.info("[NeoFobric] {}: {} - the requirement is relaxed and the Fabric build answers "
                            + "for it at runtime", fileName, substitution);
                }
                LOGGER.info("[NeoFobric] Left {} to the rewritten copy {}", fileName, copy.getFileName());
            }
        } catch (IOException e) {
            LOGGER.debug("[NeoFobric] Could not list the mods folder for requirement relaxation", e);
        }
    }

    private record RelaxedToml(byte[] bytes, java.util.List<String> substitutions) {}

    /** One {@code [[dependencies.<owner>]]} block while it is being evaluated. */
    private static final class DependencyBlock {
        String owner;
        String modId;
        String type;
        int modIdLine = -1;
        int typeLine = -1;
    }

    /**
     * {@return the relaxed {@code META-INF/neoforge.mods.toml} of this jar, or null when every requirement
     *         is already satisfiable within FML}
     */
    private static RelaxedToml planRequirementRelaxations(Path jarPath, Map<String, FabricModInfo> fabricIds) {
        String text;
        try (var zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            var entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                return null;
            }
            text = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("[NeoFobric] Could not read neoforge.mods.toml of {}", jarPath, e);
            return null;
        }

        var lines = new ArrayList<>(java.util.List.of(text.split("\n", -1)));
        var blocks = new ArrayList<DependencyBlock>();
        DependencyBlock current = null;
        for (var i = 0; i < lines.size(); i++) {
            var stripped = lines.get(i).strip();
            if (stripped.startsWith("[[dependencies") && stripped.endsWith("]]")) {
                current = new DependencyBlock();
                current.owner = stripped.substring("[[dependencies".length(), stripped.length() - 2).strip();
                blocks.add(current);
                continue;
            }
            if (stripped.startsWith("[")) {
                current = null;
                continue;
            }
            if (current == null || stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            var eq = stripped.indexOf('=');
            if (eq < 0) {
                continue;
            }
            var key = stripped.substring(0, eq).strip();
            var value = tomlValue(stripped.substring(eq + 1).strip());
            if ("modId".equals(key)) {
                current.modId = value;
                current.modIdLine = i;
            } else if ("type".equals(key)) {
                current.type = value;
                current.typeLine = i;
            }
        }

        // An absent type means required, and only a requirement that no FML mod provides but a loading
        // Fabric mod does gets relaxed. Replacements go in by index; insertions are remembered and applied
        // afterwards in descending order, so the indexes stay valid while the list grows.
        var substitutions = new ArrayList<String>();
        var insertions = new ArrayList<Integer>();
        for (var dep : blocks) {
            if (dep.modId == null || dep.modId.isEmpty()) {
                continue;
            }
            var normalized = normalizeModId(dep.modId);
            var provider = fabricIds.get(normalized);
            if (provider == null || fmlProvidedModIds.contains(normalized)) {
                continue;
            }
            if (dep.type != null && !"required".equals(dep.type)) {
                continue;
            }
            if (dep.typeLine >= 0) {
                lines.set(dep.typeLine, "type = \"optional\"");
            } else {
                insertions.add(dep.modIdLine);
            }
            substitutions.add(dep.owner + " depends on " + dep.modId + ", answered by "
                    + provider.modId() + " " + provider.version());
        }
        if (substitutions.isEmpty()) {
            return null;
        }
        insertions.sort(java.util.Collections.reverseOrder());
        for (var after : insertions) {
            lines.add(after + 1, "type = \"optional\"");
        }
        return new RelaxedToml(String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.util.List.copyOf(substitutions));
    }

    /**
     * Writes the relaxed copy of an FML mod file into the bridge's cache directory.
     *
     * <p>Class entries go through the loader layout link while they are copied: with the Fabric build of
     * Sodium loading, the NeoForge layout the mod was compiled against does not exist, and its references
     * have to point at the Fabric one. Signature files are dropped - a rewritten entry invalidates them.
     */
    private static Path writeRelaxedCopy(Path modsDir, Path jarPath, RelaxedToml relaxed,
            java.util.Set<String> loadedFabricIds) {
        var cacheDir = modsDir.resolve(MixinJarPatcher.CACHE_DIR_NAME);
        var copy = cacheDir.resolve(jarPath.getFileName().toString() + RELAXED_SUFFIX);
        try {
            Files.createDirectories(cacheDir);
            try (var zip = new java.util.zip.ZipFile(jarPath.toFile());
                    var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(copy,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING))) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (isJarSignature(entry.getName())) {
                        continue;
                    }
                    out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                    if (entry.getName().equals("META-INF/neoforge.mods.toml")) {
                        out.write(relaxed.bytes());
                    } else if (entry.getName().endsWith(".class")) {
                        var bytes = zip.getInputStream(entry).readAllBytes();
                        out.write(net.neoforged.fabricbridge.compbridlnk.SodiumLoaderLayoutLink
                                .retargetForFabricBuild(entry.getName(), bytes, loadedFabricIds));
                    } else {
                        zip.getInputStream(entry).transferTo(out);
                    }
                    out.closeEntry();
                }
            }
            return copy;
        } catch (IOException e) {
            LOGGER.error("[NeoFobric] Could not write the relaxed copy of {}", jarPath, e);
            return null;
        }
    }

    private static boolean isJarSignature(String entryName) {
        var upper = entryName.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    private static final String SHELL_SUFFIX = ".neofobric-shell.jar";

    /**
     * NeoFobric: writes the shell FML mod for every Fabric mod that ships Architectury classes, so
     * Architectury's NeoForge build can resolve an event bus for it (see the call site). The shell
     * carries nothing but the mod metadata: same id and version as the Fabric mod, no classes, so it
     * cannot conflict with the Fabric side's copy of the mod.
     */
    private static void registerArchitecturyShellMods(IDiscoveryPipeline pipeline, Path modsDir,
            Map<Path, FabricModInfo> fabricJars) {
        if (fabricJars.isEmpty()) {
            return;
        }
        var cacheDir = modsDir.resolve(MixinJarPatcher.CACHE_DIR_NAME);
        var shellIds = new ArrayList<String>();
        var writtenShellIds = new java.util.HashSet<String>();
        for (var entry : fabricJars.entrySet()) {
            var jarPath = entry.getKey();
            var info = entry.getValue();
            var displayName = info.displayName() == null || info.displayName().isBlank()
                    ? info.modId() : info.displayName();
            if (!net.neoforged.fabricbridge.compbridlnk.ArchitecturyCompat.usesArchitectury(jarPath)) {
                continue;
            }
            // Fabric allows hyphens in mod ids; FML does not. The shell only exists so FML can hand the
            // Fabric mod an event bus, so its FML id can differ from the Fabric id (nothing depends on it).
            var shellId = fmlShellModId(info.modId());
            if (!writtenShellIds.add(shellId)) {
                continue;
            }
            var shell = cacheDir.resolve(jarPath.getFileName().toString() + SHELL_SUFFIX);
            try {
                Files.createDirectories(cacheDir);
                var toml = ("modLoader=\"javafml\"\n"
                        + "loaderVersion=\"[3,)\"\n"
                        + "license=\"See the Fabric build of this mod\"\n"
                        + "\n"
                        + "[[mods]]\n"
                        + "modId=\"" + shellId + "\"\n"
                        + "version=\"" + info.version() + "\"\n"
                        + "displayName=\"" + displayName.replace("\"", "'") + " (Fabric, bridged)\"\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(shell,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING))) {
                    out.putNextEntry(new java.util.zip.ZipEntry("META-INF/neoforge.mods.toml"));
                    out.write(toml);
                    out.closeEntry();
                }
            } catch (IOException e) {
                LOGGER.error("[NeoFobric] Could not write the shell mod copy for {}", jarPath, e);
                continue;
            }
            pipeline.addPath(shell, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
            shellIds.add(shellId);
            LOGGER.info("[NeoFobric] Registered the shell FML mod {} ({}) {} so the Fabric build's Architectury "
                    + "registrations can resolve it", shellId, info.modId(), info.version());
        }
        if (!shellIds.isEmpty()) {
            // The platform re-posts the RegisterEvent pass for these buses right behind the Fabric
            // entrypoints (Architectury's listeners arrive too late for the first pass): see
            // GameData.postRegisterEventsForShellMods.
            System.setProperty("neofobric.shellMods", String.join(",", shellIds));
        }
    }

    /** {@return a mod id that passes FML's {@code ^[a-z][a-z0-9_]*$} check} */
    private static String fmlShellModId(String modId) {
        var id = modId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (id.isEmpty() || id.charAt(0) < 'a' || id.charAt(0) > 'z') {
            id = "fabric_" + id;
        }
        return id;
    }

    /**
     * {@return the value of one TOML assignment in a mods.toml, comment and all}
     *
     * <p>NeoForge's MDK template writes its entries with a trailing annotation - {@code modId = "bagus_lib"
     * #mandatory} - and a plain unquote hands back {@code "bagus_lib" #mandatory} (quotes and comment included),
     * which then went on to become a file name. The comment is cut first, then the quotes.
     */
    private static String tomlValue(String value) {
        var inSingle = false;
        var inDouble = false;
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                value = value.substring(0, i);
                break;
            }
        }
        return unquoteToml(value.strip());
    }

    private static String unquoteToml(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * {@return the version as fabric-loader's version parser accepts it} FML versions are often
     * placeholders or loose strings ({@code ${file.jarVersion}); fabric-loader rejects anything that
     * is not a version, so those degrade to {@code 0.0.0} instead of failing resolution.
     */
    private static String sanitizeFabricVersion(String version) {
        if (version == null || version.isBlank() || version.contains("${")) {
            return "0.0.0";
        }
        var cleaned = version.replaceAll("[^0-9A-Za-z.+_-]", "-");
        return cleaned.isEmpty() ? "0.0.0" : cleaned;
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
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

        var fileName = safeNestedFileName(nestedJar);
        var cacheId = safeCacheId(parentInfo.modId());
        if (fileName == null || cacheId == null) {
            throw new IOException("Refusing to extract nested jar '" + nestedJar + "' of mod '"
                    + parentInfo.modId() + "': the entry name or the mod id is not usable as a file name");
        }

        var targetDir = getCacheDirectory().resolve(cacheId);
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
    /**
     * {@return the packages the platform classpath provides out of the packages the Fabric jars
     * claim} Only the intersection is material: it is exactly the set of packages the composite
     * module must not claim itself. Everything the platform classpath exposes counts as platform
     * content - in dev the extracted class folders plus FML/NeoForge/library jars, in production
     * the patched game jars plus the launcher libraries.
     */
    private static java.util.Set<String> platformProvidedPackages(java.util.Collection<Path> fabricJars) {
        var fabricPackages = new java.util.HashSet<String>();
        for (var path : fabricJars) {
            try (var contents = JarContents.ofPath(path)) {
                contents.visitContent((relativePath, resource) -> {
                    var packageName = packageOfEntry(relativePath);
                    if (packageName != null) {
                        fabricPackages.add(packageName);
                    }
                });
            } catch (Exception e) {
                LOGGER.debug("[NeoFobric] Failed to index the packages of {}", path, e);
            }
        }
        if (fabricPackages.isEmpty()) {
            return java.util.Set.of();
        }

        var platform = new java.util.LinkedHashSet<String>();
        // NeoFobric: the platform's own namespaces are platform content no matter where their classes
        // happen to live. In an installed client NeoForge and FML sit in the "neoforge"/"fml"/"cpw.mods"
        // modules of the transforming layer, so the classpath probe below finds nothing there, and a
        // Fabric jar carrying a copy of those classes (ForgeConfigAPIPort ships the config API) then
        // shares the package with the platform module and the whole layer is refused:
        //   Modules neofobric.fabric.mods and neoforge export package net.neoforged.neoforge.client.gui
        // Only these two namespaces are taken as given: the platform really does provide them on every
        // NeoForge install, unlike "net.minecraftforge.*" (the pre-1.20.5 Forge namespace), which only
        // ForgeConfigAPIPort provides and which must therefore stay with the Fabric jar that ships it.
        for (var packageName : fabricPackages) {
            if (isPlatformNamespace(packageName) || isAlwaysPlatformLibrary(packageName)) {
                platform.add(packageName);
            }
        }
        for (var root : systemClasspathRoots()) {
            try {
                if (Files.isDirectory(root)) {
                    for (var packageName : fabricPackages) {
                        if (Files.isDirectory(root.resolve(packageName.replace('.', '/')))) {
                            platform.add(packageName);
                        }
                    }
                } else if (Files.isRegularFile(root)) {
                    try (var contents = JarContents.ofPath(root)) {
                        contents.visitContent((relativePath, resource) -> {
                            var packageName = packageOfEntry(relativePath);
                            if (packageName != null && fabricPackages.contains(packageName)) {
                                platform.add(packageName);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("[NeoFobric] Failed to scan the classpath entry {}", root, e);
            }
            if (platform.size() == fabricPackages.size()) {
                break;
            }
        }
        // Packages the FML side will provide count the same as the classpath's: the jars that are not
        // Fabric mods (and what those jars nest) end up in FML's layer, and a package exported by both
        // modules makes the JDK refuse to build the layer - see fmlSidePackages for the c2me/voxy case.
        for (var packageName : fabricPackages) {
            if (fmlSidePackages.contains(packageName)) {
                platform.add(packageName);
            }
        }
        return platform;
    }

    /**
     * Records the packages of a jar that belongs to FML's side of the layer, nested jars included.
     *
     * <p>Called for every mods-folder jar that the bridge does not claim as a Fabric mod. The nesting
     * matters: an FML mod's JiJ modules and the wrapper jars a mod hides under {@code META-INF/jars/}
     * (c2me's OpenCL wrapper keeps lwjgl-zstd and friends there) are added to the FML layer at runtime,
     * and their packages then clash with a Fabric mod that bundles the same library.
     */
    private static void noteFmlSidePackages(Path jar) {
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            collectPackages(zip, 0);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not index the packages of the FML-side jar {}", jar, t);
        }
    }

    private static void collectPackages(java.util.zip.ZipFile zip, int depth) throws IOException {
        var nested = new java.util.ArrayList<java.util.zip.ZipEntry>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            var name = entry.getName();
            if (name.endsWith(".class")) {
                var packageName = packageOfEntry(name);
                if (packageName != null) {
                    fmlSidePackages.add(packageName);
                }
            } else if (depth < 2 && name.endsWith(".jar")) {
                nested.add(entry);
            }
        }
        for (var entry : nested) {
            Path temp = null;
            try {
                temp = Files.createTempFile("neofobric-fmlside", ".jar");
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                try (var inner = new java.util.zip.ZipFile(temp.toFile())) {
                    collectPackages(inner, depth + 1);
                }
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Could not read the nested jar {} of an FML-side mod jar", entry.getName(), t);
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                        // A leftover temp file is harmless.
                    }
                }
            }
        }
    }

    /**
     * {@return whether {@code packageName} belongs to the platform (FML, NeoForge, the boot launcher)}
     * Those namespaces are provided by the game layer on every NeoForge install, so a Fabric jar that
     * ships them must not merge them into the composite module - see
     * {@link #platformProvidedPackages}.
     */
    private static boolean isPlatformNamespace(String packageName) {
        return packageName.equals("net.neoforged") || packageName.startsWith("net.neoforged.")
                || packageName.equals("cpw.mods") || packageName.startsWith("cpw.mods.");
    }

    /**
     * {@return whether the package belongs to a library the platform always provides}
     *
     * <p>MixinExtras travels inside the platform's own {@code mixinextras.neoforge} module, which FML
     * adds to the transforming layer on every install and which exports its packages to the game's
     * module. A Fabric jar bundling its own copy of the library (ViaFabricPlus and IAS do) then files
     * the same package into the composite module, and the JDK refuses the whole layer:
     *   Modules mixinextras.neoforge and neofobric.fabric.mods export package
     *   com.llamalad7.mixinextras.sugar.ref to module minecraft
     * This library is not on the launch classpath and not a mods-folder jar, so neither probe in
     * {@link #platformProvidedPackages} could see it; the platform's copy stays and the bundled one is
     * shadowed, exactly like the platform namespaces above.
     */
    private static boolean isAlwaysPlatformLibrary(String packageName) {
        return packageName.equals("com.llamalad7.mixinextras")
                || packageName.startsWith("com.llamalad7.mixinextras.");
    }

    /**
     * {@return the packages to hide per jar, because the same Fabric mod is present in a newer version elsewhere}
     * The composite resolves a class from the last jar that carries it, so two copies of one module overwrite each
     * other piecemeal - a mixin from the newer copy calling into the older one's API, which is where
     * {@code NoSuchMethodError}s like fabric-api's {@code ScreenEvents.afterForeground} come from. Mods that
     * bundle Fabric API modules are the usual source of the second copy.
     *
     * <p>The rule is per mod, never per package: a module's packages have to come from one place, or its own
     * classes end up split across two jars and fail to initialise (that is how the first attempt broke
     * fabric-resource-loader). The copy with the highest version keeps all of its packages, the others lose
     * all of theirs.
     */
    private static java.util.Map<Path, java.util.Set<String>> duplicateFabricPackages(Map<Path, FabricModInfo> fabricJars) {
        var copies = new java.util.HashMap<String, java.util.List<Map.Entry<Path, FabricModInfo>>>();
        for (var entry : fabricJars.entrySet()) {
            copies.computeIfAbsent(entry.getValue().modId(), key -> new java.util.ArrayList<>()).add(entry);
        }

        var hidden = new java.util.HashMap<Path, java.util.Set<String>>();
        for (var modCopies : copies.entrySet()) {
            if (modCopies.getValue().size() < 2) {
                continue;
            }
            var winner = modCopies.getValue().getFirst();
            for (var candidate : modCopies.getValue()) {
                if (compareModuleVersions(candidate.getValue().version(), winner.getValue().version()) > 0) {
                    winner = candidate;
                }
            }
            for (var candidate : modCopies.getValue()) {
                if (candidate.getKey().equals(winner.getKey())) {
                    continue;
                }
                var packages = packageNamesOf(candidate.getKey());
                if (packages.isEmpty()) {
                    continue;
                }
                hidden.computeIfAbsent(candidate.getKey(), key -> new java.util.LinkedHashSet<>()).addAll(packages);
                LOGGER.info("[NeoFobric] Hid the older copy of Fabric mod {} ({}) from {} - {} {} keeps its {} package(s)",
                        modCopies.getKey(), candidate.getValue().version(), candidate.getKey().getFileName(),
                        winner.getValue().modId(), winner.getValue().version(), packages.size());
            }
        }
        return hidden;
    }

    /** {@return every package the jar provides} */
    private static java.util.Set<String> packageNamesOf(Path jar) {
        var packages = new java.util.LinkedHashSet<String>();
        try (var contents = JarContents.ofPath(jar)) {
            contents.visitContent((relativePath, resource) -> {
                var packageName = packageOfEntry(relativePath);
                if (packageName != null) {
                    packages.add(packageName);
                }
            });
        } catch (Exception e) {
            LOGGER.debug("[NeoFobric] Failed to index the packages of {}", jar, e);
        }
        return packages;
    }

    /** {@return the union of two package sets} */
    private static java.util.Set<String> union(java.util.Set<String> first, java.util.Set<String> second) {
        var union = new java.util.LinkedHashSet<String>(first);
        union.addAll(second);
        return union;
    }

    /**
     * {@return how two Fabric module versions compare} Versions carry a build suffix after {@code +} that never
     * takes part in the ordering, and are otherwise dotted numbers; anything that does not parse compares as
     * text, so an unusual version can only order wrongly, never throw.
     */
    private static int compareModuleVersions(String first, String second) {
        var left = stripBuildSuffix(first);
        var right = stripBuildSuffix(second);
        var leftParts = left.split("\\.");
        var rightParts = right.split("\\.");
        for (var i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
            var leftPart = i < leftParts.length ? leftParts[i] : "0";
            var rightPart = i < rightParts.length ? rightParts[i] : "0";
            int comparison;
            try {
                comparison = Integer.compare(Integer.parseInt(leftPart.trim()), Integer.parseInt(rightPart.trim()));
            } catch (NumberFormatException notNumeric) {
                comparison = leftPart.compareTo(rightPart);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return left.compareTo(right);
    }

    private static String stripBuildSuffix(String version) {
        var plus = version.indexOf('+');
        return plus < 0 ? version : version.substring(0, plus);
    }

    /**
     * {@return a filter that hides the classes of {@code jar} living in {@link
     * #platformProvidedPackages platform-provided packages}, and the {@code META-INF/services} files
     * named in {@code hiddenServiceFiles}} Those classes cannot be part of the composite module
     * (split package), and the platform implementation is the one that should be used anyway. Other
     * non-class entries (assets, fabric.mod.json, ...) are never filtered. What was hidden is
     * recorded in {@code shadowedPackages} for the startup report.
     */
    private static JarContents.PathFilter platformShadowFilter(
            Path jar, java.util.Set<String> platformPackages, Map<Path, java.util.Set<String>> shadowedPackages,
            java.util.Set<String> hiddenServiceFiles) {
        return relativePath -> {
            if (hiddenServiceFiles.contains(relativePath)) {
                return false;
            }
            var packageName = packageOfEntry(relativePath);
            if (packageName == null || !platformPackages.contains(packageName)) {
                return true;
            }
            shadowedPackages.computeIfAbsent(jar, key -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                    .add(packageName);
            return false;
        };
    }

    /**
     * {@return the {@code META-INF/services} files of {@code jar} whose listed providers live in a
     * platform-provided package} Filtering the classes of such a package out of the composite leaves
     * the service file pointing at a class the module no longer contains, and FML's module scan
     * rejects that outright ({@code InvalidModuleDescriptorException}: service not in this Jar file)
     * during startup. Annotation processors are the usual occupant: a NeoForge mod nesting its own
     * flavor of a library whose Fabric twin is loaded through the bridge puts the Fabric copy's
     * classes behind the platform's (AsyncParticles' mixinsquared next to c2me's Fabric copy).
     */
    private static java.util.Set<String> danglingServiceFiles(Path jar, java.util.Set<String> platformPackages) {
        if (platformPackages.isEmpty()) {
            return java.util.Set.of();
        }
        var dangling = new java.util.HashSet<String>();
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("META-INF/services/")) {
                    continue;
                }
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(zip.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        var comment = line.indexOf('#');
                        if (comment >= 0) {
                            line = line.substring(0, comment);
                        }
                        line = line.trim();
                        if (line.isEmpty()) {
                            continue;
                        }
                        var lastDot = line.lastIndexOf('.');
                        if (lastDot > 0 && platformPackages.contains(line.substring(0, lastDot))) {
                            dangling.add(entry.getName());
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not inspect the service files of {}", jar, t);
        }
        return dangling;
    }

    /**
     * {@return the package a class entry belongs to, or null if the entry is not a class or has no
     * package} Multi-release jars store classes as {@code META-INF/versions/<n>/a/b/C.class} while
     * they are loaded as {@code a.b.C}, so the version prefix is stripped - and keeping it would
     * produce the illegal package name {@code META-INF.versions.17.a.b} that JPMS rejects outright.
     */
    private static String packageOfEntry(String relativePath) {
        if (!relativePath.endsWith(".class")) {
            return null;
        }
        if (relativePath.startsWith("META-INF/versions/")) {
            var versionEnd = relativePath.indexOf('/', "META-INF/versions/".length());
            if (versionEnd < 0) {
                return null;
            }
            relativePath = relativePath.substring(versionEnd + 1);
        } else if (relativePath.startsWith("META-INF/")) {
            return null;
        }
        var lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash < 1) {
            return null;
        }
        var directory = relativePath.substring(0, lastSlash);
        // A single-segment directory (twilightforest/Foo.class) is a normal JPMS package named
        // "twilightforest", not the default package. Only a path with no directory at all is the
        // default package, which lastSlash < 1 already rejected.
        var packageName = directory.replace('/', '.');
        return isValidPackageName(packageName) ? packageName : null;
    }

    /** {@return whether every segment of the name is a legal Java identifier} */
    private static boolean isValidPackageName(String name) {
        var start = 0;
        while (start < name.length()) {
            var end = name.indexOf('.', start);
            if (end < 0) {
                end = name.length();
            }
            if (!Character.isJavaIdentifierStart(name.charAt(start))) {
                return false;
            }
            for (var i = start + 1; i < end; i++) {
                if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                    return false;
                }
            }
            start = end + 1;
        }
        return true;
    }

    /**
     * {@return every root the system classloader searches} The launcher feeds FML and the game
     * through the classpath in both dev and production, so this is the platform's own view of
     * itself - no need to guess at directory names or jar file names.
     */
    private static java.util.Set<Path> systemClasspathRoots() {
        var roots = new java.util.LinkedHashSet<Path>();
        for (var entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                roots.add(Path.of(entry));
            }
        }
        try {
            for (var url : ((java.net.URLClassLoader) ClassLoader.getSystemClassLoader()).getURLs()) {
                if ("file".equals(url.getProtocol())) {
                    roots.add(Path.of(url.toURI()));
                }
            }
        } catch (Throwable ignored) {
            // Not a URLClassLoader (or the URL is not a file) - the class path property is enough.
        }
        return roots;
    }

    public static java.util.List<Path> gameContentRoots() {
        return classpathGameContent();
    }

    private static java.util.List<Path> classpathGameContent() {
        // The installed game first: that jar is the one the runtime classes come from, and handler realignment
        // has to be resolved against exactly that. The class path is not a safe substitute on a production
        // client - there it leads to the launcher's own versions/<id>/<id>.jar, the *unpatched* game, and a
        // handler whose target NeoForge re-ordered then looks like it needs no rewrite at all. Fabric's loot
        // api realigns one in ReloadableServerRegistries, and the mismatch that followed from resolving
        // against the vanilla jar was a VerifyError that killed the client as soon as it loaded server
        // resources. In dev there is no installed game, so the class path (the extracted classes) is used.
        var found = new ArrayList<Path>(installedGameContent());
        if (found.isEmpty()) {
            for (var candidate : systemClasspathRoots()) {
                if (exposesGameContent(candidate)) {
                    found.add(candidate);
                }
            }
        }
        LOGGER.info("[NeoFobric] Game content roots for mixin target resolution (in resolution order): {}", found);
        return found;
    }

    /**
     * {@return the patched Minecraft jars from the install's library directory, most recently installed first}
     *
     * <p>These describe the game as it runs. A production dedicated server has nothing else to offer - the game
     * lives in its own module layer rather than on the class path - and without a game to resolve against the
     * whole realigning pass used to be skipped there, which cost everything the bridge does to the jars: the
     * config registration redirect never happened, ForgeConfigAPIPort threw {@code NoSuchMethodError} inside
     * Twilight Forest's entrypoint, and the server died loading registries that referred to those
     * registrations. A production client does have a game on its class path, but the wrong one, so it uses
     * these too (see {@link #classpathGameContent()}).
     */
    private static java.util.List<Path> installedGameContent() {
        var found = new ArrayList<Path>();
        var patchedRoot = FMLPaths.GAMEDIR.get().resolve("libraries").resolve("net").resolve("neoforged");
        // Whichever side this is, the newest install of either jar describes the same patched game; a
        // client-only or server-only install simply has one of them.
        for (var name : new String[] { "minecraft-client-patched", "minecraft-server-patched" }) {
            var versions = patchedRoot.resolve(name);
            if (!Files.isDirectory(versions)) {
                continue;
            }
            var byNewest = new ArrayList<Path>();
            try (var entries = Files.list(versions)) {
                entries.filter(Files::isDirectory).forEach(byNewest::add);
            } catch (IOException e) {
                LOGGER.debug("[NeoFobric] Failed to list the installed game versions under {}", versions, e);
                continue;
            }
            // Newest first, by when the version directory was written: an install that was upgraded leaves the
            // older game behind, and resolving mixin targets against it would realign handlers for a Minecraft
            // that is not the one running. (Ordering by name would misread 26.2.0.99 as newer than 26.2.0.117.)
            byNewest.sort(java.util.Comparator.comparingLong(BridgeFabricModLocator::lastModified).reversed());
            for (var versionDir : byNewest) {
                try (var jars = Files.list(versionDir)) {
                    for (var jar : jars.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
                        if (exposesGameContent(jar)) {
                            found.add(jar);
                        }
                    }
                } catch (IOException e) {
                    LOGGER.debug("[NeoFobric] Failed to list the game jars under {}", versionDir, e);
                }
                if (!found.isEmpty()) {
                    break;
                }
            }
            if (!found.isEmpty()) {
                break;
            }
        }
        return found;
    }

    /** {@return when a path was last written, or 0 when that cannot be read} Used to rank installed games. */
    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
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
     * Marker file identifying a classpath entry that exposes Minecraft's own data files.
     */
    private static final String VANILLA_DATA_MARKER = "data/minecraft/tags/block/mineable/pickaxe.json";

    /** The classpath entries carrying Minecraft's data, found on the first call. */
    private static volatile java.util.List<Path> vanillaDataRoots;

    /**
     * {@return the classpath entries that carry Minecraft's own data files}
     *
     * <p>Fabric's {@code minecraft} mod is built from the paths the bridge hands it, and Fabric APIs read vanilla
     * files by path: the client tag API walks the mods' jars for tag files, and a tag a mod aliases to a vanilla
     * one (its {@code c:ores} against {@code #minecraft:diamond_ores}) dead-ends unless the entry holding the
     * vanilla data is one of them. In dev the patched game jar holds classes only - the data lives in the
     * resources jar neodev assembles - and this has to be probed for, because the paths are needed while FML is
     * still discovering mods, long before any mod file can be queried.
     */
    public static java.util.List<Path> vanillaDataRoots() {
        var cached = vanillaDataRoots;
        if (cached != null) {
            return cached;
        }

        var found = new ArrayList<Path>();
        for (var candidate : systemClasspathRoots()) {
            if (exposesVanillaData(candidate)) {
                found.add(candidate);
            }
        }

        cached = java.util.List.copyOf(found);
        vanillaDataRoots = cached;
        LOGGER.info("[NeoFobric] Classpath entries carrying Minecraft's data: {}", cached);
        return cached;
    }

    private static boolean exposesVanillaData(Path candidate) {
        try {
            if (Files.isDirectory(candidate)) {
                return Files.isRegularFile(candidate.resolve(VANILLA_DATA_MARKER));
            }
            if (Files.isRegularFile(candidate)) {
                try (var jar = JarContents.ofPath(candidate)) {
                    return jar.readFile(VANILLA_DATA_MARKER) != null;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[NeoFobric] Failed to probe classpath entry {} for data", candidate, e);
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
     * Replaces the nested jars inside a rewritten parent jar with the copies that were rewritten too.
     *
     * <p>Nested jars are extracted and patched as mods of their own, but the parent jar still carries the
     * original bytes of each one. fabric-loader reads mods from the parent (it unpacks {@code META-INF/jars}
     * entries itself), so anything a pass changed inside a nested jar - a rewritten mixin, a shaded class -
     * only takes effect if the same bytes are also inside the parent. A parent that needed no rewrite of its
     * own gets a rewritten copy here for exactly that reason.
     */
    private static void injectPatchedNestedJars(Map<Path, FabricModInfo> fabricJars,
            java.util.Map<Path, Path> realignedJars) {
        var injected = 0;
        for (var entry : fabricJars.entrySet()) {
            var parent = entry.getKey();
            var info = entry.getValue();
            if (info.nestedJarPaths().isEmpty()) {
                continue;
            }
            var replacements = nestedReplacements(info.modId(), info.nestedJarPaths());
            if (replacements.isEmpty()) {
                continue;
            }
            try {
                var target = realignedJars.get(parent);
                if (target == null) {
                    target = MixinJarPatcher.realignedPath(parent);
                    Files.copy(parent, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                var replaced = replaceNestedEntries(target, replacements);
                if (replaced > 0) {
                    realignedJars.put(parent, target);
                    injected += replaced;
                    LOGGER.info("[NeoFobric] Carried {} rewritten nested jar(s) inside {}", replaced,
                            target.getFileName());
                }
            } catch (Exception e) {
                LOGGER.warn("[NeoFobric] Could not carry the rewritten nested jars of {} along", parent, e);
            }
        }
        if (injected > 0) {
            LOGGER.info("[NeoFobric] {} rewritten nested jar(s) now travel inside their parent jars", injected);
        }
    }

    /** {@return the nested entry name -> the rewritten copy that should replace it} */
    private static java.util.Map<String, Path> nestedReplacements(String parentModId,
            java.util.List<String> nestedJarPaths) {
        var replacements = new java.util.HashMap<String, Path>();
        var cacheId = safeCacheId(parentModId);
        if (cacheId == null) {
            return replacements;
        }
        for (var nested : nestedJarPaths) {
            var fileName = safeNestedFileName(nested);
            if (fileName == null) {
                continue;
            }
            var patched = getCacheDirectory().resolve(cacheId)
                    .resolve(fileName + ".neofobric-realigned.jar");
            if (Files.isRegularFile(patched)) {
                replacements.put(nested, patched);
            }
        }
        return replacements;
    }

    /** {@return how many nested entries were replaced} */
    private static int replaceNestedEntries(Path parentRealigned, java.util.Map<String, Path> replacements)
            throws IOException {
        var temporary = parentRealigned.resolveSibling(parentRealigned.getFileName() + ".repacking");
        var replaced = 0;
        try (var zip = new java.util.zip.ZipFile(parentRealigned.toFile());
                var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(temporary))) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                var entry = it.nextElement();
                var patched = replacements.get(entry.getName());
                var outEntry = new java.util.zip.ZipEntry(entry.getName());
                if (entry.getMethod() == java.util.zip.ZipEntry.STORED && patched == null) {
                    outEntry.setMethod(java.util.zip.ZipEntry.STORED);
                    outEntry.setSize(entry.getSize());
                    outEntry.setCompressedSize(entry.getSize());
                    outEntry.setCrc(entry.getCrc());
                }
                out.putNextEntry(outEntry);
                if (patched != null) {
                    Files.copy(patched, out);
                    replaced++;
                } else if (!entry.isDirectory()) {
                    zip.getInputStream(entry).transferTo(out);
                }
                out.closeEntry();
            }
        }
        Files.move(temporary, parentRealigned, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return replaced;
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
        /** Packages the platform owns; the composite module must not claim them (split package). */
        private final java.util.Set<String> platformPackages;
        /**
         * Classes that are not on the platform but sit in one of its packages. They are dropped:
         * JPMS cannot put a single class of the composite into the platform's package. This is
         * expected to stay empty - it only fills when a mod adds classes into platform packages.
         */
        private final java.util.Set<String> strandedClasses = java.util.concurrent.ConcurrentHashMap.newKeySet();

        FabricCompositeModuleInfo(java.util.Set<String> platformPackages) {
            this.platformPackages = platformPackages;
        }

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
            // 复合模块必须登记每个 Fabric jar 的包。FML 的 ModuleClassLoader 按"包名 -> 模块"查表来
            // 定位类，包没登记就直接抛 ClassNotFoundException —— 这正是 Fabric 入口点全部加载失败的原因
            // （Minecraft 自己的包登记在别的模块里，所以只有 mod 的类受影响）。
            var packages = new java.util.HashSet<String>();
            var serviceFiles = new java.util.ArrayList<String>();
            contents.visitContent((path, resource) -> {
                collectPackage(packages, path);
                if (path.startsWith("META-INF/services/") && !path.endsWith("/")) {
                    serviceFiles.add(path);
                }
            });
            builder.packages(packages);
            // 不能调用 scanAutomaticModule：只要有一个 service 指向不存在的可选 provider
            // (Jackson core 的 ObjectCodec -> 未安装的 databind ObjectMapper)，它就会整份失败。
            // 那会把 IrisPlatformHelpers 这类合法 ServiceLoader 入口也一起丢掉，Iris 的
            // Mixin plugin 随即 NPE。这里逐条校验 provider 类是否真的在复合模块里，只丢坏条目。
            var seenProviders = new java.util.HashSet<String>();
            for (var serviceFile : serviceFiles) {
                var serviceName = serviceFile.substring("META-INF/services/".length());
                String text;
                try {
                    text = new String(contents.readFile(serviceFile), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    LOGGER.debug("[NeoFobric] Could not read service file {}", serviceFile, e);
                    continue;
                }
                for (var raw : text.split("\\R")) {
                    var line = raw;
                    var comment = line.indexOf('#');
                    if (comment >= 0) {
                        line = line.substring(0, comment);
                    }
                    line = line.strip();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (!contents.containsFile(line.replace('.', '/') + ".class")) {
                        LOGGER.debug("[NeoFobric] Dropping stale service provider {} for {}", line, serviceName);
                        continue;
                    }
                    if (!seenProviders.add(serviceName + " " + line)) {
                        continue;
                    }
                    try {
                        builder.provides(serviceName, java.util.List.of(line));
                    } catch (RuntimeException e) {
                        LOGGER.debug("[NeoFobric] Dropping invalid service entry {} -> {}", serviceName, line, e);
                    }
                }
            }
            if (!strandedClasses.isEmpty()) {
                LOGGER.warn("[NeoFobric] Dropped {} class(es) that live in a platform-owned package and"
                                + " therefore cannot be loaded from the Fabric composite module: {}",
                        strandedClasses.size(), strandedClasses.stream().limit(10).toList());
            }
            return builder.build();
        }

        /**
         * 从一个资源路径推出它所属的包名，只认类文件。平台已经拥有的包会被跳过：复合模块声明它会直接
         * 让模块解析失败（ResolutionException: Modules neofobric.fabric.mods and neoforge export
         * package ...）。同名类的副本在进入这里之前就已被内容过滤器挡掉了，所以这里挡下的是平台没有
         * 的新类，记进 {@link #strandedClasses} 供启动日志报告。
         */
        private void collectPackage(java.util.Set<String> packages, String path) {
            var packageName = packageOfEntry(path);
            if (packageName == null) {
                return;
            }
            if (platformPackages.contains(packageName)) {
                strandedClasses.add(classNameOfEntry(path));
                return;
            }
            packages.add(packageName);
        }

        /** {@return the binary name of a class entry, with the multi-release prefix removed} */
        private static String classNameOfEntry(String path) {
            if (path.startsWith("META-INF/versions/")) {
                var versionEnd = path.indexOf('/', "META-INF/versions/".length());
                if (versionEnd >= 0) {
                    path = path.substring(versionEnd + 1);
                }
            }
            return path.substring(0, path.length() - ".class".length()).replace('/', '.');
        }
    }
}
