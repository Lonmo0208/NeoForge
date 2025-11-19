/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;

@ForEachTest(groups = LevelTests.GROUP)
public class LevelTests {
    public static final String GROUP = "level";

    /**
     * Simple test to ensure custom game rules can be registered correctly and used in game.
     * <p>
     * To test these game rules use the following commands.
     * <br>If the game rules are registered correctly, they should show up as auto-completion values and be able to be changed to valid values based on their types.
     * <br>These game rules should also show up and be editable under the {@code Edit Game Rules} screen, when creating a new world.
     * <br>{@code Create new world > More (tab) > Game Rules > Misc}
     * <ul>
     * <li>
     *
     * <pre>{@code /gamerule neotests_custom_game_rule:custom_boolean_game_rule <true|false>}</pre>
     *
     * </li>
     * Should be able to be set to either {@code true} or {@code false} (Defaulting to {@code true}).
     *
     * <li>
     *
     * <pre>{@code /gamerule neotests_custom_game_rule:custom_integer_game_rule <some integer>}</pre>
     *
     * </li>
     * Should be able to be set to any integer value (Defaulting to {@code 1337}).
     * </ul>
     */
    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Tests if custom game rules work")
    static void customGameRule(final DynamicTest test) {
        final GameRule<Boolean> booleanGameRule = GameRules.registerBoolean("%s:custom_boolean_game_rule".formatted(test.createModId()), GameRuleCategory.MISC, true);
        final GameRule<Integer> integerGameRule = GameRules.registerInteger("%s:custom_integer_game_rule".formatted(test.createModId()), GameRuleCategory.MISC, 1337, 1337);

        test.eventListeners().forge().addListener((EntityTickEvent.Pre event) -> {
            if (event.getEntity() instanceof ServerPlayer player && player.getGameProfile().name().equals("test-mock-player")) {
                if (player.level().getGameRules().get(booleanGameRule)) {
                    player.setHealth(player.getHealth() - player.level().getGameRules().get(integerGameRule));
                }
            }
        });

        test.onGameTest(helper -> {
            final ServerPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);

            var gameRules = player.level().getGameRules();
            final var oldBool = gameRules.get(booleanGameRule);
            final var oldInt = gameRules.get(integerGameRule);

            helper.startSequence()
                    .thenExecute(() -> gameRules.set(booleanGameRule, true, player.level().getServer()))
                    .thenExecute(() -> gameRules.set(integerGameRule, 12, player.level().getServer()))

                    .thenIdle(1)
                    .thenExecute(() -> helper.assertEntityProperty(player, ServerPlayer::getHealth, "player health", 8f))

                    .thenExecute(() -> gameRules.set(booleanGameRule, oldBool, player.level().getServer()))
                    .thenExecute(() -> gameRules.set(integerGameRule, oldInt, player.level().getServer()))
                    .thenSucceed();
        });
    }
}
