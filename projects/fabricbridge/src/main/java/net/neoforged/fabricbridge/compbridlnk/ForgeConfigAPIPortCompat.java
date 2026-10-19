package net.neoforged.fabricbridge.compbridlnk;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.MethodInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ForgeConfigAPIPort's named compatibility fix.
 *
 * <p>ForgeConfigAPIPort's Fabric build ships its own copy of {@code net.neoforged.fml.config}, and its
 * {@code ConfigTracker#registerConfig} takes the <em>mod id</em> as its third parameter. On NeoForge that
 * copy is shadowed by the platform's classes - the two cannot both be in the layer - and the platform's
 * overload takes a {@code ModContainer} instead, so the call dies with {@code NoSuchMethodError} inside
 * the calling mod's entrypoint. Twilight Forest registers its configs that way, and its aborted
 * entrypoint is what leaves the game without its content and, later, without a working resource reload.
 *
 * <p>{@code FabricConfigHooks} registers the config with a synthetic container for the Fabric mod id, so
 * the config is tracked and loaded exactly like a NeoForge mod's. The receiver is already on the stack
 * when the call is made, so the static helper takes it as its first argument and the call keeps its shape.
 */
public final class ForgeConfigAPIPortCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat/ForgeConfigAPIPort");

    private ForgeConfigAPIPortCompat() {}

    /**
     * Points {@code ConfigTracker.registerConfig} calls at a helper that can serve them on NeoForge.
     *
     * @return the class bytes with the call redirected, or the original when it does not contain it
     */
    public static byte[] redirectConfigRegistration(byte[] classBytes, String entryName) {
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }

        var changed = false;
        for (var method : node.methods) {
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof org.objectweb.asm.tree.MethodInsnNode call)
                        || !"net/neoforged/fml/config/ConfigTracker".equals(call.owner)
                        || !"registerConfig".equals(call.name)) {
                    continue;
                }
                var takesModId = call.desc.startsWith("(Lnet/neoforged/fml/config/ModConfig$Type;Lnet/neoforged/fml/config/IConfigSpec;Ljava/lang/String;");
                if (!takesModId) {
                    continue;
                }
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = "net/neoforged/neoforge/common/FabricConfigHooks";
                call.name = "registerFabricModConfig";
                call.desc = "(Lnet/neoforged/fml/config/ConfigTracker;" + call.desc.substring(1);
                call.itf = false;
                changed = true;
            }
        }

        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Pointed {}'s config registration at FabricConfigHooks", entryName);
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
