/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.data.internal.language;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class NeoForgeMinecraftZhTwLanguageProvider extends LanguageProvider {
    public NeoForgeMinecraftZhTwLanguageProvider(PackOutput output) {
        super(output, "minecraft", "zh_tw");
    }

    @Override
    protected void addTranslations() {
        add("advancements.adventure.barrel_roll.description", "讓木桶翻滾起來");
        add("advancements.adventure.barrel_roll.title", "來個桶滾！");
        add("advancements.adventure.build_house.description", "目睹 23 個方塊與一扇門拼合成一間小小房子");
        add("advancements.adventure.build_house.title", "小小房子，拼裝！");
        add("advancements.adventure.build_trap.description", "小心 8 個鐵柵欄（或銅柵欄），它們成群時非常危險，尤其在你分心之際！");
        add("advancements.adventure.build_trap.title", "作繭自縛");
        add("advancements.adventure.craft_chest.description", "合成一個儲物箱以掌控你的方塊");
        add("advancements.adventure.craft_chest.title", "材料隨機存取器");
        add("advancements.adventure.craft_sword.description", "合成一把劍");
        add("advancements.adventure.craft_sword.title", "開戰時刻！");
        add("advancements.adventure.crafter.description", "使用合成器自動化合成");
        add("advancements.adventure.crafter.title", "大型活體方塊模型");
        add("advancements.adventure.crafting_table.description", "合成工作台");
        add("advancements.adventure.crafting_table.title", "整合合成環境");
        add("advancements.adventure.door.description", "合成一扇門");
        add("advancements.adventure.door.title", "尋求庇護");
        add("advancements.adventure.end_portal.description", "目睹 12 個裝有終界之眼的終界傳送門框架，合併為終界傳送門");
        add("advancements.adventure.end_portal.title", "終點站");
        add("advancements.adventure.eye_frame_combination.description", "移動一個終界之眼，使其距離足以與終界傳送門框架合併");
        add("advancements.adventure.eye_frame_combination.title", "統一眼框交互作用");
        add("advancements.adventure.furnace.description", "製作一個可為你熔煉物品的熔爐");
        add("advancements.adventure.furnace.title", "火力全開");
        add("advancements.adventure.iron_ingot.description", "邁向鐵器時代");
        add("advancements.adventure.iron_ingot.title", "這鐵可真鐵啊");
        add("advancements.adventure.kill_a_mob.description", "讓你的劍殺死任何敵對生物");
        add("advancements.adventure.kill_a_mob.title", "魔物獵人（連線版）");
        add("advancements.adventure.kill_all_mobs.description", "命令一支武裝部隊殺死每一種敵對生物");
        add("advancements.adventure.mine_a_block.description", "揍方塊直至其起死回生");
        add("advancements.adventure.mine_a_block.title", "挖個方塊");
        add("advancements.adventure.nether_portal.description", "目睹 12 塊黑曜石與打火石合併為地獄傳送門");
        add("advancements.adventure.nether_portal.title", "合成地獄");
        add("advancements.adventure.obsidian.description", "挖掘一些黑曜石");
        add("advancements.adventure.obsidian.title", "你需要更多...");
        add("advancements.adventure.ol_betsy.description", "獲取一把弩");
        add("advancements.adventure.root.description", "遊牧萬物，合成天下");
        add("advancements.adventure.root.title", "遊牧世界");
        add("advancements.adventure.wooden_pickaxe.description", "合成一個可為你挖礦的工具");
        add("advancements.adventure.wooden_pickaxe.title", "做個礦工");
        add("advancements.end.barrel_roll.description", "讓木桶翻滾至宇宙盡頭");
        add("advancements.end.barrel_roll.title", "不要放棄！相信你的直覺！");
        add("advancements.nether.barrel_roll.description", "翻滾吧！就是現在！");
        add("advancements.nether.barrel_roll.title", "讓木桶翻滾吧！翻滾吧！在地獄翻滾吧！");
        add("advancements.story.enter_the_end.description", "收集足夠的終界之眼，找到終界傳送門框架，然後進入終界");
        add("advancements.story.enter_the_nether.description", "收集足夠的黑曜石並帶上打火石，以此進入地獄");
        add("block.minecraft.copper_trap", "銅陷阱");
        add("block.minecraft.exposed_copper_trap", "斑駁的銅陷阱");
        add("block.minecraft.iron_trap", "鐵陷阱");
        add("block.minecraft.oxidized_copper_trap", "氧化的銅陷阱");
        add("block.minecraft.waxed_copper_trap", "上蠟的銅陷阱");
        add("block.minecraft.waxed_exposed_copper_trap", "上蠟的斑駁銅陷阱");
        add("block.minecraft.waxed_oxidized_copper_trap", "上蠟的氧化的銅陷阱");
        add("block.minecraft.waxed_weathered_copper_trap", "上蠟的風化銅陷阱");
        add("block.minecraft.weathered_copper_trap", "風化的銅陷阱");
        add("crafting.table.unable_to_place", "合成方格需要 2×2 方塊範圍的平坦表面");
        add("entity.minecraft.crafting_action", "合成");
        add("entity.minecraft.crafting_grid", "合成方格");
        add("entity.minecraft.follow_action", "跟隨");
        add("entity.minecraft.hovering_item", "合成產物");
        add("entity.minecraft.living_block", "活體方塊");
        add("entity.minecraft.living_block_command", "活體方塊指令");
        add("entity.minecraft.punch_action", "揍");
        add("inventory.place_grid", "使用「合成」動作放置合成方格");
        add("item.minecraft.attack_action", "攻擊／挖掘");
        add("item.minecraft.build_action", "建造");
        add("item.minecraft.crafting_action", "合成");
        add("item.minecraft.follow_action", "跟隨");
        add("item.minecraft.group_action", "群組／取消群組");
        add("item.minecraft.highlight_action", "突顯");
        add("item.minecraft.move_action", "移動");
        add("item.minecraft.punch_action", "揍");
        add("item.minecraft.select_action", "選擇");
        add("item.minecraft.select_group_action.details", "已選取群組：%s");
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
