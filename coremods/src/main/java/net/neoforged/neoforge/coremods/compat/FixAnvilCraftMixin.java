/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.coremods.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.neoforged.neoforgespi.transformation.ProcessorName;
import net.neoforged.neoforgespi.transformation.SimpleClassProcessor;
import net.neoforged.neoforgespi.transformation.SimpleTransformationContext;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Fixes the {@code @Inject(method = "execute")} annotations in AnvilCraft's
 * {@code DefaultDispenseItemBehaviorMixin} to include the full method descriptor,
 * eliminating ambiguity when both {@code execute(DispenseSource, ItemStack)} and
 * {@code execute(BlockSource, ItemStack)} exist in the target class.
 * <p>
 * Without this fix, Mixin cannot determine which method to inject into because
 * "execute" matches two methods, and the {@code @At(INVOKE, target = "ItemStack.split(I)")}
 * causes the entire injection to fail with "Scanned 0 target(s)".
 * <p>
 * By qualifying the method reference with the full descriptor, Mixin targets only
 * {@code execute(BlockSource, ItemStack)}, and the injection point matches the
 * {@code stack.copy().split(1)} call provided by the bridge method.
 */
public class FixAnvilCraftMixin extends SimpleClassProcessor {
    private static final String TARGET_CLASS = "dev.dubhe.anvilcraft.mixin.DefaultDispenseItemBehaviorMixin";
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String EXECUTE_BLOCKSOURCE_DESC = "execute(Lnet/minecraft/core/dispenser/BlockSource;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;";

    @Override
    public ProcessorName name() {
        return new ProcessorName("neoforge.coremods", "fix_anvilcraft_mixin");
    }

    @Override
    public Set<Target> targets() {
        return Set.of(new Target(TARGET_CLASS));
    }

    @Override
    public void transform(ClassNode input, SimpleTransformationContext context) {
        if (input.methods == null) return;
        for (var method : input.methods) {
            if (method.visibleAnnotations == null) continue;
            for (var annotation : method.visibleAnnotations) {
                if (INJECT_DESC.equals(annotation.desc)) {
                    fixInjectAnnotation(annotation);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void fixInjectAnnotation(AnnotationNode annotation) {
        if (annotation.values == null) return;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (!"method".equals(annotation.values.get(i))) continue;
            Object value = annotation.values.get(i + 1);
            if (value instanceof List<?> methods) {
                List<String> newMethods = new ArrayList<>();
                boolean changed = false;
                for (Object m : methods) {
                    String methodStr = (String) m;
                    if ("execute".equals(methodStr)) {
                        newMethods.add(EXECUTE_BLOCKSOURCE_DESC);
                        changed = true;
                    } else {
                        newMethods.add(methodStr);
                    }
                }
                if (changed) {
                    annotation.values.set(i + 1, newMethods);
                }
            }
        }
    }
}
