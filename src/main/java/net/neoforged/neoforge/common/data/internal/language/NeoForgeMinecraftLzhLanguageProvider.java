/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.data.internal.language;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class NeoForgeMinecraftLzhLanguageProvider extends LanguageProvider {
    public NeoForgeMinecraftLzhLanguageProvider(PackOutput output) {
        super(output, "minecraft", "lzh");
    }

    @Override
    protected void addTranslations() {
        add("advancements.adventure.barrel_roll.description", "輾轉彼桶");
        add("advancements.adventure.barrel_roll.title", "翩翩木桶！");
        add("advancements.adventure.build_house.description", "坐觀廿三塊方與門合爲陋室");
        add("advancements.adventure.build_house.title", "結廬人境");
        add("advancements.adventure.build_trap.description", "慎戒鐵銅八檻，其聚也殆。不備尤甚！");
        add("advancements.adventure.build_trap.title", "咎由自取");
        add("advancements.adventure.craft_chest.description", "製箱以御諸塊");
        add("advancements.adventure.craft_chest.title", "隨心所御");
        add("advancements.adventure.craft_sword.description", "製劍");
        add("advancements.adventure.craft_sword.title", "修我戈矛！");
        add("advancements.adventure.crafter.description", "以製械代爲製作之勞");
        add("advancements.adventure.crafter.title", "活塊洪範");
        add("advancements.adventure.crafting_table.description", "製製物案");
        add("advancements.adventure.crafting_table.title", "藝苑薈萃");
        add("advancements.adventure.door.description", "製門");
        add("advancements.adventure.door.title", "衡門之下");
        add("advancements.adventure.end_portal.description", "坐觀十二嵌眼門匡合爲終界結界門");
        add("advancements.adventure.end_portal.title", "終眇之門");
        add("advancements.adventure.eye_frame_combination.description", "徙終眇眼，近終界結界門匡以合之");
        add("advancements.adventure.eye_frame_combination.title", "眼匡交感");
        add("advancements.adventure.furnace.description", "製爐以代爲冶煉");
        add("advancements.adventure.furnace.title", "冶火正旺");
        add("advancements.adventure.iron_ingot.description", "陟鐵藝之世");
        add("advancements.adventure.iron_ingot.title", "剛莫如鐵");
        add("advancements.adventure.kill_a_mob.description", "麾己身之劍，戮敵獸任意");
        add("advancements.adventure.kill_a_mob.title", "小試牛刀，攜塊同行");
        add("advancements.adventure.kill_all_mobs.description", "麾兵戈之師，戮敵獸之窮");
        add("advancements.adventure.mine_a_block.description", "擊塊迨其活焉");
        add("advancements.adventure.mine_a_block.title", "掘塊方");
        add("advancements.adventure.nether_portal.description", "坐觀十二黑曜石與燧鐮合爲焱門");
        add("advancements.adventure.nether_portal.title", "焱界之製");
        add("advancements.adventure.obsidian.description", "掘黑曜石");
        add("advancements.adventure.obsidian.title", "多多益善⋯");
        add("advancements.adventure.ol_betsy.description", "得弩");
        add("advancements.adventure.root.description", "牧遊四維，製成八荒");
        add("advancements.adventure.root.title", "牧藝");
        add("advancements.adventure.wooden_pickaxe.description", "製器以代爲採掘");
        add("advancements.adventure.wooden_pickaxe.title", "製掘器");
        add("advancements.end.barrel_roll.description", "倬彼木桶，昭回於天");
        add("advancements.end.barrel_roll.title", "我心匪石！可以轉也！");
        add("advancements.nether.barrel_roll.description", "悠哉悠哉！輾轉反側！");
        add("advancements.nether.barrel_roll.title", "翩翩木桶！悠悠我思！挑兮達兮！在焱界兮！");
        add("advancements.story.enter_the_end.description", "集終眇眼，尋得門匡，乃入終界");
        add("advancements.story.enter_the_nether.description", "集黑曜石，具備燧鐮，乃入焱界");
        add("block.minecraft.copper_trap", "銅阱");
        add("block.minecraft.exposed_copper_trap", "渡濕銅阱");
        add("block.minecraft.iron_trap", "鐵阱");
        add("block.minecraft.oxidized_copper_trap", "風蝕銅阱");
        add("block.minecraft.waxed_copper_trap", "蠟引銅阱");
        add("block.minecraft.waxed_exposed_copper_trap", "蠟引渡濕銅阱");
        add("block.minecraft.waxed_oxidized_copper_trap", "蠟引鏽刻銅阱");
        add("block.minecraft.waxed_weathered_copper_trap", "蠟引風蝕銅阱");
        add("block.minecraft.weathered_copper_trap", "風蝕銅阱");
        add("crafting.table.unable_to_place", "製陣須橫縱二方之平地");
        add("entity.minecraft.crafting_action", "製");
        add("entity.minecraft.crafting_grid", "製陣");
        add("entity.minecraft.follow_action", "隨");
        add("entity.minecraft.hovering_item", "製之所得");
        add("entity.minecraft.living_block", "活塊");
        add("entity.minecraft.living_block_command", "活塊之令");
        add("entity.minecraft.punch_action", "擊");
        add("inventory.place_grid", "施「製」以辟製陣");
        add("item.minecraft.attack_action", "伐掘");
        add("item.minecraft.build_action", "築");
        add("item.minecraft.crafting_action", "製");
        add("item.minecraft.follow_action", "隨");
        add("item.minecraft.group_action", "陣");
        add("item.minecraft.highlight_action", "彰");
        add("item.minecraft.move_action", "徙");
        add("item.minecraft.punch_action", "擊");
        add("item.minecraft.select_action", "擇");
        add("item.minecraft.select_group_action.details", "陣之所擇：%s");
        add("living_blocks.group.all", "眾");
        add("living_blocks.group.aqua", "碧");
        add("living_blocks.group.blue", "靛");
        add("living_blocks.group.lime", "翠");
        add("living_blocks.group.none", "無");
        add("living_blocks.group.purple", "紫");
        add("living_blocks.group.red", "赤");
        add("living_blocks.group.yellow", "黃");
        add("player.commanded_blocks.no_blocks_in_area", "所选之阵，此域无块");
        add("gamerule.minecraft.pick_up_item", "蹲行時，按右鍵拾活化之塊，按中鍵複製之");
    }
}
