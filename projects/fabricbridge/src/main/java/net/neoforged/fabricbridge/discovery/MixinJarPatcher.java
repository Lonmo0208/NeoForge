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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
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
     * nothing checks it against the real signature. A wrong one therefore fails silently under
     * {@code defaultRequire: 0}, which is what makes these so hard to spot in logs.
     *
     * <p>{@code fabric-registry-sync-v0} is the case that matters today: its {@code IS_SERVER}
     * ScopedValue is bound by the {@code @WrapOperation} on {@code RegistryDataLoader.load}. The
     * declared target was the pre-26.2 four-argument overload, so the wrap never installed, nothing
     * bound {@code IS_SERVER}, and {@code beforeLoad} died in a {@code CompletableFuture} worker with
     * {@code NoSuchElementException: ScopedValue not bound} - the binding does not travel to another
     * thread, so it must be established inside the same lambda the game runs on.
     *
     * <p>Values mirror ForgifiedFabricAPI-26.2.x, the upstream port that already solved this.
     */
    private static final Map<String, String> ANNOTATION_TARGET_REALIGNMENTS = Map.of(
            // RegistryDataLoader.load: Fabric API wraps a LoaderFactory overload that no longer
            // exists publicly; the public entry point takes the ResourceManager directly.
            "(Lnet/minecraft/resources/RegistryDataLoader$LoaderFactory;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;)",
            "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;"
                    + "Ljava/util/concurrent/Executor;)",
            // RegistryDataLoader.lambda$load$0: Fabric API still declares a trailing boolean that
            // was removed. This @ModifyArg is what binds IS_SERVER (see supplyAsync), so a stale
            // descriptor here leaves the ScopedValue unbound and beforeLoad then dies inside a
            // CompletableFuture worker with NoSuchElementException: ScopedValue not bound.
            "(Ljava/util/List;Lnet/minecraft/resources/RegistryDataLoader$LoaderFactory;"
                    + "Ljava/util/List;Ljava/util/concurrent/Executor;Z)",
            "(Ljava/util/List;Lnet/minecraft/resources/RegistryDataLoader$LoaderFactory;"
                    + "Ljava/util/List;Ljava/util/concurrent/Executor;)");

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
                        if (list.get(j) instanceof String text) {
                            var replaced = replacementFor(text);
                            if (replaced != null) {
                                ((List<Object>) list).set(j, replaced);
                                changed = true;
                            }
                        }
                    }
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
                    // Not a mixin: the only rewrite it may need is a call site of a renamed accessor
                    // member (Fabric's own implementation classes do the casting).
                    if (!accessorRenames.isEmpty()) {
                        var withCalls = rewriteAccessorCallSites(versionMoved, accessorRenames, entry.getName());
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
                } else if (extended != null) {
                    patched.put(entry.getName(), extended);
                } else if (renamed != null) {
                    patched.put(entry.getName(), renamed);
                } else if (callSitesMoved != null) {
                    patched.put(entry.getName(), callSitesMoved);
                } else if (realigned != null) {
                    patched.put(entry.getName(), realigned);
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
                if (entry.getMethod() == ZipEntry.STORED && replacement == null) {
                    // Keep stored entries exactly as they are (size and CRC are mandatory then).
                    outEntry.setMethod(ZipEntry.STORED);
                    outEntry.setSize(entry.getSize());
                    outEntry.setCompressedSize(entry.getSize());
                    outEntry.setCrc(entry.getCrc());
                }
                out.putNextEntry(outEntry);
                if (replacement != null) {
                    out.write(replacement);
                } else {
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
