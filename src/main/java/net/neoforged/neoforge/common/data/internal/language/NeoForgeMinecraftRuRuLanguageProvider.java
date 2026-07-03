/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.data.internal.language;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public final class NeoForgeMinecraftRuRuLanguageProvider extends LanguageProvider {
    public NeoForgeMinecraftRuRuLanguageProvider(PackOutput output) {
        super(output, "minecraft", "ru_ru");
    }

    @Override
    protected void addTranslations() {
        add("advancements.adventure.barrel_roll.description", "Создай бочку");
        add("advancements.adventure.barrel_roll.title", "Сделай Бочку!");
        add("advancements.adventure.build_house.description", "Дождитесь пока 23 блока сформируют маленький домик");
        add("advancements.adventure.build_house.title", "Домик, к бою!");
        add("advancements.adventure.build_trap.description", "Берегись 8 железных (или медных) прутьев, в группе они опасны. Особенно когда ты этого не ожидаешь!");
        add("advancements.adventure.build_trap.title", "Доигрался");
        add("advancements.adventure.craft_chest.description", "Создай сундук, чтобы хранить свои вещи");
        add("advancements.adventure.craft_chest.title", "Оперативный Переносчик");
        add("advancements.adventure.craft_sword.description", "Создай меч");
        add("advancements.adventure.craft_sword.title", "В бой!");
        add("advancements.adventure.crafter.description", "Автоматизируй создание предметов с помощью сборщика");
        add("advancements.adventure.crafter.title", "Нейро-Блочная сеть");
        add("advancements.adventure.crafting_table.description", "Создай верстак");
        add("advancements.adventure.crafting_table.title", "Крафтовый редактор");
        add("advancements.adventure.door.description", "Создай дверь");
        add("advancements.adventure.door.title", "В укрытие!");
        add("advancements.adventure.end_portal.description", "Дождитесь пока двенадцать рамок портала Энда с очами Эндера сформируют портал Энда");
        add("advancements.adventure.end_portal.title", "Нет пути");
        add("advancements.adventure.eye_frame_combination.description", "Поднесите Око Эндера достаточно близко, чтобы оно могло встать в рамку портала Энда");
        add("advancements.adventure.eye_frame_combination.title", "Базовая Интергация-Ока Система");
        add("advancements.adventure.furnace.description", "Создай печку которая способна переплавить вещи");
        add("advancements.adventure.furnace.title", "Штопор Жжот");
        add("advancements.adventure.iron_ingot.description", "Вступите в железный век");
        add("advancements.adventure.iron_ingot.title", "Железные нервы");
        add("advancements.adventure.kill_a_mob.description", "Позволь своему мечу убить любого враждебного монстра");
        add("advancements.adventure.kill_a_mob.title", "Охотник на Монстров (с друзьями)");
        add("advancements.adventure.kill_all_mobs.description", "Прикажи армии оружия убить по одному монстру каждого вида");
        add("advancements.adventure.mine_a_block.description", "Бей блок пока тот не оживёт");
        add("advancements.adventure.mine_a_block.title", "Добудь блок");
        add("advancements.adventure.nether_portal.description", "Дождитесь пока двенадцать обсидиана в и огниво сформируют портал в Незер");
        add("advancements.adventure.nether_portal.title", "Система Быстрых Перемещений");
        add("advancements.adventure.obsidian.description", "Добудь немного обсидиана");
        add("advancements.adventure.obsidian.title", "У нас нехватка...");
        add("advancements.adventure.ol_betsy.description", "Создайте арбалет");
        add("advancements.adventure.root.description", "Управляй и Крафть свой мир");
        add("advancements.adventure.root.title", "HerdCraft");
        add("advancements.adventure.wooden_pickaxe.description", "Создай инструмент способный копать для тебя");
        add("advancements.adventure.wooden_pickaxe.title", "Создать Шахтёра");
        add("advancements.end.barrel_roll.description", "Проверни бочку на Краю вселенной");
        add("advancements.end.barrel_roll.title", "Не сдаваться, не бежать, не сожалеть!");
        add("advancements.nether.barrel_roll.description", "Сделай это! Прямо сейчас!");
        add("advancements.nether.barrel_roll.title", "Хватит с нас этого мелкого мира! Молодёжь делает Бочки! Молодёжь оправляется в Незер!");
        add("advancements.story.enter_the_end.description", "Соберите достаточно очей Эндера, найдите рамки портала Энда и войди в Энд");
        add("advancements.story.enter_the_nether.description", "Соберите достаточно обсидиана, найдите огниво и войдите в Незер");
        add("block.minecraft.copper_trap", "Медная ловушка");
        add("block.minecraft.exposed_copper_trap", "Потемневшая медная ловушка");
        add("block.minecraft.iron_trap", "Железная ловушка");
        add("block.minecraft.oxidized_copper_trap", "Окислённая медная ловушка");
        add("block.minecraft.waxed_copper_trap", "Вощёная медная ловушка");
        add("block.minecraft.waxed_exposed_copper_trap", "Вощёная потемневшая медная ловушка");
        add("block.minecraft.waxed_oxidized_copper_trap", "Вощёная окисленная медная ловушка");
        add("block.minecraft.waxed_weathered_copper_trap", "Вощёная состаренная медная ловушка");
        add("block.minecraft.weathered_copper_trap", "Состаренная медная ловушка");
        add("crafting.table.unable_to_place", "Сетка крафта требует ровную поверхность шириной 2 на 2");
        add("entity.minecraft.crafting_action", "Крафт");
        add("entity.minecraft.crafting_grid", "Сетка крафта");
        add("entity.minecraft.follow_action", "Следуй за мной");
        add("entity.minecraft.hovering_item", "Результат крафта");
        add("entity.minecraft.living_block", "Живой блок");
        add("entity.minecraft.living_block_command", "Живой командный блок");
        add("entity.minecraft.punch_action", "Ударить");
        add("inventory.place_grid", "Используй действие Крафт, чтобы разместить сетку крафта");
        add("item.minecraft.attack_action", "Атаковать / Копать");
        add("item.minecraft.build_action", "Строить");
        add("item.minecraft.crafting_action", "Крафтить");
        add("item.minecraft.follow_action", "Следуй за мной");
        add("item.minecraft.group_action", "Группировать / Разгруппировать");
        add("item.minecraft.highlight_action", "Выделить");
        add("item.minecraft.move_action", "Переместить");
        add("item.minecraft.punch_action", "Ударить");
        add("item.minecraft.select_action", "Выбрать");
        add("item.minecraft.select_group_action.details", "Активная группа: %s");
        add("living_blocks.group.all", "Все");
        add("living_blocks.group.aqua", "Голубая");
        add("living_blocks.group.blue", "Синяя");
        add("living_blocks.group.lime", "Лаймовая");
        add("living_blocks.group.none", "Отсутствует");
        add("living_blocks.group.purple", "Фиолетовая");
        add("living_blocks.group.red", "Красная");
        add("living_blocks.group.yellow", "Жёлтая");
        add("player.commanded_blocks.no_blocks_in_area", "Выбранная группа не содержит блоков в этой области");
        add("gamerule.minecraft.pick_up_item", "Позволяет подбирать и копировать выброшенные предметы правым или средним кликом во время крадучись");
    }
}
