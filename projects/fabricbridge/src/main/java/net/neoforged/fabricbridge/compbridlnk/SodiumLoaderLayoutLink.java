package net.neoforged.fabricbridge.compbridlnk;

/**
 * Cross-loader compatibility link: Sodium's loader-specific class layout.
 *
 * <p>Sodium ships the same code once per loader, and each build keeps its loader-specific classes in a
 * loader-named subpackage of {@code net.caffeinemc.mods.sodium}: the Fabric build under {@code fabric/},
 * the NeoForge build under {@code neoforge/}, with the simple names adjusted to match
 * ({@code FabricBlockAccess} becomes {@code NeoForgeBlockAccess}, {@code FabricFactory} becomes
 * {@code ForgeFactory}, and so on). A Fabric mod that integrates with Sodium is compiled against the
 * Fabric layout and refers to those classes by name.
 *
 * <p>iris is the canonical case: its {@code net.irisshaders.iris.mixin.fabric.MixinFluidRendererImpl}
 * declares {@code @Mixin(net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl.class)}. With the
 * NeoForge build of Sodium loaded that class does not exist, so the mixin silently never applies, the
 * {@code VertexEncoderInterface} it was meant to add never lands, and the first water surface crashes the
 * chunk mesher with a {@code ClassCastException} (Sodium's meshing task casts its fluid renderer to that
 * interface). The measured pair list below lets the bridge move those references onto the layout of the
 * build that actually loads.
 *
 * <p>The table was measured against {@code sodium-fabric-0.9.1+mc26.2} and
 * {@code sodium-neoforge-0.9.1+mc26.2}. Classes with no counterpart in the NeoForge build are
 * deliberately absent ({@code SodiumPreLaunch}); a mod that needs one of those cannot be satisfied by a
 * rename. The companion pass in {@code MixinJarPatcher} only rewrites a name that does NOT resolve
 * against what will actually load (game content plus the Fabric jars of that pass), so a setup that
 * really loads the Fabric build keeps its own names untouched.
 *
 * <p>{@link #retargetForFabricBuild} is the mirror used on the other side: a NeoForge mod whose only
 * build of the library on disk is the Fabric one (Iris requires Sodium, and in a mixed pack Sodium is
 * the Fabric mod) is handed to FML as a rewritten copy of its file, and its own references to the
 * NeoForge layout have to move to the Fabric layout in the same step. It is called with the ids of the
 * Fabric mods that are loading; the rewrite only happens when the Fabric Sodium is one of them.
 */
public final class SodiumLoaderLayoutLink {
    /** The mod id the table is keyed on, as the Fabric loader spells it. */
    private static final String SODIUM_ID = "sodium";

    private SodiumLoaderLayoutLink() {
    }

    /** {@return every Fabric-layout internal name and the NeoForge-layout name that replaces it} */
    public static java.util.Map<String, String> movedClassNames() {
        return MOVED;
    }

    /**
     * Points a class of a NeoForge mod file at the Fabric layout of Sodium.
     *
     * <p>Called while the bridge writes the relaxed copy of an FML mod file; the rewrite only applies when
     * the Fabric build of Sodium is among the loaded Fabric mods, because then the NeoForge layout is the
     * one that does not exist at runtime.
     *
     * @param entryName the jar entry path (= internal name + {@code .class})
     * @param classBytes the class bytes
     * @param loadedFabricIds the mod ids of the Fabric mods that will load
     * @return the rewritten class bytes, or the input when the class is not affected
     */
    public static byte[] retargetForFabricBuild(String entryName, byte[] classBytes,
            java.util.Set<String> loadedFabricIds) {
        if (!loadedFabricIds.contains(SODIUM_ID)) {
            return classBytes;
        }
        var applicable = new java.util.HashMap<String, String>(MOVED.size());
        for (var move : MOVED.entrySet()) {
            applicable.put(move.getValue(), move.getKey());
        }
        return net.neoforged.fabricbridge.transform.LayoutRetargeter.retarget(entryName, classBytes, applicable);
    }

    private static final java.util.Map<String, String> MOVED = build();

    private static java.util.Map<String, String> build() {
        var moves = new java.util.LinkedHashMap<String, String>();
        var from = "net/caffeinemc/mods/sodium/fabric/";
        var to = "net/caffeinemc/mods/sodium/neoforge/";
        // Same simple name on both sides: only the loader segment of the package differs.
        moves.put(from + "render/FluidRendererImpl", to + "render/FluidRendererImpl");
        moves.put(from + "render/FluidRendererImpl$DefaultRenderContext",
                to + "render/FluidRendererImpl$DefaultRenderContext");
        moves.put(from + "render/ImprovedItemModelBuilder", to + "render/ImprovedItemModelBuilder");
        moves.put(from + "render/ImprovedItemModelBuilder$1", to + "render/ImprovedItemModelBuilder$1");
        // Renamed into the NeoForge naming scheme on top of the package move.
        moves.put(from + "block/FabricBlockAccess", to + "block/NeoForgeBlockAccess");
        moves.put(from + "level/FabricLevelAccess", to + "level/NeoForgeLevelAccess");
        moves.put(from + "level/FabricLevelRenderHooks", to + "level/NeoForgeLevelRenderHooks");
        moves.put(from + "model/FabricModelAccess", to + "model/NeoForgeModelAccess");
        moves.put(from + "config/ConfigLoaderFabric", to + "config/ConfigLoaderForge");
        moves.put(from + "FabricRuntimeInformation", to + "NeoForgeRuntimeInformation");
        moves.put(from + "SodiumFabricMod", to + "SodiumForgeMod");
        moves.put(from + "FabricMixinOverrides", to + "ForgeMixinOverrides");
        moves.put(from + "render/FabricColorProviders", to + "render/ForgeColorProviders");
        moves.put(from + "render/FabricColorProviders$FabricFluidAdapter",
                to + "render/ForgeColorProviders$ForgeFluidAdapter");
        moves.put(from + "render/FluidRendererImpl$FabricFactory", to + "render/FluidRendererImpl$ForgeFactory");
        moves.put(from + "render/FluidRendererImpl$FabricFactory$1", to + "render/FluidRendererImpl$ForgeFactory$1");
        moves.put(from + "render/FluidRendererImpl$FabricFactory$2", to + "render/FluidRendererImpl$ForgeFactory$2");
        return java.util.Collections.unmodifiableMap(moves);
    }
}
