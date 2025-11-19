/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.client;

import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModLoader;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.extensions.IDimensionSpecialEffectsExtension;
import org.jetbrains.annotations.ApiStatus;

/**
 * Manager for {@link DimensionSpecialEffects} instances.
 * <p>
 * Provides a lookup by dimension type.
 */
public final class DimensionSpecialEffectsManager {
    private static ImmutableMap<Identifier, IDimensionSpecialEffectsExtension> EFFECTS;
    private static IDimensionSpecialEffectsExtension DEFAULT_EFFECTS = new IDimensionSpecialEffectsExtension() {};

    /**
     * Finds the {@link DimensionSpecialEffects} for a given dimension type, or the default if none is registered.
     */
    public static IDimensionSpecialEffectsExtension getForType(Identifier type) {
        return EFFECTS.getOrDefault(type, DEFAULT_EFFECTS);
    }

    @ApiStatus.Internal
    public static void init() {
        var effects = new HashMap<Identifier, IDimensionSpecialEffectsExtension>();
        var event = new RegisterDimensionSpecialEffectsEvent(effects);
        ModLoader.postEventWrapContainerInModOrder(event);
        EFFECTS = ImmutableMap.copyOf(effects);
    }

    public static IDimensionSpecialEffectsExtension getDefaultEffects() {
        return DEFAULT_EFFECTS;
    }

    private DimensionSpecialEffectsManager() {}
}
