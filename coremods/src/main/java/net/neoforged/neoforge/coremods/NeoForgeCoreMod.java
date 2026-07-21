/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.coremods;

import java.util.Map;
import net.neoforged.neoforgespi.transformation.ClassProcessorProvider;

public class NeoForgeCoreMod implements ClassProcessorProvider {
    @Override
    public void createProcessors(Context context, Collector collector) {
        collector.add(new ReplaceFieldWithGetterAccess("net.minecraft.world.level.biome.Biome", Map.of(
                "climateSettings", "getModifiedClimateSettings",
                "specialEffects", "getModifiedSpecialEffects")));
        collector.add(new ReplaceFieldWithGetterAccess("net.minecraft.world.level.levelgen.structure.Structure", Map.of(
                "settings", "getModifiedStructureSettings")));
        collector.add(new ReplaceFieldWithGetterAccess("net.minecraft.world.level.block.FlowerPotBlock", Map.of(
                "potted", "getPotted")));

        collector.add(new MethodRedirector());

        collector.add(new net.neoforged.neoforge.coremods.compat.FixAnvilCraftMixin());
        // This runs before mixin to add a LivingEntity-returning bridge to ServerLevel and fix
        // AvoidEntityGoal's INVOKEVIRTUAL descriptor, so that AnvilCraft's MixinExtras expression
        // matching can find the target method with the correct return type.
        collector.add(new net.neoforged.neoforge.coremods.compat.AvoidEntityGoalCompatProcessor());
    }
}
