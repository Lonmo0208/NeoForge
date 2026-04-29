/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.level.AlterGroundEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = { LevelTests.GROUP + ".event", "event" })
public class LevelEventTests {
    @GameTest
    @EmptyTemplate(value = "9x9x9", floor = true)
    @TestHolder(description = "Tests if the sapling grow tree event is fired, replacing spruce with birch")
    static void saplingGrowTreeEvent(final DynamicTest test) {
        test.eventListeners().forge().addListener((final BlockGrowFeatureEvent event) -> {
            if (event.getFeature() != null) {
                event.setFeature(TreeFeatures.BIRCH_BEES_005);
            }
            test.pass();
        });

        test.onGameTest(helper -> helper.startSequence(helper::makeMockPlayer)
                .thenExecute(() -> helper.setBlock(4, 1, 4, Blocks.DIRT))
                .thenExecute(() -> helper.setBlock(4, 2, 4, Blocks.OAK_SAPLING))
                .thenWaitUntil(player -> helper.boneMealUntilGrown(4, 2, 4, player))
                .thenExecute(() -> helper.assertTrue(
                        helper.blocksBetween(0, 0, 0, 9, 9, 9).anyMatch(pos -> {
                            var state = helper.getLevel().getBlockState(pos);
                            return state.is(Blocks.BIRCH_LOG) || state.is(Blocks.OAK_LOG) || state.is(Blocks.SPRUCE_LOG);
                        }),
                        "Expected a tree log to have grown"))
                .thenSucceed());
    }

    @GameTest
    @TestHolder(description = "Tests if the alter ground event is fired, replacing podzol with redstone blocks")
    static void alterGroundEvent(final DynamicTest test) {
        test.registerGameTestTemplate(StructureTemplateBuilder.withSize(16, 32, 16)
                .fill(0, 0, 0, 15, 0, 15, Blocks.GRASS_BLOCK.defaultBlockState())
                .fill(7, 1, 7, 8, 1, 8, Blocks.SPRUCE_SAPLING.defaultBlockState()));

        test.eventListeners().forge().addListener((final AlterGroundEvent event) -> {
            final AlterGroundEvent.StateProvider old = event.getStateProvider();
            event.setStateProvider((level, rand, pos) -> {
                final BlockState state = old.getState(level, rand, pos);
                return state != null && state.is(Blocks.PODZOL) ? Blocks.REDSTONE_BLOCK.defaultBlockState() : state;
            });
            test.pass();
        });

        test.onGameTest(helper -> helper.startSequence(helper::makeMockPlayer)
                .thenWaitUntil(player -> helper.boneMealUntilGrown(7, 1, 7, player))
                .thenExecute(player -> {
                    if (test.status().result().passed()) {
                        boolean hasRedstone = helper.blocksBetween(0, 0, 0, 16, 1, 16)
                                .anyMatch(pos -> helper.getLevel().getBlockState(pos).is(Blocks.REDSTONE_BLOCK));
                        if (hasRedstone) {
                            boolean noPodzol = helper.blocksBetween(0, 0, 0, 16, 1, 16)
                                    .noneMatch(pos -> helper.getLevel().getBlockState(pos).is(Blocks.PODZOL));
                            helper.assertTrue(noPodzol, "Podzol was still placed!");
                        }
                    }
                })
                .thenSucceed());
    }

    /**
     * Tests {@link VanillaGameEvent} by listening for and printing out any uses of shears in the overworld.
     */
    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = "Tests the vanilla game event by hurting entities that are sheared in the overworld")
    static void vanillaGameEvent(final DynamicTest test) {
        test.eventListeners().forge().addListener((final VanillaGameEvent event) -> {
            if (event.getVanillaEvent().is(GameEvent.SHEAR) && event.getLevel().dimension() == Level.OVERWORLD) {
                final var entities = event.getLevel().getEntitiesOfClass(Entity.class, new AABB(BlockPos.containing(event.getEventPosition())), e -> e instanceof Shearable);
                if (!entities.isEmpty()) {
                    entities.get(0).hurt(event.getLevel().damageSources().generic(), event.getCause() == null ? 1 : 3);
                    test.pass();
                }
            }
        });

        test.onGameTest(helper -> helper.startSequence(() -> helper.spawnWithNoFreeWill(EntityType.SHEEP, new BlockPos(1, 2, 1)))
                .thenExecute(sheep -> sheep.setColor(DyeColor.BLACK))
                .thenExecute(sheep -> sheep.setSheared(false))

                // Prepare a dispenser to shear the sheep in the second phase
                .thenSequence(sequence -> sequence
                        .thenExecute(() -> helper.setBlock(1, 1, 1, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.UP)))
                        .thenMap(() -> helper.getBlockEntity(1, 1, 1, DispenserBlockEntity.class))
                        .thenExecute(dispenser -> dispenser.setItem(1, Items.SHEARS.getDefaultInstance())))

                .thenIdle(5)
                .thenExecute(sheep -> Items.SHEARS.getDefaultInstance().interactLivingEntity(
                        helper.makeMockPlayer(), sheep, InteractionHand.MAIN_HAND)) // Make a player shear the sheep

                .thenExecuteAfter(5, sheep -> {
                    // Prepare the sheep; reset its color and its state
                    sheep.setColor(DyeColor.BLUE);
                    sheep.setSheared(false);
                })
                .thenIdle(5)

                // Power the dispenser
                .thenExecute(() -> helper.setBlock(2, 1, 1, Blocks.REDSTONE_BLOCK))
                .thenIdle(5)

                .thenIdle(5)
                .thenExecute(() -> helper.killAllEntitiesOfClass(Sheep.class))

                .thenSucceed());
    }
}
