/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.VersionInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.util.Arguments;

/**
 * A fabric {@link GameProvider} describing the Minecraft instance that NeoForge is already
 * running. It never launches the game itself ({@link #launch} is unreachable); it exists so
 * fabric-loader has a valid game context: the {@code minecraft} builtin mod for dependency
 * checks, the game directory, and the environment type.
 */
final class BridgedGameProvider implements GameProvider {
    private final Arguments arguments = new Arguments();

    @Override
    public String getGameId() {
        return "minecraft";
    }

    @Override
    public String getGameName() {
        return "Minecraft";
    }

    @Override
    public String getRawGameVersion() {
        return minecraftVersion();
    }

    @Override
    public String getNormalizedGameVersion() {
        return minecraftVersion();
    }

    @Override
    public Collection<BuiltinMod> getBuiltinMods() {
        var metadata = new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion()).setName(getGameName()).build();
        return List.of(new BuiltinMod(List.of(minecraftJarPath()), metadata));
    }

    @Override
    public String getEntrypoint() {
        return FabricBridge.toEnvType(net.neoforged.fml.loading.FMLEnvironment.getDist()) == EnvType.CLIENT
                ? "net.minecraft.client.main.Main"
                : "net.minecraft.server.Main";
    }

    @Override
    public Path getLaunchDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean requiresUrlClassLoader() {
        return false;
    }

    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String className) {
        // Game classes are transformed by FML's class processing pipeline, not by fabric.
        return EnumSet.noneOf(BuiltinTransform.class);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        // The game was located by FML long before this provider comes into play.
        arguments.parse(args);
        return true;
    }

    @Override
    public void initialize(FabricLauncher launcher) {
        // No-op: no deobfuscation, no log handler setup, no entrypoint location needed.
    }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {
        // No-op: the game content layer is managed by FML.
    }

    @Override
    public void launch(ClassLoader loader) {
        throw new UnsupportedOperationException("The bridged game provider cannot launch Minecraft; NeoForge is already running it.");
    }

    @Override
    public GameTransformer getEntrypointTransformer() {
        // The bridge applies the entrypoint and branding patches itself via FML's class
        // processing pipeline; fabric's own GamePatches must not run a second time.
        return new GameTransformer();
    }

    @Override
    public Arguments getArguments() {
        return arguments;
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        // Fabric mods querying the launch arguments get the game arguments FML was started with.
        var loader = FMLLoader.getCurrentOrNull();
        return loader != null ? loader.getProgramArgs().getArguments() : new String[0];
    }

    @Override
    public boolean canOpenErrorGui() {
        // FML owns error reporting.
        return false;
    }

    /**
     * {@return the Minecraft version, or null when it cannot be determined} FML only derives it from
     * the discovered mod files after its whole discovery pass, and the bridge boots fabric-loader
     * <em>inside</em> that pass - mixin config plugins are constructed when the game layer transforms
     * its first class and ask the loader for their mod container. Fabric refuses to resolve mod
     * dependencies against an unknown game version ("minecraft uses the non-semantic version
     * unknown"), so the value has to come from the game files directly at that point.
     */
    private static String minecraftVersion() {
        var loader = FMLLoader.getCurrentOrNull();
        VersionInfo versionInfo = loader != null ? loader.getVersionInfo() : null;
        if (versionInfo != null && versionInfo.mcVersion() != null) {
            return versionInfo.mcVersion();
        }
        var fromVersionJson = versionJsonGameVersion();
        return fromVersionJson != null ? fromVersionJson : "unknown";
    }

    /** Cached {@code version.json} reading; the file cannot change while the game runs. */
    private static volatile String versionJsonGameVersion;

    /**
     * {@return the {@code id} of the {@code version.json} carried by the game content} That is the
     * version FML itself reads out of the jar later on, so it is the same string the dependency
     * resolution would use - the launch argument copy is gone by now (FML consumes it while building
     * its {@code VersionInfo}).
     */
    private static String versionJsonGameVersion() {
        var cached = versionJsonGameVersion;
        if (cached != null) {
            return cached;
        }

        var resource = ClassLoader.getSystemResource("version.json");
        if (resource == null) {
            return null;
        }
        try (var in = resource.openStream()) {
            var json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            var id = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (id.find()) {
                return versionJsonGameVersion = id.group(1);
            }
        } catch (Exception ignored) {
            // Reported by the caller through the usual "unknown" path.
        }
        return null;
    }

    private static Path minecraftJarPath() {
        try {
            var loader = FMLLoader.getCurrentOrNull();
            var minecraftModFile = loader != null ? LoadingModList.get().getModFileById("minecraft") : null;
            if (minecraftModFile != null) {
                return minecraftModFile.getFile().getContents().getPrimaryPath();
            }
        } catch (Throwable ignored) {
            // Fall through to the game directory.
        }
        return FMLPaths.GAMEDIR.get();
    }
}
