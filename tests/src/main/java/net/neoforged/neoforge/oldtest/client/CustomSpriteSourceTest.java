/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.oldtest.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterSpriteSourcesEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * A test creating a custom sprite source provider a sprite using it and an item using that sprite.
 */
@Mod(CustomSpriteSourceTest.MOD_ID)
public class CustomSpriteSourceTest {
    private static final boolean ENABLED = true;
    static final String MOD_ID = "custom_sprite_source_test";
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final Holder<Item> TEST_ITEM = ITEMS.registerSimpleItem("test_item");

    public CustomSpriteSourceTest(IEventBus modEventBus) {
        if (!ENABLED) return;
        if (FMLEnvironment.getDist().isClient()) {
            modEventBus.addListener(this::registerTextureAtlasSpriteLoaders);
        }
        ITEMS.register(modEventBus);
    }

    private void registerTextureAtlasSpriteLoaders(RegisterSpriteSourcesEvent event) {
        event.register(Identifier.fromNamespaceAndPath(MOD_ID, "custom_sprite_source"), CustomSpriteSource.CODEC);
    }

    private record CustomSpriteSource(Identifier id) implements SpriteSource {
        private static final Logger LOGGER = LogUtils.getLogger();
        private static final MapCodec<CustomSpriteSource> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
                Identifier.CODEC.fieldOf("id").forGetter(CustomSpriteSource::id)).apply(inst, CustomSpriteSource::new));

        @Override
        public void run(ResourceManager manager, Output output) {
            Identifier id = this.id();
            Identifier resourcelocation = TEXTURE_ID_CONVERTER.idToFile(id);
            Optional<Resource> optional = manager.getResource(resourcelocation);
            if (optional.isPresent()) {
                output.add(id, spriteResourceLoader -> spriteResourceLoader.loadSprite(id, optional.get(), CustomSpriteContents::new));
            } else {
                LOGGER.warn("Missing sprite: {}", resourcelocation);
            }
        }

        @Override
        public MapCodec<CustomSpriteSource> codec() {
            return CODEC;
        }

        static final class CustomSpriteContents extends SpriteContents {
            public CustomSpriteContents(Identifier name, FrameSize size, NativeImage image, Optional<AnimationMetadataSection> animationMetadata, List<MetadataSectionType.WithValue<?>> additionalMetadata, Optional<TextureMetadataSection> textureMetadata) {
                super(name, size, image, animationMetadata, additionalMetadata, textureMetadata);
            }

            // TODO 1.21.11 It's unclear how this test can be resurrected since Vanilla now much more strictly manages the frames of the animated sprite in GPU memory
            // @Override
            // public SpriteTicker createTicker() {
            //     return new Ticker();
            // }

            class Ticker /* TODO 1.21.11 implements SpriteTicker */ {
                final RandomSource random = RandomSource.create();

                // TODO 1.21.11 @Override
                public void tickAndUpload(int x, int y, GpuTexture texture) {
                    CustomSpriteContents.this.byMipLevel[0].fillRect(0, 0, 16, 16, 0xFF000000 | random.nextInt(0xFFFFFF));
                    CustomSpriteContents.this.uploadFirstFrame(texture, 0 /* mip level */);
                }

                // TODO 1.21.11 @Override
                public void close() {}
            }
        }
    }
}
