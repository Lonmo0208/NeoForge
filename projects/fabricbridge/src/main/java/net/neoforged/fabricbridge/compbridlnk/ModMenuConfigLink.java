package net.neoforged.fabricbridge.compbridlnk;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compatibility link that has to name a specific mod by hand (project rule: everything in this package is
 * an explicit exception to the "no mod-named code" rule).
 *
 * <p>{@link #linkModMenuConfigScreens()} hands the mod list screen of the platform every configuration
 * screen the loaded Fabric mods declare for Mod Menu. The rows of Fabric mods in that screen have no FML
 * container, so the usual way a NeoForge mod provides its config screen - the container's
 * {@code IConfigScreenFactory} extension point - cannot be used. The bridge knows both sides: it sees the
 * Fabric mods and their {@code modmenu} entry points, and it runs in the same process as the platform, so
 * each entry's factory is registered with the platform's {@code ModListConfigScreens} registry and the
 * screen's config button opens exactly the screen Mod Menu would have opened.
 *
 * <p>All of it is reflective: Mod Menu is not on this project's compile class path, and its API classes
 * only exist when Mod Menu itself is installed, which the link checks first. A mod whose entry point
 * cannot be built is skipped and logged; the link never throws.
 */
public final class ModMenuConfigLink {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat");

    private static final String MODMENU_ENTRYPOINT = "modmenu";
    private static final String MODMENU_API = "com.terraformersmc.modmenu.api.ModMenuApi";
    private static final String CONFIG_SCREEN_FACTORY = "com.terraformersmc.modmenu.api.ConfigScreenFactory";
    private static final String SCREEN = "net.minecraft.client.gui.screens.Screen";
    private static final String REGISTRY = "net.neoforged.neoforge.client.gui.modlist.ModListConfigScreens";

    private ModMenuConfigLink() {
    }

    /** Registers every {@code modmenu} config screen with the platform's mod list screen. */
    public static void linkModMenuConfigScreens() {
        var loaders = new java.util.ArrayList<ClassLoader>();
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(ModMenuConfigLink.class.getClassLoader());
        try {
            loaders.add(net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader());
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] No Fabric target class loader to try for the mod menu link", t);
        }
        loaders.removeIf(java.util.Objects::isNull);

        try {
            var apiClass = loadFirst(loaders, MODMENU_API);
            if (apiClass == null) {
                return; // Mod Menu is not installed; there are no screens to hand over.
            }
            var registry = loadFirst(loaders, REGISTRY);
            if (registry == null) {
                LOGGER.debug("[NeoFobric] This platform has no ModListConfigScreens registry to link into");
                return;
            }
            var register = registry.getMethod("register", String.class, java.util.function.BiFunction.class);
            var factoryClass = loadFirst(loaders, CONFIG_SCREEN_FACTORY);
            var screenClass = loadFirst(loaders, SCREEN);
            if (factoryClass == null || screenClass == null) {
                return;
            }
            var create = factoryClass.getMethod("create", screenClass);

            // Raw on purpose: the interface is a Mod Menu class resolved by name above, so its type
            // parameter cannot be spelled here. The loader's container type itself can be.
            @SuppressWarnings({"unchecked", "rawtypes"})
            var containers = (java.util.List<net.fabricmc.loader.api.entrypoint.EntrypointContainer<?>>) (java.util.List)
                    FabricLoader.getInstance().getEntrypointContainers(MODMENU_ENTRYPOINT, (Class) apiClass);

            var linked = new java.util.ArrayList<String>();
            for (var container : containers) {
                var modId = container.getProvider().getMetadata().getId();
                try {
                    var entrypoint = container.getEntrypoint();
                    var factory = entrypoint.getClass().getMethod("getModConfigScreenFactory").invoke(entrypoint);
                    if (factory == null) {
                        continue; // The mod declares the entry point but offers no screen.
                    }
                    java.util.function.BiFunction<Object, Object, Object> screenFactory = (minecraft, parent) -> {
                        try {
                            return create.invoke(factory, parent);
                        } catch (Throwable t) {
                            LOGGER.warn("[NeoFobric] The Mod Menu config screen of {} failed to open", modId, t);
                            return null;
                        }
                    };
                    register.invoke(null, modId, screenFactory);
                    linked.add(modId);
                } catch (Throwable t) {
                    LOGGER.warn("[NeoFobric] Could not link the Mod Menu config screen of {}", modId, t);
                }
            }
            if (!linked.isEmpty()) {
                LOGGER.info("[NeoFobric] Linked {} Fabric mod config screen(s) into the mod list: {}", linked.size(), linked);
            }
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Failed to link Fabric mod config screens into the mod list", t);
        }
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
