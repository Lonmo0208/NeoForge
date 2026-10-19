package net.neoforged.fabricbridge.compbridlnk;

import java.io.IOException;
import java.io.InputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Continuity's named compatibility fix for renderers that collect models with the level-aware
 * five-argument {@code BlockStateModel.collectParts}.
 *
 * <p>Continuity's connected-texture transform only exists in its Fabric FRAPI {@code emitQuads}
 * override. Sodium's Fabric build uses its native FRAPI emitter and calls that method directly; the
 * NeoForge build's default emitter instead collects parts through
 * {@code NeoForgeModelAccess.collectPartsOf}, which asks the model for the five-argument
 * {@code collectParts}. Continuity's model only implements the vanilla two-argument method, so every
 * CTM definition is loaded and wrapped while no quad is transformed.
 *
 * <p>This class adds the five-argument override and a helper class into Continuity's own jar. The
 * helper must live there (not in the bridge): a Fabric mod's class loader cannot see the bridge's
 * module, but it can see Minecraft and Fabric API, which is exactly what the helper needs.
 * The helper's bytes are a resource so the bridge never loads it as a class of its own.
 */
public final class ContinuityCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat/Continuity");

    private static final String CTM_MODEL_CLASS =
            "me/pepperbell/continuity/client/model/CtmBlockStateModel.class";
    private static final String HELPER_INTERNAL =
            "me/pepperbell/continuity/client/model/NeoFobricCtmHelper";
    /** The helper's jar entry, next to Continuity's own model classes. */
    public static final String HELPER_ENTRY = HELPER_INTERNAL + ".class";
    private static final String HELPER_RESOURCE = "/neofobric/ctm/NeoFobricCtmHelper.class";

    private static final String COLLECT_PARTS_DESC =
            "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;"
                    + "Ljava/util/List;)V";
    private static final String HELPER_COLLECT_DESC =
            "(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"
                    + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;"
                    + "Ljava/util/List;)V";

    private ContinuityCompat() {}

    /**
     * Adds the five-argument {@code collectParts} override to Continuity's CTM model.
     *
     * @return the rewritten bytes, or {@code null} when this entry is not the CTM model or already
     *         carries the override
     */
    public static byte[] addContextAwareCollectParts(byte[] classBytes, String entryName) {
        if (!CTM_MODEL_CLASS.equals(entryName)) {
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }
        for (var method : node.methods) {
            if ("collectParts".equals(method.name) && COLLECT_PARTS_DESC.equals(method.desc)) {
                return null;
            }
        }
        var method = new MethodNode(Opcodes.ACC_PUBLIC, "collectParts", COLLECT_PARTS_DESC, null, null);
        var insns = method.instructions;
        for (var slot = 0; slot < 6; slot++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, slot));
        }
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER_INTERNAL, "collect",
                HELPER_COLLECT_DESC, false));
        insns.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 6;
        method.maxLocals = 6;
        node.methods.add(method);
        var writer = new ClassWriter(0);
        node.accept(writer);
        LOGGER.info("[NeoFobric] Continuity's CTM model now answers the level-aware collectParts "
                + "call, so renderers without the Fabric FRAPI emitter still run its transform");
        return writer.toByteArray();
    }

    /** {@return the helper class bytes to write into Continuity's jar, or null when unavailable} */
    public static byte[] helperClass() {
        try (InputStream in = ContinuityCompat.class.getResourceAsStream(HELPER_RESOURCE)) {
            if (in == null) {
                LOGGER.error("[NeoFobric] The Continuity CTM helper resource {} is missing",
                        HELPER_RESOURCE);
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            LOGGER.error("[NeoFobric] Could not read the Continuity CTM helper resource", e);
            return null;
        }
    }
}
