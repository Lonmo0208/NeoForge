/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.transform;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Rewrites the class references of one class through a name table.
 *
 * <p>Shared by the two places that move a reference from a mod's loader-specific class layout to the
 * layout of the build that actually loads: the jar patcher (Fabric mods) and the relaxed-copy writer in
 * the mod locator (NeoForge mods, see {@code compbridlnk.SodiumLoaderLayoutLink}). Class names appear
 * verbatim in the constant pool, so one text scan filters out every class that cannot be affected before
 * any ASM work happens; a class that does not mention any of the names is returned as-is.
 */
public final class LayoutRetargeter {
    private LayoutRetargeter() {
    }

    /**
     * @param entryName the jar entry path, for logging only
     * @param classBytes the class bytes
     * @param applicable old internal name to new internal name, already filtered to the names that apply
     * @return the rewritten class bytes, or the input when no name matched
     */
    public static byte[] retarget(String entryName, byte[] classBytes, Map<String, String> applicable) {
        if (applicable.isEmpty()) {
            return classBytes;
        }
        var text = new String(classBytes, StandardCharsets.ISO_8859_1);
        var matched = new java.util.HashMap<String, String>();
        for (var move : applicable.entrySet()) {
            if (text.contains(move.getKey())) {
                matched.put(move.getKey(), move.getValue());
            }
        }
        if (matched.isEmpty()) {
            return classBytes;
        }
        try {
            var reader = new ClassReader(classBytes);
            var writer = new ClassWriter(0);
            reader.accept(new ClassRemapper(writer, new Remapper() {
                @Override
                public String map(String internalName) {
                    var moved = matched.get(internalName);
                    return moved != null ? moved : internalName;
                }
            }), 0);
            return writer.toByteArray();
        } catch (Throwable t) {
            return classBytes;
        }
    }
}
