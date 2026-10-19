package net.neoforged.fabricbridge.compbridlnk;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ViaFabricPlus's named compatibility fixes - every entry points at one of the mod's own classes.
 *
 * <p>ViaFabricPlus was written against pure Fabric's game classes, and several of its internals do not
 * match this platform's shapes. The two rewrites here are the ones a generic rule cannot express: both
 * were found by reading the mod's sources (github.com/ViaVersion/ViaFabricPlus) and fixed in its jar
 * while it is loaded.
 */
public final class ViaFabricPlusCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat/ViaFabricPlus");

    private ViaFabricPlusCompat() {}

    /**
     * Repoints ViaFabricPlus's {@code startWaitingForNewLevel} wrap at the overload that actually runs, and
     * widens its handler to the new call shape.
     *
     * <p>ViaFabricPlus wraps the {@code startWaitingForNewLevel(LocalPlayer, ClientLevel, Reason)} call
     * inside {@code handleRespawn} with a {@code @WrapWithCondition} whose handler takes the wrapped call's
     * arguments plus one {@code @Local(name = "dimensionKey")} sugar parameter. NeoForge split that method
     * into a delegating three-argument overload with no call sites left and a five-argument overload that
     * {@code handleRespawn} actually invokes, carrying the two dimension keys the sugar used to fish out of
     * the frame. Against this platform the injection therefore dies in MixinExtras' parameter validation
     * during the apply's post-phase - the target class is left half-transformed with frames that describe
     * the pre-injection layout, and the first link of {@code ClientPacketListener} verifies a different
     * method every launch ("Expecting a stackmap frame").
     *
     * <p>The fix points the {@code @At} target at the five-argument overload and lets the handler read the
     * two keys as explicit call arguments: the sugar parameter's slot and type are the first new argument's,
     * so the handler body reads it unchanged; only the second key is appended and the stale {@code @Local}
     * annotation removed.
     */
    public static byte[] realignWrapConditionOverload(byte[] classBytes, String entryName) {
        if (!"com/viaversion/viafabricplus/injection/mixin/features/v1_20_3/network/MixinClientPacketListener.class".equals(entryName)) {
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }

        var oldCallArgs = "(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;)V";
        var newCallArgs = "(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/ResourceKey;)V";
        var resourceKeyDesc = "Lnet/minecraft/resources/ResourceKey;";

        var changed = false;
        for (var method : node.methods) {
            // ViaFabricPlus uses MixinExtras' own WrapWithCondition, not sponge's - match both spellings.
            var wrap = findAnnotation(method, "Lorg/spongepowered/asm/mixin/injection/WrapWithCondition;");
            if (wrap == null) {
                wrap = findAnnotation(method, "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;");
            }
            if (wrap == null || wrap.values == null) {
                continue;
            }
            for (var i = 0; i + 1 < wrap.values.size(); i += 2) {
                if (!"at".equals(wrap.values.get(i)) || !(wrap.values.get(i + 1) instanceof org.objectweb.asm.tree.AnnotationNode at)
                        || at.values == null) {
                    continue;
                }
                for (var j = 0; j + 1 < at.values.size(); j += 2) {
                    if ("target".equals(at.values.get(j)) && at.values.get(j + 1) instanceof String target
                            && target.startsWith("Lnet/minecraft/client/multiplayer/ClientPacketListener;startWaitingForNewLevel(")
                            && target.contains(oldCallArgs)) {
                        at.values.set(j + 1, target.replace(oldCallArgs, newCallArgs));
                        changed = true;
                    }
                }
            }

            if (!method.name.equals("checkDimensionChange")) {
                continue;
            }
            var args = org.objectweb.asm.Type.getArgumentTypes(method.desc);
            if (args.length == 0 || !args[args.length - 1].getDescriptor().equals(resourceKeyDesc)) {
                continue;
            }
            var newArgs = new org.objectweb.asm.Type[args.length + 1];
            System.arraycopy(args, 0, newArgs, 0, args.length);
            newArgs[args.length] = org.objectweb.asm.Type.getObjectType("net/minecraft/resources/ResourceKey");
            method.desc = org.objectweb.asm.Type.getMethodDescriptor(org.objectweb.asm.Type.getReturnType(method.desc), newArgs);
            // The last parameter was the @Local sugar; the first appended parameter takes over its slot
            // (same type, the wrapper's own toDimension), so only the annotation goes.
            if (method.visibleParameterAnnotations != null && method.visibleParameterAnnotations.length == args.length) {
                method.visibleParameterAnnotations[args.length - 1] = null;
            }
            if (method.invisibleParameterAnnotations != null && method.invisibleParameterAnnotations.length == args.length) {
                method.invisibleParameterAnnotations[args.length - 1] = null;
            }
            if (args[args.length - 1].getSize() == 1) {
                method.maxLocals += 1;
            } else {
                method.maxLocals += 2;
            }
            method.signature = null;
            changed = true;
            LOGGER.info("[NeoFobric] Repointed {}#{} at the five-argument startWaitingForNewLevel overload and widened the handler by the explicit dimension keys", node.name.replace('/', '.'), method.name);
        }

        if (!changed) {
            return null;
        }
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Turns ViaFabricPlus's raw {@code ProtocolVersion} translation arguments into strings.
     *
     * <p>ViaFabricPlus's server-list tooltip feeds {@code Component.translatable} the
     * {@code ProtocolVersion} object itself. This platform's development environment validates
     * translatable arguments (Component, Number, Boolean or String - production skips the check), so
     * the moment a target version is set, rendering any server-list entry crashed the render thread
     * with {@code TranslatableContents' arguments must be either ...}. The call whose result the
     * translation consumes is rewritten to {@code String.valueOf} - exactly what the translation line
     * prints anyway ({@code 1.21-1.21.1 (767)}), and null-safe for the polled "checking..." entries
     * whose version is not known yet.
     */
    public static byte[] stringifyVersionArgs(byte[] classBytes, String entryName) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
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
                        || !(call.owner.equals("com/viaversion/viafabricplus/injection/access/core/IServerData"))
                        || !"viaFabricPlus$translatingVersion".equals(call.name)) {
                    continue;
                }
                // The result sits on the stack where the translation wants a String. String.valueOf is
                // null-safe: the polled "checking..." entries return a null version, and the first
                // attempt (a plain toString call) died on exactly those with an NPE.
                method.instructions.insert(call, new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/String",
                        "valueOf",
                        "(Ljava/lang/Object;)Ljava/lang/String;",
                        false));
                changed = true;
                LOGGER.info("[NeoFobric] Pointed {}#{}'s target-version argument at its toString so the translatable-argument validation accepts it",
                        node.name.replace('/', '.'), method.name);
            }
        }

        if (!changed) {
            return null;
        }
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Repoints ViaFabricPlus's 1.10 flower-pot use anchor at the getter the platform redirects the field to.
     *
     * <p>ViaFabricPlus injects at the {@code FlowerPotBlock.potted} field read inside {@code useWithoutItem}.
     * NeoForge keeps that field as an unused placeholder and redirects its reads to {@code getPotted()} with a
     * coremod - but the bridge widens the field so Fabric mixins can shadow it, and the coremod then stands
     * down (its encapsulation target is already public). The platform source now calls the getter directly, so
     * the field read the anchor names no longer exists; the same point is the {@code getPotted()} call.
     */
    public static byte[] retargetFlowerPotUseAnchor(byte[] classBytes, String entryName) {
        if (!"com/viaversion/viafabricplus/injection/mixin/features/v1_10/MixinFlowerPotBlock.class".equals(entryName)) {
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }

        var fieldTarget = "Lnet/minecraft/world/level/block/FlowerPotBlock;potted:Lnet/minecraft/world/level/block/Block;";
        var getterTarget = "Lnet/minecraft/world/level/block/FlowerPotBlock;getPotted()Lnet/minecraft/world/level/block/Block;";
        var changed = false;
        for (var method : node.methods) {
            var inject = findAnnotation(method, "Lorg/spongepowered/asm/mixin/injection/Inject;");
            if (inject == null || inject.values == null) {
                continue;
            }
            for (var i = 0; i + 1 < inject.values.size(); i += 2) {
                if (!"at".equals(inject.values.get(i))) {
                    continue;
                }
                // Inject#at() is At[], so even a single @At is encoded as a list of annotations.
                var atValue = inject.values.get(i + 1);
                if (atValue instanceof java.util.List<?> ats) {
                    for (var item : ats) {
                        if (item instanceof AnnotationNode at
                                && retargetFlowerPotAnchor(at, fieldTarget, getterTarget, node, method)) {
                            changed = true;
                        }
                    }
                } else if (atValue instanceof AnnotationNode at
                        && retargetFlowerPotAnchor(at, fieldTarget, getterTarget, node, method)) {
                    changed = true;
                }
            }
        }

        if (!changed) {
            return null;
        }
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@return whether the annotation was the flower-pot field anchor and is now the getter call} */
    private static boolean retargetFlowerPotAnchor(AnnotationNode at, String fieldTarget, String getterTarget,
            ClassNode node, MethodNode method) {
        if (at.values == null
                || !"FIELD".equals(valueOf(at, "value"))
                || !fieldTarget.equals(valueOf(at, "target"))) {
            return false;
        }
        // The ordinal/opcode pair describes the removed field read; the INVOKE target has exactly
        // one call site in useWithoutItem, so neither is needed.
        at.values = java.util.List.of("value", "INVOKE", "target", getterTarget);
        LOGGER.info("[NeoFobric] Repointed {}#{}'s flower-pot anchor at getPotted(), the call the field read was redirected to",
                node.name.replace('/', '.'), method.name);
        return true;
    }

    /** {@return the value of {@code key} in the annotation's value list, or {@code null}} */
    private static Object valueOf(AnnotationNode annotation, String key) {
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    /** {@return the method's annotation with the given descriptor, or {@code null}} */
    private static AnnotationNode findAnnotation(MethodNode method, String descriptor) {
        for (var list : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (list == null) {
                continue;
            }
            for (var annotation : list) {
                if (descriptor.equals(annotation.desc)) {
                    return annotation;
                }
            }
        }
        return null;
    }
}
