/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.client.event;

import java.util.List;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.AtlasManager;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.event.IModBusEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Fired for registering {@linkplain TextureAtlas texture atlases} to the {@link AtlasManager}.
 * <p>
 * This event fires during startup when the {@link AtlasManager} is constructed.
 * <p>
 * This event is not {@linkplain ICancellableEvent cancellable}.
 * <p>
 * This event is fired on the mod-specific event bus, only on the {@linkplain LogicalSide#CLIENT logical client}.
 */
public class RegisterTextureAtlasesEvent extends Event implements IModBusEvent {
    private final List<AtlasManager.AtlasConfig> atlases;

    @ApiStatus.Internal
    public RegisterTextureAtlasesEvent(List<AtlasManager.AtlasConfig> atlases) {
        this.atlases = atlases;
    }

    /**
     * Register a texture atlas with the given configuration
     * 
     * @param atlasConfig The configuration of the texture atlas
     */
    public void register(AtlasManager.AtlasConfig atlasConfig) {
        for (AtlasManager.AtlasConfig atlas : this.atlases) {
            if (atlas.textureId().equals(atlasConfig.textureId())) {
                throw new IllegalStateException(String.format(
                        "Duplicate registration of atlas: %s (old config: %s, new config: %s)",
                        atlasConfig.textureId(),
                        atlas,
                        atlasConfig));
            }
        }
        this.atlases.add(atlasConfig);
    }
}
