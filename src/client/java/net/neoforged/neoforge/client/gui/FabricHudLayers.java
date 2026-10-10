/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.client.gui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeMod;

/**
 * NeoFobric: runs Fabric's HUD elements around the vanilla HUD layers they were attached to.
 *
 * <p>Fabric's HUD API is layered the same way this one is, but its elements are attached around a vanilla
 * <em>render call</em> - a mixin wraps {@code Hud#extractRenderState} so that a Fabric element renders before,
 * after or instead of one of the layers it calls. This tree renders the layers through {@link GuiLayerManager}
 * instead, so those call sites are gone, and with them every Fabric element attached to them (Fabric's own HUD
 * test mod draws markers into the layers and asserts they appear).
 *
 * <p>The layers themselves are still there, under the ids in {@link VanillaGuiLayers}, and this class maps
 * Fabric's element ids onto them: for a layer Fabric has elements around, the layer is taken over in
 * {@link RenderGuiLayerEvent.Pre} - cancelled, Fabric's elements run with the layer's own render passed in as
 * the vanilla pass-through, and the corresponding {@link RenderGuiLayerEvent.Post} posted by hand so listeners
 * see the layer as rendered.
 *
 * <p>Everything Fabric-side is reached through the bridge, so this class does nothing when the renderer API is
 * not in the mod set.
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = NeoForgeMod.MOD_ID)
public final class FabricHudLayers {
    /**
     * The vanilla layers Fabric's element registry is organised around, each with the Fabric element id whose
     * elements render around it.
     *
     * <p>Fabric's ids predate this tree's layer ids and are its own names for the same layers, so the mapping
     * is by layer, not by name. The two the mapping cannot express are left out: a Fabric element attached to
     * the spectator menu (which this tree renders inside the hotbar layer on the spectator branch) and one
     * attached to the contextual info bar itself (Fabric has no element id for it, only for its background).
     */
    private static final Map<Identifier, String> FABRIC_ELEMENT_IDS = Map.ofEntries(
            Map.entry(VanillaGuiLayers.CAMERA_OVERLAYS, "minecraft:misc_overlays"),
            Map.entry(VanillaGuiLayers.CROSSHAIR, "minecraft:crosshair"),
            Map.entry(VanillaGuiLayers.HOTBAR, "minecraft:hotbar"),
            Map.entry(VanillaGuiLayers.PLAYER_HEALTH, "minecraft:health_bar"),
            Map.entry(VanillaGuiLayers.ARMOR_LEVEL, "minecraft:armor_bar"),
            Map.entry(VanillaGuiLayers.FOOD_LEVEL, "minecraft:food_bar"),
            Map.entry(VanillaGuiLayers.AIR_LEVEL, "minecraft:air_bar"),
            Map.entry(VanillaGuiLayers.VEHICLE_HEALTH, "minecraft:mount_health"),
            Map.entry(VanillaGuiLayers.CONTEXTUAL_INFO_BAR_BACKGROUND, "minecraft:info_bar"),
            Map.entry(VanillaGuiLayers.EXPERIENCE_LEVEL, "minecraft:experience_level"),
            Map.entry(VanillaGuiLayers.SELECTED_ITEM_NAME, "minecraft:held_item_tooltip"),
            Map.entry(VanillaGuiLayers.SPECTATOR_TOOLTIP, "minecraft:spectator_tooltip"),
            Map.entry(VanillaGuiLayers.EFFECTS, "minecraft:mob_effects"),
            Map.entry(VanillaGuiLayers.BOSS_OVERLAY, "minecraft:boss_bar"),
            Map.entry(VanillaGuiLayers.SLEEP_OVERLAY, "minecraft:sleep"),
            Map.entry(VanillaGuiLayers.DEMO_OVERLAY, "minecraft:demo_timer"),
            Map.entry(VanillaGuiLayers.SCOREBOARD_SIDEBAR, "minecraft:scoreboard"),
            Map.entry(VanillaGuiLayers.OVERLAY_MESSAGE, "minecraft:overlay_message"),
            Map.entry(VanillaGuiLayers.TITLE, "minecraft:title_and_subtitle"),
            Map.entry(VanillaGuiLayers.CHAT, "minecraft:chat"),
            Map.entry(VanillaGuiLayers.TAB_LIST, "minecraft:player_list"),
            Map.entry(VanillaGuiLayers.SUBTITLE_OVERLAY, "minecraft:subtitles"));

    /** A layer Fabric renders itself: the chain of elements, and the layer it was built around. */
    private record Bridge(GuiLayer vanilla, net.neoforged.fabricbridge.FabricBridgeHooks.FabricHudElements elements) {}

    /** Stands for "Fabric registered nothing around this layer" - one lookup per layer, not one per frame. */
    private static final Bridge NONE = new Bridge(null, null);

    private static final Map<Identifier, Bridge> BRIDGES = new ConcurrentHashMap<>();

    private FabricHudLayers() {}

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    @SubscribeEvent
    public static void onRenderGuiLayerPre(RenderGuiLayerEvent.Pre event) {
        var fabricElementId = FABRIC_ELEMENT_IDS.get(event.getName());
        if (fabricElementId == null) {
            return;
        }

        var bridged = BRIDGES.compute(event.getName(), (name, existing) -> {
            if (existing == NONE || (existing != null && existing.vanilla() == event.getLayer())) {
                return existing;
            }

            var vanilla = event.getLayer();
            var elements = net.neoforged.fabricbridge.FabricBridgeHooks.fabricHudElements(fabricElementId,
                    (graphics, deltaTracker) -> vanilla.render((GuiGraphicsExtractor) graphics, (DeltaTracker) deltaTracker));
            LOGGER.debug("[NeoFobric] HUD layer {} takes Fabric's {} elements", name, fabricElementId);
            return elements == null ? NONE : new Bridge(vanilla, elements);
        });
        if (bridged == NONE) {
            return;
        }

        event.setCanceled(true);
        bridged.elements().render(event.getGuiGraphics(), event.getPartialTick());
        // The manager only posts this when it rendered the layer itself, and listeners should not be able to
        // tell the difference.
        NeoForge.EVENT_BUS.post(new RenderGuiLayerEvent.Post(event.getGuiGraphics(), event.getPartialTick(), event.getName(), event.getLayer()));
    }
}
