/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

/**
 * Registers a <em>Fabric</em> mod's configuration with FML's config system.
 *
 * <p>ForgeConfigAPIPort's Fabric build carries its own copy of the config API, including a
 * {@code ConfigTracker#registerConfig(ModConfig.Type, IConfigSpec, String)} overload that takes the mod id
 * instead of a {@link ModContainer}. On NeoForge that copy is shadowed by the platform's classes (the two
 * share {@code net.neoforged.fml.config}), whose equivalent registration takes a container - so
 * ForgeConfigAPIPort's own registration call - the one a Fabric mod reaches through its
 * {@code ConfigRegistry} - dies with {@code NoSuchMethodError} and takes the calling entrypoint with it
 * (Twilight Forest's content then never registers, and the game dies later on a registry write).
 *
 * <p>The bridge points that one call at {@link #registerFabricModConfig} instead. A synthetic container
 * stands in for the Fabric mod (FML knows nothing about it), which is everything the config system needs:
 * the config is tracked and loaded from the config directory like any other, under the Fabric mod's id.
 */
@ApiStatus.Internal
public final class FabricConfigHooks {
    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** One synthetic container per mod id; the config system keeps whatever it is handed. */
    private static final Map<String, ModContainer> CONTAINERS = new ConcurrentHashMap<>();

    private FabricConfigHooks() {}

    /**
     * {@return the registered config} Same contract as the ForgeConfigAPIPort overload this stands in for.
     *
     * @param tracker the config tracker the caller was about to use (the platform's); passed on by the
     *                bridge so the call site keeps its shape
     * @param type    the config type
     * @param spec    the spec to register
     * @param modId   the id of the Fabric mod the config belongs to
     */
    public static ModConfig registerFabricModConfig(ConfigTracker tracker, ModConfig.Type type, IConfigSpec spec, String modId) {
        var config = tracker.registerConfig(type, spec, containerFor(modId));
        loadConfigNow(config, spec);
        return config;
    }

    /**
     * {@return the registered config} Same as {@link #registerFabricModConfig(ConfigTracker, ModConfig.Type,
     * IConfigSpec, String)} with an explicit file name.
     */
    public static ModConfig registerFabricModConfig(ConfigTracker tracker, ModConfig.Type type, IConfigSpec spec, String modId, String fileName) {
        var config = tracker.registerConfig(type, spec, containerFor(modId), fileName);
        loadConfigNow(config, spec);
        return config;
    }

    /**
     * Loads the file into the spec straight away.
     *
     * <p>The port this stands in for (ForgeConfigAPIPort's own tracker) loads the config as part of
     * registering it, and mods rely on that: Twilight Forest registers its configs and reads values out of
     * them in the same breath ({@code TFConfig.rebakeCommonOptions}), which FML's tracker - loading every
     * config of a type at its own stage - only allows later, and the spec answers with
     * {@code IllegalStateException: Cannot get config value before config is loaded}.
     *
     * <p>FML loads the same config again at its stage and replaces what was set here; doing it now only
     * closes the gap, it does not change where the values come from.
     */
    private static void loadConfigNow(ModConfig config, IConfigSpec spec) {
        var file = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(config.getFileName());
        try {
            CommentedConfig contents;
            if (java.nio.file.Files.isRegularFile(file)) {
                try (var reader = java.nio.file.Files.newBufferedReader(file)) {
                    contents = TomlFormat.instance().createParser().parse(reader);
                }
            } else {
                contents = TomlFormat.instance().createParser().parse(new java.io.StringReader(""));
            }

            if (!spec.isCorrect(contents)) {
                spec.correct(contents);
                save(file, contents);
            }
            var loadedConfig = loadedConfigFor(contents, file, config);
            if (loadedConfig != null) {
                spec.acceptConfig(loadedConfig);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not load the config file {} of the Fabric mod {} while registering it - FML"
                    + " loads it at its usual time", config.getFileName(), config.getModId(), e);
        }
    }

    /**
     * {@return the loaded config to hand to the spec, or {@code null} when it cannot be built}
     *
     * <p>{@code IConfigSpec.ILoadedConfig} is sealed against the implementation FML puts in its own package,
     * so the only way to one is that implementation - through reflection, because it is package-private.
     * FML's loader lives on the unnamed module, which is open, so this works; a future FML that hides it
     * only costs the early load, and the config is then loaded at FML's stage like every other one.
     */
    private static IConfigSpec.ILoadedConfig loadedConfigFor(CommentedConfig contents, java.nio.file.Path file, ModConfig config) {
        try {
            var type = Class.forName("net.neoforged.fml.config.LoadedConfig");
            var constructor = type.getDeclaredConstructor(CommentedConfig.class, java.nio.file.Path.class, ModConfig.class);
            constructor.setAccessible(true);
            return (IConfigSpec.ILoadedConfig) constructor.newInstance(contents, file, config);
        } catch (Throwable t) {
            LOGGER.warn("Could not reach FML's loaded-config type to load {} early", config.getFileName(), t);
            return null;
        }
    }

    private static void save(java.nio.file.Path file, CommentedConfig contents) throws java.io.IOException {
        if (!java.nio.file.Files.isDirectory(file.getParent())) {
            java.nio.file.Files.createDirectories(file.getParent());
        }
        try (var writer = java.nio.file.Files.newBufferedWriter(file)) {
            TomlFormat.instance().createWriter().write(contents, writer);
        }
    }

    private static ModContainer containerFor(String modId) {
        return CONTAINERS.computeIfAbsent(modId, id -> {
            LOGGER.info("Registering the configuration of the Fabric mod {} with FML's config system", id);
            return new FabricModContainer(id);
        });
    }

    /**
     * {@return the container a mod's configuration screen should use for this mod id}
     *
     * <p>An id FML knows resolves to its real container; a Fabric mod id resolves to the same synthetic
     * container its configuration was registered with. Callers reach this through the compatibility
     * {@code ConfigurationScreen(String, Screen)} constructor, which mods compiled against the older
     * platform keep calling - the Fabric build of Twilight Forest opens the screen that way from Mod Menu.
     */
    public static ModContainer containerForModId(String modId) {
        var known = net.neoforged.fml.ModList.get().getModContainerById(modId);
        return known.isPresent() ? known.get() : containerFor(modId);
    }

    /**
     * A container for a mod FML does not know about. Only the mod id and the event bus are ever asked for
     * on the config path, and the bus is a private one so nothing else is ever posted to it.
     */
    private static final class FabricModContainer extends ModContainer {
        private final IEventBus eventBus = BusBuilder.builder().build();

        FabricModContainer(String modId) {
            super(modInfoFor(modId));
        }

        @Override
        public IEventBus getEventBus() {
            return eventBus;
        }
    }

    private static IModInfo modInfoFor(String modId) {
        return (IModInfo) Proxy.newProxyInstance(FabricConfigHooks.class.getClassLoader(), new Class<?>[] { IModInfo.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getModId", "getNamespace", "getDisplayName" -> modId;
                    case "getDescription" -> "";
                    case "getVersion" -> new DefaultArtifactVersion("0.0.0");
                    case "getLogoBlur" -> false;
                    case "getConfig" -> IConfigurable.class.cast(Proxy.newProxyInstance(
                            FabricConfigHooks.class.getClassLoader(), new Class<?>[] { IConfigurable.class },
                            (configProxy, configMethod, configArgs) -> switch (configMethod.getReturnType().getSimpleName()) {
                                case "Optional" -> Optional.empty();
                                case "List" -> List.of();
                                default -> null;
                            }));
                    case "toString" -> "FabricModContainer[" + modId + "]";
                    case "hashCode" -> modId.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> emptyReturn(method.getReturnType());
                });
    }

    /** {@return the neutral value for a return type the synthetic mod info has nothing to say about} */
    private static Object emptyReturn(Class<?> type) {
        if (!type.isPrimitive()) {
            if (Optional.class.isAssignableFrom(type)) {
                return Optional.empty();
            }
            if (List.class.isAssignableFrom(type)) {
                return List.of();
            }
            if (Map.class.isAssignableFrom(type)) {
                return Map.of();
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
