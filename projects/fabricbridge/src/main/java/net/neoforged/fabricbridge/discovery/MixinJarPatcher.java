/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.fml.jarcontents.CompositeJarContents;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fabricbridge.transform.MixinHandlerDescriptorFixer;
import net.neoforged.fabricbridge.transform.NeoFobricClassTweakerProcessor;

/**
 * Applies {@link MixinHandlerDescriptorFixer} to the Mixin classes of a Fabric mod jar.
 *
 * <p>The rewrite has to reach the bytes sponge-mixin reads. FML's mixin service resolves those
 * through {@code ClassLoader.getResourceAsStream}, i.e. straight from the jar entry, and FML's
 * {@code FMLLoader#getBasePaths} only understands its own {@code JarContents} implementations - a
 * custom overlay makes FML abort at startup with "Don't know how to handle ...". Patching the jar
 * entry is therefore the layer that actually works, and it is also what nested jars already get:
 * the locator extracts them into {@code .neofobric/processedMods} before the game layer is built.
 *
 * <p>The rewrite needs no marker to stay idempotent: once a handler's parameters match its
 * target's order, the fixer finds no permutation and reports no change, so re-running the bridge on
 * an already patched jar costs one read pass and rewrites nothing. If the target class cannot be
 * resolved the jar is left untouched, letting FML's own diagnostics report the mixin.
 */
public final class MixinJarPatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinJarPatcher.class);

    private MixinJarPatcher() {}

    /**
     * Method descriptors that Fabric API declares but NeoForge's Minecraft does not have, mapped to
     * the descriptor NeoForge actually uses.
     *
     * <p>Unlike handler parameter drift this cannot be derived from the target class: the descriptor
     * appears inside a Mixin <em>annotation</em>, and the annotation only has to name the method -
     * nothing checks it against the real signature. Add an entry only after checking the descriptor
     * against the version of the mod that is actually installed.
     *
     * <p>Current entry, verified against the installed fabric-api 0.161: NeoForge widened
     * {@code ClientPacketListener.startWaitingForNewLevel} with two {@code ResourceKey} parameters
     * (the level-loading screen shows the dimension being entered), and the call site inside
     * {@code handleRespawn} uses the widened form. fabric-data-attachment-api-v1's client mixin
     * anchors an {@code @Slice} on the three-argument form, so with the stale descriptor the whole
     * {@code @WrapOperation} finds no instructions ("did not match any instructions") and the client
     * silently stops copying data attachments across a respawn. The three-argument overload still
     * exists as a deprecated shim, which is exactly why this only shows up as a silent skip.
     *
     * <p>Keys are the parenthesized parameter lists only (no return type) - {@link #replacementFor}
     * splits the descriptor at the parentheses and keeps the original return type.
     *
     * <p>History: the two entries this used to hold were written for an older fabric-registry-sync
     * whose annotations still declared pre-26.2 descriptors. Rewriting those was harmful - the wrap
     * started targeting the wrong call and every world load died with
     * {@code NoSuchElementException: ScopedValue not bound}. The matching {@code RegistryDataLoader}
     * overloads were restored to their vanilla signatures instead, so those annotations resolve as
     * written and the entries could be dropped.
     */
    private static final Map<String, String> ANNOTATION_TARGET_REALIGNMENTS = Map.of(
            "(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;)",
            "(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/ResourceKey;)");

    /**
     * Moves Fabric's common-version channel off {@code c:version}.
     *
     * <p>Fabric and NeoForge both run a configuration-phase version handshake. NeoForge uses
     * {@code minecraft:version} while Fabric uses {@code c:version}, and both sides resolve a payload
     * purely by id - so when NeoForge's {@code CommonVersionPayload} reaches Fabric's registered codec
     * the cast fails with {@code ClassCastException: neoforged...CommonVersionPayload cannot be cast to
     * fabric...CommonVersionPayload}, which aborts the connection before a world can load.
     *
     * <p>Renaming the literal keeps Fabric's handshake working and makes the two protocols disjoint:
     * both endpoints of this game are bridged, so the new id is consistent, and a mod that talks to a
     * pure Fabric server is unaffected because retransforming only happens here.
     *
     * @return the class bytes with the literal replaced, or the original when it does not contain it
     */
    /**
     * Fabric channels that collide with NeoForge's own configuration-phase protocol.
     *
     * <p>Both loaders run a handshake before play begins and resolve a payload purely by id, so when
     * NeoForge's payload reaches Fabric's registered codec the cast fails with
     * {@code ClassCastException: neoforged...X cannot be cast to fabric...X} - which aborts the
     * connection in the configuration phase, so no world can load, singleplayer included. NeoForge also
     * means to own this handshake, so moving Fabric's channels aside keeps both intact and makes the
     * protocols disjoint. Both endpoints here are bridged, so the new ids stay consistent with each
     * other.
     */
    private static final Map<String, Map<String, String>> FABRIC_CHANNEL_RENAMES = Map.of(
            "net/fabricmc/fabric/impl/networking/CommonVersionPayload.class",
            Map.of("c:version", "neofobric:version"),
            "net/fabricmc/fabric/impl/networking/CommonRegisterPayload.class",
            Map.of("c:register", "neofobric:register"));

    /**
     * Fabric's creative tab paging API is implemented by a client mixin that adds its own page arrows and
     * its own tab visibility filter next to the ones the host loader already has. That mixin is not
     * registered here (see {@code FabricBootstrapProcessor.DEFAULT_SKIPPED_MIXIN_CONFIGS}) - one page
     * switcher is enough - which would leave every method of this interface throwing
     * {@code AssertionError("Implemented by mixin")} for Fabric mods that use the API.
     *
     * <p>The interface bodies are rewritten to route onto the page state the loader keeps on the creative
     * screen instead (the {@code getCreativeTabPage*} methods of
     * {@code net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen}), so both APIs
     * describe the same pages: whatever the Fabric API reports or switches is what the screen shows.
     */
    private static final String CREATIVE_TAB_SCREEN_INTERFACE =
            "net/fabricmc/fabric/api/client/creativetab/v1/FabricCreativeModeInventoryScreen.class";

    /** Fabric interface method -> the screen method that answers it. */
    private static final Map<String, String> CREATIVE_TAB_DELEGATES = Map.of(
            "switchToPage(I)Z", "switchToCreativeTabPage(I)Z",
            "getCurrentPage()I", "getCreativeTabPageIndex()I",
            "getPageCount()I", "getCreativeTabPageCount()I",
            "getTabsOnPage(I)Ljava/util/List;", "getCreativeTabPageTabs(I)Ljava/util/List;",
            "getPage(Lnet/minecraft/world/item/CreativeModeTab;)I", "getCreativeTabPageOf(Lnet/minecraft/world/item/CreativeModeTab;)I",
            "hasAdditionalPages()Z", "hasAdditionalCreativeTabPages()Z",
            "getSelectedTab()Lnet/minecraft/world/item/CreativeModeTab;", "getSelectedCreativeTab()Lnet/minecraft/world/item/CreativeModeTab;",
            "setSelectedTab(Lnet/minecraft/world/item/CreativeModeTab;)Z", "selectCreativeTab(Lnet/minecraft/world/item/CreativeModeTab;)Z");

    /**
     * @param classBytes the class bytes
     * @param entryName the jar entry path
     * @return the class bytes with the interface's default bodies delegating to the screen, or null when
     *         this is not the interface or nothing matched
     */
    private static byte[] bridgeCreativeTabApi(byte[] classBytes, String entryName) {
        if (!CREATIVE_TAB_SCREEN_INTERFACE.equals(entryName)) {
            return null;
        }
        try {
            var node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
            var changed = false;
            for (var method : node.methods) {
                var target = CREATIVE_TAB_DELEGATES.get(method.name + method.desc);
                if (target == null) {
                    continue;
                }
                var targetName = target.substring(0, target.indexOf('('));
                var targetDesc = target.substring(target.indexOf('('));
                var argumentTypes = Type.getArgumentTypes(method.desc);

                method.instructions.clear();
                method.tryCatchBlocks.clear();
                method.localVariables = null;
                method.visibleLocalVariableAnnotations = null;
                method.invisibleLocalVariableAnnotations = null;

                var stack = 1;
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                // The default body lives in the interface, so `this` is typed as the interface - the
                // call to the screen needs the cast spelled out or the verifier rejects it.
                method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, CREATIVE_TAB_SCREEN_OWNER));
                for (var i = 0; i < argumentTypes.length; i++) {
                    method.instructions.add(new VarInsnNode(argumentTypes[i].getOpcode(Opcodes.ILOAD), 1 + i));
                    stack += argumentTypes[i].getSize();
                }
                method.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, CREATIVE_TAB_SCREEN_OWNER, targetName, targetDesc, false));
                method.instructions.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
                method.maxStack = Math.max(stack, 1 + Type.getReturnType(method.desc).getSize());
                method.maxLocals = 1 + java.util.Arrays.stream(argumentTypes).mapToInt(Type::getSize).sum();
                changed = true;
            }
            return changed ? writeClass(node) : null;
        } catch (Exception e) {
            LOGGER.warn("[NeoFobric] Failed to bridge the Fabric creative tab page API in {}", entryName, e);
            return null;
        }
    }

    private static final String CREATIVE_TAB_SCREEN_OWNER =
            "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";

    /**
     * @param bytes the class bytes
     * @param entryName the jar entry path
     * @return the class bytes with the channel literal replaced, or the original when it is not one of
     *         the affected classes
     */
    private static byte[] renameFabricVersionChannel(byte[] bytes, String entryName) {
        var renames = FABRIC_CHANNEL_RENAMES.get(entryName);
        if (renames == null) {
            return bytes;
        }
        try {
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            var changed = false;
            for (var method : node.methods) {
                for (var insn : method.instructions) {
                    if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc
                            && ldc.cst instanceof String text
                            && renames.containsKey(text)) {
                        ldc.cst = renames.get(text);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return bytes;
            }
            var writer = new ClassWriter(0);
            node.accept(writer);
            LOGGER.info("[NeoFobric] Moved Fabric channels {} in {}", renames.keySet(), entryName);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not move Fabric channels in {}", entryName, t);
            return bytes;
        }
    }

    /**
     * Rewrites stale method descriptors inside this class's Mixin annotations.
     *
     * <p>Idempotent: each entry is only rewritten from its old form, so a jar that was already
     * realigned produces no further change.
     *
     * @return the rewritten class bytes, or {@code null} when nothing matched
     */
    private static byte[] realignMixinTargets(byte[] bytes, String entryName) {
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
        } catch (Exception e) {
            return null;
        }

        var changed = false;
        for (var method : node.methods) {
            changed |= realignAnnotations(method.visibleAnnotations);
            changed |= realignAnnotations(method.invisibleAnnotations);
        }
        if (!changed) {
            return null;
        }

        var writer = new ClassWriter(0);
        node.accept(writer);
        LOGGER.info("[NeoFobric] Realigned stale Mixin annotation targets in {}", entryName);
        return writer.toByteArray();
    }

    private static boolean realignAnnotations(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        var changed = false;
        for (var annotation : annotations) {
            if (annotation.values == null) {
                continue;
            }
            for (var i = 1; i < annotation.values.size(); i += 2) {
                var value = annotation.values.get(i);
                if (value instanceof String text) {
                    var replaced = replacementFor(text);
                    if (replaced != null) {
                        annotation.values.set(i, replaced);
                        changed = true;
                    }
                } else if (value instanceof List<?> list) {
                    for (var j = 0; j < list.size(); j++) {
                        var element = list.get(j);
                        if (element instanceof String text) {
                            var replaced = replacementFor(text);
                            if (replaced != null) {
                                ((List<Object>) list).set(j, replaced);
                                changed = true;
                            }
                        } else if (element instanceof AnnotationNode nested) {
                            // Array-valued members hold nested annotations too: MixinExtras declares
                            // slice()/@Slice's from as arrays, so @WrapOperation(slice = @Slice(from =
                            // @At(target = ...))) reaches the target through a list, not a direct node.
                            changed |= realignAnnotations(java.util.List.of(nested));
                        }
                    }
                } else if (value instanceof AnnotationNode nested) {
                    // Targets also live in nested annotations - @Inject(at = @At(target = ...)), and
                    // @WrapOperation(..., slice = @Slice(from = @At(target = ...))) two levels down.
                    changed |= realignAnnotations(java.util.List.of(nested));
                }
            }
        }
        return changed;
    }

    /** Builds the corrected descriptor by swapping the old parameter list for the new one. */
    private static String replacementFor(String descriptor) {
        var open = descriptor.indexOf('(');
        var close = descriptor.indexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        var params = descriptor.substring(open, close + 1);
        var replacement = ANNOTATION_TARGET_REALIGNMENTS.get(params);
        if (replacement == null) {
            return null;
        }
        return descriptor.substring(0, open) + replacement + descriptor.substring(close + 1);
    }

    private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";

    /**
     * Widens constructor-injecting handlers to a constructor NeoForge gave extra parameters.
     *
     * <p>NeoForge's patches add record components - and therefore constructor parameters - to
     * Minecraft classes. A Fabric mixin injecting into the vanilla-signature constructor keeps
     * applying, because that constructor still exists, but when all construction flows through the
     * widened canonical constructor instead, the callback never runs: the mixin degrades into silent
     * no-op state (uninitialized {@code @Unique} fields) rather than an error. The case that
     * motivated this: sodium's {@code BakedQuadMixin} computes per-quad normal data in an
     * {@code @Inject} at the tail of {@code BakedQuad}'s vanilla constructor; NeoForge's record grew
     * {@code BakedNormals}/{@code BakedColors} components, {@code FaceBakery} constructs every quad
     * through the widened canonical constructor, and the uninitialized normal data killed the first
     * chunk mesh with a {@code NullPointerException} in sodium's quad encoder.
     *
     * <p>The rewrite fires only when the shape is unambiguous: the handler targets {@code <init>}
     * and its parameters (everything before the {@code CallbackInfo}) exactly match a constructor E
     * of the target whose body delegates to a wider constructor D of the same class - the
     * record-canonical shape {@code this(..., defaults)} - with D's leading parameters equal to E's
     * and the delegation as E's only same-class constructor call. The handler is then widened to D's
     * full parameter list and the injection retargeted to D. Because E delegates to D, every object
     * that used to pass through E now passes through D, so the callback keeps running for exactly
     * the constructions it used to cover. Appending the new parameters before the CallbackInfo
     * shifts the callback's slot (and any locals after it), so those slot references are renumbered
     * - the same treatment MixinHandlerDescriptorFixer applies to reordered slots.
     *
     * <p>This cannot be done at the NeoForge source level: a record's canonical constructor must
     * assign every component itself and cannot delegate to the vanilla-signature constructor, and
     * removing the added components would break NeoForge's own public API. Keeping the
     * vanilla-signature constructor on the construction path is therefore impossible, which makes
     * this bytecode rewrite the remaining option (strategy 3).
     *
     * <p>Idempotent: the rewrite only fires for a bare-name (or short-descriptor) annotation target
     * and rewrites it to the widened constructor's descriptor, which no longer matches.
     *
     * @return the rewritten class bytes, or {@code null} when nothing matched
     */
    private static byte[] extendConstructorInjectorHandlers(byte[] classBytes, String entryName,
            java.util.function.Function<String, byte[]> targetBytecode) {
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }

        var changed = false;
        for (var handler : node.methods) {
            if ((handler.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) {
                continue;
            }
            var inject = findAnnotation(handler, "Lorg/spongepowered/asm/mixin/injection/Inject;");
            if (inject == null) {
                continue;
            }
            var args = Type.getArgumentTypes(handler.desc);
            if (args.length < 2 || !args[args.length - 1].getClassName().equals(CALLBACK_INFO.replace('/', '.'))) {
                continue;
            }
            var targetParams = new Type[args.length - 1];
            System.arraycopy(args, 0, targetParams, 0, targetParams.length);
            if (targetParams.length == 0) {
                continue;
            }
            var shortDesc = Type.getMethodDescriptor(Type.VOID_TYPE, targetParams);
            var methodValue = singleMethodTarget(inject);
            if (methodValue == null || !(methodValue.equals("<init>") || methodValue.equals("<init>" + shortDesc))) {
                continue;
            }

            var targetClass = firstTargetClass(node, targetBytecode);
            if (targetClass == null) {
                continue;
            }
            var constructors = new HashMap<String, MethodNode>();
            for (var method : targetClass.methods) {
                if ("<init>".equals(method.name)) {
                    constructors.put(method.desc, method);
                }
            }
            var delegatee = delegatedConstructor(targetClass.name, constructors.get(shortDesc));
            if (delegatee == null || delegatee.desc.equals(shortDesc)) {
                continue;
            }
            var delegateeArgs = Type.getArgumentTypes(delegatee.desc);
            if (delegateeArgs.length <= targetParams.length) {
                continue;
            }
            for (var i = 0; i < targetParams.length; i++) {
                if (!delegateeArgs[i].equals(targetParams[i])) {
                    // The delegating constructor passes its arguments on in a different order or
                    // shape - widening would silently bind the handler to the wrong values.
                    delegatee = null;
                    break;
                }
            }
            if (delegatee == null) {
                continue;
            }

            widenHandler(handler, inject, delegatee, targetParams);
            LOGGER.info("[NeoFobric] Widened constructor-injection handler {}#{} from {} to the widened constructor {}{}",
                    node.name.replace('/', '.'), handler.name, shortDesc, node.name, delegatee.desc);
            changed = true;
        }

        if (!changed) {
            return null;
        }
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * {@return whether the handler declares a MixinExtras sugar parameter (like {@code @Local}) after
     * its callback parameter}
     */
    /**
     * Generates the bytecode of {@code BridgeModelDiscoveryMixin} - see the injection comment in
     * {@link #patchJar}. Written here because the bridge compiles without Minecraft on its classpath;
     * the generated class references only types that the game layer can see at runtime.
     */
    /**
     * Generates the bytecode of {@code BridgeModelDiscoveryMixin} - see the injection comment in
     * {@link #patchJar}. Written here because the bridge compiles without Minecraft on its classpath;
     * the generated class references only types that the game layer can see at runtime.
     *
     * <p>{@code createSide} picks which source of extra models the handler feeds into discovery: the
     * Fabric plugin dispatcher (generated into fabric-model-loading-api-v1's jar, whose classes it
     * references) or Create's registered partial models (generated into create's jar). Each variant
     * only ever lands in the jar whose classes it needs, so neither has to guard against the other
     * being absent - and, being straight-line code with a single loop, it needs none of the frame
     * computation tricks an exception-guarded body would.
     */
    private static byte[] generateBridgeModelDiscoveryMixin(boolean createSide) {
        var self = createSide
                ? "com/zurrtum/create/client/mixin/BridgeCreatePartialModelMixin"
                : "net/fabricmc/fabric/mixin/client/model/loading/BridgeModelDiscoveryMixin";
        var discovery = "net/minecraft/client/resources/model/ModelDiscovery";
        var cls = new ClassNode();
        cls.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, self, null, "java/lang/Object", null);
        // @Mixin is RetentionPolicy.CLASS: sponge-mixin reads it through Annotations.getInvisible,
        // i.e. from RuntimeInvisibleAnnotations. Writing it visible makes MixinInfo.readPriority throw
        // "missing an @Mixin annotation" and the whole config fails to prepare. @Inject below is
        // RUNTIME and must stay visible.
        var mixin = cls.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        mixin.visit("value", java.util.List.of(org.objectweb.asm.Type.getObjectType(discovery)));
        mixin.visitEnd();

        var ctor = cls.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        var handler = new MethodNode(Opcodes.ACC_PRIVATE,
                createSide ? "neofobric$feedCreatePartials" : "neofobric$feedExtraModels",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
        cls.methods.add(handler);
        var inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        inject.visit("method", java.util.List.of("resolve"));
        var at = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = java.util.List.of("value", "HEAD");
        inject.visit("at", at);
        inject.visitEnd();

        var loop = new org.objectweb.asm.tree.LabelNode();
        var done = new org.objectweb.asm.tree.LabelNode();
        var insns = handler.instructions;

        if (createSide) {
            // for (Identifier id : PartialModelEventHandler.getRegisterAdditional().keySet())
            //     ((ModelDiscovery) (Object) this).getOrCreateModel(id);
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/zurrtum/create/client/flywheel/lib/model/baked/PartialModelEventHandler", "getRegisterAdditional",
                    "()Ljava/util/Map;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "keySet", "()Ljava/util/Set;", true));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true));
        } else {
            // ModelLoadingEventDispatcher dispatcher = ModelLoadingEventDispatcher.CURRENT.get();
            // if (dispatcher == null) return;
            // for (UnbakedExtraModel<?> model : dispatcher.getExtraModels().values())
            //     ((ModelDiscovery) (Object) this).addRoot(model);
            insns.add(new FieldInsnNode(Opcodes.GETSTATIC,
                    "net/fabricmc/fabric/impl/client/model/loading/ModelLoadingEventDispatcher", "CURRENT",
                    "Ljava/lang/ThreadLocal;"));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false));
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/fabricmc/fabric/impl/client/model/loading/ModelLoadingEventDispatcher"));
            insns.add(new VarInsnNode(Opcodes.ASTORE, 1));
            insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
            insns.add(new JumpInsnNode(Opcodes.IFNULL, done));
            insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "net/fabricmc/fabric/impl/client/model/loading/ModelLoadingEventDispatcher", "getExtraModels",
                    "()Ljava/util/Map;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "values", "()Ljava/util/Collection;", true));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true));
        }
        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, done));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, discovery));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        if (createSide) {
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/resources/Identifier"));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, discovery, "getOrCreateModel",
                    "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/resources/model/ModelDiscovery$ModelWrapper;",
                    false));
            insns.add(new InsnNode(Opcodes.POP));
        } else {
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/client/resources/model/ResolvableModel"));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, discovery, "addRoot",
                    "(Lnet/minecraft/client/resources/model/ResolvableModel;)V", false));
        }
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(done);
        insns.add(new InsnNode(Opcodes.RETURN));
        handler.maxLocals = 3;
        handler.maxStack = 3;
        cls.visitEnd();

        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                if (type1.equals(type2)) {
                    return type1;
                }
                // Never load classes here: this runs on the bootstrap layer, outside the game layer.
                return "java/lang/Object";
            }
        };
        cls.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Mixin injector handlers whose {@code @At(value = "RETURN", ordinal = N)} selector points past
     * the end of a target method NeoForge patched into a different shape.
     *
     * <p>Vanilla's {@code KeyboardHandler.keyPress} has six RETURNs and Create's
     * {@code KeyboardHandlerMixin} injects at the sixth (ordinal 5) - the method's final return.
     * NeoForge's own patch restructured that method down to three RETURNs, so the ordinal is out of
     * range and the injection is silently skipped (the bridge relaxes requires): key <em>presses</em>
     * still reach Create - its press handler uses {@code @At("TAIL")} - but key <em>releases</em> never
     * do, which leaves every hold-and-release interaction dead. The schematic tool menu is exactly
     * that: the key opens the menu on press and the release is what focuses/commits the selection, so
     * with the release handler missing the whole blueprint key set looks unresponsive.
     *
     * <p>Retargeting the selector to {@code TAIL} is the exact vanilla placement: TAIL injects before
     * the method's last RETURN, and the ordinal-5 return <em>was</em> the last RETURN in vanilla. The
     * table is explicit because "which return the ordinal meant" is not derivable from the patched
     * class - a middle-return ordinal would need a different anchor.
     */
    private static final Map<String, java.util.Set<String>> STALE_RETURN_ORDINAL_HANDLERS = Map.of(
            "com/zurrtum/create/client/mixin/KeyboardHandlerMixin", java.util.Set.of("onKeyReleased"));

    /**
     * Rewrites {@code @At(value = "RETURN", ordinal = N)} to {@code @At("TAIL")} on the handlers named
     * by {@link #STALE_RETURN_ORDINAL_HANDLERS}.
     */
    private static byte[] retargetStaleReturnOrdinals(byte[] classBytes, String entryName) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        var internalName = entryName.substring(0, entryName.length() - ".class".length());
        var handlers = STALE_RETURN_ORDINAL_HANDLERS.get(internalName);
        if (handlers == null) {
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
            if (!handlers.contains(method.name)) {
                continue;
            }
            for (var annotations : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (annotations == null) {
                    continue;
                }
                for (var annotation : annotations) {
                    if (annotation.values == null) {
                        continue;
                    }
                    for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
                        if (!"at".equals(annotation.values.get(i))) {
                            continue;
                        }
                        var atValue = annotation.values.get(i + 1);
                        var atNodes = atValue instanceof java.util.List<?> list
                                ? list.stream().filter(org.objectweb.asm.tree.AnnotationNode.class::isInstance)
                                        .map(org.objectweb.asm.tree.AnnotationNode.class::cast).toList()
                                : atValue instanceof org.objectweb.asm.tree.AnnotationNode single ? java.util.List.of(single) : java.util.List.<org.objectweb.asm.tree.AnnotationNode>of();
                        for (var at : atNodes) {
                            if (at.values == null) {
                                continue;
                            }
                            for (var j = 0; j + 1 < at.values.size(); j += 2) {
                                if (!"value".equals(at.values.get(j)) || !"RETURN".equals(at.values.get(j + 1))) {
                                    continue;
                                }
                                at.values.set(j + 1, "TAIL");
                                for (var k = 0; k + 1 < at.values.size(); k += 2) {
                                    if ("ordinal".equals(at.values.get(k))) {
                                        at.values.remove(k);
                                        at.values.remove(k);
                                        break;
                                    }
                                }
                                LOGGER.info("[NeoFobric] Retargeted @At(RETURN, ordinal) to @At(TAIL) on {}#{} - NeoForge's patch changed the target's return count",
                                        internalName.replace('/', '.'), method.name);
                                changed = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return changed ? writeClass(node) : null;
    }

    private static boolean hasTrailingSugar(MethodNode handler, int cirIndex) {
        var sugarPrefix = "Lcom/llamalad7/mixinextras/sugar/";
        for (var lists : java.util.Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
            if (lists == null) {
                continue;
            }
            for (var i = cirIndex + 1; i < lists.length && i < cirIndex + 9; i++) {
                var list = lists[i];
                if (list == null) {
                    continue;
                }
                for (var annotation : list) {
                    if (annotation.desc.startsWith(sugarPrefix)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Moves parameter annotations at or after {@code fromIndex} right by {@code inserted}, matching a
     * descriptor that grew by {@code inserted} parameters in that range.
     *
     * @return the shifted array ({@code null} when there was nothing to shift)
     */
    private static java.util.List<org.objectweb.asm.tree.AnnotationNode>[] shiftParameterAnnotations(
            java.util.List<org.objectweb.asm.tree.AnnotationNode>[] annotations, int fromIndex, int inserted) {
        if (annotations == null || inserted == 0) {
            return annotations;
        }
        @SuppressWarnings("unchecked")
        java.util.List<org.objectweb.asm.tree.AnnotationNode>[] shifted =
                new java.util.List[annotations.length + inserted];
        for (var i = 0; i < annotations.length; i++) {
            shifted[i < fromIndex ? i : i + inserted] = annotations[i];
        }
        return shifted;
    }

    /**
     * Appends the target's trailing parameters to an {@code @Inject} handler that declared only a
     * prefix of them.
     *
     * <p>NeoForge keeps vanilla-shaped convenience shims next to the methods it widened (the
     * deprecated 3-argument {@code ModelManager.discoverModelDependencies} next to the real 4-argument
     * one). A Fabric mixin selecting that method by bare name then binds ambiguously and - worse -
     * once the shim is gone, an {@code @Inject} handler declaring only the vanilla 3-parameter prefix
     * cannot bind the 4-parameter target at all: "Invalid descriptor ... Expected (..., extra, CIR)
     * but found (..., CIR)". Both fabric-model-loading-api-v1's {@code resolveExtraModels} and
     * Create's partial-model collector died this way, and every extra model they register went
     * undiscovered (Create's moving parts drew the missing-model checkerboard).
     *
     * <p>The handler body never touches the parameters it did not declare, so appending them to the
     * descriptor and moving every local slot at or above the callback up by the added width is enough;
     * same technique as {@link #widenHandler}. The annotation's {@code method} selector is pinned to
     * the exact descriptor so the handler can only bind the widened method.
     */
    private static byte[] extendInjectorHandlerParams(byte[] classBytes, String entryName,
            java.util.function.Function<String, byte[]> targetBytecode) {
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }
        var targetClass = firstTargetClass(node, targetBytecode);
        if (targetClass == null) {
            return null;
        }

        var changed = false;
        for (var handler : node.methods) {
            var inject = findAnnotation(handler, "Lorg/spongepowered/asm/mixin/injection/Inject;");
            if (inject == null) {
                continue;
            }
            var args = Type.getArgumentTypes(handler.desc);
            var callbackClass = CALLBACK_INFO.replace('/', '.');
            var cirIndex = -1;
            for (var i = 0; i < args.length; i++) {
                var argClass = args[i].getClassName();
                if (argClass.equals(callbackClass) || argClass.equals(callbackClass + "Returnable")) {
                    cirIndex = i;
                    break;
                }
            }
            if (cirIndex <= 0) {
                continue;
            }
            var isStatic = (handler.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
            var prefix = new Type[cirIndex];
            System.arraycopy(args, 0, prefix, 0, cirIndex);
            if (prefix.length == 0) {
                continue;
            }
            // Handlers carrying MixinExtras sugar (@Local) after the callback are left alone: appending
            // a parameter makes the sugar engine's wrapper machinery fail (it reads sugar generics from
            // the handler signature and falls back to the raw injector, which then rejects the sugar
            // parameter), and a half-processed class breaks other handlers in it. Fabric mods needing
            // those get the value fed by the bridge's own sugar-free mixin instead
            // (BridgeModelDiscoveryMixin, generated into fabric-model-loading-api-v1's realigned jar).
            if (hasTrailingSugar(handler, cirIndex)) {
                continue;
            }

            // The target(s): named methods whose parameters start with exactly this prefix. A prefix
            // that equals a whole target (the vanilla shim) has nothing to append to and is ignored;
            // only one longer match may exist.
            var name = singleMethodTarget(inject);
            if (name == null || name.contains("(")) {
                continue;
            }
            MethodNode longerTarget = null;
            var ambiguous = false;
            for (var target : targetClass.methods) {
                if (!target.name.equals(name)) {
                    continue;
                }
                var targetArgs = Type.getArgumentTypes(target.desc);
                if (targetArgs.length < prefix.length) {
                    continue;
                }
                var matches = true;
                for (var i = 0; i < prefix.length; i++) {
                    if (!targetArgs[i].equals(prefix[i])) {
                        matches = false;
                        break;
                    }
                }
                if (!matches) {
                    continue;
                }
                if (targetArgs.length == prefix.length) {
                    continue;
                }
                if (longerTarget != null) {
                    ambiguous = true;
                    break;
                }
                longerTarget = target;
            }
            if (ambiguous || longerTarget == null) {
                continue;
            }

            var targetArgs = Type.getArgumentTypes(longerTarget.desc);
            var missing = new Type[targetArgs.length - prefix.length];
            System.arraycopy(targetArgs, prefix.length, missing, 0, missing.length);
            var sugar = new Type[args.length - cirIndex - 1];
            System.arraycopy(args, cirIndex + 1, sugar, 0, sugar.length);
            var newArgs = new Type[prefix.length + missing.length + 1 + sugar.length];
            System.arraycopy(prefix, 0, newArgs, 0, prefix.length);
            System.arraycopy(missing, 0, newArgs, prefix.length, missing.length);
            newArgs[prefix.length + missing.length] = args[cirIndex];
            System.arraycopy(sugar, 0, newArgs, prefix.length + missing.length + 1, sugar.length);

            var baseSlot = isStatic ? 0 : 1;
            var oldCIRSlot = baseSlot;
            for (var i = 0; i < prefix.length; i++) {
                oldCIRSlot += prefix[i].getSize();
            }
            var addedSlots = 0;
            for (var missingType : missing) {
                addedSlots += missingType.getSize();
            }

            for (var insn : handler.instructions) {
                if (insn instanceof VarInsnNode varInsn && varInsn.var >= oldCIRSlot) {
                    varInsn.var += addedSlots;
                } else if (insn instanceof IincInsnNode iinc && iinc.var >= oldCIRSlot) {
                    iinc.var += addedSlots;
                }
            }
            if (handler.localVariables != null) {
                for (var local : handler.localVariables) {
                    if (local.index >= oldCIRSlot) {
                        local.index += addedSlots;
                    }
                }
            }
            // Parameter annotations (MixinExtras' @Local sugar) are indexed by parameter: entries at or
            // after the callback move right with it, or the sugar would end up "on" the callback - and
            // MixinExtras rejects non-trailing sugared parameters outright.
            handler.visibleParameterAnnotations =
                    shiftParameterAnnotations(handler.visibleParameterAnnotations, cirIndex, missing.length);
            handler.invisibleParameterAnnotations =
                    shiftParameterAnnotations(handler.invisibleParameterAnnotations, cirIndex, missing.length);
            if (handler.parameters != null) {
                for (var i = 0; i < missing.length; i++) {
                    handler.parameters.add(cirIndex, new org.objectweb.asm.tree.ParameterNode(null, 0));
                }
            }
            // The generic signature still describes the original parameter list; a stale one makes
            // MixinExtras' sugar handling fail (it reads the sugar generics from it) and fall back to
            // the raw injector, which then dies on the sugar parameter it does not know about.
            handler.signature = null;
            handler.maxLocals += addedSlots;
            handler.desc = Type.getMethodDescriptor(Type.getReturnType(handler.desc), newArgs);

            for (var i = 0; i + 1 < inject.values.size(); i += 2) {
                if ("method".equals(inject.values.get(i))) {
                    inject.values.set(i + 1, name + longerTarget.desc);
                }
            }
            LOGGER.info("[NeoFobric] Appended {} missing trailing parameter(s) to @Inject handler {}#{} (target {}.{})",
                    missing.length, node.name.replace('/', '.'), handler.name,
                    targetClass.name.replace('/', '.'), name);
            changed = true;
        }

        if (!changed) {
            return null;
        }
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Rewrites the handler to cover {@code delegatee}'s parameters and retargets the annotation.
     */
    private static void widenHandler(MethodNode handler, AnnotationNode inject, MethodNode delegatee, Type[] targetParams) {
        var oldArgs = Type.getArgumentTypes(handler.desc);
        var delegateeArgs = Type.getArgumentTypes(delegatee.desc);
        var newArgs = new Type[delegateeArgs.length + 1];
        System.arraycopy(delegateeArgs, 0, newArgs, 0, delegateeArgs.length);
        newArgs[delegateeArgs.length] = oldArgs[oldArgs.length - 1];

        var oldCISlot = 1;
        for (var param : targetParams) {
            oldCISlot += param.getSize();
        }
        var addedSlots = 0;
        for (var i = targetParams.length; i < delegateeArgs.length; i++) {
            addedSlots += delegateeArgs[i].getSize();
        }

        // Everything the body addresses at or above the callback's old slot - the callback itself
        // and any local variables declared after it - moves up by the slots the new parameters take.
        for (var insn : handler.instructions) {
            if (insn instanceof VarInsnNode varInsn && varInsn.var >= oldCISlot) {
                varInsn.var += addedSlots;
            } else if (insn instanceof IincInsnNode iinc && iinc.var >= oldCISlot) {
                iinc.var += addedSlots;
            }
        }
        if (handler.localVariables != null) {
            for (var local : handler.localVariables) {
                if (local.index >= oldCISlot) {
                    local.index += addedSlots;
                }
            }
        }
        handler.maxLocals += addedSlots;
        handler.desc = Type.getMethodDescriptor(Type.VOID_TYPE, newArgs);

        // Target the widened constructor by descriptor: the vanilla-signature constructor still
        // exists and would now mismatch the widened handler, which Mixin reports as an error.
        for (var i = 0; i + 1 < inject.values.size(); i += 2) {
            if ("method".equals(inject.values.get(i))) {
                inject.values.set(i + 1, java.util.List.of("<init>" + delegatee.desc));
            }
        }
    }

    /**
     * {@return the first resolvable class the mixin targets, or {@code null}}
     */
    /**
     * Removes the merge-colliding members from an allowlisted duck-type mixin so the remainder can
     * still apply.
     *
     * <p>{@link net.neoforged.fabricbridge.transform.MixinConflictFilter} drops a mixin whose merged
     * member already exists on the target - necessary, because Mixin refuses the whole class
     * ("cannot overwrite method ... @Overwrite is required"). For a duck mixin that implements a
     * Fabric interface that wholesale drop is worse than the collision: the interface stays absent
     * (or its throwing defaults stay in place), and Fabric code calling it dies. When the mixin has
     * members that do <em>not</em> collide, stripping only the colliding ones lets Mixin hang the
     * interface on the target and merge the rest; the interface method whose member was stripped
     * resolves to the target's own implementation.
     *
     * <p>Gated to an explicit allowlist: whether a colliding member's semantics match the target's
     * own implementation is a per-mixin judgement the bytecode cannot make. Verified entry:
     * fabric-item-api-v1's {@code DataComponentMapBuilderMixin} - NeoForge's
     * {@code DataComponentMap.Builder#get} has the same erased descriptor and the same map lookup
     * the mixin writes, so losing the mixin's copy costs nothing, while
     * {@code getOrCreate}/{@code getOrEmpty}/{@code contains} still merge and satisfy
     * {@code FabricComponentMapBuilder}. Without this, {@code DefaultItemComponentTest} (and any
     * mod using {@code DefaultItemComponentEvents}) dies with
     * {@code AssertionError: Implemented in Mixin} when components are initialized.
     */
    private static final java.util.Set<String> STRIP_COLLIDING_MEMBER_MIXINS = java.util.Set.of(
            "net/fabricmc/fabric/mixin/item/DataComponentMapBuilderMixin");

    /**
     * Injector handlers that must never register, named per allowlisted mixin class.
     *
     * <p>{@code ModelManagerMixin} (fabric-model-loading-api-v1) implements Fabric's custom JSON model
     * formats as a <em>pair</em> of handlers: a {@code @Redirect} that cancels vanilla's model
     * deserialization and a {@code @ModifyArg} that substitutes Fabric's own deserializer. NeoForge
     * replaced the vanilla call with its own {@code UnbakedModelParser.parse}, so the cancel half
     * misses (silently, requires being relaxed) while the substitute half still applies - and then
     * Fabric's deserializer handles every vanilla model and fails on all of them: every block and item
     * draws black-and-magenta. NeoForge's parser covers the same ground its own way, so both handlers
     * go; the rest of the mixin keeps driving the plugin machinery Fabric mods need.
     */
    private static final java.util.Map<String, java.util.Set<String>> STRIP_HANDLER_METHODS = java.util.Map.of(
            "net/fabricmc/fabric/mixin/client/model/loading/ModelManagerMixin",
            java.util.Set.of("cancelVanillaDeserialize", "actuallyDeserializeModel"));

    /**
     * Removes the injector handlers named by {@link #STRIP_HANDLER_METHODS} from an allowlisted mixin
     * class. Handlers are registrations Mixin scans for, never code the target calls, so removing one
     * only means that injection does not happen.
     */
    private static byte[] stripListedInjectorHandlers(byte[] classBytes, String entryName) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        var internalName = entryName.substring(0, entryName.length() - ".class".length());
        var handlerNames = STRIP_HANDLER_METHODS.get(internalName);
        if (handlerNames == null) {
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }
        var removed = node.methods.stream()
                .filter(m -> handlerNames.contains(m.name))
                .map(m -> m.name + m.desc)
                .toList();
        if (removed.isEmpty()) {
            return null;
        }
        node.methods.removeIf(m -> handlerNames.contains(m.name));
        LOGGER.info("[NeoFobric] Removed {} injector handler(s) from {}: {}",
                removed.size(), internalName, removed);
        return writeClass(node);
    }

    public static byte[] stripCollidingDuckMembers(byte[] classBytes, String entryName,
            java.util.function.Function<String, byte[]> targetBytecode) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        var internalName = entryName.substring(0, entryName.length() - ".class".length());
        if (!STRIP_COLLIDING_MEMBER_MIXINS.contains(internalName)) {
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }
        var targets = readMixinTargets(node);
        if (targets.isEmpty()) {
            return null;
        }
        var candidates = new ArrayList<MethodNode>();
        for (var method : node.methods) {
            if (isMergeCandidate(method)) {
                candidates.add(method);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        var colliding = new ArrayList<MethodNode>();
        for (var method : candidates) {
            var key = method.name + method.desc;
            for (var target : targets) {
                var targetBytes = targetBytecode.apply(target);
                if (targetBytes == null) {
                    continue;
                }
                if (targetHasMethod(targetBytes, key)) {
                    colliding.add(method);
                    break;
                }
            }
        }
        if (colliding.isEmpty() || colliding.size() == candidates.size()) {
            // Nothing to strip, or nothing would survive - the wholesale drop stays the filter's job.
            return null;
        }
        node.methods.removeAll(colliding);
        var stripped = colliding.stream().map(m -> m.name + m.desc).toList();
        LOGGER.info("[NeoFobric] Stripped {} colliding member(s) from {} so the remaining duck methods can merge: {}",
                colliding.size(), internalName, stripped);
        return writeClass(node);
    }

    /**
     * {@return whether one of the target classes declares a member with the given name+descriptor}
     */
    private static boolean targetHasMethod(byte[] targetBytes, String nameAndDesc) {
        try {
            var node = new ClassNode();
            new ClassReader(targetBytes).accept(node, 0);
            for (var method : node.methods) {
                if ((method.name + method.desc).equals(nameAndDesc)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // Unresolvable target - treat as absent, the conflict filter catches real collisions.
        }
        return false;
    }

    /**
     * {@return whether the mixin method would be merged into the target} Same rule set as
     * {@code MixinConflictFilter.isCandidate}: private, constructor, {@code @Shadow},
     * {@code @Unique}, {@code @Accessor}/{@code @Invoker}, {@code @Overwrite} and injector-handler
     * members are not merged.
     */
    private static final java.util.Set<String> NOT_MERGED_ANNOTATIONS = java.util.Set.of(
            "Lorg/spongepowered/asm/mixin/Overwrite;",
            "Lorg/spongepowered/asm/mixin/Shadow;",
            "Lorg/spongepowered/asm/mixin/Unique;",
            "Lorg/spongepowered/asm/mixin/gen/Accessor;",
            "Lorg/spongepowered/asm/mixin/gen/Invoker;",
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;");

    private static boolean isMergeCandidate(MethodNode method) {
        if (method.name.equals("<init>") || method.name.equals("<clinit>")) {
            return false;
        }
        if (java.lang.reflect.Modifier.isPrivate(method.access & 0x7)) {
            return false;
        }
        for (var list : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (list == null) {
                continue;
            }
            for (var annotation : list) {
                if (NOT_MERGED_ANNOTATIONS.contains(annotation.desc)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ClassNode firstTargetClass(ClassNode mixin, java.util.function.Function<String, byte[]> targetBytecode) {
        for (var target : readMixinTargets(mixin)) {
            var bytes = targetBytecode.apply(target);
            if (bytes == null) {
                continue;
            }
            try {
                var targetNode = new ClassNode();
                // No SKIP_CODE: the delegating constructor's body is what identifies the widened
                // canonical constructor, so method bodies have to be read.
                new ClassReader(bytes).accept(targetNode, 0);
                return targetNode;
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * {@return the same-class constructor that {@code constructor} delegates to, or {@code null}}
     *
     * <p>A record-canonical delegation is the constructor call inside the body; a constructor that
     * contains several distinct same-class calls (or none) is not a convenience constructor and is
     * left alone.
     */
    private static MethodNode delegatedConstructor(String owner, MethodNode constructor) {
        if (constructor == null || constructor.instructions == null) {
            return null;
        }
        String delegatedDesc = null;
        for (var insn : constructor.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.name.equals("<init>")
                    && call.owner.equals(owner)) {
                if (delegatedDesc != null && !delegatedDesc.equals(call.desc)) {
                    return null;
                }
                delegatedDesc = call.desc;
            }
        }
        return delegatedDesc == null ? null : new MethodNode(constructor.access, "<init>", delegatedDesc, null, null);
    }

    /**
     * {@return the single {@code method} element of an injector annotation, or {@code null} when it
     *         is absent or has more than one entry}
     */
    private static String singleMethodTarget(AnnotationNode inject) {
        if (inject.values == null) {
            return null;
        }
        for (var i = 0; i + 1 < inject.values.size(); i += 2) {
            if ("method".equals(inject.values.get(i))) {
                var value = inject.values.get(i + 1);
                if (value instanceof String name) {
                    return name;
                }
                if (value instanceof List<?> list && list.size() == 1 && list.get(0) instanceof String name) {
                    return name;
                }
                return null;
            }
        }
        return null;
    }

    /** Suffix of the realigned copy that the game layer is pointed at instead of the original. */
    public static final String PATCHED_SUFFIX = ".neofobric-realigned.jar";

    /** Subdirectory of the mods folder the bridge keeps its generated copies in. */
    public static final String CACHE_DIR_NAME = ".neofobric";

    /**
     * {@return where the realigned copy of {@code jarPath} is written}
     *
     * <p>A jar sitting at the top level of the mods folder gets its copy under
     * {@code mods/.neofobric/} rather than next to the original. FML's own mods-folder locator lists
     * that folder - top level only, non-recursively - and read a sibling copy as a second mod, which
     * greeted the player with a red
     * "File mods/foo.jar.neofobric-realigned.jar is a Fabric mod and cannot be loaded" warning
     * ({@code IncompatibleModReason.FABRIC}) before the main menu. A subdirectory is not listed, so
     * the copies stay invisible to it. Nested jars already live in the bridge cache directory where
     * nothing scans them, so their copies stay next to them.
     */
    private static Path realignedPath(Path jarPath) {
        var fileName = jarPath.getFileName().toString();
        var parent = jarPath.toAbsolutePath().normalize().getParent();
        var modsDir = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
        if (modsDir.equals(parent)) {
            return modsDir.resolve(CACHE_DIR_NAME).resolve(fileName + PATCHED_SUFFIX);
        }
        return jarPath.resolveSibling(fileName + PATCHED_SUFFIX);
    }

    /**
     * Resolves Mixin target classes for one discovery pass, with the classpath jars held open and
     * results memoized.
     *
     * <p>Every jar patched in a pass resolves the same handful of hot targets (Minecraft,
     * LivingEntity, ...) repeatedly, and the access scan resolves hundreds of classes per jar. Doing
     * that with a fresh {@code JarContents.ofPath} per lookup meant reopening - and re-parsing the
     * central directory of - the same multi-megabyte game jar thousands of times, and a miss fell
     * back to walking the whole mods folder once per lookup. One resolver per pass turns all of that
     * into one open per jar plus a built-once index of the other Fabric jars.
     *
     * <p>Not thread-safe by itself in the sense of coordinate work, but every mutable field is a
     * concurrent collection, so it is safe to share if patching is ever parallelized.
     */
    public static final class TargetResolver implements java.util.function.Function<String, byte[]>, AutoCloseable {
        /** Sentinel for a memoized miss. */
        private static final byte[] MISS = new byte[0];
        private static final int MEMO_ENTRY_LIMIT = 512 * 1024;
        private static final long MEMO_BYTE_BUDGET = 64L * 1024 * 1024;

        private final java.util.List<Path> gameContent;
        private final java.util.List<Path> extraJars;
        private final java.util.Map<Path, JarContents> openJars = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, byte[]> memo = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong memoBytes = new java.util.concurrent.atomic.AtomicLong();
        private volatile java.util.Map<String, Path> extraIndex;

        /**
         * @param gameContent classpath entries (directories or jars) that expose the Minecraft classes
         * @param extraJars the Fabric jars of this pass, searched as a fallback for targets that live in
         *            another Fabric mod
         */
        public TargetResolver(java.util.List<Path> gameContent, java.util.Collection<Path> extraJars) {
            this.gameContent = java.util.List.copyOf(gameContent);
            this.extraJars = java.util.List.copyOf(extraJars);
        }

        @Override
        public byte[] apply(String internalName) {
            var cached = memo.get(internalName);
            if (cached != null) {
                return cached == MISS ? null : cached;
            }
            var bytes = load(internalName);
            if (bytes == null) {
                memo.put(internalName, MISS);
            } else if (bytes.length <= MEMO_ENTRY_LIMIT && memoBytes.get() < MEMO_BYTE_BUDGET) {
                memoBytes.addAndGet(bytes.length);
                memo.put(internalName, bytes);
            }
            return bytes;
        }

        private byte[] load(String internalName) {
            var path = internalName + ".class";
            for (var root : gameContent) {
                try {
                    if (Files.isDirectory(root)) {
                        var candidate = root.resolve(path);
                        if (Files.isRegularFile(candidate)) {
                            return Files.readAllBytes(candidate);
                        }
                    } else if (Files.isRegularFile(root)) {
                        var jar = open(root);
                        if (jar != null) {
                            var bytes = jar.readFile(path);
                            if (bytes != null) {
                                return bytes;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("[NeoFobric] Failed to read {} from {}", path, root, e);
                }
            }

            var owner = extraIndex().get(path);
            if (owner == null) {
                return null;
            }
            var jar = open(owner);
            if (jar == null) {
                return null;
            }
            try {
                return jar.readFile(path);
            } catch (Exception e) {
                return null;
            }
        }

        /** Opens a jar once and keeps it open for the rest of the pass. */
        private JarContents open(Path root) {
            var existing = openJars.get(root);
            if (existing != null) {
                return existing;
            }
            try {
                var created = JarContents.ofPath(root);
                var raced = openJars.putIfAbsent(root, created);
                if (raced != null) {
                    created.close();
                    return raced;
                }
                return created;
            } catch (Exception e) {
                LOGGER.debug("[NeoFobric] Failed to open {} while resolving Mixin targets", root, e);
                return null;
            }
        }

        /**
         * {@return every class entry of the other Fabric jars, indexed once}
         *
         * <p>This replaces the previous per-lookup walk of the mods folder, which opened and probed
         * every jar in it (nested jars included) for each class the game content did not have.
         */
        private java.util.Map<String, Path> extraIndex() {
            var index = extraIndex;
            if (index != null) {
                return index;
            }
            synchronized (this) {
                if (extraIndex != null) {
                    return extraIndex;
                }
                var built = new HashMap<String, Path>();
                for (var jarPath : extraJars) {
                    var jar = open(jarPath);
                    if (jar == null) {
                        continue;
                    }
                    try {
                        jar.visitContent((name, resource) -> {
                            if (name.endsWith(".class")) {
                                built.putIfAbsent(name, jarPath);
                            }
                        });
                    } catch (Throwable t) {
                        LOGGER.debug("[NeoFobric] Failed to index {} for Mixin targets", jarPath, t);
                    }
                }
                extraIndex = built;
                return built;
            }
        }

        @Override
        public void close() {
            for (var jar : openJars.values()) {
                try {
                    jar.close();
                } catch (Exception ignored) {
                    // Closing is best effort; the pass is over either way.
                }
            }
            openJars.clear();
        }
    }

    /**
     * @param jarPath the mod jar to inspect
     * @param gameContent classpath entries (directories or jars) that expose the Minecraft classes
     * @return the jar the game layer should load: the original when nothing changed, otherwise a
     *         realigned copy next to it
     */
    public static Path patchJar(Path jarPath, java.util.List<Path> gameContent) {
        try (var resolver = new TargetResolver(gameContent, java.util.List.of())) {
            return patchJar(jarPath, resolver, java.util.Map.of());
        }
    }

    /**
     * @param jarPath the mod jar to inspect
     * @param resolver the shared target resolver of this discovery pass
     * @return the jar the game layer should load: the original when nothing changed, otherwise a
     *         realigned copy next to it
     */
    public static Path patchJar(Path jarPath, TargetResolver resolver) {
        return patchJar(jarPath, resolver, java.util.Map.of());
    }

    /** Prefix of the names colliding accessor members are moved to. */
    static final String ACCESSOR_RENAME_PREFIX = "neofobric$";

    /**
     * Finds accessor/invoker members whose target class already declares a method with the same name and
     * descriptor, and plans a rename for each.
     *
     * <p>Mixin generates an {@code @Accessor} member in the target class and refuses the merge when the
     * target already declares one with that name and descriptor - NeoForge adds methods pure Fabric
     * Minecraft does not have ({@code ServerCommonPacketListenerImpl#getConnection()}), so
     * fabric-networking-api-v1's and fabric-recipe-api-v1's accessors failed with "cannot overwrite
     * method ... because @Overwrite is required" and the interface Fabric casts to was never applied.
     *
     * <p>The rename is Sinytra Connector's answer to the same collision (its
     * {@code AccessorRedirectTransformer}): the member is moved to a unique name so Mixin can generate
     * it, and every call site is moved with it, so both sides keep working. Deleting the annotation
     * instead was tried here and is not an option: Mixin only lets a mixin class be loaded directly
     * while its SubType is ACCESSOR, and Fabric's API code casts to these interfaces.
     *
     * <p>Run before any jar is patched: a call site can live in a different jar than the accessor.
     *
     * @return mixin class internal name to (original {@code name+descriptor} to new name)
     */
    public static java.util.Map<String, java.util.Map<String, String>> scanAccessorCollisions(
            java.util.Collection<Path> fabricJars, TargetResolver resolver) {
        if (Boolean.getBoolean("neofobric.noAccessorRenames")) {
            return java.util.Map.of();
        }
        var renames = new HashMap<String, java.util.Map<String, String>>();
        for (var jarPath : fabricJars) {
            try (var zip = new ZipFile(jarPath.toFile())) {
                var it = zip.entries();
                while (it.hasMoreElements()) {
                    var entry = it.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")
                            || !looksLikeMixinClass(entry.getName())) {
                        continue;
                    }
                    ClassNode node;
                    try {
                        node = new ClassNode();
                        new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, 0);
                    } catch (Throwable t) {
                        continue;
                    }
                    if ((node.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) == 0) {
                        continue;
                    }
                    var plan = planAccessorRenames(node, resolver);
                    if (!plan.isEmpty()) {
                        renames.put(node.name, plan);
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("[NeoFobric] Failed to scan {} for colliding accessors", jarPath, e);
            }
        }
        if (!renames.isEmpty()) {
            LOGGER.info("[NeoFobric] Will rename {} colliding accessor member(s) across {} mixin class(es)",
                    renames.values().stream().mapToInt(java.util.Map::size).sum(), renames.size());
        }
        return renames;
    }

    /**
     * {@return the renames needed for one accessor mixin: {@code name+descriptor} to a unique name}
     */
    private static java.util.Map<String, String> planAccessorRenames(ClassNode mixin, TargetResolver resolver) {
        var plan = new HashMap<String, String>();
        var targets = readMixinTargets(mixin);
        if (targets.isEmpty()) {
            return plan;
        }
        for (var method : mixin.methods) {
            if (!isAccessorMember(method)) {
                continue;
            }
            for (var target : targets) {
                var targetBytes = resolver.apply(target);
                if (targetBytes == null) {
                    continue;
                }
                ClassNode targetNode;
                try {
                    targetNode = new ClassNode();
                    new ClassReader(targetBytes).accept(targetNode, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                } catch (Throwable t) {
                    continue;
                }
                if (targetDeclaresMethod(targetNode, method)) {
                    plan.put(method.name + method.desc, ACCESSOR_RENAME_PREFIX + method.name);
                    break;
                }
            }
        }
        return plan;
    }

    /**
     * Renames the planned accessor members and keeps Mixin able to find their member: the annotation's
     * value is written out explicitly first, because without one Mixin derives the member name from the
     * method name - which the rename is about to change.
     */
    private static boolean applyAccessorRenames(ClassNode mixin, java.util.Map<String, String> plan) {
        var changed = false;
        for (var method : mixin.methods) {
            var newName = plan.get(method.name + method.desc);
            if (newName == null) {
                continue;
            }
            var accessor = findAnnotation(method, "Lorg/spongepowered/asm/mixin/gen/Accessor;");
            var invoker = findAnnotation(method, "Lorg/spongepowered/asm/mixin/gen/Invoker;");
            var annotation = accessor != null ? accessor : invoker;
            if (annotation != null && !hasExplicitValue(annotation)) {
                var derived = derivedMemberName(method.name, accessor != null);
                if (derived != null) {
                    annotation.values = new ArrayList<>(List.of("value", derived));
                }
            }
            method.name = newName;
            changed = true;
        }
        if (changed) {
            LOGGER.info("[NeoFobric] Renamed colliding accessor member(s) {} in {}",
                    plan.keySet(), mixin.name.replace('/', '.'));
        }
        return changed;
    }

    private static boolean hasExplicitValue(AnnotationNode annotation) {
        if (annotation.values == null) {
            return false;
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if ("value".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String text
                    && !text.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return the member an accessor/invoker without an explicit value refers to} Mirrors Mixin's own
     * derivation: {@code getFoo}/{@code setFoo}/{@code isFoo} name the field {@code foo}, and
     * {@code callFoo} names the method {@code foo}.
     */
    private static String derivedMemberName(String methodName, boolean accessor) {
        if (!accessor) {
            return methodName.startsWith("call") && methodName.length() > 4 ? methodName.substring(4) : methodName;
        }
        for (var prefix : new String[] { "get", "set", "is" }) {
            if (methodName.length() > prefix.length() && methodName.startsWith(prefix)) {
                return Character.toLowerCase(methodName.charAt(prefix.length())) + methodName.substring(prefix.length() + 1);
            }
        }
        return methodName;
    }

    /**
     * Moves call sites of renamed accessor members to their new names.
     *
     * <p>A call site is an invocation whose owner is the accessor interface and whose name and
     * descriptor match the planned rename; both the declaration (in the mixin) and the calls have to
     * move together, which is what keeps Fabric's own code working.
     *
     * @return the rewritten class bytes, or {@code null} when nothing matched
     */
    private static byte[] rewriteAccessorCallSites(byte[] classBytes,
            java.util.Map<String, java.util.Map<String, String>> renames, String entryName) {
        if (renames.isEmpty()) {
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
            for (var insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                var plan = renames.get(call.owner);
                if (plan == null) {
                    continue;
                }
                var newName = plan.get(call.name + call.desc);
                if (newName != null) {
                    call.name = newName;
                    changed = true;
                }
            }
        }
        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Moved the call site(s) of a renamed accessor member in {}", entryName);
        return writeClass(node);
    }

    /**
     * Renames the colliding accessor members of one mixin class.
     *
     * @return the rewritten class bytes, or {@code null} when this class has no planned rename
     */
    private static byte[] renameCollidingAccessors(byte[] classBytes, String entryName,
            java.util.Map<String, java.util.Map<String, String>> renames) {
        if (renames.isEmpty()) {
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }
        var plan = renames.get(node.name);
        if (plan == null || !applyAccessorRenames(node, plan)) {
            return null;
        }
        return writeClass(node);
    }

    /**
     * @param jarPath the mod jar to inspect
     * @param resolver the shared target resolver of this discovery pass
     * @param accessorRenames the colliding-accessor renames of the whole pass, see
     *            {@link #scanAccessorCollisions}
     * @return the jar the game layer should load: the original when nothing changed, otherwise a
     *         realigned copy next to it
     */
    public static Path patchJar(Path jarPath, TargetResolver resolver,
            java.util.Map<String, java.util.Map<String, String>> accessorRenames) {
        if (Boolean.getBoolean("neofobric.noJarPatcher")) {
            return jarPath;
        }
        var patchedPath = realignedPath(jarPath);
        var entries = new ArrayList<String>();
        var patched = new HashMap<String, byte[]>();
        // Every class in this jar. Fabric reaches private Minecraft members from ordinary
        // implementation classes too (ModPackResourcesUtil calls PackMetadataSection.codecForPackType),
        // so the access scan has to look at all of them, not only the mixins.
        var allClasses = new ArrayList<byte[]>();
        // Mixin classes only, for the rewrites below.
        var mixinClasses = new ArrayList<byte[]>();
        try (var zip = new ZipFile(jarPath.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                var entry = it.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                entries.add(entry.getName());
                // The bridge's own sugar-free extra-model feed. Fabric's extra-model collector (and
                // Create's partial-model collector) target ModelManager.discoverModelDependencies by
                // bare name, which NeoForge's deprecated vanilla-shape overload makes ambiguous - the
                // injection silently matches neither overload, so every Fabric-registered extra model
                // goes undiscovered and dynamically rendered parts draw the missing-model checkerboard.
                // Rewriting their @Local-sugar handlers breaks MixinExtras' wrapper machinery, so the
                // job is done by one generated sugar-free mixin injected into this module instead: it
                // feeds the same sources (Create's registered partials, Fabric's plugin-registered
                // extra models) into discovery through ModelDiscovery.resolve's receiver, which needs
                // no local capture at all.
                if (jarPath.getFileName().toString().startsWith("fabric-model-loading-api-v1-")
                        && entry.getName().equals("fabric-model-loading-api-v1.mixins.json")) {
                    var configText = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    var mixinClassPath = "net/fabricmc/fabric/mixin/client/model/loading/BridgeModelDiscoveryMixin.class";
                    if (!configText.contains("BridgeModelDiscoveryMixin") && configText.contains("\"client\"")) {
                        var withEntry = configText.replaceFirst("(\"client\"\\s*:\\s*\\[)", "$1\n    \"BridgeModelDiscoveryMixin\",");
                        patched.put("fabric-model-loading-api-v1.mixins.json", withEntry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        entries.add(mixinClassPath);
                        patched.put(mixinClassPath, generateBridgeModelDiscoveryMixin(false));
                        LOGGER.info("[NeoFobric] Injected BridgeModelDiscoveryMixin into {}", jarPath.getFileName());
                    }
                }
                if (entry.getName().equals("create.client.mixins.json")) {
                    var configText = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    // Only Create Fly's own config (its mixin package is com.zurrtum.create.client.mixin).
                    if (configText.contains("\"package\": \"com.zurrtum.create.client.mixin\"")
                            && !configText.contains("BridgeCreatePartialModelMixin")) {
                        var withEntry = configText.replaceFirst("(\"client\"\\s*:\\s*\\[)", "$1\n    \"BridgeCreatePartialModelMixin\",");
                        patched.put(entry.getName(), withEntry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var mixinClassPath = "com/zurrtum/create/client/mixin/BridgeCreatePartialModelMixin.class";
                        entries.add(mixinClassPath);
                        patched.put(mixinClassPath, generateBridgeModelDiscoveryMixin(true));
                        LOGGER.info("[NeoFobric] Injected BridgeCreatePartialModelMixin into {}", jarPath.getFileName());
                    }
                }
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                var bytes = zip.getInputStream(entry).readAllBytes();
                var versionMoved = renameFabricVersionChannel(bytes, entry.getName());
                if (versionMoved != bytes) {
                    patched.put(entry.getName(), versionMoved);
                }
                allClasses.add(versionMoved);
                if (!looksLikeMixinClass(entry.getName())) {
                    // Not a mixin: the only rewrites it may need are a call site of a renamed accessor
                    // member (Fabric's own implementation classes do the casting) or a page API that is
                    // served by the host loader's creative screen.
                    var plain = versionMoved;
                    var bridgedPages = bridgeCreativeTabApi(plain, entry.getName());
                    if (bridgedPages != null) {
                        plain = bridgedPages;
                        patched.put(entry.getName(), plain);
                    }
                    if (!accessorRenames.isEmpty()) {
                        var withCalls = rewriteAccessorCallSites(plain, accessorRenames, entry.getName());
                        if (withCalls != null) {
                            patched.put(entry.getName(), withCalls);
                        }
                    }
                    continue;
                }
                // Feed the access scan every class, not just the mixins: Fabric also reaches private
                // Minecraft members from ordinary implementation classes (ModPackResourcesUtil calls
                // PackMetadataSection.codecForPackType), and those produced IllegalAccessError while a
                // save was being opened. Non-mixin classes are only inspected, never rewritten.
                mixinClasses.add(bytes);
                // Annotation targets first: MixinHandlerDescriptorFixer reads the target signature to
                // decide the parameter order, so the target has to be correct before it runs.
                var realigned = realignMixinTargets(bytes, entry.getName());
                var source = realigned != null ? realigned : bytes;
                var retargeted = retargetStaleReturnOrdinals(source, entry.getName());
                if (retargeted != null) {
                    source = retargeted;
                }
                var stripped = stripCollidingDuckMembers(source, entry.getName(), resolver);
                if (stripped != null) {
                    source = stripped;
                }
                var handlerStripped = stripListedInjectorHandlers(source, entry.getName());
                if (handlerStripped != null) {
                    source = handlerStripped;
                }
                var callSitesMoved = accessorRenames.isEmpty() ? null : rewriteAccessorCallSites(source, accessorRenames, entry.getName());
                if (callSitesMoved != null) {
                    source = callSitesMoved;
                }
                var renamed = renameCollidingAccessors(source, entry.getName(), accessorRenames);
                if (renamed != null) {
                    source = renamed;
                }
                var extended = extendConstructorInjectorHandlers(source, entry.getName(), resolver);
                if (extended != null) {
                    source = extended;
                }
                var appended = extendInjectorHandlerParams(source, entry.getName(), resolver);
                if (appended != null) {
                    source = appended;
                }
                byte[] fixed = null;
                if (!Boolean.getBoolean("neofobric.noHandlerFix")) {
                    fixed = MixinHandlerDescriptorFixer.fix(source, resolver);
                }
                // Off by default; the method itself carries the reasoning.
                byte[] trimmed = neutralizeProvidedAccessors(fixed != null ? fixed : source, entry.getName(), resolver);

                if (trimmed != null) {
                    patched.put(entry.getName(), trimmed);
                } else if (fixed != null) {
                    patched.put(entry.getName(), fixed);
                } else if (extended != null || appended != null) {
                    patched.put(entry.getName(), source);
                } else if (renamed != null) {
                    patched.put(entry.getName(), renamed);
                } else if (callSitesMoved != null) {
                    patched.put(entry.getName(), callSitesMoved);
                } else if (realigned != null || stripped != null || handlerStripped != null || retargeted != null) {
                    patched.put(entry.getName(), source);
                }
            }
        } catch (IOException e) {
            LOGGER.error("[NeoFobric] Failed to inspect {} for Mixin handler drift", jarPath, e);
            return jarPath;
        }

        // Report any member these mixins reach that this tree keeps private. Fabric covers those with
        // its own access wideners; NeoForge's access transformer covers a different set, and a gap
        // surfaces as an IllegalAccessError while the class loads. Scanning here means the whole set is
        // known after one startup instead of one member per attempt. Failures are never fatal - the
        // worst case is that a gap is reported later, at runtime, as before.
        try {
            var found = net.neoforged.fabricbridge.transform.MixinAccessScanner.findPrivateMembers(allClasses, resolver);
            var members = found.values().stream().mapToInt(java.util.Set::size).sum();
            LOGGER.info("[NeoFobric] Access scan of {}: {} classes ({}, mixins), {} class(es)/{} member(s) to widen",
                    jarPath.getFileName(), allClasses.size(), mixinClasses.size(), found.size(), members);
            if (!found.isEmpty()) {
                net.neoforged.fabricbridge.transform.NeoFobricClassTweakerProcessor.registerWidenedMembers(found);
            }
        } catch (Throwable t) {
            // A registration that fails halfway silently loses every owner the scan found after the
            // throw point - the game then dies with IllegalAccessError far away. Loud enough to see.
            LOGGER.error("[NeoFobric] Failed to register the access-scan findings of {}", jarPath, t);
        }

        if (patched.isEmpty()) {
            // Nothing to realign: drop a stale copy from an earlier run so the game layer goes back
            // to the untouched jar.
            deleteQuietly(patchedPath);
            return jarPath;
        }

        // The realigned copy is never written over the original: on Windows the mod jar may still be
        // held open, and replacing a file the game layer is about to read is not worth the risk. The
        // original stays byte-identical for the user, and the copy lives in the bridge's own cache
        // directory (see realignedPath).
        try {
            Files.createDirectories(patchedPath.getParent());
        } catch (IOException e) {
            LOGGER.error("[NeoFobric] Failed to create the realigned-copy directory for {}", jarPath, e);
            return jarPath;
        }
        try (var zip = new ZipFile(jarPath.toFile());
                var out = new ZipOutputStream(Files.newOutputStream(patchedPath))) {
            for (var name : entries) {
                var entry = zip.getEntry(name);
                var replacement = patched.get(name);
                var outEntry = new ZipEntry(name);
                if (entry != null && entry.getMethod() == ZipEntry.STORED && replacement == null) {
                    // Keep stored entries exactly as they are (size and CRC are mandatory then).
                    outEntry.setMethod(ZipEntry.STORED);
                    outEntry.setSize(entry.getSize());
                    outEntry.setCompressedSize(entry.getSize());
                    outEntry.setCrc(entry.getCrc());
                }
                out.putNextEntry(outEntry);
                if (replacement != null) {
                    out.write(replacement);
                } else if (entry != null) {
                    zip.getInputStream(entry).transferTo(out);
                }
                out.closeEntry();
            }
        } catch (IOException e) {
            LOGGER.error("[NeoFobric] Failed to write the realigned copy of {}", jarPath, e);
            deleteQuietly(patchedPath);
            return jarPath;
        }

        for (var name : patched.keySet()) {
            LOGGER.info("[NeoFobric] Realigned Mixin handler(s) in {} of {}", name, jarPath.getFileName());
        }
        return patchedPath;
    }

    /**
     * Scoped to fabric-loader's mixin packages: this pass runs while the game layer is built, so
     * Minecraft and library classes must be skipped without touching them.
     */
    private static boolean looksLikeMixinClass(String path) {
        if (!path.endsWith(".class")) {
            return false;
        }
        // Deliberately not restricted to net/fabricmc: third-party Fabric mods ship their mixins under
        // their own packages (sodium uses net/caffeinemc/...), and those need the same handling - both
        // the handler realignment and the access scan, which is how members like
        // SectionBufferBuilderPool#<init> get discovered at all.
        return path.contains("/mixin/") || path.contains("/mixin.");
    }

    /**
     * Removes {@code @Accessor}/{@code @Invoker} members from an accessor mixin when the target class
     * already provides them.
     *
     * <p>NeoForge adds members that pure Fabric Minecraft does not have, so a Fabric accessor mixin
     * can end up requesting one of them. The bridge used to drop such a mixin from its config
     * entirely, which makes Mixin refuse to load the accessor interface at all
     * ({@code IllegalClassLoadError: Illegal classload request for accessor mixin ... the mixin is
     * missing from <config> and the mixin has not been applied}). That error fires the moment any
     * Fabric code touches the interface - in practice during a multiplayer login - and disconnects
     * the player, while singleplayer never touches it.
     *
     * <p>Trimming only the clashing member keeps the mixin registered, so Mixin still applies it and
     * still generates the interface with its remaining accessors. The removed accessor is harmless:
     * the member it exposed already exists on the target class.
     *
     * @return the rewritten class bytes, or {@code null} when nothing had to be trimmed
     */
    /**
     * Strips the {@code @Accessor}/{@code @Invoker} annotation from every accessor whose target
     * <em>method</em> already exists.
     *
     * <p>Mixin's accessor handling generates the annotated member in the target class and refuses the
     * merge when the target already declares the same name and descriptor. NeoForge adds methods that
     * pure Fabric Minecraft does not have - {@code ServerCommonPacketListenerImpl#getConnection()},
     * {@code ClientCommonPacketListenerImpl#getConnection()} - so fabric-networking-api-v1's and
     * fabric-recipe-api-v1's accessors failed to apply with "getConnection() ... cannot overwrite
     * method ... because @Overwrite is required", and the interface Fabric casts to was never added.
     *
     * <p>Without the annotation the method is an ordinary abstract method of the (interface) mixin:
     * Mixin adds the interface to the target class, the target's own method satisfies it, and Fabric's
     * casts and calls work. Removing the method instead was tried before and made things worse - Mixin
     * rebuilds accessor members while applying the mixin, so callers were left with
     * {@code NoSuchMethodError}. Stripping the annotation is what actually stops the generation.
     *
     * <p>Only a method with the exact name and descriptor counts as "provided": an accessor exposing a
     * field that has no getter must keep its annotation, because nothing else could satisfy the
     * interface method.
     *
     * @return the rewritten class bytes, or {@code null} when nothing had to change
     */
    private static byte[] neutralizeProvidedAccessors(byte[] classBytes, String classPath, TargetResolver resolver) {
        // Disabled on purpose, and the reason is subtle: Mixin only lets a mixin class be loaded
        // directly while its SubType is ACCESSOR (MixinInfo.isLoadable() is true only there). Fabric's
        // API code casts to these accessor interfaces, so the class has to stay loadable. Stripping the
        // annotation turns the mixin into a plain interface mixin; Mixin then refuses the load with
        // "IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced
        // directly", and the client can no longer connect to a server - a worse failure than the
        // "cannot overwrite method" warning this would fix. Leaving the accessor alone keeps everything
        // else working, and only the Fabric feature that uses that accessor degrades.
        //
        // A real fix has to keep the ACCESSOR subtype: remove the colliding method from the target
        // class instead and let the accessor regenerate it (NeoForge's method is a plain field getter
        // there, so the regenerated one is equivalent) - with the caveat that two accessors targeting
        // the same member (fabric-networking-api-v1 and fabric-recipe-api-v1 both expose
        // ServerCommonPacketListenerImpl#getConnection) would then race to generate it.
        if (!Boolean.getBoolean("neofobric.neutralizeAccessors")) {
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }

        // Interface mixins only: their abstract methods are satisfied by the target class, whereas a
        // class mixin's method would be merged into the target and collide either way.
        if ((node.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) == 0) {
            return null;
        }

        var accessors = new java.util.ArrayList<MethodNode>();
        for (var method : node.methods) {
            if (isAccessorMember(method)) {
                accessors.add(method);
            }
        }
        if (accessors.isEmpty()) {
            return null;
        }
        var targets = readMixinTargets(node);
        if (targets.isEmpty()) {
            return null;
        }

        var neutralized = new java.util.ArrayList<String>();
        for (var target : targets) {
            var targetBytes = resolver.apply(target);
            if (targetBytes == null) {
                continue;
            }
            ClassNode targetNode;
            try {
                targetNode = new ClassNode();
                new ClassReader(targetBytes).accept(targetNode, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            } catch (Throwable t) {
                continue;
            }
            for (var accessor : new java.util.ArrayList<>(accessors)) {
                if (!targetDeclaresMethod(targetNode, accessor)) {
                    continue;
                }
                if (stripAnnotation(accessor, "Lorg/spongepowered/asm/mixin/gen/Accessor;",
                        "Lorg/spongepowered/asm/mixin/gen/Invoker;")) {
                    accessors.remove(accessor);
                    neutralized.add(accessor.name);
                }
            }
        }

        if (neutralized.isEmpty()) {
            return null;
        }
        LOGGER.info("[NeoFobric] Turned accessor(s) {} in {} into plain interface methods - the target already declares them",
                neutralized, classPath);
        return writeClass(node);
    }

    /**
     * {@return whether the target class declares a method with the accessor's exact name and descriptor}
     */
    private static boolean targetDeclaresMethod(ClassNode targetNode, MethodNode accessor) {
        for (var method : targetNode.methods) {
            if (method.name.equals(accessor.name) && method.desc.equals(accessor.desc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return whether the method carries any of the given annotations} Removes them when it does.
     */
    private static boolean stripAnnotation(MethodNode method, String... descriptors) {
        var changed = false;
        for (var list : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (list == null) {
                continue;
            }
            for (var it = list.iterator(); it.hasNext();) {
                var annotation = it.next();
                for (var descriptor : descriptors) {
                    if (descriptor.equals(annotation.desc)) {
                        it.remove();
                        changed = true;
                        break;
                    }
                }
            }
        }
        return changed;
    }

    /**
     * {@return whether the method is an {@code @Accessor}/{@code @Invoker} member}
     */
    private static boolean isAccessorMember(MethodNode method) {
        return hasAnnotation(method, "Lorg/spongepowered/asm/mixin/gen/Accessor;")
                || hasAnnotation(method, "Lorg/spongepowered/asm/mixin/gen/Invoker;");
    }

    /**
     * {@return the field an accessor exposes}
     *
     * <p>An explicit {@code @Accessor("name")} wins; otherwise Mixin derives the name from the method
     * ({@code getFoo} to {@code foo}), stripping a {@code set} argument list as Mixin does.
     */
    private static String accessorFieldName(MethodNode accessor) {
        for (var list : java.util.Arrays.asList(accessor.visibleAnnotations, accessor.invisibleAnnotations)) {
            if (list == null) {
                continue;
            }
            for (var annotation : list) {
                if (!"Lorg/spongepowered/asm/mixin/gen/Accessor;".equals(annotation.desc) || annotation.values == null) {
                    continue;
                }
                for (var i = 1; i < annotation.values.size(); i += 2) {
                    if (annotation.values.get(i) instanceof String explicit && !explicit.isEmpty()) {
                        return explicit;
                    }
                }
            }
        }
        var name = accessor.name;
        for (var prefix : new String[] { "get", "set", "is" }) {
            if (name.length() > prefix.length() && name.startsWith(prefix)) {
                return Character.toLowerCase(name.charAt(prefix.length())) + name.substring(prefix.length() + 1);
            }
        }
        return null;
    }

    private static boolean hasAnnotation(MethodNode method, String descriptor) {
        return findAnnotation(method, descriptor) != null;
    }

    /**
     * {@return the method's annotation with the given descriptor, or {@code null}}
     */
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

    /**
     * {@return the internal names of the classes the mixin targets}
     */
    private static java.util.List<String> readMixinTargets(ClassNode node) {
        var targets = new java.util.ArrayList<String>();
        for (var list : java.util.Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (list == null) {
                continue;
            }
            for (var annotation : list) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc) || annotation.values == null) {
                    continue;
                }
                for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
                    var key = annotation.values.get(i);
                    if ("value".equals(key)) {
                        if (annotation.values.get(i + 1) instanceof java.util.List<?> types) {
                            for (var type : types) {
                                if (type instanceof org.objectweb.asm.Type t) {
                                    targets.add(t.getInternalName());
                                }
                            }
                        } else if (annotation.values.get(i + 1) instanceof org.objectweb.asm.Type t) {
                            targets.add(t.getInternalName());
                        }
                    } else if ("targets".equals(key) && annotation.values.get(i + 1) instanceof String name) {
                        targets.add(name.replace('.', '/'));
                    }
                }
            }
        }
        return targets;
    }

    private static byte[] writeClass(ClassNode node) {
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("[NeoFobric] Failed to delete {}", path, e);
        }
    }
}
