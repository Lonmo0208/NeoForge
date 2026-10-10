/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.discovery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal streaming parser for the parts of {@code fabric.mod.json} the bridge cares about,
 * using the Gson reader bundled inside the fabric-loader jar.
 */
public final class FabricModJsonParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricModJsonParser.class);

    private FabricModJsonParser() {}

    /**
     * Parses the given {@code fabric.mod.json} content.
     *
     * @return the parsed info, or {@code null} if the file is not valid JSON
     */
    public static FabricModInfo parse(byte[] content) {
        try (var reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            reader.setLenient(true);

            String modId = null;
            String version = "1.0.0";
            String environment = "*";
            List<String> commonMixins = List.of();
            List<String> clientMixins = List.of();
            List<String> serverMixins = List.of();
            List<String> nestedJars = List.of();
            String accessWidener = null;
            String displayName = null;
            String description = "";
            List<String> authors = List.of();
            String license = "";
            String websiteUrl = null;
            String issuesUrl = null;
            String iconPath = null;

            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "id" -> modId = reader.nextString();
                    case "version" -> version = readVersion(reader);
                    case "environment" -> environment = reader.nextString();
                    case "mixins" -> {
                        var sideMixins = readMixinEntries(reader);
                        commonMixins = sideMixins.common();
                        clientMixins = sideMixins.client();
                        serverMixins = sideMixins.server();
                    }
                    case "client" -> clientMixins = readStringArray(reader);
                    case "server" -> serverMixins = readStringArray(reader);
                    case "jars" -> nestedJars = readNestedJarPaths(reader);
                    case "accessWidener" -> accessWidener = reader.nextString();
                    case "name" -> displayName = reader.nextString();
                    case "description" -> description = readStringOrSkip(reader);
                    case "authors" -> authors = readAuthorNames(reader);
                    case "license" -> license = readLicense(reader);
                    case "contact" -> {
                        var contact = readContact(reader);
                        websiteUrl = contact.website();
                        issuesUrl = contact.issues();
                    }
                    case "icon" -> iconPath = readIcon(reader);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();

            if (modId == null) {
                LOGGER.debug("[NeoFobric] fabric.mod.json has no id field, ignoring it");
                return null;
            }

            return new FabricModInfo(modId, version, environment, commonMixins, clientMixins, serverMixins,
                    nestedJars, accessWidener,
                    displayName != null && !displayName.isBlank() ? displayName.trim() : modId,
                    description == null ? "" : description.strip(),
                    authors, license, websiteUrl, issuesUrl, iconPath,
                    // Filled in by the locator when the jar turns out to be carried inside another
                    // mod's jar; fabric.mod.json itself does not say who bundles it.
                    "");
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[NeoFobric] Failed to parse fabric.mod.json", e);
            return null;
        }
    }

    private static String readVersion(JsonReader reader) throws IOException {
        var token = reader.peek();
        if (token == JsonToken.STRING || token == JsonToken.NUMBER) {
            return reader.nextString();
        }
        reader.skipValue();
        return "1.0.0";
    }

    /** A string value, skipped and returned as null when the value is some other JSON shape. */
    private static String readStringOrSkip(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.STRING) {
            return reader.nextString();
        }
        reader.skipValue();
        return null;
    }

    /**
     * The {@code authors} array: plain names, or objects with a {@code name} member (the fabric
     * metadata spec allows both).
     */
    private static List<String> readAuthorNames(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue();
            return List.of();
        }
        var names = new ArrayList<String>();
        reader.beginArray();
        while (reader.hasNext()) {
            switch (reader.peek()) {
                case STRING -> names.add(reader.nextString());
                case BEGIN_OBJECT -> {
                    String name = null;
                    reader.beginObject();
                    while (reader.hasNext()) {
                        if ("name".equals(reader.nextName()) && reader.peek() == JsonToken.STRING) {
                            name = reader.nextString();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();
                    if (name != null && !name.isBlank()) {
                        names.add(name.trim());
                    }
                }
                default -> reader.skipValue();
            }
        }
        reader.endArray();
        return List.copyOf(names);
    }

    /** The {@code license} declaration, which may be a string or an array of strings. */
    private static String readLicense(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case STRING -> {
                return reader.nextString();
            }
            case BEGIN_ARRAY -> {
                var joined = new ArrayList<String>();
                reader.beginArray();
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.STRING) {
                        joined.add(reader.nextString());
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endArray();
                return String.join(", ", joined);
            }
            default -> {
                reader.skipValue();
                return "";
            }
        }
    }

    private record Contact(String website, String issues) {}

    /** The {@code contact} object; only the two links the Mods screen can show are kept. */
    private static Contact readContact(JsonReader reader) throws IOException {
        var contact = new Contact(null, null);
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "homepage" -> contact = new Contact(readStringOrSkip(reader), contact.issues());
                case "issues" -> contact = new Contact(contact.website(), readStringOrSkip(reader));
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        return contact;
    }

    /**
     * The {@code icon} declaration: a single path, or a map of pixel size to path - the largest
     * listed size wins, since the Mods screen scales whatever it gets anyway.
     */
    private static String readIcon(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.STRING) {
            return reader.nextString();
        }
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue();
            return null;
        }
        String best = null;
        int bestSize = -1;
        reader.beginObject();
        while (reader.hasNext()) {
            var sizeKey = reader.nextName();
            var path = readStringOrSkip(reader);
            int size;
            try {
                size = sizeKey.contains("x") ? Integer.parseInt(sizeKey.substring(0, sizeKey.indexOf('x'))) : Integer.parseInt(sizeKey);
            } catch (NumberFormatException e) {
                size = -1;
            }
            if (path != null && size >= bestSize) {
                best = path;
                bestSize = size;
            }
        }
        reader.endObject();
        return best;
    }

    /**
     * The mixin entries of a {@code fabric.mod.json}, split by side.
     *
     * @param common configs that apply everywhere
     * @param client configs declared for the client only
     * @param server configs declared for the server only
     */
    private record MixinEntries(List<String> common, List<String> client, List<String> server) {}

    /**
     * Reads the {@code mixins} array, which holds two shapes side by side.
     *
     * <p>A plain string names a config that applies everywhere, but a mod may instead give an object
     * with a {@code config} and an {@code environment} ({@code "*"}, {@code "client"} or
     * {@code "server"}). Only the string form used to be read, so every client-only config was dropped
     * silently - fabric-resource-loader-v1 keeps nine of them, including the one that adds Fabric mods
     * to the resource pack list. Without it no Fabric mod resources were ever loaded: Sodium's language
     * file among them, which is why its whole UI showed raw keys such as
     * {@code sodium.options.search.hint}.
     */
    private static MixinEntries readMixinEntries(JsonReader reader) throws IOException {
        var common = new ArrayList<String>();
        var client = new ArrayList<String>();
        var server = new ArrayList<String>();
        reader.beginArray();
        while (reader.hasNext()) {
            switch (reader.peek()) {
                case STRING -> common.add(reader.nextString());
                case BEGIN_OBJECT -> {
                    String config = null;
                    var environment = "*";
                    reader.beginObject();
                    while (reader.hasNext()) {
                        switch (reader.nextName()) {
                            case "config" -> config = reader.nextString();
                            case "environment" -> environment = reader.nextString();
                            default -> reader.skipValue();
                        }
                    }
                    reader.endObject();
                    if (config != null) {
                        switch (environment) {
                            case "client" -> client.add(config);
                            case "server" -> server.add(config);
                            default -> common.add(config);
                        }
                    }
                }
                default -> reader.skipValue();
            }
        }
        reader.endArray();
        return new MixinEntries(List.copyOf(common), List.copyOf(client), List.copyOf(server));
    }

    private static List<String> readStringArray(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue();
            return List.of();
        }

        var result = new ArrayList<String>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.STRING) {
                result.add(reader.nextString());
            } else {
                reader.skipValue();
            }
        }
        reader.endArray();
        return List.copyOf(result);
    }

    private static List<String> readNestedJarPaths(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue();
            return List.of();
        }

        var result = new ArrayList<String>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue();
                continue;
            }
            reader.beginObject();
            while (reader.hasNext()) {
                if ("file".equals(reader.nextName()) && reader.peek() == JsonToken.STRING) {
                    result.add(reader.nextString());
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
        reader.endArray();
        return List.copyOf(result);
    }
}
