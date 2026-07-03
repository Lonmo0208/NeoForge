/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.data.internal.language;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class NeoForgeMinecraftEnUsLanguageProvider extends LanguageProvider {
    public NeoForgeMinecraftEnUsLanguageProvider(PackOutput output) {
        super(output, "minecraft", "en_us");
    }

    @Override
    protected void addTranslations() {
        add("advancements.adventure.barrel_roll.description", "Make a barrel roll");
        add("advancements.adventure.barrel_roll.title", "Do a Barrel Roll!");
        add("advancements.adventure.build_house.description", "Watch 23 blocks and a door combine into a tiny house");
        add("advancements.adventure.build_house.title", "Tiny House, Assemble!");
        add("advancements.adventure.build_trap.description", "Watch out for 8 iron (or copper) bars, they're dangerous in groups. When you least expect it!");
        add("advancements.adventure.build_trap.title", "You Played Yourself");
        add("advancements.adventure.craft_chest.description", "Craft a Chest to keep track of your blocks");
        add("advancements.adventure.craft_chest.title", "Random Access Materials");
        add("advancements.adventure.craft_sword.description", "Craft a Sword");
        add("advancements.adventure.craft_sword.title", "Time to Fight!");
        add("advancements.adventure.crafter.description", "Automate your crafting with a Crafter");
        add("advancements.adventure.crafter.title", "Large Living Blocks Model");
        add("advancements.adventure.crafting_table.description", "Craft a Crafting Table");
        add("advancements.adventure.crafting_table.title", "Integrated Crafting Environment");
        add("advancements.adventure.door.description", "Craft a Door");
        add("advancements.adventure.door.title", "Seek Shelter");
        add("advancements.adventure.end_portal.description", "Watch twelve End Portal Frames with Eyes of Ender inserted combine into an End Portal");
        add("advancements.adventure.end_portal.title", "End of Line");
        add("advancements.adventure.eye_frame_combination.description", "Move an Eye of Ender close enough to an End Portal Frame for them to combine");
        add("advancements.adventure.eye_frame_combination.title", "Unifying Eye-Frame Interaction");
        add("advancements.adventure.furnace.description", "Make a furnace that can smelt things for you");
        add("advancements.adventure.furnace.title", "You're Cookin'");
        add("advancements.adventure.iron_ingot.description", "Make your way to the iron age");
        add("advancements.adventure.iron_ingot.title", "Ferrocious Ferrous");
        add("advancements.adventure.kill_a_mob.description", "Have your sword kill any hostile monster");
        add("advancements.adventure.kill_a_mob.title", "Monster Hunter (With Friends)");
        add("advancements.adventure.kill_all_mobs.description", "Command an army of weapons to kill one of every hostile monster");
        add("advancements.adventure.mine_a_block.description", "Punch a block until it comes alive");
        add("advancements.adventure.mine_a_block.title", "Mine a Block");
        add("advancements.adventure.nether_portal.description", "Watch twelve Obsidian and a Flint and Steel combine into a Nether Portal");
        add("advancements.adventure.nether_portal.title", "Nether-field Crafting");
        add("advancements.adventure.obsidian.description", "Mine some Obsidian");
        add("advancements.adventure.obsidian.title", "You require additional...");
        add("advancements.adventure.ol_betsy.description", "Aqcuire a Crossbow");
        add("advancements.adventure.root.description", "Herding and crafting your world");
        add("advancements.adventure.root.title", "HerdCraft");
        add("advancements.adventure.wooden_pickaxe.description", "Craft a tool that can mine for you");
        add("advancements.adventure.wooden_pickaxe.title", "Craft a Miner");
        add("advancements.end.barrel_roll.description", "Roll a barrel to the ends of the universe");
        add("advancements.end.barrel_roll.title", "Never Give Up! Trust Your Instincts!");
        add("advancements.nether.barrel_roll.description", "Do it! Do it now!");
        add("advancements.nether.barrel_roll.title", "Do a Barrel Roll! Do it! Do it! Do it in the Nether!");
        add("advancements.story.enter_the_end.description", "Gather enough Eyes of Ender, find some End Portal Frames and enter the End");
        add("advancements.story.enter_the_nether.description", "Gather enough Obsidian and a Flint and Steel, then enter the Nether");
        add("block.minecraft.copper_trap", "Copper Trap");
        add("block.minecraft.exposed_copper_trap", "Exposed Copper Trap");
        add("block.minecraft.iron_trap", "Iron Trap");
        add("block.minecraft.oxidized_copper_trap", "Oxidized Copper Trap");
        add("block.minecraft.waxed_copper_trap", "Waxed Copper Trap");
        add("block.minecraft.waxed_exposed_copper_trap", "Waxed Exposed Copper Trap");
        add("block.minecraft.waxed_oxidized_copper_trap", "Waxed Oxidized Copper Trap");
        add("block.minecraft.waxed_weathered_copper_trap", "Waxed Weathered Copper Trap");
        add("block.minecraft.weathered_copper_trap", "Weathered Copper Trap");
        add("crafting.table.unable_to_place", "The crafting grid needs a 2x2 block wide level surface");
        add("entity.minecraft.crafting_action", "Craft");
        add("entity.minecraft.crafting_grid", "Crafting Grid");
        add("entity.minecraft.follow_action", "Follow Me");
        add("entity.minecraft.hovering_item", "Crafting Results");
        add("entity.minecraft.living_block", "Living Block");
        add("entity.minecraft.living_block_command", "Living Block Command");
        add("entity.minecraft.punch_action", "Punch");
        add("inventory.place_grid", "Use the Craft action to place a crafting grid");
        add("item.minecraft.attack_action", "Attack / Mine");
        add("item.minecraft.build_action", "Build");
        add("item.minecraft.crafting_action", "Craft");
        add("item.minecraft.follow_action", "Follow Me");
        add("item.minecraft.group_action", "Group / Ungroup");
        add("item.minecraft.highlight_action", "Highlight");
        add("item.minecraft.move_action", "Move");
        add("item.minecraft.punch_action", "Punch");
        add("item.minecraft.select_action", "Select");
        add("item.minecraft.select_group_action.details", "Selected Group: %s");
        add("living_blocks.group.all", "All");
        add("living_blocks.group.aqua", "Aqua");
        add("living_blocks.group.blue", "Blue");
        add("living_blocks.group.lime", "Lime");
        add("living_blocks.group.none", "None");
        add("living_blocks.group.purple", "Purple");
        add("living_blocks.group.red", "Red");
        add("living_blocks.group.yellow", "Yellow");
        add("player.commanded_blocks.no_blocks_in_area", "Selected group contains no blocks in the area");
        add("gamerule.minecraft.pick_up_item", "Allows picking up and copying dropped items with right-click or middle-click while sneaking");
    }
}
