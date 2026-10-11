/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import net.neoforged.fml.jarcontents.JarContents;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

/**
 * Resource enumeration for <em>Fabric</em> mod jars.
 *
 * <p>Fabric mods scan their own package through {@link ClassLoader#getResources(String)} - it is how
 * Twilight Forest's bean context discovers its {@code @Component} classes, and how several other mods find
 * their own files. The loader in this environment answers for class files but returns nothing for the
 * package directories those scans ask for, so the scan comes up empty and the mod falls over later
 * ("Found 0 @Component classes", then an entrypoint that dies on the null it got back).
 *
 * <p>The bridge points {@code getResources} calls inside a Fabric jar at
 * {@link #fabricModResources(ClassLoader, String, String)}, which asks the loader first and only falls back
 * to the jar the caller came from when the loader has nothing - so behaviour elsewhere is unchanged, and a
 * mod that scans its own package gets the directory entry it expects.
 */
@ApiStatus.Internal
public final class FabricResourceHooks {
    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private FabricResourceHooks() {}

    /**
     * {@return the resources of {@code name}, asking {@code loader} first and the jar at {@code jarPath}
     * when it has none} The URLs of the fallback look like {@code jar:<file>!/<entry>}, which is what a
     * Fabric mod's directory scan opens a file system for.
     */
    public static Enumeration<URL> fabricModResources(ClassLoader loader, String name, String jarPath) {
        try {
            var found = loader.getResources(name);
            if (found.hasMoreElements()) {
                return found;
            }
        } catch (IOException | RuntimeException e) {
            // Fall through to the jar; the caller only asked for whatever exists.
        }

        List<URL> urls = new ArrayList<>(1);
        var path = Path.of(jarPath);
        try (var jar = JarContents.ofPath(path)) {
            var prefix = name.endsWith("/") ? name : name + "/";
            var present = new boolean[1];
            jar.visitContent((relativePath, resource) -> {
                if (relativePath.startsWith(prefix)) {
                    present[0] = true;
                }
            });
            if (present[0]) {
                // One directory URL, the shape a directory scan opens a file system for and then walks.
                urls.add(URI.create("jar:" + path.toUri() + "!/" + prefix).toURL());
            }
        } catch (Exception e) {
            LOGGER.debug("Could not enumerate {} in {}", name, path, e);
        }
        return Collections.enumeration(urls);
    }

    /**
     * {@return the stream of {@code name}, asking {@code receiver} first and the jar at {@code jarPath} when
     * it has none}
     *
     * <p>Some Fabric mods read their own data files through a <em>platform</em> class: WorldWeaver's
     * structure loader asks {@code MinecraftServer.class.getResourceAsStream("/data/<mod>/structure/...")},
     * and other mods do the same through {@code Minecraft}. Under Fabric the whole game is one class path,
     * so that works; here the mod's files sit in the Fabric module while {@code Minecraft} sits in the
     * platform one, the lookup finds nothing, and the mod carries on with the null it got (WorldWeaver's
     * city generator then dies on {@code this.structure}). The bridge points such calls at this method,
     * which keeps the original answer whenever there is one and only falls back to the caller's own jar.
     */
    public static InputStream fabricModResourceStream(Class<?> receiver, String name, String jarPath) {
        var direct = ask(() -> receiver == null ? null : receiver.getResourceAsStream(name));
        return direct != null ? direct : openJarEntry(name, jarPath);
    }

    /** As {@link #fabricModResourceStream(Class, String, String)}, for a lookup through a class loader. */
    public static InputStream fabricModResourceStream(ClassLoader loader, String name, String jarPath) {
        var direct = ask(() -> loader == null ? null : loader.getResourceAsStream(name));
        return direct != null ? direct : openJarEntry(name, jarPath);
    }

    /** As {@link #fabricModResourceStream(Class, String, String)}, for {@code Class#getResource}. */
    public static URL fabricModResource(Class<?> receiver, String name, String jarPath) {
        var direct = ask(() -> receiver == null ? null : receiver.getResource(name));
        return direct != null ? direct : findEntryUrl(name, jarPath);
    }

    /** {@return the answer of {@code lookup}, or null when it threw - the caller only asked for what exists} */
    private static <T> T ask(java.util.function.Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@return the jar entry {@code name} as a stream, or null when no Fabric-side jar has it} */
    private static InputStream openJarEntry(String name, String jarPath) {
        if (name == null || !name.startsWith("/")) {
            // A relative name only the receiver could resolve, and the package it means is the platform's.
            return null;
        }
        var entry = name.substring(1);
        var own = readEntry(jarPath, entry);
        if (own != null) {
            return own;
        }
        // Fabric keeps every mod on one class path, so the file may belong to a different mod than the caller
        // - WorldWeaver asks for BetterNether's structure templates - and the bridge looks through all of them.
        return readEntry(net.neoforged.fabricbridge.FabricBridgeHooks.fabricModResourceJar(entry), entry);
    }

    /** {@return the entry of the jar at {@code jarPath} as a stream, or null when that jar has no such entry} */
    private static InputStream readEntry(String jarPath, String entry) {
        if (jarPath == null) {
            return null;
        }
        try (var jar = JarContents.ofPath(Path.of(jarPath))) {
            if (!jar.containsFile(entry)) {
                return null;
            }
            // Read it out: the stream may live in a file system that closing the container takes away.
            return new java.io.ByteArrayInputStream(jar.readFile(entry));
        } catch (Exception e) {
            LOGGER.debug("Could not read {} from {}", entry, jarPath, e);
            return null;
        }
    }

    /** {@return the URL of the entry, from the caller's jar first and then from any Fabric-side one} */
    private static URL findEntryUrl(String name, String jarPath) {
        if (name == null || !name.startsWith("/")) {
            return null;
        }
        var entry = name.substring(1);
        for (var candidate : new String[] { jarPath, net.neoforged.fabricbridge.FabricBridgeHooks.fabricModResourceJar(entry) }) {
            var url = urlOf(candidate, entry);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    /** {@return the {@code jar:} URL of the entry of the jar at {@code jarPath}, or null when it has none} */
    private static URL urlOf(String jarPath, String entry) {
        if (jarPath == null) {
            return null;
        }
        try (var jar = JarContents.ofPath(Path.of(jarPath))) {
            return jar.findFile(entry).map(uri -> {
                try {
                    return uri.toURL();
                } catch (Exception e) {
                    return null;
                }
            }).orElse(null);
        } catch (Exception e) {
            LOGGER.debug("Could not resolve the resource {} in {}", entry, jarPath, e);
            return null;
        }
    }
}
