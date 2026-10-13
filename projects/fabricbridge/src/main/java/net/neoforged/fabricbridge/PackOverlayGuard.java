/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pack-metadata guard standing behind {@code MixinJarPatcher#guardForeignPackOverlayConditions}.
 *
 * <p>Fabric API's {@code fabric-resource-conditions-api-v1} reads a {@code fabric:overlays} section out of
 * <em>every</em> pack it meets, and hard-fails the whole pack read when a condition in it names a type its
 * registry does not know. That is fine in a Fabric-only install, where every pack's own build registered
 * its condition types. A NeoForge-build jar of a dual-loader mod carries the section too, though - it
 * belongs to the mod's Fabric twin - and the types it names are registered on the NeoForge side
 * ({@code tectonic:config} is Tectonic's). Fabric's parser then dies with "Unknown resource condition
 * key", Minecraft reports "Failed to read pack ... metadata" and hands {@code null} to the caller - and
 * Tectonic hands that null straight into the pack repository, so opening or re-creating any world dies
 * with an NPE in {@code PackRepository.discoverAvailable}, which a player sees as "world creation is
 * broken".
 *
 * <p>For a pack owned by an FML mod the correct view is the one plain NeoForge has: the {@code fabric:}
 * sections of that jar belong to its sibling build and stay unread. {@link #getOverlaySection} answers
 * {@code null} - "no Fabric overlay conditions" - for those packs, which is what makes world creation
 * work again, and lets packs owned by real Fabric mods through to Fabric's parser unchanged.
 *
 * <p>{@link #copyOfOverlays} is the second half of the repair, for the packs that get that far: the
 * Fabric handler replaces the {@code overlaySet} local with an immutable {@code List.copyOf} result, but
 * NeoForge's own patch to {@code readPackMetadata} adds the {@code neoforge:overlays} entries to that
 * local with {@code addAll} - an immutable list there dies with an {@code UnsupportedOperationException}
 * ("Failed to read pack ... metadata" again). A handler return value that stays a plain
 * {@code ArrayList} satisfies both sides.
 *
 * <p>The bridge compiles without the game on its class path, so packs are handled through reflection;
 * the lookups are resolved once per pack class and kept.
 */
public final class PackOverlayGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat");

    /** Pack ids already reported, so the log carries one line per pack rather than one per read. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static final Map<Class<?>, Method> PACK_ID = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> METADATA_SECTION = new ConcurrentHashMap<>();

    private PackOverlayGuard() {}

    /**
     * Stands in for {@code PackResources#getMetadataSection(MetadataSectionType)} at the one call site that
     * asks for Fabric's overlay conditions (see the class javadoc). Genuine read failures still propagate:
     * only the "whose pack is this" decision is this method's business.
     */
    public static Object getOverlaySection(Object pack, Object type) throws IOException {
        try {
            if (isForeignBuildPack(pack)) {
                return null;
            }
            return findMethod(METADATA_SECTION, pack, "getMetadataSection").invoke(pack, type);
        } catch (InvocationTargetException e) {
            // The pack's own failure, not the guard's: hand it on exactly as a direct call would.
            var cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (Throwable t) {
            // A guard must never take down the read it was added to protect.
            LOGGER.warn("[NeoFobric] Fabric overlay-condition guard failed for a pack; reading it the way Fabric would", t);
            return null;
        }
    }

    /**
     * Stands in for the {@code List.copyOf} at the end of Fabric's overlay-condition handler; see the class
     * javadoc for why the result has to stay mutable under this bridge.
     */
    public static List<String> copyOfOverlays(Collection<String> overlays) {
        return overlays == null ? new ArrayList<>() : new ArrayList<>(overlays);
    }

    /** {@return whether this pack comes from an FML mod's jar} */
    private static boolean isForeignBuildPack(Object pack) {
        try {
            // Pack ids read "mod/<namespace>:<path>"; vanilla and file packs have no namespace to test.
            var id = (String) findMethod(PACK_ID, pack, "packId").invoke(pack);
            var colon = id.indexOf(':');
            if (colon < 0) {
                return false;
            }
            var slash = id.indexOf('/', colon + 1);
            var namespace = id.substring(colon + 1, slash < 0 ? id.length() : slash);
            // FML's mod list is the part of the test pure Fabric cannot make: the bridge also exposes FML
            // mods to the Fabric side (stub metadata for the mod lists), so asking fabric-loader would say
            // "known" for both kinds of mod.
            if (ModList.get().getModContainerById(namespace).isEmpty()) {
                return false;
            }
            if (REPORTED.add(id)) {
                LOGGER.info("[NeoFobric] Leaving pack {} to its NeoForge build - its Fabric overlay conditions"
                        + " belong to the Fabric build of the same mod, whose condition types only the NeoForge"
                        + " side registered", id);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@return the named no-argument-or-one-argument method as seen from the target's own class}
     *
     * <p>Looked up on the runtime class on purpose: the game layer holds several class-loader views of the
     * same interfaces (a pack from an extracted Fabric API module implements a different
     * {@code PackResources} object than a plain {@code Class.forName} from this bridge would find), and a
     * method resolved through the wrong view rejects the receiver at invoke time.
     */
    private static Method findMethod(Map<Class<?>, Method> cache, Object target, String name) throws ReflectiveOperationException {
        var cls = target.getClass();
        var cached = cache.get(cls);
        if (cached != null) {
            return cached;
        }
        for (var method : cls.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() <= 1) {
                cache.put(cls, method);
                return method;
            }
        }
        throw new NoSuchMethodException(cls.getName() + "." + name);
    }
}
