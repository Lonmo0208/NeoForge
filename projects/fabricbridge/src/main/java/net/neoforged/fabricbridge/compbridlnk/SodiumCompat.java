package net.neoforged.fabricbridge.compbridlnk;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sodium's named compatibility fixes for the entrypoint order.
 *
 * <p>Fabric's renderer registry accepts exactly one rendering plug-in and throws
 * {@code UnsupportedOperationException} at the second registration, so on an instance carrying both
 * Sodium and Indigo the winner is simply whichever client entrypoint fabric-loader happens to return
 * first - an order the bridge does not control, and one that has been observed to flip between
 * otherwise identical launches. {@link #pinBeforeIndigo(List)} makes Sodium win, which is the
 * configuration every successful run used; {@link #yieldsRendererToSodium(String, String)} is the
 * opposite lever, kept behind a flag while Indigo's model loading is still the one every model
 * bakes through.
 */
public final class SodiumCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat/Sodium");

    private SodiumCompat() {}

    /**
     * Runs Sodium's entrypoints before Indigo's.
     *
     * <p>The two possible winners are not equally good: with Indigo holding the plug-in slot, Sodium's
     * {@code ExtendedBlockModelFeatureRenderer} still runs and replays Indigo-built meshes into Sodium's
     * own quad emitter, and Indigo's {@code MeshViewImpl} casts that emitter to its own type - the first
     * frame dies with {@code ClassCastException: MutableQuadViewWrapper cannot be cast to
     * MutableQuadViewImpl}. With Sodium first, Indigo's registration fails exactly as it always has on
     * those launches and everything else keeps working.
     *
     * <p>Only reorders when both are present, so no other mod's entrypoint order changes.
     */
    public static <T> void pinBeforeIndigo(List<EntrypointContainer<T>> containers) {
        var hasSodium = false;
        var hasIndigo = false;
        for (var container : containers) {
            var modId = container.getProvider().getMetadata().getId();
            hasSodium |= "sodium".equals(modId);
            hasIndigo |= "fabric-renderer-indigo".equals(modId);
        }
        if (!hasSodium || !hasIndigo) {
            return;
        }
        List<EntrypointContainer<T>> ordered = new ArrayList<>(containers.size());
        for (var container : containers) {
            if ("sodium".equals(container.getProvider().getMetadata().getId())) {
                ordered.add(container);
            }
        }
        for (var container : containers) {
            if (!"sodium".equals(container.getProvider().getMetadata().getId())) {
                ordered.add(container);
            }
        }
        containers.clear();
        containers.addAll(ordered);
        LOGGER.info("[NeoFobric] Pinned Sodium's entrypoint ahead of the Fabric renderer to keep the"
                + " one renderer plug-in slot");
    }

    /**
     * {@return whether this entrypoint should stand down so Sodium can take the renderer slot}
     *
     * <p>Indigo registers from its client entrypoint, which runs before Sodium's, so with both installed
     * Sodium's FRAPI integration was rejected with "Attempted to register a second rendering plug-in" and
     * the mod never took over block rendering. Blocks then only appeared once something forced a rebuild -
     * breaking a block re-rendered its own chunk - and items drew through a pipeline that left them
     * unlit and dark.
     *
     * <p>Off by default: Indigo is not only a renderer, it is also the model baker, so standing it down
     * left every block and item model without a texture - the game drew black-and-magenta placeholders.
     * Kept behind a flag because taking the renderer slot is still the right move once Indigo's model
     * loading is routed through NeoForge's.
     */
    public static boolean yieldsRendererToSodium(String key, String modId) {
        if (!Boolean.getBoolean("neofobric.indigoYieldsToSodium")) {
            return false;
        }
        if (!"client".equals(key) || !"fabric-renderer-indigo".equals(modId)) {
            return false;
        }
        try {
            return FabricLoaderImpl.INSTANCE.isModLoaded("sodium");
        } catch (Throwable t) {
            return false;
        }
    }
}
