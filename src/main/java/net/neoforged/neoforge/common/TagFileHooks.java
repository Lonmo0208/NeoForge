/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import net.minecraft.tags.TagFile;

/**
 * NeoFobric: understands Fabric API's spelling of a tag file's removal list.
 *
 * <p>Fabric's tag-removal extension writes its removal list as a {@code fabric:remove} field, and its client-side
 * tag API loads tag files straight out of the mods' jars and parses them itself. NeoForge's own tag removal reads
 * the plain {@code remove} key, and both are the same idea, so the Fabric key is folded into it before a file is
 * parsed - a data file written for either loader then removes the same entries, whichever side reads it.
 *
 * <p>The codec itself cannot carry the second spelling: Fabric wraps {@code TagFile.CODEC} (to keep the removal
 * list it reads) and the client's registry sync runs through that wrapped codec, where a changed shape leaves the
 * client without the tags it needs to parse the registries it received.
 */
public final class TagFileHooks {
    private TagFileHooks() {}

    /**
     * {@return the same element with Fabric's {@code fabric:remove} key folded into {@code remove}}
     *
     * <p>Entries from both keys are kept when a file carries both.
     */
    public static JsonElement mergeFabricRemove(JsonElement element) {
        if (!(element instanceof JsonObject object)) {
            return element;
        }

        var fabricRemove = object.remove("fabric:remove");
        if (fabricRemove == null) {
            return element;
        }

        if (object.get("remove") instanceof JsonArray existingRemove && fabricRemove instanceof JsonArray fabricEntries) {
            fabricEntries.forEach(existingRemove::add);
        } else {
            object.add("remove", fabricRemove);
        }

        return object;
    }

    /**
     * Parses a tag file the way a Fabric caller expects its fields to be spelled.
     *
     * @see #mergeFabricRemove(JsonElement)
     */
    public static DataResult<TagFile> parseTagFile(Codec<TagFile> codec, Dynamic<JsonElement> input) {
        var merged = mergeFabricRemove(input.getValue());
        return merged == input.getValue() ? codec.parse(input) : codec.parse(new Dynamic<>(input.getOps(), merged));
    }
}
