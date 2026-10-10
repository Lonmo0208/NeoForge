/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fabricbridge.discovery.FabricJarRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.MappingConfiguration;

/**
 * A fabric-launcher implementation that adapts fabric-loader to the already running NeoForge
 * instance: game classes are not loaded by fabric (they exist on the FML game content layer
 * already), and mod jars were added to that layer during discovery, so the classpath methods
 * only validate that fabric-loader's expectations match what the bridge set up.
 */
final class BridgedLauncher implements FabricLauncher {
    private static final String CLIENT_ENTRYPOINT = "net.minecraft.client.main.Main";
    private static final String SERVER_ENTRYPOINT = "net.minecraft.server.Main";

    private final EnvType envType;
    private final boolean development;

    BridgedLauncher(EnvType envType) {
        this.envType = envType;
        this.development = !net.neoforged.fml.loading.FMLEnvironment.isProduction();
    }

    /**
     * The classloader that owns the game content and every Fabric mod class.
     *
     * <p>Resolved on every access instead of being captured once: FML swaps
     * {@code FMLLoader.currentClassLoader} while it boots. It is the transforming game layer while
     * the class processors run (which is when the bridge registers its launcher), but afterwards the
     * bridge would keep that reference while FML replaces the field with a
     * {@code ResourceMaskingClassLoader} - a loader that only masks already-present resources and
     * otherwise delegates to the plain system classpath. Caching the boot-time value therefore made
     * every Fabric entrypoint class unloadable ({@code ClassNotFoundException}), even though the
     * classes sit in the game layer and their mixins applied correctly.
     */
    private static ClassLoader targetClassLoader() {
        return FMLLoader.getCurrent().getCurrentClassLoader();
    }

    @Override
    public MappingConfiguration getMappingConfiguration() {
        // Minecraft is distributed unobfuscated these days; there is nothing to remap.
        return new MappingConfiguration();
    }

    @Override
    public void addToClassPath(Path path, String... allowedPrefixes) {
        // Every path fabric-loader adds here is already loadable:
        //  - top-level Fabric mods and the nested jars the bridge extracted are game content
        //    (see FabricJarRegistry#isGameLayerPath),
        //  - nested jars fabric-loader re-extracted to .fabric/processedMods duplicate game
        //    content that is already present,
        //  - mods found on the classpath (e.g. the fabric-loader jar itself in dev) are visible
        //    through the parent classloader chain.
        // So this is always a no-op; only a missing file indicates a genuine problem.
        if (!FabricJarRegistry.isGameLayerPath(path) && !java.nio.file.Files.isRegularFile(path)) {
            throw new IllegalStateException(
                    "[NeoFobric] fabric-loader requested adding " + path + " to the classpath, but the file does not exist. "
                            + "If this jar is nested inside a Fabric mod, it must declare itself in the parent's fabric.mod.json \"jars\" entry.");
        }
    }

    @Override
    public void setAllowedPrefixes(Path path, String... prefixes) {
        // No-op: the game layer has no per-source prefix restrictions.
    }

    @Override
    public void setValidParentClassPath(Collection<Path> paths) {
        // No-op: the parent classpath is managed by FML.
    }

    @Override
    public EnvType getEnvironmentType() {
        return envType;
    }

    @Override
    public boolean isClassLoaded(String name) {
        try {
            Class.forName(name, false, targetClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Override
    public Class<?> loadIntoTarget(String name) throws ClassNotFoundException {
        return Class.forName(name, true, targetClassLoader());
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return targetClassLoader().getResourceAsStream(name);
    }

    @Override
    public ClassLoader getTargetClassLoader() {
        return targetClassLoader();
    }

    @Override
    public byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
        var location = name.replace('.', '/') + ".class";
        try (var in = targetClassLoader().getResourceAsStream(location)) {
            if (in == null) {
                throw new IOException("Could not find class " + name + " on the game content layer");
            }
            return in.readAllBytes();
        }
    }

    @Override
    public Manifest getManifest(Path originPath) {
        try (var jarFile = new JarFile(originPath.toFile(), false, JarFile.OPEN_READ)) {
            return jarFile.getManifest();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public boolean isDevelopment() {
        return development;
    }

    @Override
    public String getEntrypoint() {
        return envType == EnvType.CLIENT ? CLIENT_ENTRYPOINT : SERVER_ENTRYPOINT;
    }

    @Override
    public List<Path> getClassPath() {
        // Game and mod content is managed by FML; fabric-loader never needs the real classpath.
        return List.of();
    }
}
