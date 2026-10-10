/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforgespi.transformation.ClassProcessor;

/**
 * Removes Fabric mixin entries whose "plain" methods (not annotated with {@code @Unique},
 * {@code @Overwrite} or an injector annotation) collide with methods that already exist on the
 * target class.
 *
 * <p>NeoForge adds methods to Minecraft classes that pure Fabric environments do not have (for
 * example {@code ServerCommonPacketListenerImpl#getConnection} or the block-model geometry
 * dispatch). Fabric accessor and duck mixins written against pure Fabric expect those methods to
 * be absent and fail to merge with {@code "cannot overwrite method ... because @Overwrite is
 * required"}. The first such failure is logged as a warning by the mixin engine, but later passes
 * re-throw it while the class is being defined, crashing the game.
 *
 * <p>When the method already exists, the mixin's purpose is already fulfilled by NeoForge, so
 * dropping the entry before registration is both safe and the least invasive way to avoid the
 * crash. Mixins marked {@code @Overwrite} are kept: they intentionally replace the method.
 *
 * <p>Known deliberate drop with a preserved behaviour: fabric-renderer-api-v1's
 * {@code block.model.MultiPartModelMixin}, {@code SingleVariantMixin} and
 * {@code WeightedVariantsMixin} implement the Fabric renderer interface and collide exactly on
 * {@code createGeometryKey} / {@code particleMaterial(...)}, which NeoForge's model classes already
 * override with equivalent delegation logic (their unique {@code emitQuads} is covered by the
 * interface default, which {@code BlockStateModelMixin} - kept once the Fabric interface hangs under
 * NeoForge's {@code BlockStateModelExtension}, see NeoFobricClassTweakerProcessor - puts on
 * {@code BlockStateModel}).</p>
 */
public final class MixinConflictFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinConflictFilter.class);

    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";

    /**
     * Mixin classes that are dropped by name, as a last resort for ones the automatic fixes cannot
     * reach.
     *
     * <p>{@code BlockStateModelUnbakedMixin} (fabric-model-loading-api-v1) redirects the two
     * {@code Codec.flatComapMap} calls of {@code BlockStateModel.Unbaked.<clinit>} and replaces their
     * results with Fabric's own custom-model codecs. On NeoForge the same two calls exist (the
     * ordinals still match, so this is not an injection miss - it applies), but the codecs carry
     * NeoForge's own custom-model dispatch ({@code CustomUnbakedBlockStateModel}); replacing the
     * results bypasses it and every blockstate model then fails to decode - the
     * "Failed to load model minecraft:models/block/<name>.json" catastrophe for all of vanilla, which
     * is why the whole module used to be skipped. The module's other five mixins are kept (the config
     * is registered again) and drive the plugin machinery Fabric mods - Create's partial models among
     * them - need; only this codec replacement stays out.
     *
     * <p>Entries this list used to hold, kept as history so nobody re-evaluates them:
     * {@code RegistryDataLoaderMixin} (fabric-registry-sync-v0's stale annotation left its
     * {@code IS_SERVER} ScopedValue unbound) is solved properly by
     * {@code MixinJarPatcher.ANNOTATION_TARGET_REALIGNMENTS} being emptied - the simple-name skip also
     * dropped Create's own mixin of the same name, which is how Create adds its datapack registries.
     * The four fabric-networking payload mixins are harmless repeats: NeoForge already patches the
     * same ground ({@code BundlePacket.<init>} calls {@code BundlePacketUtils.flatten},
     * {@code FriendlyByteBuf.readCollection}/{@code readMap} already clamp), and the two
     * custom-payload mixins cannot apply at all (they target the two-argument
     * {@code CustomPacketPayload.codec} while NeoForge's is four-argument). Skipping those cost
     * working functionality - large-payload splitting in particular - for no benefit.
     *
     * <p>Overridden by {@code -Dneofobric.noDefaultMixinSkips=true} and by
     * {@code -Dneofobric.registerAllMixinConfigs=true}.
     */
    private static final List<String> DEFAULT_SKIPPED_MIXIN_CLASSES = List.of(
            "BlockStateModelUnbakedMixin");

    /**
     * {@return whether {@code mixinClass} names exactly the class {@code simpleName}}
     *
     * <p>Mixin entries in a config are relative to its {@code package} and may be nested
     * ({@code block.model.FooMixin}), so this accepts the bare simple name or anything ending in
     * {@code .simpleName}. A plain {@code contains} would also match
     * {@code RegistryFriendlyByteBufMixin} when looking for {@code FriendlyByteBufMixin}, silently
     * dropping a mixin that works.
     */
    private static boolean isSameMixinClass(String mixinClass, String simpleName) {
        return mixinClass.equals(simpleName) || mixinClass.endsWith("." + simpleName);
    }

    private static volatile Function<String, byte[]> targetBytecode = name -> null;

    private MixinConflictFilter() {}

    /**
     * Installs the bytecode source used to inspect mixin target classes.
     */
    public static void install(ClassProcessor.LinkContext context) {
        var provider = context.bytecodeProvider();
        targetBytecode = name -> {
            try {
                return provider.getByteCode(name.replace('/', '.'));
            } catch (Throwable t) {
                return null;
            }
        };
    }

    /**
     * Removes mixin entries from the config that conflict with methods already present on their
     * target classes. Mixin classes are resolved through {@code mixinClassSource}.
     *
     * <p>While scanning, every mixin class is also offered to
     * {@link MixinHandlerDescriptorFixer}; classes it rewrites are served with their patched bytes
     * by the game library overlay (see the discovery package), which is why the fixer runs against
     * the {@link #install installed} bytecode provider here.
     *
     * @param clientSide whether the running side is the client; on the dedicated server, mixin
     *            classes that reference client-only Minecraft classes are dropped
     * @return the config content with conflicting entries removed (or the original content)
     */
    public static byte[] filter(byte[] configContent, JarContents jar, String modId, boolean clientSide) {
        if (Boolean.getBoolean("neofobric.noConflictFilter")) {
            LOGGER.warn("[NeoFobric] Mixin conflict filtering disabled (neofobric.noConflictFilter)");
            return configContent;
        }
        var text = new String(configContent, java.nio.charset.StandardCharsets.UTF_8);
        var removals = new ArrayList<String>();
        // Mixin entries are relative to the config's "package" unless they are fully qualified.
        var mixinPackage = readTopLevelString(text, "package");

        for (var arrayKey : List.of("mixins", "client", "server")) {
            for (var mixinClass : readArrayEntries(text, arrayKey)) {
                try {
                    // Entries are relative to the config package first; fall back to a
                    // fully-qualified name (short names like "block.model.X" also contain dots).
                    var candidatePaths = new ArrayList<String>();
                    if (mixinPackage != null) {
                        candidatePaths.add((mixinPackage + "." + mixinClass).replace('.', '/') + ".class");
                    }
                    candidatePaths.add(mixinClass.replace('.', '/') + ".class");

                    byte[] classBytes = null;
                    String classPath = null;
                    for (var candidatePath : candidatePaths) {
                        classBytes = jar.readFile(candidatePath);
                        if (classBytes != null) {
                            classPath = candidatePath;
                            break;
                        }
                    }
                    if (classBytes == null) {
                        continue;
                    }
                    if (Boolean.getBoolean("neofobric.traceMixinClasses")
                            && mixinClass.contains("PacketListener")) {
                        LOGGER.info("[NeoFobric] Trace: config entry {} from {} kept (file present, {} bytes)",
                                mixinClass, modId, classBytes.length);
                    }
                    if (!clientSide && referencesClientClasses(classBytes)) {
                        // A client-only Fabric mixin on the dedicated server. It can never apply
                        // (its targets do not exist), and letting it through makes FML's dist
                        // cleaner log one ERROR per client class while loading it - hundreds of
                        // lines that bury real problems. Skipping the entry is exactly the outcome
                        // the relaxed config would reach anyway, just without the noise.
                        removals.add(mixinClass);
                        continue;
                    }
                    // A/B diagnosis: skip individual mixin classes by name.
                    var skipClass = System.getProperty("neofobric.skipMixinClasses");
                    if (skipClass != null && !skipClass.isBlank()) {
                        var skipAny = false;
                        for (var needle : skipClass.split(",")) {
                            if (!needle.isBlank() && mixinClass.contains(needle.trim())) {
                                skipAny = true;
                                break;
                            }
                        }
                        if (skipAny) {
                            LOGGER.warn("[NeoFobric] Skipping mixin class {} (diagnostic filter)", mixinClass);
                            removals.add(mixinClass);
                            continue;
                        }
                    }
                    // Classes that break startup for a reason the automatic fixes cannot reach.
                    // Matched on the exact class name, not as a substring: entries here are short
                    // enough that a substring test also catches longer siblings, and silently
                    // dropping e.g. RegistryFriendlyByteBufMixin alongside FriendlyByteBufMixin
                    // costs working functionality.
                    if (!Boolean.getBoolean("neofobric.registerAllMixinConfigs")
                            && !Boolean.getBoolean("neofobric.noDefaultMixinSkips")
                            && DEFAULT_SKIPPED_MIXIN_CLASSES.stream().anyMatch(skipped -> isSameMixinClass(mixinClass, skipped))) {
                        LOGGER.warn("[NeoFobric] Skipping mixin class {} - it cannot bind its state on this loader", mixinClass);
                        removals.add(mixinClass);
                        continue;
                    }
                    var duckInterfaces = conflictsWithTarget(classBytes, jar);
                    if (duckInterfaces != null) {
                        // Before giving up on the mixin, let the jar patcher strip merge-colliding
                        // members from the allowlisted duck mixins (idempotent: it returns null when
                        // nothing collides, e.g. because the realigned jar copy is already stripped).
                        // With a collision gone the remaining methods can merge, and dropping the
                        // entry - which abandons the whole interface - is no longer necessary.
                        var stripped = net.neoforged.fabricbridge.discovery.MixinJarPatcher
                                .stripCollidingDuckMembers(classBytes, classPath, targetBytecode::apply);
                        if (stripped != null) {
                            continue;
                        }
                    }
                    if (duckInterfaces != null) {
                        // The mixin implements Fabric-owned interfaces (a duck-type mixin). Keep the
                        // interface injection alive even though the mixin body is dropped, so casts
                        // from other applied mixins keep working; interface methods get empty
                        // synthetic implementations (feature degradation instead of a crash).
                        for (var target : readTargets(classBytes)) {
                            NeoFobricClassTweakerProcessor.registerDuckFallback(target, duckInterfaces);
                        }
                        removals.add(mixinClass);
                    } else {
                        // Realign injector handler parameters that NeoForge's reshaped signatures moved
                        // around; see MixinHandlerDescriptorFixer. The rewritten bytes are written to
                        // the realigned jar copy by MixinJarPatcher.
                        MixinHandlerDescriptorFixer.fix(classBytes, targetBytecode::apply);
                    }
                } catch (Exception e) {
                    LOGGER.debug("[NeoFobric] Failed to inspect mixin class {} for conflicts", mixinClass, e);
                }
            }
        }

        if (removals.isEmpty()) {
            return configContent;
        }

        var relaxed = text;
        for (var mixinClass : removals) {
            var quoted = java.util.regex.Pattern.quote("\"" + mixinClass + "\"");
            // Remove the element together with a trailing or leading comma to keep the array valid.
            var withTrailing = java.util.regex.Pattern.compile(quoted + "\\s*,\\s*");
            var withLeading = java.util.regex.Pattern.compile("\\s*,\\s*" + quoted);
            if (withTrailing.matcher(relaxed).find()) {
                relaxed = withTrailing.matcher(relaxed).replaceAll("");
            } else {
                relaxed = withLeading.matcher(relaxed).replaceAll("");
            }
            LOGGER.info("[NeoFobric] Dropped mixin {} from {} - its target method already exists in NeoForge", mixinClass, modId);
        }

        if (relaxed.equals(text)) {
            return configContent;
        }
        return relaxed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * {@return whether the mixin class refers to client-only Minecraft classes}
     *
     * <p>A mixin can depend on the client in several ways that all end up as a string in the class
     * file: {@code @Mixin(Target.class)} types, {@code @Mixin(targets = "net.minecraft.client...")}
     * names, {@code @Shadow} members and handler parameter types. Scanning the constant pool for the
     * client package covers every one of them with a single cheap pass, which is what this needs to
     * be - it runs for every mixin class of every mod.
     */
    private static boolean referencesClientClasses(byte[] mixinClassBytes) {
        try {
            var bytes = mixinClassBytes;
            var reader = new org.objectweb.asm.ClassReader(bytes);
            var pool = new java.io.ByteArrayOutputStream();
            reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                        String superName, String[] interfaces) {
                    write(superName);
                    if (interfaces != null) {
                        for (var iface : interfaces) {
                            write(iface);
                        }
                    }
                }

                @Override
                public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    write(descriptor);
                    // Annotation values are where @Mixin(targets = "...") lives.
                    return forwardingVisitor();
                }

                @Override
                public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                        String signature, Object value) {
                    write(descriptor);
                    write(value);
                    return null;
                }

                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    write(descriptor);
                    // Method bodies are checked too: a handler can reach a client class through a
                    // field/method reference or a checkcast without it appearing in any signature.
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            write(type);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            write(owner);
                            write(descriptor);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface) {
                            write(owner);
                            write(descriptor);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            write(value);
                        }
                    };
                }

                private org.objectweb.asm.AnnotationVisitor forwardingVisitor() {
                    return new org.objectweb.asm.AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            write(value);
                        }

                        @Override
                        public void visitEnum(String name, String descriptor, String value) {
                            write(descriptor);
                        }

                        @Override
                        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String name, String descriptor) {
                            write(descriptor);
                            return forwardingVisitor();
                        }

                        @Override
                        public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
                            return forwardingVisitor();
                        }
                    };
                }

                private void write(Object value) {
                    if (value == null) {
                        return;
                    }
                    var text = value instanceof org.objectweb.asm.Type type ? type.getInternalName() : value.toString();
                    if (CLIENT_ONLY_PREFIXES.stream().anyMatch(text::contains)) {
                        pool.write(1);
                    }
                }
            }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return pool.size() > 0;
        } catch (Exception e) {
            // Unreadable: let Mixin decide.
            return false;
        }
    }

    /**
     * Packages that only exist on the client. {@code com.mojang.blaze3d} is Minecraft's own client
     * rendering library, so it is just as absent from a dedicated server as {@code net.minecraft.client}.
     */
    private static final java.util.List<String> CLIENT_ONLY_PREFIXES = java.util.List.of(
            "net.minecraft.client.",
            "net/minecraft/client/",
            "com.mojang.blaze3d.",
            "com/mojang/blaze3d/");

    /**
     * Extracts a plain top-level string value (e.g. "package") from the config JSON.
     */
    private static String readTopLevelString(String text, String key) {
        var matcher = java.util.regex.Pattern.compile("\"" + key + "\"\s*:\s*\"([^\"]*)\"").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Extracts plain string entries of a top-level string array from the config JSON.
     */
    private static List<String> readArrayEntries(String text, String arrayKey) {
        var entries = new ArrayList<String>();
        var pattern = java.util.regex.Pattern.compile("\"" + arrayKey + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        var matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return entries;
        }
        for (var entry : matcher.group(1).split(",")) {
            var trimmed = entry.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                entries.add(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        return entries;
    }

    /**
     * Extracts the plain methods of the mixin class and checks them against its targets.
     *
     * @return the Fabric-owned interfaces the mixin implements (readable from the same jar) if a
     *         conflict was found, otherwise { null}
     */
    private static List<String> conflictsWithTarget(byte[] mixinClassBytes, JarContents jar) {
        var node = readClass(mixinClassBytes);
        if (node == null) {
            return null;
        }

        List<String> targets = null;
        for (var ann : allAnnotations(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (MIXIN_ANNOTATION.equals(ann.desc)) {
                targets = readTargetTypes(ann);
            }
        }
        if (targets == null || targets.isEmpty()) {
            return null;
        }

        var candidateKeys = new ArrayList<String>();
        for (var method : node.methods) {
            if (isCandidate(method)) candidateKeys.add(method.name + method.desc);
        }
        if (candidateKeys.isEmpty()) {
            return null;
        }

        var conflict = false;
        for (var target : targets) {
            var targetBytes = targetBytecode.apply(target);
            if (targetBytes == null) {
                continue;
            }
            var targetNode = readClass(targetBytes);
            if (targetNode == null) {
                continue;
            }
            for (var targetMethod : targetNode.methods) {
                if (candidateKeys.contains(targetMethod.name + targetMethod.desc)) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) break;
        }
        if (!conflict) {
            return null;
        }

        // Collect the Fabric-owned interfaces this mixin implements (classes that live in the
        // same mod jar). They must stay present on the target even without the mixin body.
        var duckInterfaces = new ArrayList<String>();
        for (var iface : node.interfaces) {
            try {
                if (jar.readFile(iface + ".class") != null) {
                    duckInterfaces.add(iface);
                }
            } catch (Exception e) {
                // unreadable jar entry - not a Fabric-owned interface then
            }
        }
        return duckInterfaces;
    }

    /**
     * @return the mixin targets declared by the class (both Class and String forms)
     */
    private static List<String> readTargets(byte[] mixinClassBytes) {
        var targets = new ArrayList<String>();
        var node = readClass(mixinClassBytes);
        if (node == null) {
            return targets;
        }
        for (var ann : allAnnotations(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (MIXIN_ANNOTATION.equals(ann.desc)) {
                targets.addAll(readTargetTypes(ann));
            }
        }
        return targets;
    }

    /**
     * Members Mixin <em>generates</em> while applying an accessor mixin rather than merging into the
     * target. They can never conflict with an existing target member.
     */
    private static final Set<String> ACCESSOR_ANNOTATIONS = Set.of(
            "Lorg/spongepowered/asm/mixin/gen/Accessor;",
            "Lorg/spongepowered/asm/mixin/gen/Invoker;");

    private static final Set<String> INJECTOR_ANNOTATIONS = Set.of(
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

    /**
     * {@return whether the mixin method will be merged into the target} Private methods and
     * members marked {@code @Unique} stay local; injector handlers are callbacks;
     * {@code @Overwrite} deliberately replaces the target member, so the target already having
     * that member is its precondition, not a conflict.
     */
    private static boolean isCandidate(MethodNode method) {
        for (var ann : allAnnotations(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (ann.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                return false;
            }
            if (INJECTOR_ANNOTATIONS.contains(ann.desc)) {
                return false;
            }
        }
        if (java.lang.reflect.Modifier.isPrivate(method.access & 0x7)) {
            return false;
        }
        if (method.name.equals("<init>") || method.name.equals("<clinit>")) {
            return false;
        }
        // @Accessor/@Invoker members are not merged into the target: Mixin *generates* them while
        // applying the accessor mixin, so "the target already has that member" is not a conflict.
        // Treating them as candidates dropped whole accessor mixins, and Fabric code that then asked
        // for the interface died with
        // "IllegalClassLoadError: Illegal classload request for accessor mixin ... the mixin is missing
        // from <config>" - which is exactly the failure that blocked logging into a server.
        for (var ann : allAnnotations(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (ACCESSOR_ANNOTATIONS.contains(ann.desc)) {
                return false;
            }
        }
        // @Shadow declarations reference members that already exist on the target - they are
        // the same member, not a merge conflict. (Interface methods are abstract too, so
        // abstractness alone must not exclude a candidate.)
        for (var ann : allAnnotations(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (ann.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                return false;
            }
        }
        for (var ann : allAnnotations(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (ann.desc.equals("Lorg/spongepowered/asm/mixin/Unique;")) {
                return false;
            }
            if (INJECTOR_ANNOTATIONS.contains(ann.desc)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> readTargetTypes(AnnotationNode mixinAnnotation) {
        var targets = new ArrayList<String>();
        var values = mixinAnnotation.values;
        if (values == null) {
            return targets;
        }
        for (int i = 0; i + 1 < values.size(); i += 2) {
            var key = values.get(i);
            if ("value".equals(key)) {
                // Class form: @Mixin(Foo.class) or @Mixin({Foo.class, Bar.class})
                if (values.get(i + 1) instanceof List<?> types) {
                    for (var type : types) {
                        if (type instanceof org.objectweb.asm.Type t) {
                            targets.add(t.getInternalName());
                        }
                    }
                } else if (values.get(i + 1) instanceof org.objectweb.asm.Type t) {
                    targets.add(t.getInternalName());
                }
            } else if ("targets".equals(key)) {
                // String form: @Mixin(targets = "com.example.Foo")
                if (values.get(i + 1) instanceof String name) {
                    targets.add(name.replace('.', '/'));
                } else if (values.get(i + 1) instanceof List<?> names) {
                    for (var name : names) {
                        if (name instanceof String s) {
                            targets.add(s.replace('.', '/'));
                        }
                    }
                }
            }
        }
        return targets;
    }

    private static List<AnnotationNode> allAnnotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var all = new ArrayList<AnnotationNode>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    private static ClassNode readClass(byte[] bytes) {
        try {
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            return node;
        } catch (Exception e) {
            return null;
        }
    }
}
