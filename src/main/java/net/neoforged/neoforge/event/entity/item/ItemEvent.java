/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.event.entity.item;

import net.minecraft.world.entity.livingblock.LivingBlock;
import net.neoforged.neoforge.event.entity.EntityEvent;

/**
 * Base class for all {@link LivingBlock} events. Contains a reference to the
 * ItemEntity of interest. For most ItemEntity events, there's little to no
 * additional useful data from the firing method that isn't already contained
 * within the ItemEntity instance.
 */
public abstract class ItemEvent extends EntityEvent {
    private final LivingBlock itemEntity;

    /**
     * Creates a new event for an {@link LivingBlock}.
     *
     * @param itemEntity The ItemEntity for this event
     */
    public ItemEvent(LivingBlock itemEntity) {
        super(itemEntity);
        this.itemEntity = itemEntity;
    }

    /**
     * The relevant {@link LivingBlock} for this event.
     */
    @Override
    public LivingBlock getEntity() {
        return itemEntity;
    }
}
