/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.data.internal.language;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class NeoForgeMinecraftJaJpLanguageProvider extends LanguageProvider {
    public NeoForgeMinecraftJaJpLanguageProvider(PackOutput output) {
        super(output, "minecraft", "ja_jp");
    }

    @Override
    protected void addTranslations() {
        add("advancements.adventure.barrel_roll.description", "樽を転がす");
        add("advancements.adventure.barrel_roll.title", "バレルロールで弾くんだ！");
        add("advancements.adventure.build_house.description", "23個のブロックとドアが1軒の小さな家に組み上がる様子を観察する");
        add("advancements.adventure.build_house.title", "小さなおうち、組み立て完了！");
        add("advancements.adventure.build_trap.description", "8つの鉄格子（または銅格子）に気を付けろ！群れると危険だ。油断するとやられるぞ！");
        add("advancements.adventure.build_trap.title", "自縄自縛");
        add("advancements.adventure.craft_chest.description", "ブロックを見失わないように、チェストをクラフトする");
        add("advancements.adventure.craft_chest.title", "ランダムアクセス素材");
        add("advancements.adventure.craft_sword.description", "剣をクラフトする");
        add("advancements.adventure.craft_sword.title", "いざ開戦！");
        add("advancements.adventure.crafter.description", "自動作業台でクラフトを自動化する");
        add("advancements.adventure.crafter.title", "大規模生きたブロックモデル");
        add("advancements.adventure.crafting_table.description", "作業台をクラフトする");
        add("advancements.adventure.crafting_table.title", "統合クラフト環境");
        add("advancements.adventure.door.description", "ドアをクラフトする");
        add("advancements.adventure.door.title", "隠れ家を求めて");
        add("advancements.adventure.end_portal.description", "エンダーアイがはまった12個のエンドポータルフレームがエンドポータルに組みあがる様子を観察する");
        add("advancements.adventure.end_portal.title", "最終組み立て工程");
        add("advancements.adventure.eye_frame_combination.description", "エンダーアイをエンドポータルフレームの近くに移動させて組み合わせる");
        add("advancements.adventure.eye_frame_combination.title", "眼枠相互作用の統一理論");
        add("advancements.adventure.furnace.description", "君のために製錬してくれるかまどを作る");
        add("advancements.adventure.furnace.title", "火力全開");
        add("advancements.adventure.iron_ingot.description", "鉄器時代への道を拓く");
        add("advancements.adventure.iron_ingot.title", "冷徹な鉄");
        add("advancements.adventure.kill_a_mob.description", "剣に敵対的なモンスターを倒させる");
        add("advancements.adventure.kill_a_mob.title", "モンスターハンター（マルチプレイ）");
        add("advancements.adventure.kill_all_mobs.description", "武器の軍団に全種の敵対モンスターの討伐を命じる");
        add("advancements.adventure.mine_a_block.description", "生命が吹き込まれるまでブロックをパンチする");
        add("advancements.adventure.mine_a_block.title", "ブロックの採掘");
        add("advancements.adventure.nether_portal.description", "12個の黒曜石と1個の火打石と打ち金がネザーポータルに組み上がる様子を観察する");
        add("advancements.adventure.nether_portal.title", "ネザー世界のクラフト");
        add("advancements.adventure.obsidian.description", "黒曜石を採掘する");
        add("advancements.adventure.obsidian.title", "もっと必要だ……");
        add("advancements.adventure.ol_betsy.description", "クロスボウを手に入れる");
        add("advancements.adventure.root.description", "世界を引き連れ、世界を創造しよう");
        add("advancements.adventure.root.title", "HerdCraft");
        add("advancements.adventure.wooden_pickaxe.description", "君のために採掘してくれる道具をクラフトする");
        add("advancements.adventure.wooden_pickaxe.title", "採掘家のクラフト");
        add("advancements.end.barrel_roll.description", "宇宙の果てで樽を転がす");
        add("advancements.end.barrel_roll.title", "決して諦めるな　自分の感覚を信じろ！");
        add("advancements.nether.barrel_roll.description", "やれ！今だ！");
        add("advancements.nether.barrel_roll.title", "バレルロールだ！やれ！ネザーでやれ！");
        add("advancements.story.enter_the_end.description", "エンダーアイを十分に集め、エンドポータルフレームを見つけてエンドに入る");
        add("advancements.story.enter_the_nether.description", "火打石と打ち金と十分な黒曜石を集め、ネザーに入る");
        add("block.minecraft.copper_trap", "銅の罠");
        add("block.minecraft.exposed_copper_trap", "風化した銅の罠");
        add("block.minecraft.iron_trap", "鉄の罠");
        add("block.minecraft.oxidized_copper_trap", "酸化した銅の罠");
        add("block.minecraft.waxed_copper_trap", "錆止めされた銅の罠");
        add("block.minecraft.waxed_exposed_copper_trap", "錆止めされた風化した銅の罠");
        add("block.minecraft.waxed_oxidized_copper_trap", "錆止めされた酸化した銅の罠");
        add("block.minecraft.waxed_weathered_copper_trap", "錆止めされた錆びた銅の罠");
        add("block.minecraft.weathered_copper_trap", "錆びた銅の罠");
        add("crafting.table.unable_to_place", "クラフトグリッドには2×2ブロックの広さの平面が必要です");
        add("entity.minecraft.crafting_action", "クラフト");
        add("entity.minecraft.crafting_grid", "クラフトグリッド");
        add("entity.minecraft.follow_action", "追従");
        add("entity.minecraft.hovering_item", "クラフト完成品");
        add("entity.minecraft.living_block", "生きたブロック");
        add("entity.minecraft.living_block_command", "生きたブロックへの命令");
        add("entity.minecraft.punch_action", "パンチ");
        add("inventory.place_grid", "アクション「クラフト」でクラフトグリッドを設置できます");
        add("item.minecraft.attack_action", "攻撃／採掘");
        add("item.minecraft.build_action", "設置");
        add("item.minecraft.crafting_action", "クラフト");
        add("item.minecraft.follow_action", "追従");
        add("item.minecraft.group_action", "グループに追加／解除");
        add("item.minecraft.highlight_action", "強調表示");
        add("item.minecraft.move_action", "移動");
        add("item.minecraft.punch_action", "パンチ");
        add("item.minecraft.select_action", "選択");
        add("item.minecraft.select_group_action.details", "選択中のグループ：%s");
        add("living_blocks.group.all", "すべて");
        add("living_blocks.group.aqua", "水色");
        add("living_blocks.group.blue", "青色");
        add("living_blocks.group.lime", "黄緑色");
        add("living_blocks.group.none", "なし");
        add("living_blocks.group.purple", "紫色");
        add("living_blocks.group.red", "赤色");
        add("living_blocks.group.yellow", "黄色");
        add("player.commanded_blocks.no_blocks_in_area", "選択したグループにはこのエリアにブロックがありません");
        add("gamerule.minecraft.pick_up_item", "スニーク中に右クリックまたは中クリックでドロップしたアイテムを拾得・複製できるようにする");
    }
}
