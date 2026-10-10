/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.net.URI;
import java.net.URL;
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
}
