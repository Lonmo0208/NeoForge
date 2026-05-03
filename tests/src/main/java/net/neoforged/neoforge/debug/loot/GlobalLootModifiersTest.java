/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.minecraft.advancements.criterion.ItemPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.MatchTool;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.common.loot.LootTableIdCondition;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.TestFramework;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.OnInit;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.condition.TestEnabledLootCondition;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.registration.RegistrationHelper;

@ForEachTest(groups = "loot", idPrefix = "glm_")
public class GlobalLootModifiersTest {
    public static final RegistrationHelper HELPER = RegistrationHelper.create("neotests_glm_test");

    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> GLM = HELPER.registrar(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS);

    private static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<DungeonLootEnhancerModifier>> DUNGEON_LOOT = GLM.register("dungeon_loot", DungeonLootEnhancerModifier.CODEC);
    private static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<SmeltingEnchantmentModifier>> SMELTING = GLM.register("smelting", SmeltingEnchantmentModifier.CODEC);
    private static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<WheatSeedsConverterModifier>> WHEATSEEDS = GLM.register("wheat_harvest", WheatSeedsConverterModifier.CODEC);
    private static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<SilkTouchTestModifier>> SILKTOUCH = GLM.register("silk_touch_bamboo", SilkTouchTestModifier.CODEC);
//    private static final DeferredHolder<Enchantment, Enchantment> SMELT = ENCHANTS.register("smelt", () -> new Enchantment(
//            Enchantment.definition(ItemTags.MINING_ENCHANTABLE, 10, 1, Enchantment.dynamicCost(1, 10), Enchantment.dynamicCost(5, 10), 1, EquipmentSlot.MAINHAND)));
    private static final ResourceKey<Enchantment> SMELT = ResourceKey.create(Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(HELPER.modId(), "smelt"));

    @OnInit
    static void init(final TestFramework framework) {
        HELPER.register(framework.modEventBus(), framework.container());
    }

    /**
     * The smelting enchantment causes this modifier to be invoked, via the smelting loot_modifier json
     */
    private static class SmeltingEnchantmentModifier extends LootModifier {
        public static final Supplier<MapCodec<SmeltingEnchantmentModifier>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.mapCodec(inst -> codecStart(inst).apply(inst, SmeltingEnchantmentModifier::new)));

        public SmeltingEnchantmentModifier(LootItemCondition[] conditions, int priority) {
            super(conditions, priority);
        }

        @Override
        public ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
            ObjectArrayList<ItemStack> ret = new ObjectArrayList<ItemStack>();
            generatedLoot.forEach((stack) -> ret.add(smelt(stack, context)));
            return ret;
        }

        private static ItemStack smelt(ItemStack stack, LootContext context) {
            SingleRecipeInput input = new SingleRecipeInput(stack);
            return context.getLevel().recipeAccess().getRecipeFor(RecipeType.SMELTING, input, context.getLevel())
                    .map(smeltingRecipe -> smeltingRecipe.value().assemble(input))
                    .filter(itemStack -> !itemStack.isEmpty())
                    .map(itemStack -> itemStack.copyWithCount(stack.getCount() * itemStack.getCount()))
                    .orElse(stack);
        }

        @Override
        public MapCodec<? extends IGlobalLootModifier> codec() {
            return CODEC.get();
        }
    }

    /**
     * When harvesting blocks with bamboo, this modifier is invoked, via the silk_touch_bamboo loot_modifier json
     */
    private static class SilkTouchTestModifier extends LootModifier {
        public static final Supplier<MapCodec<SilkTouchTestModifier>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.mapCodec(inst -> codecStart(inst).apply(inst, SilkTouchTestModifier::new)));

        public SilkTouchTestModifier(LootItemCondition[] conditions, int priority) {
            super(conditions, priority);
        }

        @Override
        public ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
            ItemStack ctxTool = context.getOptionalParameter(LootContextParams.TOOL) instanceof ItemStack stack ? stack : null;
            var reg = context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            // return early if silk-touch is already applied (otherwise we'll get stuck in an infinite loop).
            if (ctxTool == null || ctxTool.getEnchantmentLevel(reg.getOrThrow(Enchantments.SILK_TOUCH)) > 0)
                return generatedLoot;
            ItemStack fakeTool = ctxTool.copy();
            fakeTool.enchant(reg.getOrThrow(Enchantments.SILK_TOUCH), 1);
            LootParams.Builder builder = new LootParams.Builder(context.getLevel());
            builder.withParameter(LootContextParams.TOOL, fakeTool);
            return context.getOptionalParameter(LootContextParams.BLOCK_STATE).getBlock().getLootTable()
                    .map(key -> {
                        var loottable = context.getLevel().getServer().reloadableRegistries().getLootTable(key);
                        return loottable.getRandomItems(builder.create(LootContextParamSets.EMPTY));
                    })
                    .orElseGet(ObjectArrayList::of);
        }

        @Override
        public MapCodec<? extends IGlobalLootModifier> codec() {
            return CODEC.get();
        }
    }

    /**
     * When harvesting wheat with shears, this modifier is invoked via the wheat_harvest loot_modifier json<br/>
     * This modifier checks how many seeds were harvested and turns X seeds into Y wheat (3:1)
     */
    private static class WheatSeedsConverterModifier extends LootModifier {
        public static final Supplier<MapCodec<WheatSeedsConverterModifier>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.mapCodec(inst -> codecStart(inst).and(
                inst.group(
                        Codec.INT.fieldOf("numSeeds").forGetter(m -> m.numSeedsToConvert),
                        BuiltInRegistries.ITEM.byNameCodec().fieldOf("seedItem").forGetter(m -> m.itemToCheck),
                        BuiltInRegistries.ITEM.byNameCodec().fieldOf("replacement").forGetter(m -> m.itemReward)))
                .apply(inst, WheatSeedsConverterModifier::new)));

        private final int numSeedsToConvert;
        private final Item itemToCheck;
        private final Item itemReward;

        public WheatSeedsConverterModifier(LootItemCondition[] conditions, int priority, int numSeeds, Item itemCheck, Item reward) {
            super(conditions, priority);
            numSeedsToConvert = numSeeds;
            itemToCheck = itemCheck;
            itemReward = reward;
        }

        @Override
        public ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
            //
            // Additional conditions can be checked, though as much as possible should be parameterized via JSON data.
            // It is better to write a new ILootCondition implementation than to do things here.
            //
            int numSeeds = 0;
            for (ItemStack stack : generatedLoot) {
                if (stack.getItem() == itemToCheck)
                    numSeeds += stack.getCount();
            }
            if (numSeeds >= numSeedsToConvert) {
                generatedLoot.removeIf(x -> x.getItem() == itemToCheck);
                generatedLoot.add(new ItemStack(itemReward, (numSeeds / numSeedsToConvert)));
                numSeeds = numSeeds % numSeedsToConvert;
                if (numSeeds > 0)
                    generatedLoot.add(new ItemStack(itemToCheck, numSeeds));
            }
            return generatedLoot;
        }

        @Override
        public MapCodec<? extends IGlobalLootModifier> codec() {
            return CODEC.get();
        }
    }

    private static class DungeonLootEnhancerModifier extends LootModifier {
        public static final Supplier<MapCodec<DungeonLootEnhancerModifier>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.mapCodec(inst -> codecStart(inst)
                .and(ExtraCodecs.POSITIVE_INT.optionalFieldOf("multiplication_factor", 2).forGetter(m -> m.multiplicationFactor))
                .apply(inst, DungeonLootEnhancerModifier::new)));

        private final int multiplicationFactor;

        public DungeonLootEnhancerModifier(LootItemCondition[] conditions, int priority, int multiplicationFactor) {
            super(conditions, priority);
            this.multiplicationFactor = multiplicationFactor;
        }

        @Override
        protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
            if (context.hasParameter(LootContextParams.THIS_ENTITY)) {
                // Only modify if a player attempts to open it
                return generatedLoot.stream()
                        .map(ItemStack::copy)
                        .peek(stack -> stack.setCount(Math.min(stack.getMaxStackSize(), stack.getCount() * this.multiplicationFactor)))
                        .collect(Collectors.toCollection(ObjectArrayList::new));
            }
            return generatedLoot;
        }

        @Override
        public MapCodec<? extends IGlobalLootModifier> codec() {
            return CODEC.get();
        }
    }

    @GameTest
    @EmptyTemplate(floor = true)
    @TestHolder(description = "Tests if the silk touch bamboo GLM works, by breaking leaves with bamboo and verifying the leaf block is dropped")
    static void silkTouchBambooTest(final DynamicTest test) {
        HELPER.clientProvider(GlobalLootModifierProvider.class, prov -> prov.add("silk_touch_bamboo", new SilkTouchTestModifier(
                new LootItemCondition[] {
                        MatchTool.toolMatches(ItemPredicate.Builder.item().of(null, Items.BAMBOO)).build(),
                        new TestEnabledLootCondition(test)
                },
                IGlobalLootModifier.DEFAULT_PRIORITY)));

        test.onGameTest(helper -> helper.startSequence(() -> helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL).preventItemPickup())
                .thenExecute(player -> player.setItemInHand(InteractionHand.MAIN_HAND, Items.BAMBOO.getDefaultInstance()))

                .thenExecute(() -> helper.setBlock(1, 2, 1, Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true)))

                .thenIdle(5)
                .thenExecute(player -> player.gameMode.destroyBlock(helper.absolutePos(new BlockPos(1, 2, 1))))
                .thenIdle(5)
                // The silk touch bamboo modifier should cause oak leaves to drop the leaf block itself
                .thenExecute(player -> helper.assertEntityPresent(EntityType.LIVING_BLOCK, new BlockPos(1, 2, 1), 1d))

                .thenSucceed());
    }

    @GameTest
    @EmptyTemplate
    @TestHolder(description = "Tests if dungeon loot modifiers work, by rolling the simple_dungeon loot table")
    static void dungeonLootTest(final DynamicTest test) {
        HELPER.clientProvider(GlobalLootModifierProvider.class, prov -> prov.add("dungeon_loot", new DungeonLootEnhancerModifier(
                new LootItemCondition[] {
                        LootTableIdCondition.builder(Identifier.withDefaultNamespace("chests/simple_dungeon")).build(),
                        new TestEnabledLootCondition(test)
                },
                IGlobalLootModifier.DEFAULT_PRIORITY,
                2)));

        test.onGameTest(helper -> helper.startSequence()
                .thenExecute(() -> helper.setBlock(1, 2, 1, Blocks.CHEST.defaultBlockState()))
                .thenMap(() -> helper.getBlockEntity(1, 2, 1, ChestBlockEntity.class))
                .thenExecute(chest -> chest.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("chests/simple_dungeon")), 124424))

                .thenExecute(chest -> chest.unpackLootTable(helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL)))

                .thenMap(chest -> IntStream.range(0, 27)
                        .mapToObj(chest::getItem)
                        .filter(Predicate.not(ItemStack::isEmpty))
                        .collect(Collectors.toMap(ItemStack::getItem, ItemStack::getCount, Integer::sum)))

                .thenMapToSequence(stacks -> helper
                        .startSequence(() -> helper.getLevel().getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("chests/simple_dungeon")))
                                .getRandomItems(new LootParams.Builder(helper.getLevel())
                                        .withParameter(LootContextParams.ORIGIN, helper.absoluteVec(new Vec3(1, 3, 1)))
                                        .create(LootContextParamSets.CHEST), 124424))
                        .thenMap(base -> base.stream()
                                .collect(Collectors.toMap(ItemStack::getItem, stack -> Math.min(stack.getMaxStackSize(), stack.getCount() * 2))))
                        .thenExecute(expected -> helper.assertTrue(
                                stacks.get().equals(expected),
                                "Stacks weren't as expected")))
                .thenSucceed());
    }
}
