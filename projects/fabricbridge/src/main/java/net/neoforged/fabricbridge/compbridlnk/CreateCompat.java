package net.neoforged.fabricbridge.compbridlnk;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.fabricbridge.FabricBridgeHooks;

/**
 * Create's (create-fly's) named compatibility fix.
 *
 * <p>Create's {@code SmartBlockEntity} attaches its behaviours - client ones included - from
 * {@code initialize()}, which it runs lazily on the entity's first tick. A block entity that reaches the
 * client during chunk handling can be rendered in that same frame, before that tick has happened, and a
 * renderer that needs a behaviour (Create's mixer, for one) then throws on the null it gets back.
 *
 * <p>The lookup therefore completes the initialisation itself - see
 * {@link #ensureBlockEntityInitialised(Object)}, which also reports the cases it cannot handle, so a
 * run that still crashes says why.
 */
public final class CreateCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat/Create");

    private CreateCompat() {}

    /**
     * Makes Create's block entity behaviours available before the first tick, not only after it.
     *
     * @return the class bytes with the lookup guarded, or the original when nothing needed to change
     */
    public static byte[] initialiseBehavioursOnLookup(byte[] classBytes, String entryName) {
        if (!"com/zurrtum/create/foundation/blockEntity/SmartBlockEntity".equals(entryName.replace(".class", ""))) {
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
            if (!"getBehaviour".equals(method.name)
                    || !"(Lcom/zurrtum/create/foundation/blockEntity/behaviour/BehaviourType;)Lcom/zurrtum/create/api/behaviour/BlockEntityBehaviour;".equals(method.desc)) {
                continue;
            }
            // An unconditional call, so no branch and no new stack map frames are needed; the guard lives in
            // the helper, which returns immediately once the entity is initialised.
            var ensure = new InsnList();
            ensure.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ensure.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "net/neoforged/fabricbridge/compbridlnk/CreateCompat", "ensureBlockEntityInitialised",
                    "(Ljava/lang/Object;)V", false));
            method.instructions.insert(ensure);
            changed = true;
        }

        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Made Create's behaviour lookup initialise the block entity first");
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code SmartBlockEntity}'s lazy-init flag plus {@code initialize} and {@code hasLevel}, resolved once. */
    private static volatile java.lang.reflect.Field createInitialisedField;
    private static volatile java.lang.reflect.Method createHasLevelMethod;
    /** {@code initialize} is inherited in different places, so it is cached per concrete class. */
    private static final Map<Class<?>, java.lang.reflect.Method> createInitialiseMethods = new ConcurrentHashMap<>();

    /** Names already reported, so the diagnostic logging below stays quiet. */
    private static final Set<String> PROBED = ConcurrentHashMap.newKeySet();

    /**
     * Completes Create's block entity initialisation when a behaviour is looked up before the entity ever
     * ticked. Called from the head of {@code SmartBlockEntity#getBehaviour}, so it returns as fast as it can:
     * once the entity is initialised this is a flag read and nothing else.
     *
     * <p>Create attaches its behaviours, client ones included, from {@code initialize()}, which runs lazily on
     * the entity's first tick; a renderer asking for one in the frame before that tick throws on the null it
     * gets back. Doing it here makes the lookup self-sufficient, and it cannot run twice: the flag is set
     * before {@code initialize()} is entered.
     */
    public static void ensureBlockEntityInitialised(Object blockEntity) {
        if (!FabricBridgeHooks.bridgeActive() || blockEntity == null) {
            return;
        }
        var simpleName = blockEntity.getClass().getSimpleName();
        try {
            var field = createInitialisedField;
            if (field == null) {
                // Walk up: the flag is declared on SmartBlockEntity, while the entity asking is a subclass -
                // and getDeclaredField answers for one class only, so a plain lookup throws and the catch below
                // used to swallow it, leaving the hook doing nothing at all.
                for (var type = blockEntity.getClass(); type != null && field == null; type = type.getSuperclass()) {
                    try {
                        field = type.getDeclaredField("initialized");
                    } catch (NoSuchFieldException ignored) {
                        // Keep walking; the declaring class is the one that matters.
                    }
                }
                if (field == null) {
                    return;
                }
                field.setAccessible(true);
                createInitialisedField = field;
            }
            if (field.getBoolean(blockEntity)) {
                return;
            }
            var hasLevel = createHasLevelMethod;
            if (hasLevel == null) {
                hasLevel = blockEntity.getClass().getMethod("hasLevel");
                createHasLevelMethod = hasLevel;
            }
            if (!Boolean.TRUE.equals(hasLevel.invoke(blockEntity))) {
                if (PROBED.add("nolevel" + simpleName)) {
                    LOGGER.debug("[NeoFobric] Behaviour looked up on an uninitialised block entity with no level yet: {}", simpleName);
                }
                return;
            }
            var initialise = createInitialiseMethods.get(blockEntity.getClass());
            if (initialise == null) {
                initialise = blockEntity.getClass().getMethod("initialize");
                createInitialiseMethods.put(blockEntity.getClass(), initialise);
            }
            // Call first, mark after: with the flag set early, an initialize() that throws would leave the
            // block entity permanently marked as initialised with no behaviours - every later lookup would
            // early-return, nothing would ever retry, and the first real crash would carry no trace of this.
            initialise.invoke(blockEntity);
            field.setBoolean(blockEntity, true);
            if (PROBED.add("early" + simpleName)) {
                LOGGER.debug("[NeoFobric] Initialised a block entity from its behaviour lookup, before its first tick: {}", simpleName);
            }
        } catch (Throwable t) {
            if (PROBED.add("failed" + simpleName)) {
                LOGGER.warn("[NeoFobric] Behaviour-lookup initialisation of {} failed - the block entity stays uninitialised and the next lookup will retry", simpleName, t);
            }
        }
    }

    /** The generated mixin class's entry name in Create's jar. */
    public static final String PARTIAL_MODEL_MIXIN_CLASS = "com/zurrtum/create/client/mixin/BridgeCreatePartialModelMixin.class";

    /** {@return whether this is Create's client mixin config} */
    public static boolean handlesMixinConfig(String entryName) {
        return "create.client.mixins.json".equals(entryName);
    }

    /**
     * Removes Create's {@code WorldOpenFlowsMixin} from its client config.
     *
     * <p>The mixin wraps the biome-feature step of Fabric's world-open flow and reads
     * {@code LevelStem.OVERWORLD} from the registry access handed to it. On NeoForge the level-stem
     * registry is not populated at that point, so the lookup returns null and every world load dies with
     * an NPE before the server starts. Its only job is adding Create's zinc/striated-ore features to the
     * biomes; dropping it costs those features but lets the world load. The property keeps the old
     * behaviour for debugging once NeoForge's world-open flow carries the stems.
     *
     * @return the config text without the entry, or {@code null} when it was not present
     */
    public static String withoutWorldOpenFlowsMixin(String configText) {
        if (!configText.contains("\"package\": \"com.zurrtum.create.client.mixin\"")
                || !configText.contains("WorldOpenFlowsMixin")) {
            return null;
        }
        // The entry is last in Create Fly's config, so its separator is the comma *before* it.
        var filtered = configText.replaceFirst(",\\s*\"WorldOpenFlowsMixin\"", "");
        if (filtered.equals(configText)) {
            filtered = configText.replaceFirst("\"WorldOpenFlowsMixin\"\\s*,", "");
        }
        return filtered.equals(configText) ? null : filtered;
    }

    /**
     * Adds the partial-model mixin to Create's client mixin config.
     *
     * @return the config text with the entry, or {@code null} when it is not Create's config or already carries it
     */
    public static String addPartialModelMixin(String configText) {
        // Only Create Fly's own config (its mixin package is com.zurrtum.create.client.mixin).
        if (!configText.contains("\"package\": \"com.zurrtum.create.client.mixin\"")
                || configText.contains("BridgeCreatePartialModelMixin")) {
            return null;
        }
        return configText.replaceFirst("(\"client\"\\s*:\\s*\\[)", "$1\n    \"BridgeCreatePartialModelMixin\",");
    }

    /**
     * Generates the bytecode of {@code BridgeCreatePartialModelMixin}.
     *
     * <p>Create registers its partial models into a map that the vanilla model pipeline never asks about. On
     * pure Fabric, Create's own mixin feeds them into {@code ModelManager.discoverModelDependencies} by bare
     * name; NeoForge's deprecated vanilla-shape overload makes that injection ambiguous, so it matches
     * neither overload and every partial model draws the missing-model checkerboard. This mixin is
     * sugar-free and injects into {@code ModelDiscovery.resolve}'s receiver instead, feeding the same map.
     *
     * <p>Written in bytecode because the bridge compiles without Minecraft on its classpath; the generated
     * class references only types the game layer can see at runtime.
     */
    public static byte[] generatePartialModelDiscoveryMixin() {
        var self = "com/zurrtum/create/client/mixin/BridgeCreatePartialModelMixin";
        var discovery = "net/minecraft/client/resources/model/ModelDiscovery";
        var cls = new ClassNode();
        cls.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, self, null, "java/lang/Object", null);
        // @Mixin is RetentionPolicy.CLASS: sponge-mixin reads it through Annotations.getInvisible,
        // i.e. from RuntimeInvisibleAnnotations. Writing it visible makes MixinInfo.readPriority throw
        // "missing an @Mixin annotation" and the whole config fails to prepare. @Inject below is
        // RUNTIME and must stay visible.
        var mixin = cls.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        mixin.visit("value", java.util.List.of(Type.getObjectType(discovery)));
        mixin.visitEnd();

        var ctor = cls.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        var handler = new MethodNode(Opcodes.ACC_PRIVATE, "neofobric$feedCreatePartials",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
        cls.methods.add(handler);
        var inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        inject.visit("method", java.util.List.of("resolve"));
        var at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = java.util.List.of("value", "HEAD");
        inject.visit("at", at);
        inject.visitEnd();

        var loop = new LabelNode();
        var done = new LabelNode();
        var insns = handler.instructions;

        // for (Identifier id : PartialModelEventHandler.getRegisterAdditional().keySet())
        //     ((ModelDiscovery) (Object) this).getOrCreateModel(id);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/zurrtum/create/client/flywheel/lib/model/baked/PartialModelEventHandler", "getRegisterAdditional",
                "()Ljava/util/Map;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "keySet", "()Ljava/util/Set;", true));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, done));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, discovery));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/resources/Identifier"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, discovery, "getOrCreateModel",
                "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/resources/model/ModelDiscovery$ModelWrapper;",
                false));
        insns.add(new InsnNode(Opcodes.POP));
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
}
