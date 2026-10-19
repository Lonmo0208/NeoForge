/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.transform;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweaker;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fabricbridge.FabricBridge;
import net.neoforged.fabricbridge.compbridlnk.PolymerCompat;
import net.neoforged.fabricbridge.compbridlnk.SodiumCompat;
import net.neoforged.fabricbridge.discovery.FabricJarRegistry;
import net.neoforged.neoforgespi.transformation.ClassProcessor;
import net.neoforged.neoforgespi.transformation.ProcessorName;

public final class NeoFobricClassTweakerProcessor implements ClassProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(NeoFobricClassTweakerProcessor.class);

    private static final Map<String, List<String>> DUCK_FALLBACKS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Map<String, String[]>> ACCESSOR_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();


    public static void registerAccessorField(String targetInternalName, String methodName,
            String ownerInternalName, String fieldName, String fieldDesc) {
        ACCESSOR_FIELDS.computeIfAbsent(targetInternalName, key -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(methodName, new String[] { fieldName, fieldDesc, ownerInternalName });
    }

    public static void registerDuckFallback(String targetInternalName, List<String> interfaces) {
        DUCK_FALLBACKS.computeIfAbsent(targetInternalName, key -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .addAll(interfaces);
    }

    /**
     * Game enums a Fabric mod extends through Mixin's enum extension.
     *
     * <p>FML refuses that feature on any class implementing {@code IExtensibleEnum} - the enum NeoForge made
     * extensible itself, so that mods use its registration API instead. A Fabric mod cannot know about that API,
     * and with the interface left in place its mixin is rejected during config preparation, which takes the whole
     * game down before the main menu (IllagerInvasion's raid types were the case that surfaced this). The bridge
     * therefore drops the marker from exactly the classes a Fabric enum extension targets, detected from the
     * mixin itself rather than from a list of mods, and Mixin's own extension then applies as it does on Fabric.
     */
    private static final java.util.Set<String> ENUM_EXTENSION_TARGETS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final String I_EXTENSIBLE_ENUM = "net/neoforged/fml/common/asm/enumextension/IExtensibleEnum";

    /** Marks a game enum that some Fabric mixin extends, so the marker interface can step aside. */
    public static void registerEnumExtensionTarget(String targetInternalName) {
        if (ENUM_EXTENSION_TARGETS.add(targetInternalName)) {
            LOGGER.info("[NeoFobric] {} is enum-extended by a Fabric mixin; the platform's IExtensibleEnum marker will be dropped from it",
                    targetInternalName.replace('/', '.'));
        }
    }

    private static final String FABRIC_RESOURCE_INTERFACE = "net/fabricmc/fabric/api/resource/v1/FabricResource";

    private static final String FABRIC_CHANNELED_ADDON =
            "net/fabricmc/fabric/impl/networking/AbstractChanneledNetworkAddon";

    private static final Set<String> FABRIC_CHANNEL_CLASSES = Set.of(
            FABRIC_CHANNELED_ADDON,
            "net/fabricmc/fabric/impl/networking/server/ServerConfigurationNetworkAddon",
            "net/fabricmc/fabric/impl/networking/server/ServerPlayNetworkAddon",
            "net/fabricmc/fabric/impl/networking/server/ServerLoginNetworkAddon");

    private static final List<String> FABRIC_CHANNEL_PACKET_SENDERS = List.of(
            "handleRegistration", "handleUnregistration");

    /** Holds the {@code minecraft:register}/{@code minecraft:unregister} channel constants of Fabric. */
    private static final String FABRIC_NETWORKING_IMPL = "net/fabricmc/fabric/impl/networking/NetworkingImpl";

    private static final String CHANNEL_NAME_DESC = "(Lnet/minecraft/resources/Identifier;)V";

    /**
     * BuiltInRegistries: Fabric's BootstrapMixin redirects Bootstrap's bootStrap() to
     * createContents(), which is private in NeoForge's tree. Fabric reaches it directly, so the call
     * dies with IllegalAccessError during Bootstrap and nothing ever launches. Widening the method is
     * the minimal fix - the behaviour Fabric wants (create the contents without the freeze) is intact.
     */
    private static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";

    /**
     * Ingredient: Fabric's recipe API extends it ({@code CustomIngredientImpl extends Ingredient}), but
     * NeoForge's tree marks the class final, so loading the subclass dies with
     * {@code IncompatibleClassChangeError} during Bootstrap and nothing starts. Dropping ACC_FINAL keeps
     * everything else about the class intact.
     */
    private static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";

    /**
     * Fabric renderer API's baked-model interface, and the NeoForge extension it must hang off of.
     *
     * <p>Upstream (pure-Fabric) {@code FabricBlockStateModel} re-declares {@code createGeometryKey} and
     * {@code particleMaterial(BlockAndTintGetter, BlockPos, BlockState)} with defaults behaviourally
     * identical to NeoForge's {@code BlockStateModelExtension} - but the two interfaces are unrelated.
     * When the renderer-api mixin adds {@code FabricBlockStateModel} onto {@code BlockStateModel},
     * which NeoForge's source already makes extend the extension, every model class inherits two
     * unrelated defaults of the same signature and dies with
     * {@code IncompatibleClassChangeError: Conflicting default methods} while loading. And skipping
     * that mixin instead is not an option either, because sodium and Indigo cast every model to the
     * Fabric interface ({@code FRAPIEmitter}), which then dies with "does not implement the requested
     * interface".
     *
     * <p>The Forgified Fabric API build solves exactly this by declaring
     * {@code FabricBlockStateModel extends BlockStateModelExtension} in its source, turning the
     * re-declared methods into proper overrides on a single inheritance chain. That supertype edge
     * cannot be reproduced at source level here: the fabric-api jar is read-only upstream, and
     * NeoForge's source cannot reference Fabric classes to declare its interface as the subtype. So
     * the bridge adds the edge to the interface's bytecode as the interface loads (form B: hang the
     * Fabric-side interface under the NeoForge extension). The re-declared default bodies are kept;
     * they call the no-context vanilla methods, which is what the extension's defaults do too.
     */
    private static final String FABRIC_BLOCK_STATE_MODEL =
            "net/fabricmc/fabric/api/client/renderer/v1/model/FabricBlockStateModel";

    private static final String BLOCK_STATE_MODEL_EXTENSION =
            "net/neoforged/neoforge/client/extensions/BlockStateModelExtension";

    /**
     * The block-state half of the same story as {@link #FABRIC_BLOCK_STATE_MODEL}: Fabric's
     * {@code FabricBlockState} and NeoForge's {@code IBlockStateExtension} both declare a default
     * {@code getAppearance(BlockAndLightGetter, BlockPos, Direction, BlockState, BlockPos)} with the
     * same erased descriptor, and both interfaces end up on {@code BlockState} (NeoForge injects its
     * extension at compile time, fabric-block-api-v1's {@code BlockStateMixin} adds Fabric's). A class
     * inheriting two unrelated same-signature defaults dies with
     * {@code IncompatibleClassChangeError: Conflicting default methods} the first time one is called -
     * Create's connected-texture code does exactly that while rendering a schematic. Hanging
     * {@code FabricBlockState} under {@code IBlockStateExtension} gives the two defaults one chain, so
     * Fabric's own (equal in meaning, delegating to the block) wins by being most specific.
     */
    private static final String FABRIC_BLOCK_STATE =
            "net/fabricmc/fabric/api/block/v1/FabricBlockState";

    private static final String IBLOCK_STATE_EXTENSION =
            "net/neoforged/neoforge/common/extensions/IBlockStateExtension";

    /**
     * The item half of the same story as {@link #FABRIC_BLOCK_STATE}: Fabric's {@code FabricItemStack} and
     * NeoForge's {@code ItemInstanceExtension} both declare a default {@code getCraftingRemainder()}, and
     * both interfaces end up on {@code ItemStack} - Fabric's through the duck fallback, NeoForge's through
     * its own build (and, in a dev run, through the same fallback). The JVM refuses to choose between two
     * unrelated defaults and the first use dies with
     * {@code IncompatibleClassChangeError: Conflicting default methods}; a brewing stand does exactly that
     * while brewing, which is what fabric-item-api-v1's gametest hits. Hanging Fabric's interface under
     * NeoForge's gives the two defaults one chain, where Fabric's overrides as it should.
     */
    private static final String FABRIC_ITEM_STACK =
            "net/fabricmc/fabric/api/item/v1/FabricItemStack";

    private static final String ITEM_INSTANCE_EXTENSION =
            "net/neoforged/neoforge/common/extensions/ItemInstanceExtension";

    /**
     * The block-side twin of {@link #FABRIC_BLOCK_STATE}: {@code FabricBlock.getAppearance(BlockState,
     * BlockAndLightGetter, BlockPos, Direction, BlockState, BlockPos)} and NeoForge's
     * {@code IBlockExtension.getAppearance(...)} have the same erased descriptor and both default to
     * {@code return state} - and both interfaces sit on {@code Block}. Fabric's state-level default
     * calls {@code block.getAppearance(...)}, so the block-level conflict would fire on the very next
     * frame after the state-level one is fixed.
     */
    private static final String FABRIC_BLOCK =
            "net/fabricmc/fabric/api/block/v1/FabricBlock";

    private static final String IBLOCK_EXTENSION =
            "net/neoforged/neoforge/common/extensions/IBlockExtension";

    /**
     * Fabric's own access-widener/class-tweaker rules, read out of every mod's {@code accessWidener}
     * file by Fabric's {@code loadClassTweakers()} (called from {@code FabricBridge.bootIfNeeded}).
     *
     * <p>In Fabric these rules are applied by {@code FabricTransformer} while Knot loads a class. The
     * bridge does not use Knot, so nothing applied them here and every mod that widens or un-finals a
     * game class broke: iris declares {@code extendable class net/minecraft/client/OptionInstance}
     * and its {@code ShadowDistanceOption} then died with "cannot inherit from final class", which
     * happens the moment {@code Minecraft} starts.
     *
     * <p>The rules are applied by handing the class to Fabric's own
     * {@link ClassTweaker#createClassVisitor}, so the whole rule language (v1/v2 headers, transitive
     * forms, member access, class access, un-final, injected interfaces, enum extensions) behaves
     * exactly as it does on a Fabric install instead of being re-implemented here.
     */
    private static volatile ClassTweaker fabricClassTweaker;

    /**
     * {@return Fabric's class tweaker, or {@code null} when the loader is not reachable} The very same
     * instance is populated by {@code loadClassTweakers()}, so caching it before the rules are read is
     * safe.
     */
    private static ClassTweaker fabricClassTweaker() {
        var tweaker = fabricClassTweaker;
        if (tweaker != null) {
            return tweaker;
        }
        try {
            tweaker = FabricLoaderImpl.INSTANCE.getClassTweaker();
            fabricClassTweaker = tweaker;
            return tweaker;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isFabricClassTweakerTarget(String internalName) {
        var tweaker = fabricClassTweaker();
        if (tweaker == null) {
            return false;
        }
        try {
            return tweaker.getTargets().contains(internalName);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Hand the class to Fabric's class tweaker and adopt the result.
     *
     * <p>Only access flags, interfaces and (for enum extensions) members are ever changed, so the
     * transformed class is copied back onto the node the pipeline owns; the method bodies and their
     * stack map frames travel through unchanged.
     */
    private static boolean applyFabricClassTweaker(ClassNode node) {
        var tweaker = fabricClassTweaker();
        if (tweaker == null) {
            return false;
        }
        try {
            var output = new ClassNode();
            node.accept(tweaker.createClassVisitor(FabricLoaderImpl.ASM_VERSION, output, null));

            node.version = output.version;
            node.access = output.access;
            node.name = output.name;
            node.signature = output.signature;
            node.superName = output.superName;
            node.interfaces = output.interfaces;
            node.sourceFile = output.sourceFile;
            node.sourceDebug = output.sourceDebug;
            node.module = output.module;
            node.outerClass = output.outerClass;
            node.outerMethod = output.outerMethod;
            node.outerMethodDesc = output.outerMethodDesc;
            node.visibleAnnotations = output.visibleAnnotations;
            node.invisibleAnnotations = output.invisibleAnnotations;
            node.visibleTypeAnnotations = output.visibleTypeAnnotations;
            node.invisibleTypeAnnotations = output.invisibleTypeAnnotations;
            node.attrs = output.attrs;
            node.innerClasses = output.innerClasses;
            node.nestHostClass = output.nestHostClass;
            node.nestMembers = output.nestMembers;
            node.permittedSubclasses = output.permittedSubclasses;
            node.recordComponents = output.recordComponents;
            node.fields = output.fields;
            node.methods = output.methods;

            LOGGER.debug("[NeoFobric] Applied the Fabric class tweaker (access widener rules) to {}",
                    node.name.replace('/', '.'));
            return true;
        } catch (Throwable t) {
            LOGGER.error("[NeoFobric] Failed to apply the Fabric class tweaker to {}", node.name, t);
            return false;
        }
    }

    /**
     * Classes whose private members Fabric mixins reach directly, keyed by class, valued by member name.
     *
     * <p>Fabric normally relies on its own access wideners to touch vanilla internals. NeoForge's access
     * transformer covers a different set, so every gap becomes an {@code IllegalAccessError} while the
     * game is starting - and because the JVM reports it as the class is loaded, it shows up as an opaque
     * crash rather than anything Fabric-side. Widening the member is what Fabric's own access widener
     * would have done.
     *
     * <p>Grows as new gaps surface; the values are simple member names, matched exactly.
     */
    private static final Map<String, Set<String>> WIDEN_PRIVATE_MEMBERS = buildPinnedWidenMembers();

    /**
     * The pinned entries start as plain sorted sets; the first {@code registerWidenedMembers} merge
     * copies each one it touches into a {@link java.util.concurrent.ConcurrentSkipListSet}. Keeping
     * them mutable (never {@code Set.of}) remains the rule for any future pin: an immutable value once
     * made the merge throw {@code UnsupportedOperationException}, an exception the jar patcher
     * swallowed at debug level, so every owner alphabetically after the first pinned class the scan hit
     * was silently never registered (that is how GrassColor.pixels could be found by the scan yet
     * still crash at runtime).
     */
    private static Map<String, Set<String>> buildPinnedWidenMembers() {
        var map = new java.util.concurrent.ConcurrentHashMap<String, Set<String>>();
        map.put("net/minecraft/client/renderer/SectionBufferBuilderPool", new java.util.TreeSet<>(Set.of("<init>", "allocate")));
        map.put("net/minecraft/world/item/CreativeModeTabs", new java.util.TreeSet<>(Set.of("CACHED_PARAMETERS")));
        map.put("com/mojang/blaze3d/vertex/PoseStack$Pose", new java.util.TreeSet<>(Set.of("trustedNormals")));
        map.put("net/minecraft/server/packs/metadata/pack/PackMetadataSection", new java.util.TreeSet<>(Set.of("codecForPackType", "<init>")));
        map.put("net/minecraft/server/packs/repository/FolderRepositorySource", new java.util.TreeSet<>(Set.of("packSource")));
        map.put("net/minecraft/server/packs/repository/PackRepository", new java.util.TreeSet<>(Set.of("sources")));
        return map;
    }

    /**
     * Adds members found by {@link MixinAccessScanner}. Called while the game layer is built, once per
     * Fabric mod jar - and those scan-and-merge calls run <em>in parallel</em>, so the per-owner sets
     * this merges into have to be thread-safe. They started out as {@code TreeSet}s, and two jars whose
     * scans reached the same owner corrupted one red-black tree between them: one worker died with a
     * {@code NullPointerException} out of {@code TreeMap.rotateLeft} while the other spun forever inside
     * {@code TreeSet.add} on the broken tree - the client hung in its patching phase, two cores pegged.
     */
    public static void registerWidenedMembers(Map<String, Set<String>> found) {
        found.forEach((owner, members) -> {
            // compute() is atomic per key, so exactly one thread builds the concurrent set for an
            // owner and every later merge - and every reader above - sees that same instance. The add
            // below is then safe, and iteration by the class transform is weakly consistent instead of
            // throwing ConcurrentModificationException.
            var existing = WIDEN_PRIVATE_MEMBERS.compute(owner, (key, current) -> {
                if (current instanceof java.util.concurrent.ConcurrentSkipListSet) {
                    return current;
                }
                var concurrent = new java.util.concurrent.ConcurrentSkipListSet<String>();
                if (current != null) {
                    concurrent.addAll(current);
                }
                return concurrent;
            });
            for (var member : members) {
                if (existing.add(member)) {
                    // Debug, not info: the scan widens every private member of every referenced
                    // class, which is tens of thousands of members - printing them was 98% of the
                    // whole log and cost real startup time in synchronous console writes. The
                    // per-jar summary (Access scan of ...) carries the counts.
                    LOGGER.debug("[NeoFobric] Will widen {}.{} to public (reached by a Fabric mixin)",
                            owner.replace('/', '.'), member);
                }
            }
        });
    }
    private static final String SEND_INITIAL_REGISTRATION = "sendInitialChannelRegistrationPacket";

    private static final Set<String> RESOURCE_IMPL_CANDIDATES = Set.of(
            "net/minecraft/server/packs/resources/PathResource",
            "net/minecraft/server/packs/resources/FileResource",
            "net/minecraft/server/packs/resources/Resource",
            "net/minecraft/server/packs/PathPackResources",
            "net/minecraft/server/packs/resources/FallbackResourceManager");

    @Override
    public ProcessorName name() {
        return new ProcessorName("neofobric", "class_tweaker");
    }

    @Override
    public boolean handlesClass(SelectionContext selection) {
        if (!FabricBridge.hasFabricMods()) {
            return false;
        }
        if (Boolean.getBoolean("neofobric.noClassTweaker")) {
            return false;
        }
        var name = selection.type().getInternalName();
        if (DUCK_FALLBACKS.containsKey(name) || ACCESSOR_FIELDS.containsKey(name)) {
            return true;
        }
        return RESOURCE_IMPL_CANDIDATES.contains(name) || FABRIC_CHANNEL_CLASSES.contains(name)
                || FABRIC_NETWORKING_IMPL.equals(name)
                || BUILT_IN_REGISTRIES.equals(name) || INGREDIENT.equals(name)
                || PolymerCompat.handlesClass(name)
                || FABRIC_BLOCK_STATE_MODEL.equals(name) || FABRIC_BLOCK_STATE.equals(name) || FABRIC_BLOCK.equals(name)
                || FABRIC_ITEM_STACK.equals(name)
                || WIDEN_PRIVATE_MEMBERS.containsKey(name)
                || InjectedInterfaceSource.get().containsKey(name)
                || ENUM_EXTENSION_TARGETS.contains(name)
                || isFabricClassTweakerTarget(name);
    }

    @Override
    public ComputeFlags processClass(TransformationContext context) {
        // Whichever of the bridge's processors sees a class first asks Mixin to prepare the Fabric configs;
        // until that has happened, a config's target class would keep no mixins from it, silently. See
        // FabricBootstrapProcessor#forceMixinPreparationIfPending.
        FabricBootstrapProcessor.forceMixinPreparationIfPending();
        // Guarded: this runs before every other decision, so anything it throws would abort the whole
        // transformation for this class - which is how a missing interface injection could look like
        // "handlesClass said yes but nothing happened".
        boolean isResourceImpl;
        try {
            isResourceImpl = FABRIC_RESOURCE_INTERFACE.equals(pendingResourceInterface(context.node()));
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] pendingResourceInterface failed for {}", context.node().name, t);
            isResourceImpl = false;
        }
        if (!DUCK_FALLBACKS.containsKey(context.node().name) && !isResourceImpl
                && !ACCESSOR_FIELDS.containsKey(context.node().name)
                && !FABRIC_CHANNEL_CLASSES.contains(context.node().name)
                && !FABRIC_NETWORKING_IMPL.equals(context.node().name)
                && !BUILT_IN_REGISTRIES.equals(context.node().name)
                && !INGREDIENT.equals(context.node().name)
                && !PolymerCompat.handlesClass(context.node().name)
                && !FABRIC_BLOCK_STATE_MODEL.equals(context.node().name)
                && !FABRIC_BLOCK_STATE.equals(context.node().name)
                && !FABRIC_BLOCK.equals(context.node().name)
                && !FABRIC_ITEM_STACK.equals(context.node().name)
                && !WIDEN_PRIVATE_MEMBERS.containsKey(context.node().name)
                && !InjectedInterfaceSource.get().containsKey(context.node().name)
                && !ENUM_EXTENSION_TARGETS.contains(context.node().name)
                && !isFabricClassTweakerTarget(context.node().name)) {
            return ComputeFlags.NO_REWRITE;
        }

        var node = context.node();
        var changed = false;
        // A Fabric enum extension cannot apply while the platform's marker sits on the class - FML rejects the
        // mixin for it - and a Fabric mod has no way to know about the platform API that marker stands for.
        if (ENUM_EXTENSION_TARGETS.contains(node.name) && node.interfaces.remove(I_EXTENSIBLE_ENUM)) {
            changed = true;
            LOGGER.info("[NeoFobric] Dropped the IExtensibleEnum marker from {} so the Fabric enum extension applies",
                    node.name.replace('/', '.'));
        }
        // Polymer's tab teardown writes a static int page cursor reflectively - see PolymerCompat.
        if (PolymerCompat.giveCreativeScreenPageCursor(node)) {
            changed = true;
        }
        // First: a mod's access widener can widen or un-final anything, and the rest of this method
        // (and Mixin, which runs later) should see the tweaked class.
        if (isFabricClassTweakerTarget(node.name)) {
            changed |= applyFabricClassTweaker(node);
            // A widener that makes a private method accessible leaves it un-overridable - that is the
            // point for a private method, nobody was allowed to override it - and the visitor spells that out
            // by marking the now public method final. Every other mod then reads "cannot override" for a
            // method the platform has always allowed overriding, and a NeoForge mod that overrides it dies on
            // IncompatibleClassChangeError at class load (sophisticatedbackpacks overriding
            // AbstractContainerScreen#getHoveredSlot). Dropping the flag only relaxes the class.
            var unfinalised = 0;
            for (var method : node.methods) {
                if ((method.access & Opcodes.ACC_FINAL) != 0) {
                    method.access &= ~Opcodes.ACC_FINAL;
                    unfinalised++;
                }
            }
            if (unfinalised > 0) {
                changed = true;
                LOGGER.debug("[NeoFobric] Dropped ACC_FINAL from {} method(s) of {} so the class stays overridable",
                        unfinalised, node.name.replace('/', '.'));
            }
        }
        var addedMethods = false;
        var duckFallback = DUCK_FALLBACKS.get(context.node().name);
        if (duckFallback != null || ACCESSOR_FIELDS.containsKey(context.node().name)) {
            changed |= applyDuckFallback(node, duckFallback != null ? duckFallback : List.of());
            addedMethods = true;
        }
        if (isResourceImpl && findMethod(node, "getFabricPackSource",
                "()Lnet/minecraft/server/packs/repository/PackSource;") == null) {
            changed |= applyDuckFallback(node, List.of(FABRIC_RESOURCE_INTERFACE));
            addedMethods = true;
        }
        if (FABRIC_CHANNEL_CLASSES.contains(node.name)) {
            changed |= suppressFabricChannelPackets(node);
        }
        if (FABRIC_NETWORKING_IMPL.equals(node.name)) {
            changed |= renameFabricCommonChannelIds(node);
        }
        if (BUILT_IN_REGISTRIES.equals(node.name)) {
            changed |= widenCreateContents(node);
        }
        if (INGREDIENT.equals(node.name)) {
            changed |= dropFinal(node);
        }
        if (FABRIC_BLOCK_STATE_MODEL.equals(node.name)) {
            changed |= hangFabricBlockStateModelUnderExtension(node);
        }
        if (FABRIC_BLOCK_STATE.equals(node.name)) {
            changed |= hangInterfaceUnder(node, IBLOCK_STATE_EXTENSION);
        }
        if (FABRIC_BLOCK.equals(node.name)) {
            changed |= hangInterfaceUnder(node, IBLOCK_EXTENSION);
        }
        if (FABRIC_ITEM_STACK.equals(node.name)) {
            changed |= hangInterfaceUnder(node, ITEM_INSTANCE_EXTENSION);
        }
        var toWiden = WIDEN_PRIVATE_MEMBERS.get(node.name);
        if (toWiden != null) {
            changed |= widenMembers(node, toWiden);
            if ((node.access & Opcodes.ACC_PUBLIC) == 0) {
                // Fabric's access wideners make these classes accessible ("accessible class"); a
                // non-public game class that Fabric bytecode names directly - sodium reaching the
                // package-private GlDevice - fails class resolution with IllegalAccessError even when
                // every member is already public.
                node.access |= Opcodes.ACC_PUBLIC;
                changed = true;
                LOGGER.debug("[NeoFobric] Widened class {} to public (Fabric bytecode references it directly)",
                        node.name.replace('/', '.'));
            }
        }
        var missing = InjectedInterfaceSource.get().get(node.name);
        // Off by default: the interfaces are already present at runtime (the dev jar carries them),
        // so injecting them again is redundant - and harmful where Fabric's own mixin adds an interface
        // with a method of the same name, because two unrelated defaults of the same signature make the
        // class unloadable with "Conflicting default methods". Kept behind a flag for a jar that really
        // is missing them.
        if (missing != null && Boolean.getBoolean("neofobric.injectInterfaces")) {
            changed |= injectMissingInterfaces(node, missing);
        }
        // Whatever the class already declares for those interfaces has to be public, or the JVM will
        // not use it and will not fall back to the interface's default either: a class's own method of
        // the same signature hides the default even when it is private.
        // Also off by default. It matched on name and parameters only, which made it hit synthetic
        // lambdas (lambda$lookupOrThrow$0) as well as the intended methods; ensurePublicSelf below
        // handles the case that actually matters, precisely.
        if (missing != null && Boolean.getBoolean("neofobric.unshadowInterfaces")) {
            changed |= unshadowInterfaceMethods(node, node.interfaces);
        }
        if (SELF_IMPLEMENTATIONS.containsKey(node.name)) {
            changed |= ensurePublicSelf(node, SELF_IMPLEMENTATIONS.get(node.name));
        }

        if (changed) {
            context.audit("neofobric.class_tweaker", "applied class tweaker rules");
        }
        if (addedMethods) {
            return ComputeFlags.COMPUTE_MAXS;
        }
        return changed ? ComputeFlags.SIMPLE_REWRITE : ComputeFlags.NO_REWRITE;
    }

    /**
     * Moves Fabric's own {@code minecraft:register}/{@code minecraft:unregister} channels onto ids of their
     * own ({@code minecraft:fabric_register} / {@code minecraft:fabric_unregister}).
     *
     * <p>Both loaders negotiate modded channels, and both originally did it on those two ids with different
     * wire formats: Fabric sends its {@code RegistrationPayload}, NeoForge its {@code MinecraftRegisterPayload}.
     * The collision used to be handled by stubbing Fabric's sends out ({@link #suppressFabricChannelPackets}),
     * which cost Fabric mods their channel declarations - the peer's "what can you receive" set stayed empty -
     * until it broke a mod that gates on {@code ServerConfigurationNetworking#canSend}. Renaming the two
     * constants lets both exchanges run side by side: Fabric's payloads travel on their own ids, where its own
     * payload lookup (its packet mixins wrap the vanilla {@code CustomPacketPayload#codec} call, see
     * {@code ClientboundCustomPayloadPacket}) answers for them, and NeoForge keeps its own ids untouched.
     *
     * <p>The ids are built in {@code NetworkingImpl}'s static initialiser as
     * {@code Identifier.withDefaultNamespace("register")}; every other user reads the resulting constant,
     * so rewriting the two literals here covers Fabric's whole stack. The resulting ids are also listed in
     * {@code FabricBridgeHooks#isFabricChannel}, so NeoForge's send gate lets them through.
     */
    private static boolean renameFabricCommonChannelIds(ClassNode node) {
        var changed = false;
        for (var method : node.methods) {
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) || !(ldc.cst instanceof String literal)) {
                    continue;
                }
                if (!"register".equals(literal) && !"unregister".equals(literal)) {
                    continue;
                }
                if (!(insn.getNext() instanceof MethodInsnNode call)
                        || !"withDefaultNamespace".equals(call.name)
                        || !"net/minecraft/resources/Identifier".equals(call.owner)) {
                    continue;
                }
                ldc.cst = "fabric_" + literal;
                changed = true;
            }
        }
        if (changed) {
            LOGGER.info("[NeoFobric] Moved Fabric's register/unregister channels off the ids NeoForge claims");
        }
        return changed;
    }

    /**
     * Stubs Fabric's channel registration and de-registration sends out, so the bridge can keep Fabric's
     * channel bookkeeping out of the wire.
     *
     * <p>This was the bridge's answer to the {@code minecraft:register} collision: Fabric's
     * {@code RegistrationPayload} and NeoForge's {@code MinecraftRegisterPayload} claimed the same ids with
     * different formats, and encoding one through the other's codec killed the connection. That collision is
     * gone since {@link #renameFabricCommonChannelIds} moved Fabric's constants to ids of their own, so the
     * exchange is left alone and Fabric's side of the channel negotiation works again - which is what the
     * mods asking {@code ServerConfigurationNetworking#canSend} need. The switch remains for diagnosing a
     * Fabric peer whose declarations misbehave: {@code -Dneofobric.suppressFabricChannelPackets=true}.
     */
    private static boolean suppressFabricChannelPackets(ClassNode node) {
        if (!Boolean.getBoolean("neofobric.suppressFabricChannelPackets")) {
            return false;
        }

        var changed = false;
        for (var method : node.methods) {
            var isInitialRegistration = SEND_INITIAL_REGISTRATION.equals(method.name) && "()V".equals(method.desc);
            var isIncremental = FABRIC_CHANNEL_PACKET_SENDERS.contains(method.name)
                    && CHANNEL_NAME_DESC.equals(method.desc);
            if (isInitialRegistration || isIncremental) {
                changed |= stubOut(method, node);
            }
        }
        return changed;
    }

    /**
     * Makes {@code BuiltInRegistries#createContents()} public.
     *
     * <p>Fabric's {@code BootstrapMixin} redirects {@code Bootstrap.bootStrap()} to that method, which
     * NeoForge's tree declares private. Without this the very first call aborts startup with
     * {@code IllegalAccessError}, so not even the main menu is reached.
     */
    private static boolean widenCreateContents(ClassNode node) {
        for (var method : node.methods) {
            if (!"createContents".equals(method.name) || !"()V".equals(method.desc)) {
                continue;
            }
            if ((method.access & Opcodes.ACC_PUBLIC) != 0) {
                return false;
            }
            method.access = (method.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            LOGGER.info("[NeoFobric] Widened {}#createContents to public for Fabric's BootstrapMixin",
                    node.name.replace('/', '.'));
            return true;
        }
        return false;
    }

    /**
     * Makes {@code FabricBlockStateModel} extend NeoForge's {@code BlockStateModelExtension}.
     *
     * <p>See {@link #FABRIC_BLOCK_STATE_MODEL} for why the supertype edge has to be added here. This
     * is the same shape the Forgified Fabric API ships in its own source. Idempotent: an interface
     * already present is left alone.
     */
    private static boolean hangFabricBlockStateModelUnderExtension(ClassNode node) {
        return hangInterfaceUnder(node, BLOCK_STATE_MODEL_EXTENSION);
    }

    /**
     * Adds {@code superInterface} to the interface {@code node} when it is not there yet, giving two
     * interfaces with same-signature defaults one inheritance chain instead of a conflict.
     */
    private static boolean hangInterfaceUnder(ClassNode node, String superInterface) {
        if (node.interfaces.contains(superInterface)) {
            return false;
        }
        node.interfaces.add(superInterface);
        LOGGER.info("[NeoFobric] Made {} extend {} so Fabric's and NeoForge's defaults share one chain",
                node.name.replace('/', '.'), superInterface.replace('/', '.'));
        return true;
    }

    /**
     * Makes the named members public.
     *
     * <p>Members Fabric's mixins reference but NeoForge's tree keeps private. The JVM raises
     * {@code IllegalAccessError} when the accessing class is loaded, so the game dies during startup
     * with a stack trace pointing at a mixin rather than at a missing access widener entry.
     */
    private static boolean widenMembers(ClassNode node, Set<String> members) {
        var changed = false;
        for (var field : node.fields) {
            if (members.contains(field.name) && (field.access & Opcodes.ACC_PUBLIC) == 0) {
                field.access = (field.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                changed = true;
                // Debug: roughly ten thousand members get widened per launch, and one INFO line each
                // was the single largest cost in the startup log.
                LOGGER.debug("[NeoFobric] Widened field {}#{} to public", node.name.replace('/', '.'), field.name);
            }
        }
        for (var method : node.methods) {
            if (members.contains(method.name) && (method.access & Opcodes.ACC_PUBLIC) == 0) {
                method.access = (method.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                changed = true;
                LOGGER.debug("[NeoFobric] Widened method {}#{} to public", node.name.replace('/', '.'), method.name);
            }
        }
        return changed;
    }

    /**
     * Adds the interfaces NeoForge declares for this class but that its runtime tree is missing.
     *
     * <p>In a development run the Minecraft classes come from the access-transformed vanilla jar, which
     * never went through NeoForge's interface-injection build step. The result is an
     * {@code AbstractMethodError} the first time a default method is used - for example ticking a glow
     * squid, which reaches {@code ILivingEntityExtension#canDrownInFluidType} and then the missing
     * {@code self()}. Adding the interface is what the build step would have done; because the interfaces
     * carry default implementations, no method has to be generated.
     *
     * <p>Idempotent: an interface the class already declares is skipped, so a jar that has the injection
     * is unaffected.
     */
    private static boolean injectMissingInterfaces(ClassNode node, List<String> interfaces) {
        var changed = false;
        for (var declared : interfaces) {
            // The declaration carries generics (IRegistryExtension<T>, Comparable<ResourceKey<?>>); the
            // class file needs the raw name. Injecting the parameterised form makes Mixin's ClassInfo
            // lookup miss, and its failure surfaces far away - as a NullPointerException while analysing
            // an unrelated @ModifyExpressionValue.
            var iface = stripGenerics(declared);
            if (!iface.isEmpty() && !node.interfaces.contains(iface)) {
                node.interfaces.add(iface);
                changed = true;
                LOGGER.info("[NeoFobric] Added missing interface {} to {}",
                        iface.replace('/', '.'), node.name.replace('/', '.'));
            }
        }
        return changed;
    }

    /**
     * Makes methods that implement an injected interface public.
     *
     * <p>A class-level method hides an interface's default method of the same signature even when the
     * class-level one is private, and the JVM then refuses to use it because it is not accessible. The
     * interface cannot supply its default either, so the call site dies with {@code AbstractMethodError}
     * naming a method the class appears to have. {@code LivingEntity} arrived with a private
     * {@code self()} shadowing {@code ILivingEntityExtension.self()}.
     *
     * <p>Only methods whose name and parameter types match a method the interface declares are touched,
     * and only to raise their visibility.
     */
    /**
     * Classes that must expose a public {@code self()}.
     *
     * <p>{@code IEntityExtension} declares {@code self()} as private, and {@code ILivingEntityExtension}
     * overrides it with a default returning the narrowed type. A Fabric mixin on the class calls
     * {@code this.self()}, which Mixin satisfies by materialising the method on the target - privately,
     * because that is how the declaring interface has it. A class-level private method hides the
     * interface default even from the JVM, and is not accessible to the interface's own default methods
     * either, so {@code ILivingEntityExtension.canDrownInFluidType} blew up with
     * {@code AbstractMethodError: GlowSquid does not define or inherit ... self()} the moment a glow
     * squid ticked.
     *
     * <p>Declaring the method public up front leaves the mixin nothing to add and gives the default
     * methods something to reach.
     */
    private static final Map<String, String> SELF_IMPLEMENTATIONS = Map.of(
            "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/LivingEntity");

    /**
     * Ensures the class declares a public {@code self()} returning its own type, either by widening an
     * existing one or by adding the trivial {@code return (Type) this;}.
     */
    private static boolean ensurePublicSelf(ClassNode node, String returnType) {
        var desc = "()L" + returnType + ";";
        for (var method : node.methods) {
            if ("self".equals(method.name) && desc.equals(method.desc)) {
                if ((method.access & Opcodes.ACC_PUBLIC) != 0) {
                    return false;
                }
                method.access = (method.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                        | Opcodes.ACC_PUBLIC;
                LOGGER.info("[NeoFobric] Widened existing {}.self(){} to public", node.name.replace('/', '.'), desc);
                return true;
            }
        }
        // No method yet: add the trivial implementation. Branch-free, so no stack map frame is needed -
        // FML does not recompute frames for what a class processor adds.
        var method = new MethodNode(Opcodes.ACC_PUBLIC, "self", desc, null, null);
        method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, returnType));
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        node.methods.add(method);
        LOGGER.info("[NeoFobric] Added public {}.self(){} so its interface default is reachable",
                node.name.replace('/', '.'), desc);
        return true;
    }

    private static boolean unshadowInterfaceMethods(ClassNode node, List<String> interfaces) {
        var changed = false;
        for (var iface : interfaces) {
            var interfaceMethods = interfaceMethodsOf(iface);
            if (interfaceMethods.isEmpty()) {
                continue;
            }
            for (var method : node.methods) {
                if ((method.access & Opcodes.ACC_PUBLIC) != 0 || (method.access & Opcodes.ACC_PRIVATE) == 0) {
                    continue;
                }
                if (interfaceMethods.contains(method.name + method.desc)) {
                    method.access = (method.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                            | Opcodes.ACC_PUBLIC;
                    changed = true;
                    LOGGER.info("[NeoFobric] Made shadowing {}.{}{} public so its interface default is reachable",
                            node.name.replace('/', '.'), method.name, method.desc);
                }
            }
        }
        return changed;
    }

    /** Cache of interface name to its "name + descriptor" method keys. */
    private static final Map<String, Set<String>> INTERFACE_METHODS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@return the {@code name + descriptor} of every method the interface declares, or an empty set
     * when its class file cannot be read}
     *
     * <p>Read from the class file rather than reflection: this runs while classes are being transformed,
     * and loading an interface then can pull in classes that are still being defined.
     */
    private static Set<String> interfaceMethodsOf(String iface) {
        return INTERFACE_METHODS.computeIfAbsent(iface, key -> {
            try (var in = NeoFobricClassTweakerProcessor.class.getClassLoader()
                    .getResourceAsStream(key + ".class")) {
                if (in == null) {
                    return Set.of();
                }
                var probe = new ClassNode();
                new org.objectweb.asm.ClassReader(in.readAllBytes())
                        .accept(probe, org.objectweb.asm.ClassReader.SKIP_CODE
                                | org.objectweb.asm.ClassReader.SKIP_FRAMES);
                var methods = new java.util.HashSet<String>();
                for (var method : probe.methods) {
                    // "<init>" never comes from an interface, and a static initialiser is not a method
                    // an implementor provides.
                    if (!"<init>".equals(method.name) && !"<clinit>".equals(method.name)) {
                        methods.add(method.name + method.desc);
                    }
                }
                return Set.copyOf(methods);
            } catch (Throwable t) {
                return Set.of();
            }
        });
    }

    private static boolean dropFinal(ClassNode node) {
        if ((node.access & Opcodes.ACC_FINAL) == 0) {
            return false;
        }
        node.access &= ~Opcodes.ACC_FINAL;
        LOGGER.info("[NeoFobric] Dropped ACC_FINAL from {} so Fabric can extend it",
                node.name.replace('/', '.'));
        return true;
    }

    private static boolean stubOut(MethodNode method, ClassNode node) {
        var body = new InsnList();
        body.add(new InsnNode(Opcodes.RETURN));
        method.instructions.clear();
        method.tryCatchBlocks = new java.util.ArrayList<>();
        method.localVariables = null;
        method.instructions.add(body);
        method.maxStack = 0;
        method.maxLocals = 1 + org.objectweb.asm.Type.getArgumentTypes(method.desc).length;
        LOGGER.info("[NeoFobric] Suppressed {}{} so Fabric stops sending its own channel packet",
                node.name.replace('/', '.'), method.desc);
        return true;
    }

    private static boolean applyDuckFallback(ClassNode node, List<String> interfaces) {
        var changed = false;

        var recorded = ACCESSOR_FIELDS.get(node.name);
        if (recorded != null) {
            for (var entry : recorded.entrySet()) {
                var methodName = entry.getKey();
                var methodDesc = inferGetterDesc(methodName, entry.getValue()[1]);
                if (methodDesc == null || findMethod(node, methodName, methodDesc) != null) {
                    continue;
                }
                node.methods.add(accessorGetter(entry.getValue()[2], methodName, methodDesc,
                        entry.getValue()[0], entry.getValue()[1]));
                changed = true;
                LOGGER.info("[NeoFobric] Synthesised accessor {}#{} reading field {} of {}",
                        node.name.replace('/', '.'), methodName, entry.getValue()[0], entry.getValue()[2]);
            }
        }

        for (var iface : interfaces) {
            if (!node.interfaces.contains(iface)) {
                node.interfaces.add(iface);
                changed = true;
            }

            var ifaceBytes = readFromFabricJars(iface);
            if (ifaceBytes == null) {
                continue;
            }
            var ifaceNode = readClassNode(ifaceBytes);
            if (ifaceNode == null) {
                continue;
            }
            for (var method : ifaceNode.methods) {
                if ((method.access & Opcodes.ACC_ABSTRACT) == 0 || method.name.equals("<init>")) {
                    continue;
                }
                if (findMethod(node, method.name, method.desc) != null) {
                    continue;
                }
                var real = specialImplementation(iface, node, method);
                node.methods.add(real != null ? real : syntheticImplementation(method));
                changed = true;
            }
        }
        return changed;
    }

    private static MethodNode specialImplementation(String iface, ClassNode node, MethodNode method) {
        if (!FABRIC_RESOURCE_INTERFACE.equals(iface) || !"getFabricPackSource".equals(method.name)) {
            return null;
        }
        if (findMethod(node, "source", "()Lnet/minecraft/server/packs/PackResources;") == null) {
            return null;
        }

        var synthetic = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "getFabricPackSource",
                "()Lnet/minecraft/server/packs/repository/PackSource;",
                null, null);
        var body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "net/minecraft/server/packs/resources/Resource",
                "source", "()Lnet/minecraft/server/packs/PackResources;", true));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "net/fabricmc/fabric/impl/resource/PackSourceTracker",
                "getSource", "(Lnet/minecraft/server/packs/PackResources;)Lnet/minecraft/server/packs/repository/PackSource;",
                false));
        body.add(new InsnNode(Opcodes.ARETURN));
        synthetic.instructions = body;
        synthetic.maxStack = 1;
        synthetic.maxLocals = 1;
        LOGGER.info("[NeoFobric] Synthesised FabricResource#getFabricPackSource for {}", node.name.replace('/', '.'));
        return synthetic;
    }

    private static MethodNode syntheticImplementation(MethodNode method) {
        var synthetic = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                method.name, method.desc, null, null);
        var body = new InsnList();
        var returnType = org.objectweb.asm.Type.getReturnType(method.desc);
        switch (returnType.getSort()) {
            case org.objectweb.asm.Type.VOID -> body.add(new InsnNode(Opcodes.RETURN));
            case org.objectweb.asm.Type.BOOLEAN, org.objectweb.asm.Type.BYTE,
                    org.objectweb.asm.Type.CHAR, org.objectweb.asm.Type.SHORT,
                    org.objectweb.asm.Type.INT -> {
                body.add(new InsnNode(Opcodes.ICONST_0));
                body.add(new InsnNode(Opcodes.IRETURN));
            }
            case org.objectweb.asm.Type.LONG -> {
                body.add(new InsnNode(Opcodes.LCONST_0));
                body.add(new InsnNode(Opcodes.LRETURN));
            }
            case org.objectweb.asm.Type.FLOAT -> {
                body.add(new InsnNode(Opcodes.FCONST_0));
                body.add(new InsnNode(Opcodes.FRETURN));
            }
            case org.objectweb.asm.Type.DOUBLE -> {
                body.add(new InsnNode(Opcodes.DCONST_0));
                body.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                body.add(new InsnNode(Opcodes.ACONST_NULL));
                body.add(new InsnNode(Opcodes.ARETURN));
            }
        }
        synthetic.instructions = body;
        synthetic.maxStack = 2;
        synthetic.maxLocals = 1 + org.objectweb.asm.Type.getArgumentTypes(method.desc).length;
        return synthetic;
    }

    private static MethodNode accessorGetter(String owner, String methodName, String methodDesc,
            String fieldName, String fieldDesc) {
        var node = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                methodName, methodDesc, null, null);
        var body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new FieldInsnNode(Opcodes.GETFIELD, owner, fieldName, fieldDesc));
        body.add(new InsnNode(returnOpcode(fieldDesc)));
        node.instructions = body;
        node.maxStack = 1;
        node.maxLocals = 1;
        return node;
    }

    private static String inferGetterDesc(String methodName, String fieldDesc) {
        for (var prefix : new String[] { "get", "is" }) {
            if (methodName.length() > prefix.length() && methodName.startsWith(prefix)) {
                return "()" + fieldDesc;
            }
        }
        return null;
    }

    private static int returnOpcode(String fieldDesc) {
        return switch (fieldDesc) {
            case "J" -> Opcodes.LRETURN;
            case "D" -> Opcodes.DRETURN;
            case "F" -> Opcodes.FRETURN;
            case "I", "S", "B", "C", "Z" -> Opcodes.IRETURN;
            default -> Opcodes.ARETURN;
        };
    }

    private static String pendingResourceInterface(ClassNode node) {
        if (node.interfaces == null) {
            return null;
        }
        if (findMethod(node, "source", "()Lnet/minecraft/server/packs/PackResources;") == null) {
            return null;
        }
        for (var iface : node.interfaces) {
            if ("net/minecraft/server/packs/resources/Resource".equals(iface)
                    && !node.interfaces.contains(FABRIC_RESOURCE_INTERFACE)) {
                return FABRIC_RESOURCE_INTERFACE;
            }
        }
        return null;
    }

    private static String stripGenerics(String name) {
        int bracket = name.indexOf('<');
        return bracket >= 0 ? name.substring(0, bracket) : name;
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        for (var method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    private static byte[] readFromFabricJars(String internalName) {
        var path = internalName + ".class";
        for (var entry : FabricJarRegistry.getEntries().entrySet()) {
            try (var jar = JarContents.ofPath(entry.getKey())) {
                var bytes = jar.readFile(path);
                if (bytes != null) {
                    return bytes;
                }
            } catch (IOException e) {
                // try the next jar
            }
        }
        return null;
    }

    private static ClassNode readClassNode(byte[] bytes) {
        try {
            var node = new ClassNode();
            new org.objectweb.asm.ClassReader(bytes).accept(node,
                    org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return node;
        } catch (Exception e) {
            return null;
        }
    }
}