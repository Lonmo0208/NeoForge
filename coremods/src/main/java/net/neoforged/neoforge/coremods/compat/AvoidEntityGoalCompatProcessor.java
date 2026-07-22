/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.coremods.compat;

import java.util.Set;
import net.neoforged.neoforgespi.transformation.ClassProcessor;
import net.neoforged.neoforgespi.transformation.ClassProcessor.ComputeFlags;
import net.neoforged.neoforgespi.transformation.ClassProcessor.SelectionContext;
import net.neoforged.neoforgespi.transformation.ClassProcessor.TransformationContext;
import net.neoforged.neoforgespi.transformation.ClassProcessorIds;
import net.neoforged.neoforgespi.transformation.ProcessorName;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Runs BEFORE mixin to transform:
 * - {@code ServerLevel}: adds a bridge method returning {@code LivingEntity} for {@code getNearestEntity}
 * - {@code AvoidEntityGoal}: fixes the INVOKEVIRTUAL descriptor in {@code canUse()} to return {@code LivingEntity}
 * <p>
 * This processor must run before the mixin processor because AnvilCraft's {@code AvoidEntityGoalMixin}
 * uses MixinExtras EXPRESSION matching with a {@code @Definition} that specifies {@code LivingEntity}
 * as the return type of the method descriptor. Without this transformation, the bytecode returns
 * {@code Entity} (due to the generic bound change in 26w14a: {@code <T extends Entity & Targetable>}),
 * causing the expression matcher to fail with "Scanned 0 target(s)".
 */
public class AvoidEntityGoalCompatProcessor implements ClassProcessor {
    private static final String SERVER_LEVEL_CLASS = "net.minecraft.server.level.ServerLevel";
    private static final String AVOID_ENTITY_GOAL_CLASS = "net.minecraft.world.entity.ai.goal.AvoidEntityGoal";
    private static final String LIVING_ENTITY_DESC = "Lnet/minecraft/world/entity/LivingEntity;";

    @Override
    public ProcessorName name() {
        return new ProcessorName("neoforge.coremods", "avoid_entity_goal_compat");
    }

    @Override
    public Set<ProcessorName> runsBefore() {
        return Set.of(ClassProcessorIds.MIXIN);
    }

    @Override
    public boolean handlesClass(SelectionContext context) {
        if (context.empty()) return false;
        String className = context.type().getClassName();
        return SERVER_LEVEL_CLASS.equals(className) || AVOID_ENTITY_GOAL_CLASS.equals(className);
    }

    @Override
    public ComputeFlags processClass(TransformationContext context) {
        String className = context.type().getClassName();
        ClassNode classNode = context.node();

        if (SERVER_LEVEL_CLASS.equals(className)) {
            addBridgeToServerLevel(classNode);
        } else if (AVOID_ENTITY_GOAL_CLASS.equals(className)) {
            fixAvoidEntityGoalInvoke(classNode);
        }

        return ComputeFlags.SIMPLE_REWRITE;
    }

    /**
     * Adds a bridge method to ServerLevel that returns {@code LivingEntity}:
     * 
     * <pre>
     * public LivingEntity getNearestEntity(List entities, TargetingConditions conditions, LivingEntity source, double x, double y, double z) {
     *     return (LivingEntity) ServerEntityGetter.super.getNearestEntity(entities, conditions, source, x, y, z);
     * }
     * </pre>
     * 
     * This method has the same erased parameter types as the generic interface method but returns
     * {@code LivingEntity} directly. When code calls {@code getServerLevel(mob).getNearestEntity(...)},
     * the compiler generates INVOKEVIRTUAL with {@code LivingEntity} return type.
     */
    private void addBridgeToServerLevel(ClassNode classNode) {
        // Check if the bridge already exists
        for (MethodNode method : classNode.methods) {
            if ("getNearestEntity".equals(method.name) && method.desc.startsWith("(Ljava/util/List;") && method.desc.endsWith(LIVING_ENTITY_DESC)) {
                return; // Already added
            }
        }

        // Build: LivingEntity getNearestEntity(List, TargetingConditions, LivingEntity, double, double, double)
        String bridgeDesc = "(Ljava/util/List;Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"
                + "Lnet/minecraft/world/entity/LivingEntity;DDD)" + LIVING_ENTITY_DESC;

        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "getNearestEntity",
                bridgeDesc,
                null, // no generic signature
                null  // no checked exceptions
        );

        bridge.instructions = new InsnList();
        // Load 'this'
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // Load all 6 parameters
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); // List entities
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2)); // TargetingConditions
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3)); // LivingEntity source
        bridge.instructions.add(new VarInsnNode(Opcodes.DLOAD, 4)); // double x
        bridge.instructions.add(new VarInsnNode(Opcodes.DLOAD, 6)); // double y
        bridge.instructions.add(new VarInsnNode(Opcodes.DLOAD, 8)); // double z
        // INVOKESPECIAL ServerEntityGetter.super.getNearestEntity
        bridge.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/server/level/ServerEntityGetter",
                "getNearestEntity",
                "(Ljava/util/List;Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;"
                        + "Lnet/minecraft/world/entity/LivingEntity;DDD)"
                        + "Lnet/minecraft/world/entity/Entity;",
                true // itf = true since ServerEntityGetter is an interface
        ));
        // CHECKCAST LivingEntity
        bridge.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/entity/LivingEntity"));
        // ARETURN
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));

        // Set maxStack and maxLocals
        bridge.maxStack = 10;
        bridge.maxLocals = 10;

        classNode.methods.add(bridge);
    }

    /**
     * Fixes the INVOKEVIRTUAL descriptor in AvoidEntityGoal.canUse().
     * The call to {@code ServerLevel.getNearestEntity} has a descriptor that returns
     * {@code Entity}. We change the return type in the descriptor to {@code LivingEntity}.
     */
    private void fixAvoidEntityGoalInvoke(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if ("canUse".equals(method.name) && "()Z".equals(method.desc)) {
                boolean modified = false;
                var it = method.instructions.iterator();
                while (it.hasNext()) {
                    var insn = it.next();
                    if (insn instanceof MethodInsnNode methodInsn) {
                        // Look for INVOKEVIRTUAL ServerLevel.getNearestEntity 
                        if (methodInsn.getOpcode() == Opcodes.INVOKEVIRTUAL
                                && "getNearestEntity".equals(methodInsn.name)
                                // Check that we're calling on ServerLevel and the descriptor returns Entity
                                && methodInsn.owner.equals("net/minecraft/server/level/ServerLevel")
                                && methodInsn.desc.endsWith(")Lnet/minecraft/world/entity/Entity;")) {
                            // Change return type in descriptor from Entity to LivingEntity
                            String oldDesc = methodInsn.desc;
                            String newDesc = oldDesc.substring(0, oldDesc.lastIndexOf(')') + 1) + LIVING_ENTITY_DESC;
                            methodInsn.desc = newDesc;
                            modified = true;
                        }
                    }
                }
                if (modified) {
                    break;
                }
            }
        }
    }
}
