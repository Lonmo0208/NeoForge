/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.client.model.block;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.random.Weighted;
import net.neoforged.fml.ModLoader;
import net.neoforged.neoforge.client.event.RegisterBlockStateModels;
import net.neoforged.neoforge.common.util.NeoForgeExtraCodecs;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public class BlockStateModelHooks {
    static final ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends CustomUnbakedBlockStateModel>> BLOCK_STATE_MODEL_IDS = new ExtraCodecs.LateBoundIdMapper<>();
    static final ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends CustomBlockModelDefinition>> BLOCK_MODEL_DEFINITION_IDS = new ExtraCodecs.LateBoundIdMapper<>();

    public static void init() {
        ModLoader.postEvent(new RegisterBlockStateModels(BLOCK_STATE_MODEL_IDS, BLOCK_MODEL_DEFINITION_IDS));
    }

    public static MapCodec<Either<CustomUnbakedBlockStateModel, SingleVariant.Unbaked>> makeSingleModelCodec() {
        // NeoFabric: Fabric API's own blockstate model types are dispatched on a "fabric:type" key and
        // registered through its CustomUnbakedBlockStateModel.register, which the bridge points at
        // registerFabricModelType below - so those ids are in this registry too. Try the platform's key
        // first, then Fabric's, then fall back to a plain variant: a blockstate written for Fabric
        // (Twilight Forest's noise_varying and giant_block among them) then decodes into the model the mod
        // registered instead of degrading to the missing model.
        var platformCodec = NeoForgeExtraCodecs.<MapCodec<? extends CustomUnbakedBlockStateModel>, CustomUnbakedBlockStateModel, SingleVariant.Unbaked>dispatchMapOrElse(
                BLOCK_STATE_MODEL_IDS.codec(Identifier.CODEC),
                CustomUnbakedBlockStateModel::codec,
                c -> c,
                SingleVariant.Unbaked.MAP_CODEC);
        var fabricAwareCodec = NeoForgeExtraCodecs.<MapCodec<? extends CustomUnbakedBlockStateModel>, CustomUnbakedBlockStateModel, Either<CustomUnbakedBlockStateModel, SingleVariant.Unbaked>>dispatchMapOrElse(
                "fabric:type",
                BLOCK_STATE_MODEL_IDS.codec(Identifier.CODEC),
                CustomUnbakedBlockStateModel::codec,
                c -> c,
                platformCodec);
        // Flatten the extra Either the second dispatch adds on its fallback side; writing a model back out
        // goes through the platform codec, which writes the id under "type" rather than "fabric:type" - a
        // Fabric mod's own codec, which knows its key, is what its models are serialised with.
        return fabricAwareCodec.xmap(BlockStateModelHooks::flatten, BlockStateModelHooks::nest);
    }

    private static Either<CustomUnbakedBlockStateModel, SingleVariant.Unbaked> flatten(
            Either<CustomUnbakedBlockStateModel, Either<CustomUnbakedBlockStateModel, SingleVariant.Unbaked>> either) {
        return either.left().isPresent() ? Either.left(either.left().get()) : either.right().orElseThrow();
    }

    private static Either<CustomUnbakedBlockStateModel, Either<CustomUnbakedBlockStateModel, SingleVariant.Unbaked>> nest(
            Either<CustomUnbakedBlockStateModel, SingleVariant.Unbaked> flat) {
        return Either.right(flat);
    }

    /**
     * Registers a <em>Fabric</em> mod's blockstate model type with this platform's dispatch.
     *
     * <p>The bridge points Fabric API's {@code CustomUnbakedBlockStateModel.register} at this method (see
     * {@code MixinJarPatcher}), so a mod registering its model types the Fabric way ends up here: the type is
     * handed to Fabric's own registry as well, and the model it decodes - the mod's, reached through the
     * game's own {@link BlockStateModel.Unbaked} interface - is wrapped so this dispatch can carry it, see
     * {@link FabricBlockStateModel}.
     *
     * @param id    the type id, as written in the blockstate's {@code "fabric:type"}
     * @param codec the mod's codec for that type
     */
    public static void registerFabricModelType(Identifier id, MapCodec<?> codec) {
        net.neoforged.fabricbridge.FabricBridgeHooks.registerFabricBlockStateModelType(id, codec);
        BLOCK_STATE_MODEL_IDS.put(id, adapterCodec(codec));
    }

    @SuppressWarnings("unchecked")
    private static MapCodec<? extends CustomUnbakedBlockStateModel> adapterCodec(MapCodec<?> codec) {
        // The codec decodes the mod's own model, which implements the game's BlockStateModel.Unbaked - the
        // only interface this side needs. The cast is what erasure leaves here.
        var original = (MapCodec<BlockStateModel.Unbaked>) codec;
        return original.xmap(unbaked -> new FabricBlockStateModel(unbaked, original), FabricBlockStateModel::delegate);
    }

    /**
     * A blockstate model a <em>Fabric</em> mod provided, as this platform's dispatch sees it.
     *
     * <p>Everything goes through {@link BlockStateModel.Unbaked}, which the game defines and Fabric's model
     * interface extends, so the wrapped model is used directly.
     */
    private record FabricBlockStateModel(BlockStateModel.Unbaked delegate,
            MapCodec<BlockStateModel.Unbaked> modelCodec) implements CustomUnbakedBlockStateModel {
        @Override
        public BlockStateModel bake(ModelBaker modelBaker) {
            return this.delegate.bake(modelBaker);
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            this.delegate.resolveDependencies(resolver);
        }

        @Override
        public MapCodec<? extends CustomUnbakedBlockStateModel> codec() {
            // Only asked for when a model is written back out. The mod's codec writes the "fabric:type" key
            // itself, which the dispatches above know nothing about.
            return adapterCodec(this.modelCodec);
        }
    }

    public static Codec<Weighted<Either<CustomUnbakedBlockStateModel, SingleVariant.Unbaked>>> makeElementCodec() {
        var singleModelCodec = makeSingleModelCodec();
        return RecordCodecBuilder.create(
                instance -> instance.group(
                        singleModelCodec.forGetter(Weighted::value),
                        ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(Weighted::weight))
                        .apply(instance, Weighted::new));
    }

    public static Codec<BlockStateModelDispatcher> makeDefinitionCodec() {
        return NeoForgeExtraCodecs.dispatchMapOrElse(
                "neoforge:definition_type",
                BLOCK_MODEL_DEFINITION_IDS.codec(Identifier.CODEC),
                CustomBlockModelDefinition::codec,
                Function.identity(),
                BlockStateModelDispatcher.VANILLA_CODEC).xmap(
                        BlockStateModelHooks::packDefinition,
                        BlockStateModelHooks::unpackDefinition)
                .codec();
    }

    private static BlockStateModelDispatcher packDefinition(Either<CustomBlockModelDefinition, BlockStateModelDispatcher> definition) {
        return definition.map(def -> new BlockStateModelDispatcher(Optional.empty(), Optional.empty(), Optional.of(def)), Function.identity());
    }

    private static Either<CustomBlockModelDefinition, BlockStateModelDispatcher> unpackDefinition(BlockStateModelDispatcher definition) {
        return definition.customDefinition().isPresent() ? Either.left(definition.customDefinition().get()) : Either.right(definition);
    }

    private BlockStateModelHooks() {}
}
