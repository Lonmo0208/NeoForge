/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.data.internal.language;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class NeoForgeMinecraftEnpLanguageProvider extends LanguageProvider {
    public NeoForgeMinecraftEnpLanguageProvider(PackOutput output) {
        super(output, "minecraft", "enp");
    }

    @Override
    protected void addTranslations() {
        add("advancements.adventure.build_house.description", "Watch 23 clots and a door bind into a small house");
        add("advancements.adventure.build_house.title", "Small House, Sam!");
        add("advancements.adventure.build_trap.description", "Watch out for 8 iron (or copper) rods, they're baneful together. When you least foredeem it!");
        add("advancements.adventure.build_trap.title", "You Played Yourself");
        add("advancements.adventure.craft_chest.description", "Craft a Chest to steer your clots");
        add("advancements.adventure.craft_chest.title", "Hapsomely Reached Anworks");
        add("advancements.adventure.craft_sword.description", "Craft a Sword");
        add("advancements.adventure.craft_sword.title", "Time to Fight!");
        add("advancements.adventure.crafter.description", "Selfshrithe your crafting with a Crafter");
        add("advancements.adventure.crafter.title", "Big Living Clots Forebisen");
        add("advancements.adventure.crafting_table.description", "Craft a Workbench");
        add("advancements.adventure.crafting_table.title", "Overall Crafting Umbworld");
        add("advancements.adventure.door.description", "Craft a Door");
        add("advancements.adventure.door.title", "Seek Shelter");
        add("advancements.adventure.end_portal.title", "End of Line");
        add("advancements.adventure.furnace.description", "Make a kiln that can smelt things for you");
        add("advancements.adventure.furnace.title", "You're Cookin'");
        add("advancements.adventure.iron_ingot.description", "Make your way to the iron eld");
        add("advancements.adventure.iron_ingot.title", "Inwidful Iron");
        add("advancements.adventure.kill_a_mob.description", "Have you sword kill any foewarry");
        add("advancements.adventure.kill_a_mob.title", "Warryhunter (With Friends)");
        add("advancements.adventure.kill_all_mobs.description", "Behest a heer of weapons to kill one of every foewarry");
        add("advancements.adventure.mine_a_block.description", "Hit a clot until it comes alive");
        add("advancements.adventure.mine_a_block.title", "Delve a Clot");
        add("advancements.adventure.nether_portal.description", "Watch twelve Ravenflint and a Flint and Steel bind into a Nether Ingang");
        add("advancements.adventure.nether_portal.title", "Nether-field Crafting");
        add("advancements.adventure.obsidian.description", "Delve some Ravenflint");
        add("advancements.adventure.obsidian.title", "You need more...");
        add("advancements.adventure.ol_betsy.description", "Get a Crossbow");
        add("advancements.adventure.root.description", "Herding and crafting your world");
        add("advancements.adventure.root.title", "HerdCraft");
        add("advancements.adventure.wooden_pickaxe.description", "Craft a tool that can delve for you");
        add("advancements.adventure.wooden_pickaxe.title", "Craft a Delver");
        add("advancements.nether.barrel_roll.description", "Do it! Do it now!");
        add("advancements.story.enter_the_nether.description", "Gather enough Ravenflint and a Flint and Steel, then go into the Nether");
        add("block.minecraft.copper_trap", "Copper Trap");
        add("block.minecraft.exposed_copper_trap", "Unwried Copper Trap");
        add("block.minecraft.iron_trap", "Iron Trap");
        add("block.minecraft.oxidized_copper_trap", "Sourshafted Copper Trap");
        add("block.minecraft.waxed_copper_trap", "Waxed Copper Trap");
        add("block.minecraft.waxed_exposed_copper_trap", "Waxed Unwried Copper Trap");
        add("block.minecraft.waxed_oxidized_copper_trap", "Waxed Sourshafted Copper Trap");
        add("block.minecraft.waxed_weathered_copper_trap", "Waxed Weathered Copper Trap");
        add("block.minecraft.weathered_copper_trap", "Weathered Copper Trap");
        add("entity.minecraft.crafting_action", "Craft");
        add("entity.minecraft.crafting_grid", "Crafting Layout");
        add("entity.minecraft.follow_action", "Follow Me");
        add("entity.minecraft.hovering_item", "Crafting Outcome");
        add("entity.minecraft.living_block", "Living Clot");
        add("entity.minecraft.living_block_command", "Living Clot Hest");
        add("entity.minecraft.punch_action", "Hit");
        add("inventory.place_grid", "Brook the Craft deed to stow a crafting layout");
        add("item.minecraft.attack_action", "Strike / Delve");
        add("item.minecraft.build_action", "Build");
        add("item.minecraft.crafting_action", "Craft");
        add("item.minecraft.follow_action", "Follow Me");
        add("item.minecraft.group_action", "Team / Unteam");
        add("item.minecraft.highlight_action", "Highlight");
        add("item.minecraft.move_action", "Shrithe");
        add("item.minecraft.punch_action", "Hit");
        add("item.minecraft.select_action", "Choose");
        add("item.minecraft.select_group_action.details", "Chosen Team: %s");
        add("living_blocks.group.all", "All");
        add("living_blocks.group.aqua", "Light Bloe");
        add("living_blocks.group.blue", "Bloe");
        add("living_blocks.group.lime", "Light Green");
        add("living_blocks.group.none", "None");
        add("living_blocks.group.purple", "Purple");
        add("living_blocks.group.red", "Red");
        add("living_blocks.group.yellow", "Yellow");
        add("player.commanded_blocks.no_blocks_in_area", "Selected group contains no blocks in the area");
        add("gamerule.minecraft.pick_up_item", "Allows ye to be pickin' up and copyin' dropped loot with right-click or middle-click while sneakin', arrr!");
    }
}
