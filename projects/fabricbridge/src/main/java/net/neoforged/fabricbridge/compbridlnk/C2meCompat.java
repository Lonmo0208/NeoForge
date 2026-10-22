package net.neoforged.fabricbridge.compbridlnk;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Targeted compatibility patches for c2me.
 *
 * <p>c2me starts its FlowSched worker threads from {@code GlobalExecutors}' static initialiser. Each
 * worker immediately runs a prologue that loads {@code ThreadPriorityPresets} (and its OSHI/native
 * bindings) to set its own thread priority. That class load happens while the main thread is still
 * inside {@code MixinProcessor.selectConfigs}, and Mixin's config map is not safe against a class load
 * from another thread at that moment: the worker dies with
 * {@code MixinException: java.util.ConcurrentModificationException} before it ever picks up a task.
 *
 * <p>Replacing the prologue with a no-op removes the only class load the fresh workers perform during
 * mixin selection. Thread priorities are a tuning detail; chunk generation does not depend on them.
 */
public final class C2meCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat/c2me");

    private C2meCompat() {}

    /**
     * {@return the class bytes with the worker prologue neutralised, or {@code null} when this is not
     * c2me's {@code GlobalExecutors} or it carries no prologue}
     */
    public static byte[] neutraliseWorkerPrologue(byte[] classBytes, String entryName) {
        var className = entryName.endsWith(".class")
                ? entryName.substring(0, entryName.length() - ".class".length())
                : entryName;
        if (!"com/ishland/c2me/base/common/GlobalExecutors".equals(className)) {
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
            if (!"lambda$static$1".equals(method.name) || !"()V".equals(method.desc)) {
                continue;
            }
            method.instructions.clear();
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            method.tryCatchBlocks.clear();
            method.localVariables = null;
            var writer = new ClassWriter(0);
            node.accept(writer);
            LOGGER.info("[NeoFobric] Neutralised c2me's worker thread-priority prologue: it loaded classes"
                    + " on the new workers while Mixin was selecting configs, which killed them with a"
                    + " ConcurrentModificationException");
            return writer.toByteArray();
        }
        return null;
    }
}
