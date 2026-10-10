/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.transform;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads NeoForge's {@code META-INF/injected-interfaces.json}: which interfaces the game classes are
 * meant to implement.
 *
 * <p>NeoForge applies this while building its sources, so the compiled NeoForge jar has it. The jar the
 * development environment loads for Minecraft, however, is the access-transformed vanilla one, which
 * never went through that step - so at runtime {@code LivingEntity} does not implement
 * {@code ILivingEntityExtension}, and the first call into {@code canDrownInFluidType} or any other
 * default method dies with {@code AbstractMethodError: ... does not define or inherit ... self()}.
 * That is what crashed a server while ticking a glow squid.
 *
 * <p>The bridge injects the missing interfaces itself, which is what the build step would have done. It
 * is idempotent: a class that already declares the interface is left alone, so a jar that carries the
 * injection keeps working unchanged.
 */
public final class InjectedInterfaceSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(InjectedInterfaceSource.class);

    private static final String RESOURCE = "META-INF/injected-interfaces.json";

    /** Matches {@code "class": [ ... ]} including the nested string list. */
    private static final Pattern ENTRY =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[([^\\]]*)\\]");

    /** Matches one quoted string inside a value list. */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    private static Map<String, List<String>> interfaces;

    private InjectedInterfaceSource() {}

    /**
     * {@return class internal name to the interfaces it should implement}
     *
     * <p>Empty when the resource cannot be read, which only means the bridge does not add anything -
     * exactly the previous behaviour.
     */
    public static synchronized Map<String, List<String>> get() {
        if (interfaces == null) {
            interfaces = load();
        }
        return interfaces;
    }

    private static Map<String, List<String>> load() {
        for (var loader : new ClassLoader[] {
                InjectedInterfaceSource.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader() }) {
            if (loader == null) {
                continue;
            }
            try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    continue;
                }
                var json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                var parsed = parse(json);
                LOGGER.info("[NeoFobric] Loaded {} injected-interface declarations", parsed.size());
                return parsed;
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Could not read {} through {}", RESOURCE, loader, t);
            }
        }
        LOGGER.warn("[NeoFobric] {} not found - classes will keep whatever interfaces they already have",
                RESOURCE);
        return Map.of();
    }

    /**
     * Parses the flat {@code {"class": ["iface", ...]}} shape.
     *
     * <p>Hand-rolled rather than pulling in a JSON library: the file is generated with a fixed shape, and
     * the regex is anchored on the exact structure it produces.
     */
    private static Map<String, List<String>> parse(String json) {
        var result = new LinkedHashMap<String, List<String>>();
        var entries = ENTRY.matcher(json);
        while (entries.find()) {
            var owner = entries.group(1);
            var values = new ArrayList<String>();
            var quoted = QUOTED.matcher(entries.group(2));
            while (quoted.find()) {
                values.add(quoted.group(1));
            }
            if (!values.isEmpty()) {
                result.put(owner, List.copyOf(values));
            }
        }
        return result;
    }
}
