/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.minecraft.Hooks;

/**
 * The hand-off points between NeoForge/Minecraft and the fabric-loader runtime.
 *
 * <p>{@link #runFabricEntrypoints()} is called by NeoForge's mod loader
 * ({@code CommonModLoader#begin}) inside the registry-initialization window, where the registries
 * are open, so Fabric mods can register content exactly like they do in a pure Fabric
 * environment. The remaining methods are invoked from small runtime patches the bridge applies to
 * Minecraft classes (game instance hand-off and server branding).
 */
public final class FabricBridgeHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricBridgeHooks.class);

    private FabricBridgeHooks() {}

    /**
     * {@return whether the bridge has discovered Fabric mods and should inject itself into the game}
     */
    public static boolean bridgeActive() {
        return FabricBridge.hasFabricMods();
    }

    /**
     * Boots the fabric-loader runtime (if needed) and invokes the Fabric entrypoints
     * ({@code preLaunch}, {@code main} and the side entrypoint). Called while the registries are
     * unfrozen, right after NeoForge's own registration events.
     */
    public static void runFabricEntrypoints() {
        if (!bridgeActive()) {
            return;
        }

        FabricBridge.bootIfNeeded();
        FabricBridge.invokeEntrypoints();
    }

    /**
     * Hands the constructed game instance to the fabric-loader runtime and, on the client, runs the
     * Fabric client entrypoints. Injected into the client {@code Minecraft} constructor.
     *
     * <p>The client entrypoints have to run here rather than in {@link #runFabricEntrypoints()}:
     * that happens during NeoForge's registry window, where no game instance exists yet, while the
     * entrypoints legitimately use the client (Sodium's config builder reads
     * {@code Minecraft.getInstance().getWindow()}). They equally cannot wait until after the
     * constructor, because Sodium's {@code Minecraft#setFullscreen} mixin reads the client config
     * <em>during</em> the same constructor - so the entrypoint that registers it must already have
     * run. This insertion point sits after the game instance is assigned and before the window is
     * created, which satisfies both.
     */
    public static void attachGameInstance(Object gameInstance) {
        if (!bridgeActive()) {
            return;
        }

        if (!Boolean.getBoolean("neofobric.noPrepareModInit")) {
            try {
                FabricBridge.bootIfNeeded();
                FabricLoaderImpl.INSTANCE.prepareModInit(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get(), gameInstance);
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Failed to attach the game instance to Fabric Loader", t);
            }
        }

        if (!Boolean.getBoolean("neofobric.noClientEntrypoints")) {
            // Fabric mods do register content from a client entrypoint (particle types are the classic
            // case) and NeoForge's registries are closed by this point, so the tempting move is to open a
            // registry window here - GameData.unfreezeData()/freezeData(), the same pair NeoForge uses.
            // That does not work yet: unfreezeData() dies on its very first touch of BuiltInRegistries
            // ("Not bootstrapped (called from registry minecraft:game_event)"), because Fabric's registry
            // sync defers the registry bootstrap and the class is initialised late. Opening the window is
            // therefore left to whoever figures out that ordering; until then a mod that registers content
            // here fails with "Registry is already frozen", which is exactly what the Fabric API particle
            // test mod reports.
            try {
                FabricBridge.invokeClientEntrypoints();
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Failed to run the Fabric client entrypoints", t);
            }
        }
    }

    /**
     * Hands the dedicated server instance to the fabric-loader runtime. Injected into the server
     * main class where the server object is constructed.
     */
    public static void setServerGameInstance(Object gameInstance) {
        if (!bridgeActive()) {
            return;
        }

        try {
            FabricLoaderImpl.INSTANCE.setGameInstance(gameInstance);
        } catch (IllegalStateException e) {
            // Already set; harmless.
            LOGGER.debug("[NeoFobric] Game instance was already set", e);
        } catch (Throwable t) {
            LOGGER.error("[NeoFobric] Failed to set the server game instance on Fabric Loader", t);
        }
    }

    /**
     * Appends {@code fabric} to the game's brand. Injected into
     * {@code MinecraftServer#getServerModName} and {@code ClientBrandRetriever#getClientModName},
     * mirroring fabric-loader's {@code BrandingPatch}.
     */
    public static String insertBranding(String brand) {
        if (!bridgeActive()) {
            return brand;
        }
        return Hooks.insertBranding(brand);
    }

    /** Guards the fallback path so the redirect only consults Fabric once per lookup. */
    private static final ThreadLocal<Boolean> IN_CODEC_LOOKUP = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * {@return Fabric's codec for this payload id, or NeoForge's own lookup when Fabric has none}
     *
     * <p>Fabric and NeoForge both claim {@code minecraft:register}, but with different payload classes -
     * Fabric's {@code RegistrationPayload} versus NeoForge's {@code MinecraftRegisterPayload}. Without
     * this, NeoForge's encoder resolves the id to its own class and the cast fails with
     * {@code ClassCastException}, killing the connection in the configuration phase so that no world can
     * be entered, singleplayer included.
     *
     * <p>The fallback re-enters {@code NetworkRegistry#getCodec}, which has been redirected here; the
     * flag makes that second entry run NeoForge's original logic. Both sides are reached reflectively so
     * this class keeps no compile-time dependency on either, and any failure just falls back to NeoForge.
     */
    public static Object fabricPayloadCodec(Object id, Object protocol, Object flow) {
        if (bridgeActive() && !IN_CODEC_LOOKUP.get()) {
            IN_CODEC_LOOKUP.set(Boolean.TRUE);
            try {
                var fromFabric = lookupFabricCodec(id, protocol, flow);
                if (fromFabric != null) {
                    return fromFabric;
                }
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Fabric payload codec lookup failed for {}", id, t);
            } finally {
                IN_CODEC_LOOKUP.set(Boolean.FALSE);
            }
            // Not a Fabric payload: hand the lookup back to NeoForge, which now runs unredirected.
            return lookupNeoForgeCodec(id, protocol, flow);
        }
        if (bridgeActive() && IN_CODEC_LOOKUP.get()) {
            return lookupNeoForgeCodec(id, protocol, flow);
        }
        return null;
    }

    private static Object lookupNeoForgeCodec(Object id, Object protocol, Object flow) {
        try {
            return Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry")
                    .getMethod("getCodec",
                            Class.forName("net.minecraft.resources.Identifier"),
                            Class.forName("net.minecraft.network.ConnectionProtocol"),
                            Class.forName("net.minecraft.network.protocol.PacketFlow"))
                    .invoke(null, id, protocol, flow);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object lookupFabricCodec(Object id, Object protocol, Object flow) throws Exception {
        var registries = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl");
        var protocolName = String.valueOf(protocol);
        var flowName = String.valueOf(flow);

        String field;
        if (protocolName.endsWith("CONFIGURATION")) {
            field = flowName.endsWith("SERVERBOUND") ? "SERVERBOUND_CONFIGURATION" : "CLIENTBOUND_CONFIGURATION";
        } else if (protocolName.endsWith("PLAY")) {
            field = flowName.endsWith("SERVERBOUND") ? "SERVERBOUND_PLAY" : "CLIENTBOUND_PLAY";
        } else {
            return null;
        }

        var registry = registries.getField(field).get(null);
        var typeAndCodec = registry.getClass()
                .getMethod("get", Class.forName("net.minecraft.resources.Identifier"))
                .invoke(registry, id);
        if (typeAndCodec == null) {
            return null;
        }
        var codec = typeAndCodec.getClass().getMethod("codec").invoke(typeAndCodec);
        // Widen the payload type: it was registered as StreamCodec<B, T>, while NeoForge asks for
        // StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload>.
        return codec.getClass()
                .getMethod("map", java.util.function.Function.class, java.util.function.Function.class)
                .invoke(codec, java.util.function.Function.identity(), java.util.function.Function.identity());
    }
}
