package net.neoforged.fabricbridge.compbridlnk;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.fabricbridge.discovery.FabricJarRegistry;
import net.neoforged.fml.jarcontents.JarContents;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies owo-lib's {@code CompoundTagMixin} with the class tweaker instead of Mixin.
 *
 * <p>{@code net.minecraft.nbt.CompoundTag} is loaded by the platform's own startup code (the network
 * codecs reach it around 37 s) before the Fabric mixin configs are selected (around 42 s). Mixin never
 * revisits a class that was already defined, so owo's mixin - which adds {@code MapCarrier}'s
 * {@code get/put/delete/has} methods - is silently skipped. Endec then calls
 * {@code CompoundTag.put(SerializationContext, KeyedEndec, Object)} on the player join and the server
 * dies with {@code NoSuchMethodError}.
 *
 * <p>The class tweaker runs as part of the game layer's transformation, independent of when the Fabric
 * configs are prepared, so its methods are copied into {@code CompoundTag} here. The owo config entry
 * is removed from {@code owo.mixins.json} by {@code MixinJarPatcher} so Mixin cannot add them twice.
 * The copies only reference {@code CompoundTag}'s own public NBT methods after remapping the mixin's
 * shadow calls; the rest of the carrier contract comes from the Endec interface's default methods.
 */
public final class OwoCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat/owo");
    private static final String TARGET = "net/minecraft/nbt/CompoundTag";
    private static final String MIXIN = "io/wispforest/owo/mixin/serialization/CompoundTagMixin";
    private static final String MAP_CARRIER = "io/wispforest/endec/util/MapCarrier";

    /** {@return whether this game class is the one owo's mixin targets} */
    public static boolean handlesClass(String internalName) {
        return TARGET.equals(internalName) && !mixinMethods().isEmpty();
    }

    private static volatile List<MethodNode> mixinMethods;
    private static volatile boolean resolved;

    /**
     * Adds {@code MapCarrier} and owo's concrete methods to {@code CompoundTag}.
     *
     * @return whether the class was changed
     */
    public static boolean addMapCarrier(ClassNode target) {
        var methods = mixinMethods();
        if (methods.isEmpty()) {
            return false;
        }
        var changed = false;
        if (!target.interfaces.contains(MAP_CARRIER)) {
            target.interfaces.add(MAP_CARRIER);
            changed = true;
        }
        for (var method : methods) {
            if (hasMethod(target, method.name, method.desc)) {
                continue;
            }
            target.methods.add(cloneRemapped(method));
            changed = true;
        }
        if (changed) {
            LOGGER.info("[NeoFobric] Added owo's MapCarrier methods to CompoundTag through the class tweaker"
                    + " - Mixin could not see the class because the platform loads it before the Fabric"
                    + " configs are prepared");
        }
        return changed;
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        for (var method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode cloneRemapped(MethodNode source) {
        var copy = new MethodNode(source.access, source.name, source.desc, source.signature,
                source.exceptions == null ? null : source.exceptions.toArray(String[]::new));
        source.accept(copy);
        for (var insn = copy.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode method && MIXIN.equals(method.owner)) {
                method.owner = TARGET;
            } else if (insn instanceof FieldInsnNode field && MIXIN.equals(field.owner)) {
                field.owner = TARGET;
            }
        }
        return copy;
    }

    private static List<MethodNode> mixinMethods() {
        if (!resolved) {
            synchronized (OwoCompat.class) {
                if (!resolved) {
                    mixinMethods = readMixinMethods();
                    resolved = true;
                }
            }
        }
        return mixinMethods;
    }

    private static List<MethodNode> readMixinMethods() {
        for (var entry : FabricJarRegistry.getEntries().entrySet()) {
            if (!"owo".equals(entry.getValue().modId())) {
                continue;
            }
            try (var jar = JarContents.ofPath(entry.getKey())) {
                var bytes = jar.readFile(MIXIN + ".class");
                if (bytes == null) {
                    continue;
                }
                var node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                var methods = new ArrayList<MethodNode>();
                for (var method : node.methods) {
                    if ((method.access & Opcodes.ACC_ABSTRACT) != 0 || method.name.startsWith("<")) {
                        continue;
                    }
                    methods.add(method);
                }
                return List.copyOf(methods);
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Could not read owo's CompoundTagMixin from {}", entry.getKey(), t);
            }
        }
        return List.of();
    }
}
