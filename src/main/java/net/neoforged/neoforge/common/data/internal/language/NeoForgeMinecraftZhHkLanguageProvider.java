/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.data.internal.language;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class NeoForgeMinecraftZhHkLanguageProvider extends LanguageProvider {
    public NeoForgeMinecraftZhHkLanguageProvider(PackOutput output) {
        super(output, "minecraft", "zh_hk");
    }

    @Override
    protected void addTranslations() {
        add("advancements.adventure.barrel_roll.description", "使木桶翻滾");
        add("advancements.adventure.barrel_roll.title", "做個桶滾！");
        add("advancements.adventure.build_house.description", "目睹 23 個方塊與 1 道門合併為一間迷你屋");
        add("advancements.adventure.build_house.title", "集合吧！迷你屋組裝隊");
        add("advancements.adventure.build_trap.description", "小心 8 個鐵欄杆（或銅欄杆），它們成羣時非常危險，尤其在你分心之際！");
        add("advancements.adventure.build_trap.title", "咎由自取");
        add("advancements.adventure.craft_chest.description", "合成一個儲物箱以掌控你的方塊");
        add("advancements.adventure.craft_chest.title", "隨機存取物體（RAM）");
        add("advancements.adventure.craft_sword.description", "合成一把劍");
        add("advancements.adventure.craft_sword.title", "開戰時刻！");
        add("advancements.adventure.crafter.description", "用合成器使合成自動化");
        add("advancements.adventure.crafter.title", "大型活體方塊模型");
        add("advancements.adventure.crafting_table.description", "合成一個工作台");
        add("advancements.adventure.crafting_table.title", "整合合成環境（ICE）");
        add("advancements.adventure.door.description", "合成一道門");
        add("advancements.adventure.door.title", "尋求庇護");
        add("advancements.adventure.end_portal.description", "目睹 12 個裝有終界之眼的終界傳送門框，合併為終界傳送門");
        add("advancements.adventure.end_portal.title", "「終」點站");
        add("advancements.adventure.eye_frame_combination.description", "移動一個終界之眼，使其距離足以與終界傳送門框合併");
        add("advancements.adventure.eye_frame_combination.title", "統一眼眶互動");
        add("advancements.adventure.furnace.description", "製作一個可為你熔煉物品的熔爐");
        add("advancements.adventure.furnace.title", "開行火力");
        add("advancements.adventure.iron_ingot.description", "邁向鐵器時代");
        add("advancements.adventure.iron_ingot.title", "堅過「鐵」堅");
        add("advancements.adventure.kill_a_mob.description", "令你的劍殺死任意一種敵對生物");
        add("advancements.adventure.kill_a_mob.title", "怪物獵人（連線版）");
        add("advancements.adventure.kill_all_mobs.description", "命令一支武裝部隊殺死每一種敵對生物");
        add("advancements.adventure.mine_a_block.description", "擊打一個方塊直至其起死回生");
        add("advancements.adventure.mine_a_block.title", "掘個方塊");
        add("advancements.adventure.nether_portal.description", "目睹 12 塊黑曜石與打火石合併為地獄傳送門");
        add("advancements.adventure.nether_portal.title", "合成地獄");
        add("advancements.adventure.obsidian.description", "挖掘一些黑曜石");
        add("advancements.adventure.obsidian.title", "你需要更多⋯⋯");
        add("advancements.adventure.ol_betsy.description", "獲取一把弩");
        add("advancements.adventure.root.description", "遊牧四方，合成萬物");
        add("advancements.adventure.root.title", "Minecraft：遊牧版");
        add("advancements.adventure.wooden_pickaxe.description", "合成一個可為你挖掘的工具");
        add("advancements.adventure.wooden_pickaxe.title", "做個礦工");
        add("advancements.end.barrel_roll.description", "令木桶翻滾至宇宙盡頭");
        add("advancements.end.barrel_roll.title", "畀啲掙扎！搏盡無悔！");
        add("advancements.nether.barrel_roll.description", "碌喇！再唔碌蚊都瞓喇！");
        add("advancements.nether.barrel_roll.title", "碌個木桶！去喇！喺地獄度搏晒老命碌喇！");
        add("advancements.story.enter_the_end.description", "收集足夠的終界之眼，得到一些終界傳送門框，之後進入終界");
        add("advancements.story.enter_the_nether.description", "收集足夠的黑曜石並帶上打火石，之後進入地獄");
        add("block.minecraft.copper_trap", "銅陷阱");
        add("block.minecraft.exposed_copper_trap", "斑駁的銅陷阱");
        add("block.minecraft.iron_trap", "鐵陷阱");
        add("block.minecraft.oxidized_copper_trap", "氧化的銅陷阱");
        add("block.minecraft.waxed_copper_trap", "打蠟的銅陷阱");
        add("block.minecraft.waxed_exposed_copper_trap", "打蠟的斑駁銅陷阱");
        add("block.minecraft.waxed_oxidized_copper_trap", "打蠟的氧化銅陷阱");
        add("block.minecraft.waxed_weathered_copper_trap", "打蠟的生鏽銅陷阱");
        add("block.minecraft.weathered_copper_trap", "生鏽的銅陷阱");
        add("crafting.table.unable_to_place", "合成方格須一個 2×2 方塊闊的平面");
        add("entity.minecraft.crafting_action", "合成");
        add("entity.minecraft.crafting_grid", "合成方格");
        add("entity.minecraft.follow_action", "跟隨");
        add("entity.minecraft.hovering_item", "合成產物");
        add("entity.minecraft.living_block", "活體方塊");
        add("entity.minecraft.living_block_command", "活體方塊命令");
        add("entity.minecraft.punch_action", "擊打");
        add("inventory.place_grid", "用「合成」動作放置一個合成方格");
        add("item.minecraft.attack_action", "攻擊／挖掘");
        add("item.minecraft.build_action", "建造");
        add("item.minecraft.crafting_action", "合成");
        add("item.minecraft.follow_action", "跟隨");
        add("item.minecraft.group_action", "分組／取消分組");
        add("item.minecraft.highlight_action", "凸顯");
        add("item.minecraft.move_action", "移動");
        add("item.minecraft.punch_action", "擊打");
        add("item.minecraft.select_action", "選擇");
        add("item.minecraft.select_group_action.details", "已選擇組別：%s");
        add("living_blocks.group.all", "全部");
        add("living_blocks.group.aqua", "水藍色");
        add("living_blocks.group.blue", "藍色");
        add("living_blocks.group.lime", "淺綠色");
        add("living_blocks.group.none", "無");
        add("living_blocks.group.purple", "紫色");
        add("living_blocks.group.red", "紅色");
        add("living_blocks.group.yellow", "黃色");
        add("player.commanded_blocks.no_blocks_in_area", "所選群組在此區域不包含任何方塊");
        add("gamerule.minecraft.pick_up_item", "允許玩家潛行時，右鍵拾取活化方塊，中鍵複製活化方塊");
    }
}
