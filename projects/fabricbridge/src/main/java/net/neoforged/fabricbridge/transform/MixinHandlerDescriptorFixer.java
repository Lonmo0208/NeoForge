/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Realigns Mixin injector handler parameters with a NeoForge-reshaped target method.
 *
 * <p>sponge-mixin binds the leading parameters of an injector handler to the target method's
 * parameters <em>by position</em>. When the target method still declares the same parameter types
 * but in a different order, Mixin rejects the injection with
 * {@code InvalidInjectionException: Invalid descriptor ... Expected (...)V but found (...)V}, and -
 * because the bridge relaxes the configs - the injection is dropped instead of applied. Fabric mods
 * compiled against pure Fabric Minecraft hit this whenever NeoForge patches a parameter order (for
 * example {@code ReloadableServerRegistries#lambda$scheduleRegistryLoad$0}, which NeoForge builds
 * as {@code (LootDataType, RegistryOps, ResourceManager)} where Fabric API's mixin declares
 * {@code (LootDataType, ResourceManager, RegistryOps)}).
 *
 * <p><strong>Why only the descriptor changes.</strong> The annotation processor wrote the handler
 * body against local slots, not against the descriptor: slot <i>n</i> already holds the argument
 * whose <em>type</em> it was declared with. Swapping two entries of the descriptor therefore makes
 * the descriptor name the types that the slots already carry - the instruction list stays correct
 * and the verifier keeps passing. Only {@link MethodNode#desc} (and the matching debug
 * {@code LocalVariableTable} entries) is rewritten; {@code MethodNode.instructions} is never
 * touched.
 *
 * <p>The pass is deliberately conservative. It fires only when the handler's leading parameters are
 * a pure permutation of the target's parameter types (every type matched exactly once, no leftovers
 * inside the target's parameter range), and it logs every rewrite. A missing type, a changed type or
 * an ambiguous trailing sugar parameter is left alone for Mixin to report as before.
 */
public final class MixinHandlerDescriptorFixer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinHandlerDescriptorFixer.class);

    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";

    /**
     * Injector annotations whose handlers take the target's parameters as leading arguments.
     * {@code @WrapMethod}/{@code @ModifyReturnValue}/{@code @ModifyReceiver} are excluded: the first
     * two do not receive the target's parameters, the third receives the receiver only.
     *
     * <p>The "value first" injectors are excluded as well: {@code @ModifyVariable}, {@code @ModifyArg},
     * {@code @ModifyArgs}, {@code @ModifyConstant} and {@code @ModifyExpressionValue} hand the modified
     * value (or the argument array, or the constant) in as the <em>first</em> parameter, with the target's
     * own parameters following it.
     * A handler like Twilight Forest's {@code (float, ServerLevel, DamageSource)} therefore looks like a
     * permutation of {@code hurtServer(ServerLevel, DamageSource, float)} and used to be reordered into
     * exactly that order - after which Mixin's handling of the modified variable no longer matched the
     * generated local-capture helper, and the woven class died in verification:
     * <pre>
     * java.lang.VerifyError: Bad local variable type
     *   at net/minecraft/world/entity/LivingEntity.localvar$bac000$twilightforest$tf$modifyIncomingDamage(...)
     * </pre>
     * Their parameter lists follow the injector's own convention, in which the permutation heuristic the
     * fixer uses cannot tell the value parameter apart from a target parameter of the same type, so they
     * are left untouched. A genuine drift in one of them then stays a (lenient, logged) injection
     * failure instead of a hard verification error.
     */
    private static final Set<String> POSITIONAL_INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");

    private MixinHandlerDescriptorFixer() {}

    /**
     * Patches the injector handlers of one mixin class whose parameters are a permutation of their
     * target's parameters.
     *
     * @param mixinClassBytes the mixin class exactly as stored in the mod jar
     * @param targetBytecode resolves a target class internal name to its bytecode ({@code null} when
     *            unavailable)
     * @return the patched class bytes, or {@code null} when nothing needed to change
     */
    public static byte[] fix(byte[] mixinClassBytes, Function<String, byte[]> targetBytecode) {
        ClassNode node;
        try {
            node = readClass(mixinClassBytes);
        } catch (Exception e) {
            return null;
        }

        var targetMethods = collectTargetMethods(node, targetBytecode);
        if (targetMethods.isEmpty()) {
            return null;
        }

        var changed = false;
        for (var handler : node.methods) {
            var injector = findPositionalInjector(handler);
            if (injector == null) {
                continue;
            }
            var targetName = readTargetMethodName(injector);
            if (targetName == null) {
                continue;
            }
            var candidates = targetMethods.get(targetName);
            if (candidates == null) {
                continue;
            }
            for (var target : candidates) {
                if (reorder(handler, target)) {
                    LOGGER.info("[NeoFobric] Realigned handler {}#{} to the parameter order of target method {}{}",
                            node.name.replace('/', '.'), handler.name, target.name, target.desc);
                    changed = true;
                    break;
                }
            }
        }

        if (!changed) {
            return null;
        }
        // COMPUTE_FRAMES is required: reorder() permutes a handler's leading parameters, and when that
        // moves a type across slots (mixed-type handlers, long/double slot widths) the frames the class
        // already carries describe the OLD layout. Mixin inlines these handler bodies into the game
        // class, so a stale frame surfaces as "VerifyError: Expecting a stackmap frame" / "Bad local
        // variable type" on the GAME class the moment it first links (fabric-data-attachment's
        // copyAttachmentsOnClientRespawn bridge, VFP's connection handlers - all died that way).
        // Recomputing needs a class hierarchy the jar alone cannot answer, so the common-superclass
        // lookup falls back to java/lang/Object: frames only ever need a real answer where two
        // unrelated reference types merge, and "Object" is a verifier-acceptable answer there.
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Reorders {@code handler}'s leading parameter types into {@code target}'s parameter order.
     *
     * @return whether the handler descriptor was rewritten
     */
    private static boolean reorder(MethodNode handler, MethodNode target) {
        var handlerArgs = Type.getArgumentTypes(handler.desc);
        var targetArgs = Type.getArgumentTypes(target.desc);
        if (targetArgs.length == 0 || handlerArgs.length < targetArgs.length) {
            return false;
        }

        // Match every target parameter to a distinct handler slot holding the same type.
        var taken = new boolean[handlerArgs.length];
        var sourceSlot = new int[targetArgs.length];
        Arrays.fill(sourceSlot, -1);
        for (var t = 0; t < targetArgs.length; t++) {
            for (var h = 0; h < handlerArgs.length; h++) {
                if (!taken[h] && handlerArgs[h].equals(targetArgs[t])) {
                    taken[h] = true;
                    sourceSlot[t] = h;
                    break;
                }
            }
            if (sourceSlot[t] < 0) {
                // The handler does not declare this target parameter type at all: a genuine
                // signature change, not a reordering. Leave it to Mixin.
                return false;
            }
        }

        // Any leftover handler slot inside the target's parameter range would make the meaning of a
        // sugar parameter depend on a guess - refuse instead.
        for (var h = 0; h < targetArgs.length; h++) {
            if (!taken[h]) {
                return false;
            }
        }

        // The handler's parameter list must end up in the target's order, so each position takes the
        // type that belongs there. sourceSlot[h] tells which handler position currently holds the
        // type for target position h, i.e. it *is* the permutation to apply to the parameter list.
        var newOrder = new Type[handlerArgs.length];
        var identity = true;
        for (var h = 0; h < targetArgs.length; h++) {
            newOrder[h] = targetArgs[h];
            if (sourceSlot[h] != h) {
                identity = false;
            }
        }
        for (var h = targetArgs.length; h < handlerArgs.length; h++) {
            // CallbackInfo/Operation parameters and MixinExtras sugar keep their position.
            newOrder[h] = handlerArgs[h];
        }
        if (identity) {
            return false;
        }

        // The slots still hold what they were declared with, so the descriptor change alone would
        // make every leading slot lie about its content. The body is written against slot numbers:
        // the value that position h must expose currently lives in slot sourceSlot[h], so every
        // access to that slot has to be renumbered to h.
        var moves = new int[handlerArgs.length];
        for (var h = 0; h < handlerArgs.length; h++) {
            moves[h] = h;
        }
        for (var h = 0; h < targetArgs.length; h++) {
            moves[sourceSlot[h]] = h;
        }
        var newDesc = Type.getMethodDescriptor(Type.getReturnType(handler.desc), newOrder);
        if (!applySlotRenumbering(handler, moves, newDesc)) {
            return false;
        }
        var oldDesc = handler.desc;
        handler.desc = newDesc;
        remapLocalVariableTypes(handler, oldDesc, newDesc);
        return true;
    }

    /**
     * Renumbers the handler's variable accesses so that slot {@code h} holds the type the new
     * descriptor declares for position {@code h}.
     *
     * <p>{@code moves[h]} is the slot that currently holds the value position {@code h} must expose.
     * Because the handler body addresses its parameters by slot number, changing the descriptor
     * without this pass would leave every affected slot lying about its content - exactly the state
     * that makes the JVM reject the woven method.
     *
     * @return whether the body was renumbered ({@code false} when the move would be unsound)
     */
    private static boolean applySlotRenumbering(MethodNode handler, int[] moves, String newDesc) {
        var oldSlots = slotTypesByIndex(handler.access, handler.desc);
        var newSlots = slotTypesByIndex(handler.access, newDesc);

        var affected = false;
        for (var h = 0; h < moves.length; h++) {
            if (moves[h] != h) {
                affected = true;
                break;
            }
        }
        if (!affected) {
            // The descriptor changed without any slot moving; nothing to renumber.
            return true;
        }

        // Safety: a slot holding a wide value (long/double) or the receiver must never be moved, and
        // a slot must keep the same category so the verifier keeps accepting it.
        for (var h = 0; h < oldSlots.length; h++) {
            var from = oldSlots[h];
            var to = newSlots[h];
            if (from == null || to == null) {
                continue;
            }
            if (from.getSize() != to.getSize()) {
                return false;
            }
            if (to.getSize() == 2) {
                return false;
            }
        }

        for (var instruction : handler.instructions) {
            if (instruction instanceof VarInsnNode varInsn) {
                if (varInsn.var >= 0 && varInsn.var < moves.length) {
                    var from = oldSlots[varInsn.var];
                    var to = newSlots[varInsn.var];
                    if (from != null && to != null && from.getSize() == 1 && to.getSize() == 1) {
                        varInsn.var = moves[varInsn.var];
                    }
                }
            } else if (instruction instanceof IincInsnNode iinc) {
                if (iinc.var >= 0 && iinc.var < moves.length) {
                    var from = oldSlots[iinc.var];
                    var to = newSlots[iinc.var];
                    if (from != null && to != null && from.getSize() == 1 && to.getSize() == 1) {
                        iinc.var = moves[iinc.var];
                    }
                }
            }
        }
        return true;
    }

    /**
     * @return the type occupying each slot, indexed by slot number ({@code null} for the second half
     *         of a wide value)
     */
    private static Type[] slotTypesByIndex(int access, String desc) {
        var args = Type.getArgumentTypes(desc);
        var isStatic = (access & Opcodes.ACC_STATIC) != 0;
        var slots = new Type[Math.max(1, args.length + (isStatic ? 0 : 1))];
        var slot = 0;
        if (!isStatic) {
            slots[slot++] = Type.getObjectType("java/lang/Object");
        }
        for (var arg : args) {
            slots[slot] = arg;
            slot += arg.getSize();
        }
        return slots;
    }

    /**
     * Keeps the debug {@code LocalVariableTable} consistent: slot numbers do not move, only the type
     * each slot is declared with.
     */
    private static void remapLocalVariableTypes(MethodNode handler, String oldDesc, String newDesc) {
        if (handler.localVariables == null || handler.localVariables.isEmpty()) {
            return;
        }
        var oldTypes = parameterSlotTypes(handler.access, oldDesc);
        var newTypes = parameterSlotTypes(handler.access, newDesc);
        for (var local : handler.localVariables) {
            if (local.index < 0 || local.index >= oldTypes.length) {
                continue;
            }
            var oldType = oldTypes[local.index];
            var newType = newTypes[local.index];
            if (oldType != null && newType != null && !oldType.equals(newType)) {
                local.desc = newType.getDescriptor();
            }
        }
    }

    /**
     * @return the type occupying each parameter slot (index 0 is the receiver for instance methods)
     */
    private static Type[] parameterSlotTypes(int access, String desc) {
        var args = Type.getArgumentTypes(desc);
        var isStatic = (access & Opcodes.ACC_STATIC) != 0;
        var types = new Type[args.length + (isStatic ? 0 : 1)];
        var slot = 0;
        if (!isStatic) {
            types[slot++] = Type.getObjectType("java/lang/Object");
        }
        for (var arg : args) {
            types[slot++] = arg;
        }
        return types;
    }

    /**
     * @return the target class bytecode indexed by method name
     */
    private static HashMap<String, List<MethodNode>> collectTargetMethods(ClassNode node, Function<String, byte[]> targetBytecode) {
        var targetMethods = new HashMap<String, List<MethodNode>>();
        for (var target : readTargets(node)) {
            var targetBytes = targetBytecode.apply(target);
            if (targetBytes == null) {
                continue;
            }
            ClassNode targetNode;
            try {
                targetNode = readClass(targetBytes);
            } catch (Exception e) {
                continue;
            }
            for (var method : targetNode.methods) {
                targetMethods.computeIfAbsent(method.name, key -> new ArrayList<>()).add(method);
            }
        }
        return targetMethods;
    }

    /**
     * @return the injector annotation of a handler method, or {@code null}
     */
    private static AnnotationNode findPositionalInjector(MethodNode method) {
        for (var annotation : allAnnotations(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (POSITIONAL_INJECTORS.contains(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * Reads the {@code method} element of an injector annotation. Mixin accepts a bare name, a name
     * with a descriptor ({@code "foo(I)V"}), a qualified form ({@code "com.example.Foo#foo"}) or an
     * array of those.
     *
     * @return the bare target method name, or {@code null}
     */
    private static String readTargetMethodName(AnnotationNode injector) {
        var values = injector.values;
        if (values == null) {
            return null;
        }
        for (var i = 0; i + 1 < values.size(); i += 2) {
            if (!"method".equals(values.get(i))) {
                continue;
            }
            var value = values.get(i + 1);
            if (value instanceof String name) {
                return bareMethodName(name);
            }
            if (value instanceof List<?> list) {
                for (var element : list) {
                    if (element instanceof String name) {
                        return bareMethodName(name);
                    }
                }
            }
        }
        return null;
    }

    private static String bareMethodName(String target) {
        var name = target;
        var descriptorStart = name.indexOf('(');
        if (descriptorStart >= 0) {
            name = name.substring(0, descriptorStart);
        }
        var separator = name.indexOf(';');
        if (separator >= 0 && separator + 1 < name.length()) {
            return name.substring(separator + 1);
        }
        var hash = name.indexOf('#');
        return hash >= 0 ? name.substring(hash + 1) : name;
    }

    /**
     * @return the internal names of every class the mixin targets
     */
    private static List<String> readTargets(ClassNode node) {
        var targets = new ArrayList<String>();
        for (var annotation : allAnnotations(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (!MIXIN_ANNOTATION.equals(annotation.desc) || annotation.values == null) {
                continue;
            }
            for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
                var key = annotation.values.get(i);
                if ("value".equals(key)) {
                    if (annotation.values.get(i + 1) instanceof List<?> types) {
                        for (var type : types) {
                            if (type instanceof Type t) {
                                targets.add(t.getInternalName());
                            }
                        }
                    } else if (annotation.values.get(i + 1) instanceof Type t) {
                        targets.add(t.getInternalName());
                    }
                } else if ("targets".equals(key)) {
                    if (annotation.values.get(i + 1) instanceof String name) {
                        targets.add(name.replace('.', '/'));
                    } else if (annotation.values.get(i + 1) instanceof List<?> names) {
                        for (var name : names) {
                            if (name instanceof String s) {
                                targets.add(s.replace('.', '/'));
                            }
                        }
                    }
                }
            }
        }
        return targets;
    }

    private static List<AnnotationNode> allAnnotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var all = new ArrayList<AnnotationNode>();
        if (visible != null) {
            all.addAll(visible);
        }
        if (invisible != null) {
            all.addAll(invisible);
        }
        return all;
    }

    private static ClassNode readClass(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
