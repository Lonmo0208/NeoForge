/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import java.util.Map;
import net.minecraft.advancements.predicates.entity.EntitySubPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Reads the pre-split form of an entity's {@code type_specific} predicate, so data written before
 * {@code type_specific} became a dispatched map keeps working.
 *
 * <p>The predicate used to be one registry entry whose value carried the kind as a field:
 *
 * <pre>{@code "type_specific": { "type": "minecraft:slime", "size": 1 }}</pre>
 *
 * <p>It is now a map from sub-predicate id to value:
 *
 * <pre>{@code "type_specific": { "minecraft:cube_mob": { "size": 1 } }}</pre>
 *
 * <p>This codec reads the old shape by resolving the old kind against today's registry - {@code slime}
 * became {@code type_specific/cube_mob}, {@code lightning} and the rest kept their name under a
 * {@code type_specific/} prefix - and handing the remaining fields to that sub-predicate's own codec, so what
 * comes out is an ordinary modern predicate. Datapacks that still ship the old shape are unaffected by the
 * split; Twilight Forest's maze slime loot table is one, and without this it fails to load and the mob drops
 * nothing.
 *
 * <p>It is wired in as the fallback of the modern codec (see {@code EntityPredicate}), so it only ever sees
 * data the modern shape rejected, and encoding always writes the modern shape.
 */
public final class LegacyTypeSpecificPredicates {
    /** Kinds the split renamed. Everything else keeps its name under {@code type_specific/}. */
    private static final Map<String, String> RENAMES = Map.of("slime", "cube_mob");

    /** Old entity predicate field name to the sub-predicate id the split gave it. */
    private static final Map<String, String> LEGACY_FIELDS = Map.ofEntries(
            Map.entry("type", "entity_type"),
            Map.entry("nbt", "nbt"),
            Map.entry("flags", "flags"),
            Map.entry("equipment", "equipment"),
            Map.entry("location", "location"),
            Map.entry("stepping_on", "stepping_on"),
            Map.entry("movement_affected_by", "movement_affected_by"),
            Map.entry("distance", "distance"),
            Map.entry("movement", "movement"),
            Map.entry("effects", "effects"),
            Map.entry("vehicle", "vehicle"),
            Map.entry("passenger", "passenger"),
            Map.entry("targeted_entity", "targeted_entity"),
            Map.entry("team", "team"),
            Map.entry("slots", "slots"),
            Map.entry("components", "components"),
            Map.entry("periodic_tick", "periodic_tick"));

    private static final String TYPE_FIELD = "type";

    private LegacyTypeSpecificPredicates() {}

    /**
     * {@return the modern map codec, falling back to the pre-split shape}
     *
     * @param modern the codec for today's {@code type_specific} map, keyed by sub-predicate codec
     */
    public static Codec<Map<Codec<? extends EntitySubPredicate>, EntitySubPredicate>> legacyFallback(
            Codec<Map<Codec<? extends EntitySubPredicate>, EntitySubPredicate>> modern) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<Map<Codec<? extends EntitySubPredicate>, EntitySubPredicate>, T>> decode(
                    DynamicOps<T> ops, T input) {
                var asIs = modern.decode(ops, input);
                if (asIs.result().isPresent() || asIs.error().isEmpty()) {
                    return asIs;
                }
                var legacy = ops.getMap(input).flatMap(map -> decodeLegacy(ops, map));
                if (legacy.result().isPresent()) {
                    return legacy;
                }
                // Neither shape fits: report what the modern one said, it is the shape callers write.
                return asIs;
            }

            @Override
            public <T> DataResult<T> encode(Map<Codec<? extends EntitySubPredicate>, EntitySubPredicate> input,
                    DynamicOps<T> ops, T prefix) {
                return modern.encode(input, ops, prefix);
            }
        };
    }

    /**
     * Reads the pre-split entity predicate map: its fields were named after what the split turned into
     * sub-predicate ids, so {@code "type": {"type": "minecraft:frog"}} became
     * {@code "minecraft:entity_type": {"type": "minecraft:frog"}} and {@code type_specific} kept the kind
     * as a field instead of in the id.
     */
    private static <T> DataResult<Pair<Map<Codec<? extends EntitySubPredicate>, EntitySubPredicate>, T>> decodeLegacy(
            DynamicOps<T> ops, MapLike<T> input) {
        var parts = new java.util.HashMap<Codec<? extends EntitySubPredicate>, EntitySubPredicate>();
        var failed = new java.util.concurrent.atomic.AtomicReference<String>();
        input.entries().forEach(entry -> {
            var key = ops.getStringValue(entry.getFirst()).result().orElse(null);
            if (key == null) {
                failed.compareAndSet(null, "Unreadable field name");
                return;
            }
            if (key.indexOf(':') >= 0) {
                // Already an id: a predicate in today own shape, mixed into a pre-split one.
                decodeInto(ops, entry.getSecond(), Identifier.tryParse(key), parts, failed);
                return;
            }
            if ("type_specific".equals(key)) {
                ops.getMap(entry.getSecond()).result().ifPresentOrElse(
                        inner -> decodeLegacyTypeSpecific(ops, inner, parts, failed),
                        () -> failed.compareAndSet(null, "type_specific is not an object"));
                return;
            }
            var mapped = LEGACY_FIELDS.get(key);
            if (mapped == null) {
                failed.compareAndSet(null, "Unknown pre-split field '" + key + "'");
                return;
            }
            decodeInto(ops, entry.getSecond(), Identifier.withDefaultNamespace(mapped), parts, failed);
        });
        var problem = failed.get();
        if (problem != null) {
            return DataResult.error(() -> problem);
        }
        if (parts.isEmpty()) {
            return DataResult.error(() -> "No predicates in a pre-split entity predicate");
        }
        return DataResult.success(Pair.of(Map.copyOf(parts), ops.empty()));
    }

    /** Decodes one predicate value with the codec registered for {@code id}. */
    private static <T> void decodeInto(DynamicOps<T> ops, T value, Identifier id,
            Map<Codec<? extends EntitySubPredicate>, EntitySubPredicate> parts,
            java.util.concurrent.atomic.AtomicReference<String> failed) {
        if (id == null) {
            failed.compareAndSet(null, "Not an id");
            return;
        }
        var codec = BuiltInRegistries.ENTITY_SUB_PREDICATE_TYPE.getOptional(id).orElse(null);
        if (codec == null) {
            failed.compareAndSet(null, "Unknown entity sub-predicate " + id);
            return;
        }
        try {
            parts.put(codec, parse(codec, ops, value));
        } catch (RuntimeException e) {
            failed.compareAndSet(null, "Failed to read " + id + ": " + e.getMessage());
        }
    }

    /** Reads {@code "type_specific": {"type": <kind>, ...fields}} into the sub-predicate the split made of it. */
    private static <T> void decodeLegacyTypeSpecific(
            DynamicOps<T> ops, MapLike<T> inner,
            Map<Codec<? extends EntitySubPredicate>, EntitySubPredicate> parts,
            java.util.concurrent.atomic.AtomicReference<String> failed) {
        var kindElement = inner.get(ops.createString(TYPE_FIELD));
        if (kindElement == null) {
            failed.compareAndSet(null, "No '" + TYPE_FIELD + "' in a pre-split type_specific predicate");
            return;
        }
        var kind = ops.getStringValue(kindElement).result().orElse(null);
        var oldKind = kind == null ? null : Identifier.tryParse(kind);
        if (oldKind == null) {
            failed.compareAndSet(null, "Expected an id in '" + TYPE_FIELD + "', got " + kindElement);
            return;
        }
        var renamed = RENAMES.getOrDefault(oldKind.getPath(), oldKind.getPath());
        for (var candidate : new String[] { "type_specific/" + renamed, renamed }) {
            var id = Identifier.fromNamespaceAndPath(oldKind.getNamespace(), candidate);
            if (BuiltInRegistries.ENTITY_SUB_PREDICATE_TYPE.getOptional(id).isPresent()) {
                // The kind leaves the value: what remains is what the sub-predicate's codec expects.
                decodeInto(ops, createWithoutType(ops, inner), id, parts, failed);
                return;
            }
        }
        failed.compareAndSet(null, "Unknown entity sub-predicate " + oldKind);
    }

    /** {@return the input map without its {@code type} entry, in the caller's ops} */
    private static <T> T createWithoutType(DynamicOps<T> ops, MapLike<T> input) {
        return ops.createMap(input.entries()
                .filter(entry -> !TYPE_FIELD.equals(ops.getStringValue(entry.getFirst()).result().orElse(null)))
                .map(entry -> Pair.of(entry.getFirst(), entry.getSecond())));
    }

    /** {@return the decoded predicate} The registry hands out a wildcard, the parsed value is the type it holds. */
    @SuppressWarnings("unchecked")
    private static <T> EntitySubPredicate parse(Codec<? extends EntitySubPredicate> codec, DynamicOps<T> ops, T input) {
        return ((Codec<EntitySubPredicate>) codec).parse(ops, input)
                .getOrThrow(message -> new IllegalStateException(message));
    }
}
