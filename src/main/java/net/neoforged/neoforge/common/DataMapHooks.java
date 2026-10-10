/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;
import net.neoforged.neoforge.registries.datamaps.builtin.Oxidizable;
import net.neoforged.neoforge.registries.datamaps.builtin.Waxable;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

// TODO: 1.21.2 remove fallback to vanilla map for waxing and oxidizing
public class DataMapHooks {
    // will be removed in 1.21.2
    /** Used in a gametest */
    @ApiStatus.Internal
    public static boolean didHaveToFallbackToVanillaMaps = false;

    private static final Map<Block, Block> INVERSE_OXIDIZABLES_DATAMAP_INTERNAL = new HashMap<>();
    private static final Map<Block, Block> INVERSE_WAXABLES_DATAMAP_INTERNAL = new HashMap<>();

    /** The inverse map of the oxidizables data map, used in vanilla when scraping oxidization off of a block */
    public static final Map<Block, Block> INVERSE_OXIDIZABLES_DATAMAP = Collections.unmodifiableMap(INVERSE_OXIDIZABLES_DATAMAP_INTERNAL);
    /** The inverse map of the waxables data map, used in vanilla when scraping wax off of a block */
    public static final Map<Block, Block> INVERSE_WAXABLES_DATAMAP = Collections.unmodifiableMap(INVERSE_WAXABLES_DATAMAP_INTERNAL);

    @Nullable
    @SuppressWarnings("deprecation")
    public static Block getNextOxidizedStage(Block block) {
        Oxidizable oxidizable = block.builtInRegistryHolder().getData(NeoForgeDataMaps.OXIDIZABLES);
        return oxidizable != null ? oxidizable.nextOxidationStage() : WeatheringCopper.NEXT_BY_BLOCK.get().get(block);
    }

    @Nullable
    @SuppressWarnings("deprecation")
    public static Block getPreviousOxidizedStage(Block block) {
        return INVERSE_OXIDIZABLES_DATAMAP.containsKey(block) ? INVERSE_OXIDIZABLES_DATAMAP.get(block) : WeatheringCopper.PREVIOUS_BY_BLOCK.get().get(block);
    }

    @Nullable
    @SuppressWarnings("deprecation")
    public static Block getBlockWaxed(Block block) {
        Waxable waxable = block.builtInRegistryHolder().getData(NeoForgeDataMaps.WAXABLES);
        return waxable != null ? waxable.waxed() : HoneycombItem.WAXABLES.get().get(block);
    }

    @Nullable
    @SuppressWarnings("deprecation")
    public static Block getBlockUnwaxed(Block block) {
        return INVERSE_WAXABLES_DATAMAP.containsKey(block) ? INVERSE_WAXABLES_DATAMAP.get(block) : HoneycombItem.WAX_OFF_BY_BLOCK.get().get(block);
    }

    /**
     * NeoFobric: the state a Fabric mod registered as this state's stripped form, or {@code null} when the
     * Fabric side has nothing to say about it.
     *
     * <p>Fabric keeps the stripped state behind a transformer rather than a plain block, because a
     * registration can ask for the original state's properties to be copied. Its own call site sits in
     * {@code AxeItem#getStripped}, a method this tree no longer calls - the axe goes through
     * {@code getAxeStrippingState} - so the lookup has to be made on Fabric's behalf from here.
     */
    @Nullable
    public static BlockState getBlockStrippedByFabric(BlockState state) {
        return (BlockState) net.neoforged.fabricbridge.FabricBridgeHooks.strippedStateFromFabric(state);
    }

    @ApiStatus.Internal
    public static FuelValues populateFuelValues(RegistryAccess lookupProvider, FeatureFlagSet features) {
        FuelValues.Builder builder = new FuelValues.Builder(lookupProvider, features);
        Registry<Item> registry = lookupProvider.lookupOrThrow(Registries.ITEM);
        registry.getDataMap(NeoForgeDataMaps.FURNACE_FUELS).forEach((key, fuel) -> builder.add(registry.getValue(key), fuel.burnTime()));
        // NeoFobric: replay Fabric API's fuel events on the table that actually gets built. Fabric fires them
        // from inside FuelValues#vanillaBurnTimes, and this tree assembles the table from the furnace-fuel data
        // map instead - the exclusion of non-flammable wood included - so a Fabric mod's fuels would never be
        // registered and its exclusions never applied. The order is Fabric's own: build callbacks first, then
        // the vanilla exclusion, then the exclusion callbacks.
        net.neoforged.fabricbridge.FabricBridgeHooks.applyFabricFuelEvents(builder, lookupProvider, features, AbstractFurnaceBlockEntity.BURN_TIME_STANDARD);
        return builder.build();
    }

    @SubscribeEvent
    static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        event.ifRegistry(Registries.BLOCK, registry -> {
            INVERSE_OXIDIZABLES_DATAMAP_INTERNAL.clear();
            INVERSE_WAXABLES_DATAMAP_INTERNAL.clear();

            registry.getDataMap(NeoForgeDataMaps.OXIDIZABLES).forEach((resourceKey, oxidizable) -> {
                var block = BuiltInRegistries.BLOCK.getValue(resourceKey);

                INVERSE_OXIDIZABLES_DATAMAP_INTERNAL.put(oxidizable.nextOxidationStage(), block);

                // Rebuild blockstate caches of oxidizables after datamaps are loaded so that they can recompute isRandomlyTicking while having access to the data map value
                // TODO - revisit this in the future if other datamaps will require rebuilding caches to avoid doing it multiple times
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    state.initCache();
                }
            });

            //noinspection deprecation
            WeatheringCopper.PREVIOUS_BY_BLOCK.get().forEach((after, before) -> {
                if (!INVERSE_OXIDIZABLES_DATAMAP_INTERNAL.containsKey(after)) {
                    INVERSE_OXIDIZABLES_DATAMAP_INTERNAL.put(after, before);
                    didHaveToFallbackToVanillaMaps = true;
                }
            });

            registry.getDataMap(NeoForgeDataMaps.WAXABLES).forEach((resourceKey, waxable) -> {
                INVERSE_WAXABLES_DATAMAP_INTERNAL.put(waxable.waxed(), BuiltInRegistries.BLOCK.getValue(resourceKey));
            });

            //noinspection deprecation
            HoneycombItem.WAX_OFF_BY_BLOCK.get().forEach((after, before) -> {
                if (!INVERSE_WAXABLES_DATAMAP_INTERNAL.containsKey(after)) {
                    INVERSE_OXIDIZABLES_DATAMAP_INTERNAL.put(after, before);
                    didHaveToFallbackToVanillaMaps = true;
                }
            });
        });
    }
}
