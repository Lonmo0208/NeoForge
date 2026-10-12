package net.neoforged.fabricbridge.compbridlnk;

import java.util.function.Supplier;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compatibility links that have to name a specific mod by hand. Everything in this package is by project
 * rule an explicit exception to the "no mod-named code" rule - the bridge proper stays free of names, and
 * whatever only exists because one particular mod needs a hand lives here, clearly labelled.
 *
 * <p>{@link #linkSodiumConfigEntryPoints()} is the first of them: Sodium's Fabric build hands mods a
 * {@code sodium:config_api_user} entrypoint and shows their pages inside its own video settings; its
 * NeoForge build instead reads the same key from FML's mod properties, which a Fabric mod can never carry.
 * A Fabric-only mod (Voxy) therefore keeps working but loses its settings page. Registering the entrypoints
 * the Fabric loader already collected makes the pages appear again - the entrypoint class implements the
 * interface from whichever Sodium build is on the classpath, and with the Fabric one skipped that is the
 * NeoForge one, so no adapter is needed.
 */
public final class SodiumConfigApiLink {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat");

    private static final String CONFIG_MANAGER = "net.caffeinemc.mods.sodium.client.config.ConfigManager";
    private static final String CONFIG_ENTRY_POINT = "net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint";
    private static final String CONFIG_ENTRY_POINT_KEY_FIELD = "CONFIG_ENTRY_POINT_KEY";

    /**
     * A class only the Fabric build of Sodium ships. Its presence on the running class path is the
     * discriminator between the two builds: a Fabric Sodium that was skipped for an FML counterpart never
     * lands on the game class path, so this only resolves when the Fabric build is the loaded one.
     */
    private static final String FABRIC_BUILD_MARKER = "net.caffeinemc.mods.sodium.fabric.SodiumFabricMod";

    private SodiumConfigApiLink() {
    }

    /**
     * Hands Sodium's NeoForge build every {@code sodium:config_api_user} entrypoint the Fabric loader found.
     * Returns quietly when Sodium is absent (its classes cannot be resolved) or when no Fabric mod declares
     * one, and never throws: a compat link that fails is a missing settings page, not a broken game.
     */
    public static void linkSodiumConfigEntryPoints() {
        // Sodium is an FML mod, so its classes live in the game/mod layer, not in whatever loader this class
        // came from - trying them in order costs nothing and is the difference between a link and a silent miss.
        var loaders = new java.util.ArrayList<ClassLoader>();
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(SodiumConfigApiLink.class.getClassLoader());
        try {
            loaders.add(net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader());
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] No Fabric target class loader to try for the Sodium config link", t);
        }
        loaders.removeIf(java.util.Objects::isNull);

        // The two builds collect their pages from opposite sides: the Fabric build from fabric.mod.json
        // entry points, the NeoForge build from FML's mod properties. Whichever one is loaded, the other
        // side's pages are missing from it, and filling them in is what the branches below do. Registering
        // the Fabric side's entry points into the Fabric build again is a duplicate mod id that kills its
        // config build ("Mod 'lss' provided a duplicate mod id: lss" out of ConfigManager.registerConfigs).
        var fabricBuild = false;
        for (var loader : loaders) {
            try {
                Class.forName(FABRIC_BUILD_MARKER, false, loader);
                fabricBuild = true;
                break;
            } catch (Throwable absent) {
                // Try the next loader.
            }
        }

        Class<?> manager = null;
        Class<?> entryPoint = null;
        for (var loader : loaders) {
            try {
                manager = Class.forName(CONFIG_MANAGER, false, loader);
                entryPoint = Class.forName(CONFIG_ENTRY_POINT, false, loader);
                break;
            } catch (Throwable absent) {
                manager = null;
                entryPoint = null;
            }
        }
        if (manager == null || entryPoint == null) {
            LOGGER.info("[NeoFobric] Sodium config API not present; nothing to link for Fabric mod settings pages");
            return; // No Sodium (or not the build with this API); nothing to link.
        }

        try {
            // The key is read from Sodium itself when it is there, so a rename on its side follows along.
            String key = CONFIG_ENTRY_POINT_KEY_FIELD;
            try {
                key = (String) manager.getField(CONFIG_ENTRY_POINT_KEY_FIELD).get(null);
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Sodium has no {} field; falling back to the literal key", CONFIG_ENTRY_POINT_KEY_FIELD, t);
                key = "sodium:config_api_user";
            }

            if (fabricBuild) {
                linkFmlModPages(manager, key, loaders);
                // The mod metadata lookup of the Fabric build answers for Fabric mods; NeoForge mods get
                // theirs from FML through the same fallback as in the other direction.
                scheduleModInfoFallback(manager);
                return;
            }

            // Raw on purpose: the entrypoint interface is a Sodium class resolved by name above, so its type
            // parameter cannot be spelled here. The loader's container type itself can be.
            @SuppressWarnings({"unchecked", "rawtypes"})
            var containers = (java.util.List<net.fabricmc.loader.api.entrypoint.EntrypointContainer<?>>) (java.util.List)
                    FabricLoader.getInstance().getEntrypointContainers(key, (Class) entryPoint);
            if (containers.isEmpty()) {
                LOGGER.info("[NeoFobric] No Fabric mod declares a Sodium config entrypoint ('{}'); nothing to link", key);
                return;
            }

            var register = manager.getMethod("registerConfigEntryPoint", Supplier.class, String.class);
            var linked = new java.util.ArrayList<String>();
            for (var container : containers) {
                var modId = container.getProvider().getMetadata().getId();
                try {
                    // Lazy on purpose: Sodium resolves the supplier when it builds its config tree, exactly
                    // like it does for its own mods, so a mod whose entrypoint cannot be built is reported by
                    // Sodium's own machinery.
                    Supplier<Object> supplier = container::getEntrypoint;
                    register.invoke(null, supplier, modId);
                    linked.add(modId);
                } catch (Throwable t) {
                    LOGGER.warn("[NeoFobric] Could not link the sodium:config_api_user entrypoint of {} into Sodium", modId, t);
                }
            }
            if (!linked.isEmpty()) {
                LOGGER.info("[NeoFobric] Linked {} Fabric mod config page(s) into Sodium: {}", linked.size(), linked);
                scheduleModInfoFallback(manager);
            }
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Failed to link Fabric mod config pages into Sodium", t);
        }
    }

    /**
     * Hands the Fabric build of Sodium every config page the NeoForge mods declare.
     *
     * <p>The Fabric build collects pages from {@code fabric.mod.json} entry points, which a NeoForge mod
     * cannot carry; its NeoForge counterpart reads the same key from FML's mod properties instead. With the
     * Fabric build loaded, a NeoForge mod's page (Iris) is therefore missing, and this puts it back. The page
     * class implements the interface of whichever Sodium build is on the class path, so no adapter is needed,
     * and mods whose id is also a loaded Fabric mod are skipped - the Fabric build reads those itself, and
     * registering them twice is the duplicate-id crash out of {@code ConfigManager.registerConfigs}.
     */
    private static void linkFmlModPages(Class<?> manager, String key, java.util.List<ClassLoader> loaders) {
        java.lang.reflect.Method register;
        try {
            register = manager.getMethod("registerConfigEntryPoint", Supplier.class, String.class);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Sodium has no registerConfigEntryPoint; NeoForge mod pages cannot be linked", t);
            return;
        }

        var loadedFabricIds = new java.util.HashSet<String>();
        for (var info : net.neoforged.fabricbridge.discovery.FabricJarRegistry.getEntries().values()) {
            loadedFabricIds.add(normalizeModId(info.modId()));
        }

        var linked = new java.util.ArrayList<String>();
        for (var mod : net.neoforged.fml.ModList.get().getMods()) {
            if (!(mod.getModProperties().get(key) instanceof String pageClass) || pageClass.isEmpty()) {
                continue;
            }
            var modId = mod.getModId();
            if (loadedFabricIds.contains(normalizeModId(modId))) {
                continue; // The Fabric build collects this one itself.
            }
            try {
                // Lazy on purpose, exactly like the other direction: Sodium resolves the supplier when it
                // builds its config tree, and a page that cannot be built is then reported by Sodium itself.
                Supplier<Object> supplier = () -> {
                    for (var loader : loaders) {
                        try {
                            return Class.forName(pageClass, false, loader).getDeclaredConstructor().newInstance();
                        } catch (Throwable absent) {
                            // Try the next loader.
                        }
                    }
                    throw new IllegalStateException("Cannot load the Sodium config page class " + pageClass);
                };
                register.invoke(null, supplier, modId);
                linked.add(modId);
            } catch (Throwable t) {
                LOGGER.warn("[NeoFobric] Could not link the Sodium config page of the NeoForge mod {}", modId, t);
            }
        }
        if (linked.isEmpty()) {
            LOGGER.info("[NeoFobric] No NeoForge mod declares a Sodium config page ('{}'); nothing to link", key);
        } else {
            LOGGER.info("[NeoFobric] Linked {} NeoForge mod config page(s) into the Fabric build of Sodium: {}",
                    linked.size(), linked);
        }
    }

    /** {@return the id in the form both loaders agree on} */
    private static String normalizeModId(String id) {
        return id.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
    }

    /**
     * Sodium labels every page it builds with a mod's display name and version, and it looks those up
     * through FML's {@code ModList} - which has never heard of a Fabric mod ("Mod with id X not found in
     * ModList" is what the build does instead). Its NeoForge build sets that lookup while {@code Minecraft}
     * is being constructed, so replacing it can only happen later: the first client tick runs long before
     * the loading overlay finishes, which is when the pages are actually assembled.
     */
    private static void scheduleModInfoFallback(Class<?> manager) {
        try {
            // Fabric API is not on this project's compile classpath, so the tick event is reached reflectively
            // through the loader that owns Sodium's classes (which is also the one that owns the Fabric mods).
            var loader = manager.getClassLoader();
            var tickEvents = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents", false, loader);
            var listener = Class.forName("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$StartTick", false, loader);
            var event = Class.forName("net.fabricmc.fabric.api.event.Event", false, loader);
            var startTick = tickEvents.getField("START_CLIENT_TICK").get(null);
            var register = event.getMethod("register", Object.class);
            var handler = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {listener}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "NeoFobric Sodium mod-info installer";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    };
                }
                if (!modInfoInstalled) {
                    modInfoInstalled = true;
                    installModInfoFallback(manager);
                }
                return null;
            });
            register.invoke(startTick, handler);
            LOGGER.info("[NeoFobric] Sodium mod-info fallback armed for the first client tick");
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not arm the Sodium mod-info fallback", t);
        }
    }

    private static volatile boolean modInfoInstalled;

    /** Installs a mod-info lookup that answers for FML mods and for Fabric mods alike. */
    private static void installModInfoFallback(Class<?> manager) {
        try {
            var metadataClass = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager$ModMetadata",
                    false, manager.getClassLoader());
            var metadataCtor = metadataClass.getDeclaredConstructor(String.class, String.class);
            metadataCtor.setAccessible(true);
            var setFunction = manager.getMethod("setModInfoFunction", java.util.function.Function.class);

            var lookup = java.lang.reflect.Proxy.newProxyInstance(
                    SodiumConfigApiLink.class.getClassLoader(),
                    new Class<?>[] {java.util.function.Function.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "NeoFobric Sodium mod-info lookup";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        var id = String.valueOf(args[0]);
                        return metadataCtor.newInstance(displayName(id), version(id));
                    });
            setFunction.invoke(null, lookup);
            LOGGER.info("[NeoFobric] Sodium's mod-info lookup now answers for Fabric mods as well");
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not install the Sodium mod-info fallback", t);
        }
    }

    /** {@return the display name FML or the Fabric loader has for this mod id} */
    private static String displayName(String id) {
        try {
            var fml = net.neoforged.fml.ModList.get().getModContainerById(id);
            if (fml.isPresent()) {
                return fml.get().getModInfo().getDisplayName();
            }
        } catch (Throwable ignored) {
            // Fall through to the Fabric side.
        }
        var fabric = FabricLoader.getInstance().getModContainer(id);
        return fabric.map(container -> container.getMetadata().getName()).orElse(id);
    }

    /** {@return the version FML or the Fabric loader has for this mod id} */
    private static String version(String id) {
        try {
            var fml = net.neoforged.fml.ModList.get().getModContainerById(id);
            if (fml.isPresent()) {
                // Reflective: the version's own type (an ArtifactVersion) is not readable from this module.
                var info = fml.get().getModInfo();
                return String.valueOf(info.getClass().getMethod("getVersion").invoke(info));
            }
        } catch (Throwable ignored) {
            // Fall through to the Fabric side.
        }
        var fabric = FabricLoader.getInstance().getModContainer(id);
        return fabric.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("");
    }
}
