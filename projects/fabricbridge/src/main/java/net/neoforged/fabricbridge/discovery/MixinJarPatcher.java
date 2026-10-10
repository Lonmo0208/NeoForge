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
     * Members the platform <em>renamed</em> rather than merely reshaped, keyed by the old member
     * (name and descriptor, without the owning class).
     *
     * <p>{@link #ANNOTATION_TARGET_REALIGNMENTS} only swaps a parameter list, which cannot express a
     * rename. NeoForge's structure patch renames vanilla's {@code StructureTemplate#placeEntities} -
     * the half of a structure paste that spawns the template's entities - to
     * {@code addEntitiesToWorld} and reshapes it (the mirror, rotation and bounding box moved into
     * the place settings, and {@code processEntityInfos} pre-transforms the positions). The loop a
     * Fabric mod hooks is still the same loop: it iterates the same {@code processEntityInfos} result,
     * and its first argument is still the level, so pointing the old annotation at the new member
     * means the same thing. With the stale name the whole mixin found no target at all and was
     * skipped silently: the case that motivated this entry filters which entities a pasted schematic
     * may spawn through {@code EntityControlStructureProcessor}s.
     *
     * <p>The key is matched as the <em>tail</em> of an annotation value, so both spellings resolve: a
     * bare {@code method} selector ({@code placeEntities(...)V}) and a class-qualified {@code @At}
     * target ({@code L…StructureTemplate;placeEntities(...)V}).
     *
     * <p>Add an entry only after checking both sides against the versions actually installed.
     */
    private static final Map<String, String> ANNOTATION_MEMBER_RENAMES = Map.of(
            "placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;ZLnet/minecraft/util/ProblemReporter;)V",
            "addEntitiesToWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/ProblemReporter;)V");

    /**
     * The same realignment, scoped to one mixin class.
     *
     * <p>Needed when one descriptor is an injection <em>target</em> for one mixin and the <em>method
     * selector</em> for another in the same module: a global rewrite would send the second one's body
     * selector to a method it does not intend. Case at hand (fabric-registry-sync-v0): its
     * {@code WorldLoaderMixin} replaces the registry list passed to the vanilla four-argument
     * {@code RegistryDataLoader.load} inside {@code WorldLoader.lambda$load$0} - that substitution is
     * literally what loads modded dynamic registries. NeoForge's WorldLoader calls its own
     * five-argument overload instead (the extra list carries the static-layer tags for condition
     * contexts), so the vanilla-shaped call is absent, the injection is skipped silently, and every
     * modded dynamic registry stays unloaded - fabric-registry-sync's testmod then dies with
     * {@code Missing registry: fabric:test_dynamic_synced_1}. The module's sibling
     * {@code RegistryDataLoaderMixin} names the same four-argument list as its {@code method} selector,
     * and that one must keep pointing at the four-argument overload.
     */
    private static final Map<String, Map<String, String>> SCOPED_ANNOTATION_TARGET_REALIGNMENTS = Map.of(
            "net/fabricmc/fabric/mixin/registry/sync/WorldLoaderMixin",
            Map.of(
                    "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;)",
                    "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;Ljava/util/List;)"),
            // Same module, second half of the same story: wrapIsServerCall binds registry-sync's
            // IS_SERVER ScopedValue around the LoaderFactory call inside the four-argument overload.
            // NeoForge's five-argument overload builds its loader factory itself and calls the
            // LoaderFactory overload directly, so that shim is off the live path and the value is never
            // bound: RegistryDataLoader's own beforeLoad wrap then sees IS_SERVER=false and never fires
            // DynamicRegistrySetupCallback, and fabric-registry-sync's testmod - which asserts the
            // callback ran before the server starts - kills the server with
            // "DRM setup was not called before startup!". Pointing the wrap at the five-argument
            // overload puts it back on the live path.
            "net/fabricmc/fabric/mixin/registry/sync/RegistryDataLoaderMixin",
            Map.of(
                    "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;)",
                    "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;Ljava/util/List;)"),
            // fabric-networking-api-v1 keeps its "what can the peer receive" bookkeeping in the buffers the
            // connection hands out: ServerConfigurationPacketListenerImplMixin wraps the vanilla one-argument
            // RegistryFriendlyByteBuf#decorator and stamps every buffer it produces with the channels the peer
            // declared - which is what a payload writer reads while it is being encoded (Fabric API's own
            // play-phase test writes that set into a payload and asserts it is not null). NeoForge's decorator
            // takes the connection type as a second argument, so the one-argument call the wrap targets no
            // longer exists, the wrap is skipped silently, and the set stays null. Pointing the wrap at
            // NeoForge's overload puts the stamping back on the live path; the handler is widened to match by
            // {@link #widenWrapOperationHandlers}.
            "net/fabricmc/fabric/mixin/networking/ServerConfigurationPacketListenerImplMixin",
            Map.of(
                    "(Lnet/minecraft/core/RegistryAccess;)",
                    "(Lnet/minecraft/core/RegistryAccess;Lnet/neoforged/neoforge/network/connection/ConnectionType;)"),
            // fabric-gametest-api-v1 starts its server-side game tests from a HEAD injection into
            // RegistryDataLoader's four-argument load(...): it records there whether the load carries the test
            // registries, and its freeze-time hook then registers the tests into them. NeoForge moved the
            // asynchronous structure into the five-argument overload (the extra list carries the static-layer
            // tags for condition contexts) and left the four-argument one as a forwarder, so that HEAD never
            // ran on the live path, the flag stayed false, and the test server ran vanilla's single smoke test
            // and nothing else - with no warning anywhere. Pointing the injection at the overload that actually
            // runs fixes it; the handler's missing parameter is appended by extendInjectorHandlerParams.
            "net/fabricmc/fabric/mixin/gametest/RegistryDataLoaderMixin",
            Map.of(
                    "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;)",
                    "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;Ljava/util/List;)"),
            // fabric-transfer-api-v1 keeps a write's side effects off the world until its transaction commits by
            // wrapping the setChanged() call inside setItem(slot, stack) and skipping it while a write is in
            // flight - its VanillaStorageTests asserts an uncommitted insert schedules no comparator update.
            // NeoForge reshaped that method: the two-argument overload is now a forwarder and the side effects
            // (including setChanged()) live in the three-argument setItem(slot, stack, insideTransaction), so
            // the wrap found no call site in the overload it names and the suppression silently degraded - every
            // uncommitted write scheduled a comparator update. Pointing the wrap at the overload that really
            // calls setChanged() restores it; it stays correct next to NeoForge's own flag because both gate the
            // same call from opposite directions (Fabric sets its flag around the write, NeoForge passes the
            // flag down from its transaction API).
            "net/fabricmc/fabric/mixin/transfer/BaseContainerBlockEntityMixin",
            Map.of(
                    "(ILnet/minecraft/world/item/ItemStack;)",
                    "(ILnet/minecraft/world/item/ItemStack;Z)"),
            // SimpleContainer's setChanged() moved into the transactional overload in the same shape.
            "net/fabricmc/fabric/mixin/transfer/SimpleContainerMixin",
            Map.of(
                    "(ILnet/minecraft/world/item/ItemStack;)",
                    "(ILnet/minecraft/world/item/ItemStack;Z)"));

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

    /** Fabric's transfer transactions, which this bridge moves onto the NeoForge transaction stack. */
    private static final String FABRIC_TRANSFER_TRANSACTION_CLASS =
            "net/fabricmc/fabric/api/transfer/v1/transaction/Transaction.class";

    /** Fabric's transaction entry point -> the hook that answers it instead. */
    private static final Map<String, String> FABRIC_TRANSFER_TRANSACTION_BRIDGES = Map.of(
            "openOuter()Lnet/fabricmc/fabric/api/transfer/v1/transaction/Transaction;", "openOuter",
            "getLifecycle()Lnet/fabricmc/fabric/api/transfer/v1/transaction/Transaction$Lifecycle;", "lifecycle",
            "getCurrentUnsafe()Lnet/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext;", "currentUnsafe",
            "openNested(Lnet/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext;)Lnet/fabricmc/fabric/api/transfer/v1/transaction/Transaction;",
            "openNested");

    private static final String FABRIC_TRANSFER_HOOKS = "net/neoforged/fabricbridge/FabricTransferHooks";

    /**
     * Fabric's transfer API runs its transactions on a thread-local stack of its own, while NeoForge runs
     * them on {@code net.neoforged.neoforge.transfer.transaction.Transaction}. Two stacks cannot both be
     * right: a Fabric storage rolls back through its transaction's close callbacks and a NeoForge handler
     * through its journals, so a transfer that touches both and then aborts would apply on one side and
     * not the other - duplicating or losing items. The four static entry points of Fabric's
     * {@code Transaction} are therefore rewritten to the bridge's hooks, which hand out Fabric-shaped
     * contexts over NeoForge transactions; everything else in Fabric's transfer API goes through those
     * entry points too ({@code StorageUtil}'s simulate helpers included), so one stack serves both APIs.
     *
     * <p>Nothing else in the Fabric API touches Fabric's own manager: its implementation classes are
     * referenced from this entry point alone, which is what makes the swap complete.
     *
     * @return the class bytes with the entry points routed to the bridge, or null when this is not that class
     */
    private static byte[] bridgeFabricTransferTransactions(byte[] classBytes, String entryName) {
        if (!FABRIC_TRANSFER_TRANSACTION_CLASS.equals(entryName)) {
            return null;
        }
        try {
            var node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
            var changed = false;
            for (var method : node.methods) {
                var hook = FABRIC_TRANSFER_TRANSACTION_BRIDGES.get(method.name + method.desc);
                if (hook == null) {
                    continue;
                }
                var argumentTypes = Type.getArgumentTypes(method.desc);
                var returnType = Type.getReturnType(method.desc);

                method.instructions.clear();
                method.tryCatchBlocks.clear();
                method.localVariables = null;
                method.visibleLocalVariableAnnotations = null;
                method.invisibleLocalVariableAnnotations = null;

                var stack = 0;
                for (var i = 0; i < argumentTypes.length; i++) {
                    method.instructions.add(new VarInsnNode(argumentTypes[i].getOpcode(Opcodes.ILOAD), i));
                    stack += argumentTypes[i].getSize();
                }
                // The hooks answer in Object so the bridge can compile without the game and Fabric API on
                // its classpath; the verifier needs the cast back to Fabric's type spelled out.
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FABRIC_TRANSFER_HOOKS, hook,
                        "(" + "Ljava/lang/Object;".repeat(argumentTypes.length) + ")Ljava/lang/Object;", false));
                method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, returnType.getInternalName()));
                method.instructions.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
                method.maxStack = Math.max(stack + 1, returnType.getSize());
                method.maxLocals = java.util.Arrays.stream(argumentTypes).mapToInt(Type::getSize).sum();
                changed = true;
            }
            if (!changed) {
                return null;
            }
            LOGGER.info("[NeoFobric] Moved Fabric's transfer transactions onto the NeoForge transaction stack in {}",
                    entryName);
            return writeClass(node);
        } catch (Exception e) {
            LOGGER.warn("[NeoFobric] Failed to bridge Fabric's transfer transactions in {}", entryName, e);
            return null;
        }
    }

    /** Where a shaded platform-namespace class of a Fabric jar is moved to. */
    private static final String SHADED_PREFIX = "neofobric/shaded/";

    /**
     * {@return the platform-namespace classes of this jar that have to be shaded, and the name each gets}
     *
     * <p>Some Fabric mods ship their own copy of a {@code net.neoforged} (or old {@code cpw.mods}) class,
     * because they either provide a NeoForge API to Fabric or extend one beyond what this NeoForge has.
     * Those classes cannot stay where they are: the platform owns that namespace, and
     * {@code platformProvidedPackages} hides the copies so the two module layers do not export the same
     * package. For a library that <em>back-ports</em> an API the platform does not have - ForgeConfigAPIPort
     * carries the config API, Fuzs' data extensions carry a whole Fabric port of the data map subsystem -
     * hiding the copy means nobody provides the API at all.
     *
     * <p>So the copies are shaded out of the platform's namespace instead, together with every reference to
     * them (see {@link #remapPlatformNamespaceReferences}), which leaves a self-consistent implementation
     * for the Fabric layer while the platform keeps its own.
     *
     * <p>Only classes the platform does not have at all qualify. That is the back-port case - the library
     * carries an API this NeoForge never got - and it is also the safe line to draw: a jar that ships its
     * own copy of a class the platform <em>does</em> have is reimplementing that API for Fabric, which is
     * what ForgeConfigAPIPort does, and those copies have to keep being shadowed by the platform (with the
     * bridge redirecting the call sites it needs) or the two forks end up half-used and neither works.
     */
    private static java.util.Map<String, String> platformNamespaceShadingPlan(java.util.zip.ZipFile zip,
            TargetResolver resolver) {
        var plan = new java.util.LinkedHashMap<String, String>();
        var it = zip.entries();
        while (it.hasMoreElements()) {
            var entry = it.nextElement();
            var name = entry.getName();
            if (entry.isDirectory() || !name.endsWith(".class")) {
                continue;
            }
            var internalName = name.substring(0, name.length() - ".class".length());
            if (!internalName.startsWith("net/neoforged/") && !internalName.startsWith("cpw/mods/")) {
                continue;
            }
            if (!resolver.platformClassNames().contains(internalName)) {
                plan.put(internalName, SHADED_PREFIX + internalName);
                continue;
            }
            // The platform does have this class, but the jar's copy may declare more than it - that is how
            // these libraries add their own accessors beside NeoForge's (Fuzs' data extensions extend the
            // registry interfaces with their own data map getters). Their mixins and their code are written
            // against the enhanced copy, so it has to be shaded too; a plain duplicate stays shadowed by the
            // platform, which is what keeps ForgeConfigAPIPort's reimplementation lined up with the redirects.
            try {
                var platformBytes = resolver.platformClassBytes(internalName);
                if (platformBytes != null && platformBytes.length > 0
                        && extendsBeyondPlatform(platformBytes, zip.getInputStream(entry).readAllBytes())) {
                    plan.put(internalName, SHADED_PREFIX + internalName);
                }
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Could not compare {} with the platform's copy", internalName, t);
            }
        }
        return plan;
    }

    /** {@return whether the jar's copy of a class declares anything the platform's copy does not} */
    private static boolean extendsBeyondPlatform(byte[] platformBytes, byte[] jarBytes) {
        var platform = new ClassNode();
        new ClassReader(platformBytes).accept(platform, ClassReader.SKIP_CODE);
        var jar = new ClassNode();
        new ClassReader(jarBytes).accept(jar, ClassReader.SKIP_CODE);
        for (var method : jar.methods) {
            if (!declaresMethod(platform.methods, method.name, method.desc)) {
                return true;
            }
        }
        for (var field : jar.fields) {
            if (!declaresField(platform.fields, field.name, field.desc)) {
                return true;
            }
        }
        if (jar.superName != null && !jar.superName.equals(platform.superName)) {
            return true;
        }
        for (var iface : jar.interfaces) {
            if (!platform.interfaces.contains(iface)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresMethod(java.util.List<MethodNode> methods, String name, String desc) {
        for (var method : methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresField(java.util.List<org.objectweb.asm.tree.FieldNode> fields, String name, String desc) {
        for (var field : fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] remapPlatformNamespaceReferences(byte[] classBytes, java.util.Map<String, String> shading) {
        if (shading.isEmpty()) {
            return null;
        }
        try {
            var reader = new ClassReader(classBytes);
            var writer = new ClassWriter(0);
            reader.accept(new org.objectweb.asm.commons.ClassRemapper(writer, new org.objectweb.asm.commons.Remapper() {
                @Override
                public String map(String internalName) {
                    var shaded = shading.get(internalName);
                    return shaded != null ? shaded : internalName;
                }
            }), 0);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Failed to shade the platform-namespace classes of a Fabric jar", t);
            return null;
        }
    }

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

        var internalName = entryName.endsWith(".class") ? entryName.substring(0, entryName.length() - ".class".length()) : entryName;
        var scoped = SCOPED_ANNOTATION_TARGET_REALIGNMENTS.getOrDefault(internalName, Map.of());

        var changed = false;
        for (var method : node.methods) {
            changed |= realignAnnotations(method.visibleAnnotations, scoped);
            changed |= realignAnnotations(method.invisibleAnnotations, scoped);
        }
        if (!changed) {
            return null;
        }

        var writer = new ClassWriter(0);
        node.accept(writer);
        LOGGER.info("[NeoFobric] Realigned stale Mixin annotation targets in {}", entryName);
        return writer.toByteArray();
    }

    private static boolean realignAnnotations(List<AnnotationNode> annotations, Map<String, String> scoped) {
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
                    var replaced = replacementFor(text, scoped);
                    if (replaced != null) {
                        annotation.values.set(i, replaced);
                        changed = true;
                    }
                } else if (value instanceof List<?> list) {
                    for (var j = 0; j < list.size(); j++) {
                        var element = list.get(j);
                        if (element instanceof String text) {
                            var replaced = replacementFor(text, scoped);
                            if (replaced != null) {
                                ((List<Object>) list).set(j, replaced);
                                changed = true;
                            }
                        } else if (element instanceof AnnotationNode nested) {
                            // Array-valued members hold nested annotations too: MixinExtras declares
                            // slice()/@Slice's from as arrays, so @WrapOperation(slice = @Slice(from =
                            // @At(target = ...))) reaches the target through a list, not a direct node.
                            changed |= realignAnnotations(java.util.List.of(nested), scoped);
                        }
                    }
                } else if (value instanceof AnnotationNode nested) {
                    // Targets also live in nested annotations - @Inject(at = @At(target = ...)), and
                    // @WrapOperation(..., slice = @Slice(from = @At(target = ...))) two levels down.
                    changed |= realignAnnotations(java.util.List.of(nested), scoped);
                }
            }
        }
        return changed;
    }

    /** Builds the corrected descriptor by swapping the old parameter list for the new one. */
    private static String replacementFor(String descriptor, Map<String, String> scoped) {
        var renamed = renamedMember(descriptor);
        if (renamed != null) {
            return renamed;
        }
        var open = descriptor.indexOf('(');
        var close = descriptor.indexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        var params = descriptor.substring(open, close + 1);
        var replacement = scoped.getOrDefault(params, ANNOTATION_TARGET_REALIGNMENTS.get(params));
        if (replacement == null) {
            return null;
        }
        return descriptor.substring(0, open) + replacement + descriptor.substring(close + 1);
    }

    /**
     * Rewrites a renamed member, keeping whatever class qualifier the annotation carried.
     *
     * <p>Matched on the tail so that {@code placeEntities(...)V} (a selector) and
     * {@code Lnet/minecraft/…/StructureTemplate;placeEntities(...)V} (a call target) both resolve.
     */
    private static String renamedMember(String descriptor) {
        for (var entry : ANNOTATION_MEMBER_RENAMES.entrySet()) {
            var old = entry.getKey();
            if (descriptor.endsWith(old)) {
                return descriptor.substring(0, descriptor.length() - old.length()) + entry.getValue();
            }
        }
        return null;
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
     * Replaces the whole-list swap in fabric-registry-sync's {@code WorldLoaderMixin} with a merge.
     *
     * <p>The mixin's handler returns Fabric's own registry list, ignoring the list it was handed - on a
     * pure Fabric game those carry the same registries, so replacing is harmless there. NeoForge's list
     * additionally carries its own datapack registries (biome modifiers, data maps, ...), so replacing
     * drops them and the server dies at startup with {@code Missing registry:
     * neoforge:biome_modifier}. The patched body hands both lists to the bridge's
     * {@code RegistryListBridge.merge}, which keeps NeoForge's entries and appends the Fabric ones that
     * are not already present. The body is straight-line either way (load, call, call, return), so no
     * stack map frames are involved.
     */
    private static byte[] mergeRegistryListSwap(byte[] classBytes, String entryName) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        var internalName = entryName.substring(0, entryName.length() - ".class".length());
        if (!"net/fabricmc/fabric/mixin/registry/sync/WorldLoaderMixin".equals(internalName)) {
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }

        for (var method : node.methods) {
            if (!"modifyLoadedEntries".equals(method.name) || !"(Ljava/util/List;)Ljava/util/List;".equals(method.desc)) {
                continue;
            }
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "net/fabricmc/fabric/api/event/registry/DynamicRegistries", "getBootstrappingRegistries",
                    "()Ljava/util/List;", false));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "net/neoforged/fabricbridge/RegistryListBridge", "merge",
                    "(Ljava/util/List;Ljava/util/List;)Ljava/util/List;", false));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            method.maxStack = 2;
            method.maxLocals = 1;
            LOGGER.info("[NeoFobric] Turned the datapack registry list swap in {} into a merge, so NeoForge's own datapack registries survive",
                    internalName.replace('/', '.'));
            return writeClass(node);
        }
        return null;
    }

    /**
    /**
     * Lifts a stale {@code @At(value = "RETURN", ordinal = N)} onto {@code @At("TAIL")} whenever the target
     * method does not have that many returns any more.
     *
     * <p>A handler asking for the sixth return of a method this tree only gives three is dead weight: the
     * injection point does not exist, so the handler never runs and whatever it was written to do silently
     * never happens. That is how Create's key-release handler left the schematic key set unresponsive - key
     * presses still arrived (that handler uses {@code @At("TAIL")}) while the release, which focuses and
     * commits the selection, never did. NeoForge restructuring such a method is what makes the ordinal stale,
     * and {@code TAIL} is the placement it meant: TAIL injects before the final RETURN, and an ordinal that
     * used to be the last return can only have been that.
     *
     * <p>The rule fires only when the ordinal is out of range for <em>every</em> method the annotation names
     * in <em>every</em> class the mixin targets. As long as it lands somewhere, the anchor means something
     * and moving it would change behaviour - and when the target class is not part of the game (a mixin into
     * its own mod's classes) nothing is decided at all. No class or handler is named here: the evidence is
     * the patched target's return count.
     */
    private static byte[] retargetStaleReturnOrdinals(byte[] classBytes, String entryName, TargetResolver resolver) {
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

        var targets = mixinTargets(node);
        if (targets.isEmpty()) {
            return null;
        }

        var changed = false;
        for (var method : node.methods) {
            for (var annotation : allAnnotationsOf(method)) {
                var selectors = readStringValues(annotation, "method");
                if (selectors.isEmpty()) {
                    continue;
                }
                for (var at : atAnnotationsOf(annotation)) {
                    if (!"RETURN".equals(readStringValue(at, "value"))) {
                        continue;
                    }
                    var ordinal = readIntValue(at, "ordinal");
                    if (ordinal == null || !ordinalIsPastEveryReturn(resolver, targets, selectors, ordinal)) {
                        continue;
                    }
                    writeStringValue(at, "value", "TAIL");
                    removeValue(at, "ordinal");
                    LOGGER.info("[NeoFobric] Lifted @At(RETURN, ordinal) onto @At(TAIL) on {}#{} - the ordinal points past every return the target has",
                            entryName.replace('/', '.'), method.name);
                    changed = true;
                }
            }
        }
        return changed ? writeClass(node) : null;
    }

    /** {@return the internal names the mixin's {@code @Mixin} annotation targets} */
    /**
     * Notes the game enums a Fabric jar extends through Mixin's enum extension, so the class tweaker processor can
     * take the platform's {@code IExtensibleEnum} marker off them before Mixin prepares their config - FML refuses
     * an enum extension on a class carrying that marker, and it refuses it while preparing configs, which takes the
     * game down before the main menu.
     *
     * <p>Nothing is rewritten here. A mixin that is itself an enum is the feature's shape, and the target has to be
     * part of the game for the platform marker to be there in the first place.
     */
    private static void noteFabricEnumExtensions(byte[] classBytes, String entryName, TargetResolver resolver) {
        if (!entryName.endsWith(".class")) {
            return;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return;
        }
        if (!"java/lang/Enum".equals(node.superName)) {
            return;
        }
        for (var target : mixinTargets(node)) {
            var targetClass = resolver.apply(target);
            if (targetClass != null && targetClass.length > 0) {
                net.neoforged.fabricbridge.transform.NeoFobricClassTweakerProcessor.registerEnumExtensionTarget(target);
            }
        }
    }

    private static java.util.List<String> mixinTargets(ClassNode node) {
        var targets = new java.util.ArrayList<String>();
        for (var annotations : java.util.Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (annotations == null) {
                continue;
            }
            for (var annotation : annotations) {
                if (annotation.values == null || !"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)) {
                    continue;
                }
                for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
                    var name = String.valueOf(annotation.values.get(i));
                    var value = annotation.values.get(i + 1);
                    if (!(value instanceof java.util.List<?> list)) {
                        continue;
                    }
                    if ("value".equals(name)) {
                        for (var entry : list) {
                            if (entry instanceof org.objectweb.asm.Type type) {
                                targets.add(type.getInternalName());
                            }
                        }
                    } else if ("targets".equals(name)) {
                        for (var entry : list) {
                            if (entry instanceof String text) {
                                targets.add(text.replace('.', '/'));
                            }
                        }
                    }
                }
            }
        }
        return targets;
    }

    /** {@return the {@code at} entries of an injection annotation, single form or list} */
    private static java.util.List<AnnotationNode> atAnnotationsOf(AnnotationNode annotation) {
        if (annotation.values == null) {
            return java.util.List.of();
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (!"at".equals(annotation.values.get(i))) {
                continue;
            }
            var value = annotation.values.get(i + 1);
            if (value instanceof java.util.List<?> list) {
                var nodes = new java.util.ArrayList<AnnotationNode>();
                for (var entry : list) {
                    if (entry instanceof AnnotationNode at) {
                        nodes.add(at);
                    }
                }
                return nodes;
            }
            if (value instanceof AnnotationNode single) {
                return java.util.List.of(single);
            }
        }
        return java.util.List.of();
    }

    /** {@return every string a named annotation element holds, flat form or single-element list} */
    private static java.util.List<String> readStringValues(AnnotationNode annotation, String name) {
        var single = readStringValue(annotation, name);
        if (single != null) {
            return java.util.List.of(single);
        }
        if (annotation.values == null) {
            return java.util.List.of();
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof java.util.List<?> list) {
                var values = new java.util.ArrayList<String>();
                for (var entry : list) {
                    if (entry instanceof String text) {
                        values.add(text);
                    }
                }
                return values;
            }
        }
        return java.util.List.of();
    }

    /** {@return the int a named annotation element holds, or null} */
    private static Integer readIntValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return null;
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof Integer value) {
                return value;
            }
        }
        return null;
    }

    /** Drops a named annotation element. */
    private static void removeValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return;
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                annotation.values.remove(i);
                annotation.values.remove(i);
                return;
            }
        }
    }

    /**
     * {@return whether the ordinal points past the returns of every method the selectors name, in every
     * target class the resolver can find} An unresolvable target, or a selector that matches nothing in a
     * class, answers {@code false}: the rule only ever fires on evidence.
     */
    private static boolean ordinalIsPastEveryReturn(TargetResolver resolver, java.util.List<String> targets,
            java.util.List<String> selectors, int ordinal) {
        var decided = false;
        for (var targetInternalName : targets) {
            var bytes = resolver.apply(targetInternalName);
            if (bytes == null || bytes.length == 0) {
                continue;
            }
            ClassNode target;
            try {
                target = new ClassNode();
                new ClassReader(bytes).accept(target, 0);
            } catch (Throwable t) {
                return false;
            }
            for (var selector : selectors) {
                var bracket = selector.indexOf('(');
                var name = bracket < 0 ? selector : selector.substring(0, bracket);
                var descriptor = bracket < 0 ? null : selector.substring(bracket);
                var matched = 0;
                for (var method : target.methods) {
                    if (!method.name.equals(name) || (descriptor != null && !method.desc.equals(descriptor))) {
                        continue;
                    }
                    matched++;
                    if (ordinal < returnCount(method)) {
                        return false;
                    }
                }
                if (matched > 0) {
                    decided = true;
                }
            }
        }
        return decided;
    }

    /** {@return the number of RETURN-family instructions in the method} */
    private static int returnCount(MethodNode method) {
        var count = 0;
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            var opcode = insn.getOpcode();
            if (opcode >= org.objectweb.asm.Opcodes.IRETURN && opcode <= org.objectweb.asm.Opcodes.RETURN) {
                count++;
            }
        }
        return count;
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
            // only one longer match may exist. A selector that names the target by descriptor (the shape
            // @Inject uses when several overloads share a name, and the shape the realigns above rewrite)
            // resolves exactly instead of by prefix search.
            var selector = singleMethodTarget(inject);
            if (selector == null) {
                continue;
            }
            MethodNode longerTarget = null;
            var annotationNeedsDesc = !selector.contains("(");
            if (annotationNeedsDesc) {
                var ambiguous = false;
                for (var target : targetClass.methods) {
                    if (!target.name.equals(selector)) {
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
                if (ambiguous) {
                    continue;
                }
            } else {
                var open = selector.indexOf('(');
                var targetName = selector.substring(0, open);
                var targetDesc = selector.substring(open);
                for (var target : targetClass.methods) {
                    if (target.name.equals(targetName) && target.desc.equals(targetDesc)) {
                        longerTarget = target;
                        break;
                    }
                }
            }
            if (longerTarget == null) {
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

            if (annotationNeedsDesc) {
                for (var i = 0; i + 1 < inject.values.size(); i += 2) {
                    if ("method".equals(inject.values.get(i))) {
                        inject.values.set(i + 1, longerTarget.name + longerTarget.desc);
                    }
                }
            }
            // Only worth a line when something was actually appended: the pass visits every @Inject handler
            // in the whole mod set, and an "Appended 0" line for each of them buries the log and costs real
            // time (these runs emit tens of thousands of log lines).
            if (missing.length > 0) {
                LOGGER.info("[NeoFobric] Appended {} missing trailing parameter(s) to @Inject handler {}#{} (target {}.{})",
                        missing.length, node.name.replace('/', '.'), handler.name,
                        targetClass.name.replace('/', '.'), longerTarget.name);
            } else {
                LOGGER.debug("[NeoFobric] @Inject handler {}#{} matches {}.{} as-is",
                        node.name.replace('/', '.'), handler.name,
                        targetClass.name.replace('/', '.'), longerTarget.name);
            }
            changed = true;
        }

        if (!changed) {
            return null;
        }
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Fabric API's tooltip-order scraper: it reads {@code ItemStack} bytecode to learn the vanilla order. */
    private static final String FABRIC_TOOLTIP_ORDER_SCANNER = "net/fabricmc/fabric/impl/item/VanillaTooltipProviderOrder";

    /** Fabric API's client-side tag loader: it reads tag files straight out of the mods' jars. */
    private static final String FABRIC_CLIENT_TAG_LOADER = "net/fabricmc/fabric/impl/tag/client/ClientTagsLoader";

    /**
     * Hands Fabric API's client-side tag loader a parse that understands its own spelling of the removal list.
     *
     * <p>{@code ClientTagsLoader} parses the tag files it finds with {@code TagFile.CODEC}, and Fabric's
     * tag-removal extension writes that list as a {@code fabric:remove} field - so this one call site is pointed
     * at {@code TagFileHooks}, which folds the Fabric key into the vanilla one before parsing. The codec itself
     * cannot carry the second spelling: Fabric wraps it (to keep the removal list it reads) and the client's
     * registry sync runs through the wrapped codec, where a changed shape leaves the client without the tags it
     * needs to parse the registries it receives.
     *
     * <p>The receiver is already on the stack when the call is made, so the static call keeps its shape by
     * taking the codec as its first argument.
     *
     * @return the class bytes with the call redirected, or the original when it does not contain it
     */
    private static byte[] redirectFabricTagFileParse(byte[] classBytes, String entryName) {
        if (!FABRIC_CLIENT_TAG_LOADER.equals(entryName.replace(".class", ""))) {
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
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "com/mojang/serialization/Codec".equals(call.owner)
                        && "parse".equals(call.name)
                        && "(Lcom/mojang/serialization/Dynamic;)Lcom/mojang/serialization/DataResult;".equals(call.desc)) {
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = "net/neoforged/neoforge/common/TagFileHooks";
                    call.name = "parseTagFile";
                    call.desc = "(Lcom/mojang/serialization/Codec;Lcom/mojang/serialization/Dynamic;)Lcom/mojang/serialization/DataResult;";
                    call.itf = false;
                    changed = true;
                }
            }
        }

        if (!changed) {
            LOGGER.info("[NeoFobric] Fabric's client tag loader no longer parses through Codec#parse; the"
                    + " fabric:remove redirect needs checking against it");
            return null;
        }
        LOGGER.info("[NeoFobric] Pointed Fabric API's client tag loader at TagFileHooks for its tag file parse");
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Points Fabric API's blockstate model type registration at the platform's dispatch.
     *
     * <p>A Fabric mod registers its blockstate model types with
     * {@code CustomUnbakedBlockStateModel.register}, and Fabric's blockstates refer to them with a
     * {@code "fabric:type"} key. NeoForge dispatches on {@code "type"} and knows only its own registry
     * (see {@code BlockStateModelHooks.makeSingleModelCodec}), and the codec replacement Fabric ships for
     * this - {@code BlockStateModelUnbakedMixin} - is deliberately dropped: it bypasses NeoForge's model
     * dispatch, and without it every vanilla model fails to decode. The result was that a Fabric mod's
     * custom-model blocks degraded to the missing model (Twilight Forest's aurora block and its giant logs
     * and leaves among them).
     *
     * <p>{@code BlockStateModelHooks.registerFabricModelType} takes the same arguments, keeps Fabric's own
     * registry in the loop, and feeds the platform's dispatch, which also learns to read the
     * {@code "fabric:type"} key for those ids.
     *
     * @return the class bytes with the call redirected, or the original when it does not contain it
     */
    private static byte[] redirectFabricBlockStateModelTypes(byte[] classBytes) {
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
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && "net/fabricmc/fabric/api/client/model/loading/v1/CustomUnbakedBlockStateModel".equals(call.owner)
                        && "register".equals(call.name)
                        && "(Lnet/minecraft/resources/Identifier;Lcom/mojang/serialization/MapCodec;)V".equals(call.desc)) {
                    call.owner = "net/neoforged/neoforge/client/model/block/BlockStateModelHooks";
                    call.name = "registerFabricModelType";
                    call.itf = false;
                    changed = true;
                }
            }
        }

        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Pointed Fabric's blockstate model type registration at BlockStateModelHooks");
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Points Fabric API's tooltip injections at the method NeoForge split them out of.
     *
     * <p>{@code ItemStack#addDetailsToTooltip} was the vanilla tooltip body, and every injection in Fabric's
     * {@code ItemStackMixin} anchors somewhere inside it: the attributes, the component entries, the advanced
     * check. NeoForge turned the public method into a wrapper around registry hooks and moved the body into
     * the private {@code addDetailsToTooltipComponents}, with the registry-name line moved on into
     * {@code addDetailsToTooltipTail}. The injections then found no target at all - "InvalidInjectionException:
     * Callback method postTooltipsNonAdvanced ..." - and Fabric's tooltip events stopped firing for every mod
     * that uses them.
     *
     * <p>Each injection is pointed at the method its own anchor moved to, which is why this cannot go through
     * the plain annotation realignment table.
     *
     * @return the class bytes with the injections retargeted, or the original when nothing needed to change
     */
    private static byte[] retargetItemStackTooltipInjections(byte[] classBytes, String entryName) {
        if (!"net/fabricmc/fabric/mixin/item/ItemStackMixin".equals(entryName.replace(".class", ""))) {
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
            for (var annotation : allAnnotationsOf(method)) {
                var target = readStringValue(annotation, "method");
                if (!"addDetailsToTooltip".equals(target)) {
                    continue;
                }
                var at = readAnnotationValue(annotation, "at");
                var anchor = at == null ? null : readStringValue(at, "target");
                var movedTo = anchor != null && anchor.contains("DefaultedRegistry;getKey")
                        ? "addDetailsToTooltipTail"
                        : "addDetailsToTooltipComponents";
                writeStringValue(annotation, "method", movedTo);
                changed = true;
            }
        }

        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Pointed Fabric's tooltip injections at the methods NeoForge split them into");
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Points Fabric's item damage hook at the call NeoForge redirected it through.
     *
     * <p>Fabric's {@code CustomDamageHandler} hook wraps the call from
     * {@code ItemStack#hurtAndBreak(int, LivingEntity, EquipmentSlot)} into the vanilla
     * {@code hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer)}. NeoForge added a
     * {@code LivingEntity} overload - so a non-player owner can be carried through to its own damage hooks -
     * and sends the entry point through that one instead, which leaves the wrap without an invocation.
     * ForgifiedFabricAPI retargets the annotation and widens the handler's {@code ServerPlayer} parameter to
     * {@code LivingEntity}; this does the same to the compiled mixin. Both types are references, so the
     * parameter's local slot and the body (which only hands the value back to {@code original.call}) are
     * unaffected.
     *
     * @return the class bytes with the hook retargeted, or the original when nothing needed to change
     */
    private static byte[] retargetItemStackDamageHook(byte[] classBytes, String entryName) {
        if (!"net/fabricmc/fabric/mixin/item/ItemStackMixin".equals(entryName.replace(".class", ""))) {
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }

        var playerOverload = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;";
        var entityOverload = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;";
        var changed = false;
        for (var method : node.methods) {
            for (var annotation : allAnnotationsOf(method)) {
                var at = readAnnotationValue(annotation, "at");
                var target = at == null ? null : readStringValue(at, "target");
                if (target == null || !target.contains(playerOverload)) {
                    continue;
                }
                writeStringValue(at, "target", target.replace(playerOverload, entityOverload));
                changed |= retypeParameter(method, "net/minecraft/server/level/ServerPlayer", "net/minecraft/world/entity/LivingEntity");
            }
        }

        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Pointed Fabric's item damage hook at the overload NeoForge routes damage through");
        return writeClass(node);
    }

    /** Rewrites one reference parameter type of a mixin handler, descriptor and debug info together. */
    private static boolean retypeParameter(org.objectweb.asm.tree.MethodNode method, String fromInternal, String toInternal) {
        var from = "L" + fromInternal + ";";
        var to = "L" + toInternal + ";";
        if (!method.desc.contains(from)) {
            return false;
        }
        method.desc = method.desc.replace(from, to);
        if (method.localVariables != null) {
            for (var local : method.localVariables) {
                if (from.equals(local.desc)) {
                    local.desc = to;
                }
            }
        }
        for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode type && from.equals(type.desc)) {
                type.desc = to;
            }
        }
        return true;
    }

    /**
     * Makes Create's block entity behaviours available before the first tick, not only after it.
     *
     * <p>{@code SmartBlockEntity} attaches its behaviours - client ones included - from {@code initialize()},
     * which it runs lazily on the entity's first tick. A block entity that reaches the client during chunk
     * handling can be rendered in that same frame, before that tick has happened, and a renderer that needs a
     * behaviour (Create's mixer, for one) then throws on the null it gets back. Every diagnostic probe run
     * confirmed that ordering by accident: inserting anything at all into this class made the crash disappear,
     * because the extra work let the tick win the race.
     *
     * <p>The lookup therefore completes the initialisation itself - see
     * {@link FabricBridgeHooks#ensureCreateBlockEntityInitialised(Object)}, which also reports the cases it
     * cannot handle, so a run that still crashes says why.
     *
     * @return the class bytes with the lookup guarded, or the original when nothing needed to change
     */
    private static byte[] initialiseCreateBehavioursOnLookup(byte[] classBytes, String entryName) {
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
            var ensure = new org.objectweb.asm.tree.InsnList();
            ensure.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
            ensure.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                    "net/neoforged/fabricbridge/FabricBridgeHooks", "ensureCreateBlockEntityInitialised",
                    "(Ljava/lang/Object;)V", false));
            method.instructions.insert(ensure);
            changed = true;
        }

        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Made Create's behaviour lookup initialise the block entity first");
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Re-bounds Fabric's block-entity unload hook inside {@code LevelChunk#getBlockEntity}.
     *
     * <p>Fabric fires {@code ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD} there by redirecting the pending
     * block-entity map's {@code remove}, bounded by a slice that starts at {@code createBlockEntity} - a call
     * NeoForge's {@code getBlockEntity} no longer makes, so the slice matches nothing and the mixin class
     * fails as a whole. That also silences the load and unload events next to it, which is why block entities
     * were still being tracked as loaded when a world closed
     * ({@code Mismatch in tracked blockentities, expected 0} in Fabric's own lifecycle test). ForgifiedFabricAPI
     * bounds the same redirect with the {@code pendingBlockEntities} field read instead; this drops that
     * slice in the same shape.
     *
     * @return the class bytes with the slice swapped, or the original when nothing needed to change
     */
    private static byte[] retargetLevelChunkBlockEntityRemoval(byte[] classBytes, String entryName) {
        // Both sides carry the same redirect: the server module keeps the load/unload pair in its event package,
        // while the client module has a module of its own for the same trick, and each needs the same re-bounding.
        var internal = entryName.replace(".class", "");
        if (!"net/fabricmc/fabric/mixin/event/lifecycle/server/LevelChunkMixin".equals(internal)
                && !"net/fabricmc/fabric/mixin/event/lifecycle/client/LevelChunkMixin".equals(internal)) {
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }

        // The redirect on the pending map, as Fabric compiles it: (Map, Object)Object.
        var removed = false;
        for (var method : node.methods) {
            if (!"(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;".equals(method.desc)) {
                continue;
            }
            for (var annotation : allAnnotationsOf(method)) {
                var at = readAnnotationValue(annotation, "at");
                var target = at == null ? null : readStringValue(at, "target");
                if (target == null || !target.startsWith("Ljava/util/Map;remove(")) {
                    continue;
                }
                var slice = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Slice;");
                var to = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
                to.visit("value", "FIELD");
                to.visit("target", "Lnet/minecraft/world/level/chunk/LevelChunk;pendingBlockEntities:Ljava/util/Map;");
                to.visit("opcode", org.objectweb.asm.Opcodes.GETFIELD);
                slice.visit("to", to);
                writeAnnotationValue(annotation, "slice", slice);
                removed = true;
            }
        }

        if (!removed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Re-bounded Fabric's block-entity unload hook to the pending block entity map");
        return writeClass(node);
    }

    /** Replaces (or adds) a nested-annotation element. */
    private static void writeAnnotationValue(AnnotationNode annotation, String name, AnnotationNode value) {
        if (annotation.values == null) {
            annotation.values = new java.util.ArrayList<>();
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                annotation.values.set(i + 1, value);
                return;
            }
        }
        annotation.values.add(name);
        annotation.values.add(value);
    }

    /**
     * Injection targets whose method NeoForge moved the body of, named per allowlisted mixin class.
     *
     * <p>Each of these wrappers still exists under the old name and still resolves, so the injection applies
     * and simply finds nothing - a silent loss of whatever Fabric fired from the body. Where the new body is
     * reachable it is worth following, and ForgifiedFabricAPI's own copies name the same new methods:
     * <ul>
     * <li>{@code AnvilMenuMixin}: NeoForge wraps {@code createResult} to fire AnvilUpdateEvent with the vanilla
     * result, and the body became {@code createResultInternal} - without this Fabric's AllowEnchanting event
     * never fires for the anvil.</li>
     * <li>{@code LanguageMixin}: the translation parse gained a component consumer, and the resource read moved
     * into the three-argument {@code parseTranslations} - without this mod-provided server language files are
     * not read.</li>
     * <li>{@code ServerConfigurationPacketListenerImplMixin}: {@code startConfiguration} now only kicks off the
     * configuration phase, whose body is {@code runConfiguration} - without this the server's known-packs list
     * is no longer narrowed to the packs it started with.</li>
     * <li>{@code ServerPlayerMixin} (entity events): the body of {@code startSleepInBed} became the one-argument
     * lambda it hands to {@code Optional#map}, so all three of Fabric's sleep hooks (sleeping direction, respawn
     * point, nearby monsters) sit in the lambda now.</li>
     * </ul>
     */
    private static final java.util.Map<String, java.util.Map<String, String>> RETARGETED_INJECTION_METHODS = java.util.Map.of(
            "net/fabricmc/fabric/mixin/item/AnvilMenuMixin",
            java.util.Map.of("createResult", "createResultInternal"),
            "net/fabricmc/fabric/mixin/resource/server/LanguageMixin",
            java.util.Map.of("parseTranslations(Ljava/util/function/BiConsumer;Ljava/lang/String;)V",
                    "parseTranslations(Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;Ljava/lang/String;)V"),
            "net/fabricmc/fabric/mixin/resource/ServerConfigurationPacketListenerImplMixin",
            java.util.Map.of("startConfiguration", "runConfiguration"),
            "net/fabricmc/fabric/mixin/entity/event/ServerPlayerMixin",
            java.util.Map.of("startSleepInBed", "lambda$startSleepInBed$0"));

    /**
     * The menu API's {@code openMenu} hook is <em>not</em> in the table above on purpose.
     *
     * <p>NeoForge added a two-argument {@code openMenu} for its extra screen data and the one-argument overload
     * only delegates to it, so Fabric's two hooks anchor on a body that is no longer there - and pointing the
     * annotation at the new overload is not enough: the handler would also need the extra parameter, and Mixin
     * then synthesises a handler whose captured local is uninitialised, which fails verification and takes the
     * server down. ForgifiedFabricAPI rewrote this mixin by hand instead. Leaving the annotation alone keeps the
     * failure benign, which is what Fabric already sees on this tree: the menu API's newer extended-menu helpers
     * stay dead, everything else in the class keeps working.
     */

    /**
     * Points the injections named by {@link #RETARGETED_INJECTION_METHODS} at the methods NeoForge moved the
     * bodies into.
     *
     * @return the class bytes with the method names retargeted, or the original when nothing needed to change
     */
    private static byte[] retargetInjectionMethods(byte[] classBytes, String entryName) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        var internalName = entryName.substring(0, entryName.length() - ".class".length());
        var moved = RETARGETED_INJECTION_METHODS.get(internalName);
        if (moved == null) {
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
            for (var annotation : allAnnotationsOf(method)) {
                var target = readStringValue(annotation, "method");
                var replacement = target == null ? null : moved.get(target);
                if (replacement != null) {
                    writeStringValue(annotation, "method", replacement);
                    changed = true;
                }
            }
        }

        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Pointed the injections in {} at the method(s) NeoForge moved the body into: {}", internalName, moved);
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The injection annotations whose {@code at} names a member: the ones a retarget can move. */
    private static final java.util.Set<String> ANCHORED_INJECTIONS = java.util.Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/wrapwithcondition/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/modifyexpressionvalue/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/modifyreceiver/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/modifyargs/ModifyArgs;");

    /** MixinExtras sugar a handler reads out of the host method's frame, which a retarget must not move. */
    private static final java.util.Set<String> HOST_FRAME_SUGAR = java.util.Set.of(
            "Lcom/llamalad7/mixinextras/sugar/Local;",
            "Lcom/llamalad7/mixinextras/sugar/Share;",
            "Lcom/llamalad7/mixinextras/sugar/Definition;",
            "Lcom/llamalad7/mixinextras/sugar/Expression;");

    /**
     * Points an injection at the method its anchor moved into, derived from the bytecode rather than listed.
     *
     * <p>NeoForge splits vanilla bodies (a wrapper plus a private worker) and adds delegating overloads, so an
     * injection can name a method that still exists while the call it wraps moved one level in. The handler is
     * validated against the wrapped call rather than the host, so pointing the selector at the method the
     * selected one delegates to brings the injection back.
     *
     * <p>Only when the handler takes no {@code @Local}/{@code @Share} sugar: sugar is read out of the host's
     * frame, and a handler moved to a method whose parameters or locals differ makes Mixin synthesise a capture
     * that fails verification - which does not fail that injection but the whole class, and for a server class
     * the server with it. Those are left alone on purpose.
     *
     * <p>Conditions, all read out of the two classes: the anchor must be absent from the selected method,
     * present in <em>exactly one</em> other method of the same target class, and that method must be called by
     * the selected one.
     */
    private static byte[] retargetMovedAnchors(byte[] classBytes, String entryName, TargetResolver resolver) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        var internalName = entryName.substring(0, entryName.length() - ".class".length());
        if (KEEP_INERT_MIXINS.contains(internalName)) {
            LOGGER.info("[NeoFobric] Leaving the injections in {} inert on purpose - the platform covers what they"
                    + " would install, and reviving them breaks it", internalName);
            return null;
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }
        var targets = mixinTargets(node);
        if (targets.isEmpty()) {
            return null;
        }

        var changed = false;
        var moves = new java.util.ArrayList<String>();
        for (var method : node.methods) {
            // Plain @Local/@Share sugar reads the host's locals, whose names and liveness cannot be checked
            // from here - those handlers are left alone. argsOnly sugar only needs the host's parameters, and
            // the move is taken when the method it moves to still takes those types.
            var sugarTypes = argsOnlySugarTypes(method);
            if (sugarTypes == null) {
                continue;
            }
            for (var annotation : allAnnotationsOf(method)) {
                if (!ANCHORED_INJECTIONS.contains(annotation.desc)) {
                    continue;
                }
                var selector = readStringValue(annotation, "method");
                var at = atAnnotationsOf(annotation);
                if (selector == null || at.size() != 1) {
                    continue;
                }
                var kind = readStringValue(at.get(0), "value");
                var anchor = readStringValue(at.get(0), "target");
                if (anchor == null || kind == null
                        || !java.util.Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD", "NEW").contains(kind)) {
                    continue;
                }
            var host = movedHost(resolver, targets, selector, anchor);
            if (host == null || !hostTakesSugarTypes(resolver, targets, host, sugarTypes)) {
                continue;
            }
            // A host the selected method calls is a wrapper handing off to its worker. When the platform instead
            // lifted the body out into a method of its own (Hud#updateContextualBarRenderer carries exactly what
            // extractHotbarAndDecorations used to run), nothing calls it from there - the new host's body does
            // the same work, which its instruction mix shows.
            if (!CALL_SIDE_INJECTIONS.contains(annotation.desc)
                    && !hostStartsWithHandlerParams(resolver, targets, host, declaredHostParams(method))) {
                continue;
            }
            if (!hostIsCalledBySelection(resolver, targets, selector, host)
                    && !hostIsBodyLookalike(resolver, targets, selector, host, anchor)) {
                continue;
            }
                writeStringValue(annotation, "method", host);
                moves.add(selector + " -> " + host);
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Moved {} injection selector(s) in {} onto the method the anchor moved into: {}",
                moves.size(), entryName.replace('/', '.'), moves);
        return writeClass(node);
    }

    /** {@return whether the handler reads sugar out of the host method's frame} */
    private static boolean usesHostFrame(org.objectweb.asm.tree.MethodNode handler) {
        // MixinExtras' sugar carries CLASS retention, so it lands in the invisible list; both are read.
        return declaresSugar(handler.visibleParameterAnnotations)
                || declaresSugar(handler.invisibleParameterAnnotations);
    }

    private static boolean declaresSugar(java.util.List<AnnotationNode>[] parameters) {
        if (parameters == null) {
            return false;
        }
        for (var annotations : parameters) {
            if (annotations == null) {
                continue;
            }
            for (var annotation : annotations) {
                if (HOST_FRAME_SUGAR.contains(annotation.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return the parameter types the handler's {@code argsOnly} sugar asks for, or null when it reads host locals}
     *
     * <p>{@code @Local(argsOnly = true)} binds a parameter of the host method, which a retarget can survive as
     * long as the method it moves to still takes that type - the caller checks that before rewriting anything.
     * Plain {@code @Local} (and {@code @Share}/{@code @Definition}) read the host's <em>locals</em>, whose names
     * and liveness cannot be checked from here, so those still block a retarget.
     */
    private static java.util.List<String> argsOnlySugarTypes(org.objectweb.asm.tree.MethodNode handler) {
        var parameterTypes = parametersFor(handler);
        var needed = new java.util.ArrayList<String>();
        var lists = new java.util.ArrayList<java.util.List<AnnotationNode>[]>();
        if (handler.visibleParameterAnnotations != null) {
            lists.add(handler.visibleParameterAnnotations);
        }
        if (handler.invisibleParameterAnnotations != null) {
            lists.add(handler.invisibleParameterAnnotations);
        }
        for (var parameters : lists) {
            for (var index = 0; index < parameters.length && index < parameterTypes.size(); index++) {
                var annotations = parameters[index];
                if (annotations == null) {
                    continue;
                }
                for (var annotation : annotations) {
                    if (!HOST_FRAME_SUGAR.contains(annotation.desc)) {
                        continue;
                    }
                    if (!annotation.desc.endsWith("/Local;")
                            || !Boolean.TRUE.equals(readBooleanValue(annotation, "argsOnly"))) {
                        return null;
                    }
                    needed.add(parameterTypes.get(index));
                }
            }
        }
        return needed;
    }

    /**
     * {@return every parameter of the method, in order, as descriptor fragments}
     *
     * <p>Primitives and arrays are kept: the result is indexed against the parameter-annotation arrays, and a
     * list that skipped them would report a handler's sugar against the wrong parameter - which is how a
     * handler reading a local slipped past the retarget check and took its whole class down.
     */
    private static java.util.List<String> parametersFor(org.objectweb.asm.tree.MethodNode handler) {
        var out = new java.util.ArrayList<String>();
        var descriptor = handler.desc;
        var index = descriptor.indexOf('(') + 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            var start = index;
            while (descriptor.charAt(index) == '[') {
                index++;
            }
            if (descriptor.charAt(index) == 'L') {
                var end = descriptor.indexOf(';', index);
                out.add(descriptor.substring(start, end + 1));
                index = end + 1;
            } else {
                out.add(descriptor.substring(start, index + 1));
                index++;
            }
        }
        return out;
    }

    private static Boolean readBooleanValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return null;
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof Boolean value) {
                return value;
            }
        }
        return null;
    }

    /** Injection kinds whose handler binds the <em>wrapped call's</em> receiver and arguments, not the host's. */
    private static final java.util.Set<String> CALL_SIDE_INJECTIONS = java.util.Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/wrapwithcondition/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/modifyexpressionvalue/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/modifyreceiver/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/modifyargs/ModifyArgs;");

    /**
     * Mixin classes whose injections have to stay inert even when their anchor is found in a new home.
     *
     * <p>Fabric's registry sync installs itself from the tail of {@code Bootstrap.bootStrap}, and this platform's
     * own registry sync does that job - reviving Fabric's half on top of it fails registry loading outright
     * ("Failed to load registries due to errors"), which is how it was found. The anchor hunt cannot tell an
     * injection that is dead by accident from one that is dead because the platform already covers it, so the
     * classes in this position are named here and skipped.
     */
    private static final java.util.Set<String> KEEP_INERT_MIXINS = java.util.Set.of(
            "net/fabricmc/fabric/mixin/registry/sync/BootstrapMixin");

    /**
     * {@return whether the method the retarget points at declares the handler's host parameters as a prefix}
     *
     * <p>{@code @Inject} and its siblings bind the host method's own parameters, in order; a handler may take a
     * prefix of them. A destination that starts with different types would make Mixin bind the handler's
     * parameters to the wrong arguments, which fails the whole class rather than that one injection.
     */
    private static boolean hostStartsWithHandlerParams(TargetResolver resolver, java.util.List<String> targets,
            String host, java.util.List<String> declared) {
        if (declared.isEmpty()) {
            return true;
        }
        for (var target : targets) {
            var bytes = resolver.apply(target);
            if (bytes == null) {
                continue;
            }
            ClassNode targetNode;
            try {
                targetNode = new ClassNode();
                new ClassReader(bytes).accept(targetNode, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (Throwable t) {
                continue;
            }
            for (var candidate : selectedMethods(targetNode, host)) {
                var available = parametersFor(candidate);
                if (available.size() >= declared.size()
                        && available.subList(0, declared.size()).equals(declared)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@return whether the selected method calls the hand-off target} */
    private static boolean hostIsCalledBySelection(TargetResolver resolver, java.util.List<String> targets,
            String selector, String host) {
        var hostName = host.substring(0, host.indexOf('('));
        var hostDesc = host.substring(host.indexOf('('));
        for (var target : targets) {
            var bytes = resolver.apply(target);
            if (bytes == null) {
                continue;
            }
            ClassNode targetNode;
            try {
                targetNode = new ClassNode();
                new ClassReader(bytes).accept(targetNode, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (Throwable t) {
                continue;
            }
            for (var selected : selectedMethods(targetNode, selector)) {
                if (callsMethod(selected, hostName, hostDesc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return whether the hand-off target is the selected method's body, lifted into a method of its own}
     *
     * <p>NeoForge splits methods up as well as delegating them: {@code Hud#extractHotbarAndDecorations} still
     * exists but no longer runs the contextual bar code, which moved verbatim into
     * {@code updateContextualBarRenderer} and is called by the layer manager instead. Nothing connects the two
     * by name, so the anchor does: when the call a Fabric mixin wraps is a call to a method of the very class
     * being patched ({@code this.nextContextualInfoState()}), the host is where that self-call went.
     *
     * <p>Two looser readings were tried and thrown away - an opcode histogram, then a contiguous instruction
     * run. Both matched a hundred methods that merely hold a similar snippet, and every move they made was a
     * mod wrapping a call it was never written for. The remaining reach of the move is small on purpose: a
     * call into another class is left where it is, since the platform is free to keep several of those.
     */
    private static boolean hostIsBodyLookalike(TargetResolver resolver, java.util.List<String> targets,
            String selector, String host, String anchor) {
        var wanted = parseAnchorMember(anchor);
        if (wanted == null) {
            return false;
        }
        for (var target : targets) {
            if (!target.equals(wanted.owner())) {
                continue;
            }
            var bytes = resolver.apply(target);
            if (bytes == null) {
                continue;
            }
            ClassNode targetNode;
            try {
                targetNode = new ClassNode();
                new ClassReader(bytes).accept(targetNode, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (Throwable t) {
                continue;
            }
            var hosts = selectedMethods(targetNode, host);
            if (hosts.isEmpty()) {
                continue;
            }
            for (var selected : selectedMethods(targetNode, selector)) {
                var selectedBody = realInstructions(selected);
                if (selectedBody.isEmpty()) {
                    continue;
                }
                for (var candidate : hosts) {
                    var hostBody = realInstructions(candidate);
                    if (hostBody.size() < 6 || hostBody.size() > selectedBody.size()) {
                        continue;
                    }
                    if (containsRun(selectedBody, hostBody)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** {@return the method's instructions, without labels, frames and line markers} */
    private static java.util.List<org.objectweb.asm.tree.AbstractInsnNode> realInstructions(
            org.objectweb.asm.tree.MethodNode method) {
        var out = new java.util.ArrayList<org.objectweb.asm.tree.AbstractInsnNode>();
        if (method.instructions == null) {
            return out;
        }
        for (var instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) {
                out.add(instruction);
            }
        }
        return out;
    }

    /** {@return whether {@code needle} appears as one contiguous run inside {@code haystack}} */
    private static boolean containsRun(java.util.List<org.objectweb.asm.tree.AbstractInsnNode> haystack,
            java.util.List<org.objectweb.asm.tree.AbstractInsnNode> needle) {
        for (var start = 0; start + needle.size() <= haystack.size(); start++) {
            var match = true;
            for (var i = 0; i < needle.size() && match; i++) {
                match = sameInstruction(haystack.get(start + i), needle.get(i));
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /** {@return whether two instructions do the same thing, operand included} */
    private static boolean sameInstruction(org.objectweb.asm.tree.AbstractInsnNode left,
            org.objectweb.asm.tree.AbstractInsnNode right) {
        if (left.getOpcode() != right.getOpcode() || left.getType() != right.getType()) {
            return false;
        }
        if (left instanceof org.objectweb.asm.tree.MethodInsnNode a
                && right instanceof org.objectweb.asm.tree.MethodInsnNode b) {
            return a.owner.equals(b.owner) && a.name.equals(b.name) && a.desc.equals(b.desc);
        }
        if (left instanceof org.objectweb.asm.tree.FieldInsnNode a
                && right instanceof org.objectweb.asm.tree.FieldInsnNode b) {
            return a.owner.equals(b.owner) && a.name.equals(b.name) && a.desc.equals(b.desc);
        }
        if (left instanceof org.objectweb.asm.tree.VarInsnNode a
                && right instanceof org.objectweb.asm.tree.VarInsnNode b) {
            return a.var == b.var;
        }
        if (left instanceof org.objectweb.asm.tree.LdcInsnNode a
                && right instanceof org.objectweb.asm.tree.LdcInsnNode b) {
            return a.cst.equals(b.cst);
        }
        if (left instanceof org.objectweb.asm.tree.IntInsnNode a
                && right instanceof org.objectweb.asm.tree.IntInsnNode b) {
            return a.operand == b.operand;
        }
        if (left instanceof org.objectweb.asm.tree.TypeInsnNode a
                && right instanceof org.objectweb.asm.tree.TypeInsnNode b) {
            return a.desc.equals(b.desc);
        }
        // Branch targets are left out of the comparison: a lifted fragment keeps its shape without the labels.
        return true;
    }

    /**
     * {@return the types a handler declares for the host method's own parameters}
     *
     * <p>{@code @Inject} and its siblings bind these to the method they inject into, so a retarget has to find
     * a method that still takes exactly them - the wrapping injectors instead bind the wrapped call's receiver
     * and arguments, which the anchor already pins down.
     */
    private static java.util.List<String> declaredHostParams(org.objectweb.asm.tree.MethodNode handler) {
        var types = parametersFor(handler);
        var out = new java.util.ArrayList<String>();
        for (var index = 0; index < types.size(); index++) {
            var type = types.get(index);
            if (hasSugar(handler, index) || type.equals("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;")
                    || type.equals("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;")
                    || type.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;")) {
                continue;
            }
            out.add(type);
        }
        return out;
    }

    /** {@return whether the handler's parameter at {@code index} carries MixinExtras sugar} */
    private static boolean hasSugar(org.objectweb.asm.tree.MethodNode handler, int index) {
        // Collected rather than List.of(...): a handler may carry only one of the two annotation lists, and
        // List.of rejects a null element.
        var lists = new java.util.ArrayList<java.util.List<AnnotationNode>[]>();
        if (handler.visibleParameterAnnotations != null) {
            lists.add(handler.visibleParameterAnnotations);
        }
        if (handler.invisibleParameterAnnotations != null) {
            lists.add(handler.invisibleParameterAnnotations);
        }
        for (var parameters : lists) {
            if (index >= parameters.length || parameters[index] == null) {
                continue;
            }
            for (var annotation : parameters[index]) {
                if (HOST_FRAME_SUGAR.contains(annotation.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@return whether the retarget's destination still declares exactly the parameters the handler binds} */
    private static boolean hostTakesHandlerParams(TargetResolver resolver, java.util.List<String> targets,
            String host, java.util.List<String> declared) {
        if (declared.isEmpty()) {
            return true;
        }
        for (var target : targets) {
            var bytes = resolver.apply(target);
            if (bytes == null) {
                continue;
            }
            ClassNode targetNode;
            try {
                targetNode = new ClassNode();
                new ClassReader(bytes).accept(targetNode, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (Throwable t) {
                continue;
            }
            for (var candidate : selectedMethods(targetNode, host)) {
                if (parametersFor(candidate).equals(declared)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return whether the method the retarget points at still takes the types the handler's sugar binds}
     *
     * <p>Mixed into a method that lost one of them, {@code @Local(argsOnly = true)} would resolve to nothing and
     * Mixin would drop the whole class rather than that one injection - so a host that does not carry them all
     * is not a host this pass may move the injection to.
     */
    private static boolean hostTakesSugarTypes(TargetResolver resolver, java.util.List<String> targets,
            String host, java.util.List<String> sugarTypes) {
        if (sugarTypes.isEmpty()) {
            return true;
        }
        for (var target : targets) {
            var bytes = resolver.apply(target);
            if (bytes == null) {
                continue;
            }
            ClassNode targetNode;
            try {
                targetNode = new ClassNode();
                new ClassReader(bytes).accept(targetNode, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (Throwable t) {
                continue;
            }
            for (var candidate : selectedMethods(targetNode, host)) {
                var available = parametersFor(candidate);
                if (available.containsAll(sugarTypes)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return {@code name+descriptor} of the one method the selected method delegates to that carries the
     * anchor, or null when the bytecode does not say so unambiguously}
     */
    private static String movedHost(TargetResolver resolver, java.util.List<String> targets,
            String selector, String anchor) {
        var wanted = parseAnchorMember(anchor);
        if (wanted == null) {
            return null;
        }
        for (var target : targets) {
            var bytes = resolver.apply(target);
            if (bytes == null) {
                continue;
            }
            ClassNode targetNode;
            try {
                targetNode = new ClassNode();
                new ClassReader(bytes).accept(targetNode, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            } catch (Throwable t) {
                continue;
            }
            var selected = selectedMethods(targetNode, selector);
            if (selected.isEmpty()) {
                continue;
            }
            var carriers = new java.util.ArrayList<org.objectweb.asm.tree.MethodNode>();
            for (var candidate : targetNode.methods) {
                if (carriesAnchor(candidate, wanted)) {
                    carriers.add(candidate);
                }
            }
            if (carriers.isEmpty() || carriers.size() > 1) {
                continue;
            }
            // Whether this host is where the work went is the caller's business: either the selected method
            // hands off to it (a wrapper and its worker, a short overload and the long one) or its body is a
            // piece of what the selected method used to run (NeoForge lifted it out into a method of its own).
            var host = carriers.get(0);
            return host.name + host.desc;
        }
        return null;
    }

    /** {@return the target class's methods the selector names, ignoring any owner prefix and omitted return type} */
    private static java.util.List<org.objectweb.asm.tree.MethodNode> selectedMethods(ClassNode targetNode,
            String selector) {
        var spec = selector;
        if (spec.startsWith("L") && spec.indexOf(';') > 0) {
            spec = spec.substring(spec.indexOf(';') + 1);
        }
        var open = spec.indexOf('(');
        var name = open < 0 ? spec : spec.substring(0, open);
        var desc = open < 0 ? null : spec.substring(open);
        var out = new java.util.ArrayList<org.objectweb.asm.tree.MethodNode>();
        for (var method : targetNode.methods) {
            if (!method.name.equals(name)) {
                continue;
            }
            if (desc != null && !method.desc.startsWith(desc.substring(0, desc.indexOf(')') + 1))) {
                continue;
            }
            out.add(method);
        }
        return out;
    }

    /** A parsed {@code @At} target: the member it names, with the parts the annotation spelled out. */
    private record AnchorMember(String owner, String name, String desc) {
    }

    private static AnchorMember parseAnchorMember(String target) {
        String owner = null;
        String name;
        String desc = null;
        if (target.startsWith("L") && target.indexOf(';') > 0) {
            var semi = target.indexOf(';');
            owner = target.substring(1, semi);
            var rest = target.substring(semi + 1);
            var open = rest.indexOf('(');
            var colon = rest.indexOf(':');
            if (open >= 0) {
                name = rest.substring(0, open);
                desc = rest.substring(open);
            } else if (colon >= 0) {
                name = rest.substring(0, colon);
                desc = rest.substring(colon + 1);
            } else {
                name = rest;
            }
        } else if (target.contains("(")) {
            var open = target.indexOf('(');
            name = target.substring(0, open);
            desc = target.substring(open);
        } else {
            name = target;
        }
        return name.isEmpty() ? null : new AnchorMember(owner, name, desc);
    }

    /** {@return whether the method's instructions call or read the anchored member} */
    private static boolean carriesAnchor(org.objectweb.asm.tree.MethodNode method, AnchorMember wanted) {
        if (method.instructions == null) {
            return false;
        }
        for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                if (call.name.equals(wanted.name()) && (wanted.owner() == null || call.owner.equals(wanted.owner()))
                        && (wanted.desc() == null || call.desc.equals(wanted.desc()))) {
                    return true;
                }
            } else if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field) {
                if (field.name.equals(wanted.name()) && (wanted.owner() == null || field.owner.equals(wanted.owner()))) {
                    return true;
                }
            } else if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode type
                    && type.getOpcode() == org.objectweb.asm.Opcodes.NEW) {
                if (wanted.owner() != null && type.desc.equals("L" + wanted.owner() + ";")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@return whether the method calls {@code owner.name+desc} on anything} */
    private static boolean callsMethod(org.objectweb.asm.tree.MethodNode method, String name, String desc) {
        if (method.instructions == null) {
            return false;
        }
        for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.name.equals(name) && call.desc.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<AnnotationNode> allAnnotationsOf(org.objectweb.asm.tree.MethodNode method) {        var all = new java.util.ArrayList<AnnotationNode>();
        if (method.visibleAnnotations != null) {
            all.addAll(method.visibleAnnotations);
        }
        if (method.invisibleAnnotations != null) {
            all.addAll(method.invisibleAnnotations);
        }
        return all;
    }

    /** {@return the string a named annotation element holds, or null} Also reads the single-element list form. */
    private static String readStringValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return null;
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (!name.equals(annotation.values.get(i))) {
                continue;
            }
            if (annotation.values.get(i + 1) instanceof String value) {
                return value;
            }
            if (annotation.values.get(i + 1) instanceof java.util.List<?> list && list.size() == 1 && list.get(0) instanceof String value) {
                return value;
            }
        }
        return null;
    }

    /**
     * {@return the nested annotation a named annotation element holds, or null}
     *
     * <p>Also reads the single-element array form: a handler's {@code at = @At(...)} reaches the class file as a
     * one-element array, so a reader that only knows the plain form silently sees no anchor at all.
     */
    private static AnnotationNode readAnnotationValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return null;
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (!name.equals(annotation.values.get(i))) {
                continue;
            }
            if (annotation.values.get(i + 1) instanceof AnnotationNode nested) {
                return nested;
            }
            if (annotation.values.get(i + 1) instanceof java.util.List<?> list && list.size() == 1
                    && list.get(0) instanceof AnnotationNode nested) {
                return nested;
            }
        }
        return null;
    }

    /** Replaces the string a named annotation element holds, in both the plain and the single-element list form. */
    private static void writeStringValue(AnnotationNode annotation, String name, String replacement) {
        if (annotation.values == null) {
            return;
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (!name.equals(annotation.values.get(i))) {
                continue;
            }
            if (annotation.values.get(i + 1) instanceof String) {
                annotation.values.set(i + 1, replacement);
            } else if (annotation.values.get(i + 1) instanceof java.util.List<?> list && list.size() == 1 && list.get(0) instanceof String) {
                annotation.values.set(i + 1, java.util.List.of(replacement));
            }
        }
    }

    /**
     * Makes a Fabric mod's own resource scan see the jar it was loaded from.
     *
     * <p>Mods scan their own package through {@code ClassLoader.getResources} - Twilight Forest's bean
     * context looks for its {@code @Component} classes that way. The loader in this environment answers for
     * class files but returns nothing for package directories, so such a scan comes up empty (Twilight
     * Forest logs "Found 0 @Component classes" and then dies on the null its entrypoint got back).
     *
     * <p>{@code FabricResourceHooks} asks the loader first and only falls back to the caller's own jar when
     * the loader had nothing, so nothing that works today changes; the fallback URL is the directory entry
     * the scan opens its file system on.
     *
     * @param jarPath the jar the patched class comes from, which is what the fallback enumerates
     * @return the class bytes with the call redirected, or the original when it does not contain it
     */
    private static byte[] redirectFabricModResourceLookup(byte[] classBytes, java.nio.file.Path jarPath) {
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
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/ClassLoader".equals(call.owner)
                        && "getResources".equals(call.name)
                        && "(Ljava/lang/String;)Ljava/util/Enumeration;".equals(call.desc)) {
                    // The receiver and the name are already on the stack; the jar path goes on top of them.
                    method.instructions.insertBefore(call, new org.objectweb.asm.tree.LdcInsnNode(jarPath.toString()));
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = "net/neoforged/neoforge/common/FabricResourceHooks";
                    call.name = "fabricModResources";
                    call.desc = "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;)Ljava/util/Enumeration;";
                    call.itf = false;
                    changed = true;
                }
            }
        }

        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Pointed the resource lookups in {} at FabricResourceHooks", jarPath.getFileName());
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Points ForgeConfigAPIPort's config registration at a helper that can serve it on NeoForge.
     *
     * <p>ForgeConfigAPIPort's Fabric build ships its own copy of {@code net.neoforged.fml.config}, and its
     * {@code ConfigTracker#registerConfig} takes the <em>mod id</em> as its third parameter. On NeoForge that
     * copy is shadowed by the platform's classes - the two cannot both be in the layer - and the platform's
     * overload takes a {@code ModContainer} instead, so the call dies with {@code NoSuchMethodError} inside
     * the calling mod's entrypoint. Twilight Forest registers its configs that way, and its aborted
     * entrypoint is what leaves the game without its content and, later, without a working resource reload.
     *
     * <p>{@code FabricConfigHooks} registers the config with a synthetic container for the Fabric mod id, so
     * the config is tracked and loaded exactly like a NeoForge mod's. The receiver is already on the stack
     * when the call is made, so the static helper takes it as its first argument and the call keeps its shape.
     *
     * @return the class bytes with the call redirected, or the original when it does not contain it
     */
    private static byte[] redirectFabricModConfigRegistration(byte[] classBytes, String entryName) {
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
                        || !"net/neoforged/fml/config/ConfigTracker".equals(call.owner)
                        || !"registerConfig".equals(call.name)) {
                    continue;
                }
                var takesModId = call.desc.startsWith("(Lnet/neoforged/fml/config/ModConfig$Type;Lnet/neoforged/fml/config/IConfigSpec;Ljava/lang/String;");
                if (!takesModId) {
                    continue;
                }
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = "net/neoforged/neoforge/common/FabricConfigHooks";
                call.name = "registerFabricModConfig";
                call.desc = "(Lnet/neoforged/fml/config/ConfigTracker;" + call.desc.substring(1);
                call.itf = false;
                changed = true;
            }
        }

        if (!changed) {
            return null;
        }
        LOGGER.info("[NeoFobric] Pointed ForgeConfigAPIPort's config registration at FabricConfigHooks");
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Points Fabric API's tooltip-order scan at the method NeoForge moved the list into.
     *
     * <p>{@code VanillaTooltipProviderOrder} reads the {@code DataComponents} field references out of
     * {@code ItemStack#addDetailsToTooltip}'s bytecode to learn the order of the vanilla tooltip components -
     * that order is what its {@code ItemComponentTooltipProviderRegistry} puts Fabric's own providers
     * around. NeoForge split the vanilla body up: the list now lives in the private
     * {@code addDetailsToTooltipComponents} (which NeoForge's own {@code ComponentTooltipOrderTest} scans by
     * name), while the public method only runs NeoForge's hooks and its registry appenders. The scan
     * therefore found nothing and every mod touching that registry died in its static initialiser with
     * "Found no component types in appendTooltip method". The component method still carries the exact
     * vanilla order - its list is the same one the registry appends - so the scan is pointed at it.
     */
    private static byte[] retargetVanillaTooltipOrderScan(byte[] classBytes, String entryName) {
        if (!FABRIC_TOOLTIP_ORDER_SCANNER.equals(entryName.replace(".class", ""))) {
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
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && "addDetailsToTooltip".equals(ldc.cst)) {
                    ldc.cst = "addDetailsToTooltipComponents";
                    changed = true;
                }
            }
        }

        if (!changed) {
            LOGGER.info("[NeoFobric] {} no longer names addDetailsToTooltip; the vanilla tooltip order scan"
                    + " needs checking against NeoForge's ItemStack", FABRIC_TOOLTIP_ORDER_SCANNER.replace('/', '.'));
            return null;
        }
        LOGGER.info("[NeoFobric] Pointed Fabric API's vanilla tooltip order scan at ItemStack#addDetailsToTooltipComponents");
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** MixinExtras' wrapper handed to a {@code @WrapOperation} handler. */
    private static final String WRAP_OPERATION_DESC = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    /** Dotted, for comparing against {@code Type#getClassName()}. */
    private static final String OPERATION = "com.llamalad7.mixinextras.injector.wrapoperation.Operation";
    /** Internal name, for comparing against {@code MethodInsnNode#owner}. */
    private static final String OPERATION_OWNER = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String OPERATION_CALL_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";

    /**
     * Appends the parameters NeoForge's version of a wrapped method has over the vanilla one to the
     * {@code @WrapOperation} handler, and feeds them to the {@code Operation.call} it makes.
     *
     * <p>{@link #extendInjectorHandlerParams} does the same for {@code @Inject} handlers; a wrap needs
     * one step more, because MixinExtras validates the arity when the handler calls
     * {@code Operation.call} - the extra parameters have to appear in that argument array as well, or the
     * call throws at the first packet. Case at hand: fabric-networking-api-v1 wraps
     * {@code RegistryFriendlyByteBuf#decorator} to stamp the buffers the connection hands out, and
     * NeoForge's decorator takes the connection type as a second argument the vanilla-shaped handler does
     * not declare.
     *
     * <p>Only static targets are handled: for an instance method the argument array starts with the
     * receiver, and this rewrite has no way to know where the handler got it from.
     */
    private static byte[] widenWrapOperationHandlers(byte[] classBytes, String entryName,
            java.util.function.Function<String, byte[]> targetBytecode) {
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable t) {
            return null;
        }
        var changed = false;
        var resolvedOwners = new java.util.HashMap<String, ClassNode>();
        for (var handler : node.methods) {
            var wrap = findAnnotation(handler, WRAP_OPERATION_DESC);
            if (wrap == null) {
                continue;
            }
            var args = Type.getArgumentTypes(handler.desc);
            var operationIndex = -1;
            for (var i = 0; i < args.length; i++) {
                if (args[i].getClassName().equals(OPERATION)) {
                    operationIndex = i;
                    break;
                }
            }
            // The wrapper is the last parameter by convention, and anything after it would be
            // MixinExtras sugar this rewrite does not carry along.
            if (operationIndex <= 0 || operationIndex != args.length - 1) {
                LOGGER.debug("[NeoFobric] Not widening @WrapOperation handler {}#{}: the Operation is not the last parameter",
                        node.name.replace('/', '.'), handler.name);
                continue;
            }
            var prefix = new Type[operationIndex];
            System.arraycopy(args, 0, prefix, 0, operationIndex);

            var target = annotationDescriptor(wrap);
            if (target == null) {
                LOGGER.debug("[NeoFobric] Not widening @WrapOperation handler {}#{}: no target descriptor in the annotation",
                        node.name.replace('/', '.'), handler.name);
                continue;
            }
            var open = target.indexOf('(');
            var close = target.indexOf(')');
            if (open < 0 || close <= open) {
                continue;
            }
            // The descriptor names its owner, and the wrapped method lives there - not necessarily in the
            // mixin's own target class (the mixin targets the class whose code makes the call; the call
            // itself usually goes to another class entirely).
            var ownerEnd = target.lastIndexOf(';', open);
            if (!target.startsWith("L") || ownerEnd < 0) {
                continue;
            }
            var owner = target.substring(1, ownerEnd);
            var name = target.substring(ownerEnd + 1, open);
            var desc = target.substring(open);
            ClassNode wrappedOwner = null;
            if (!resolvedOwners.containsKey(owner)) {
                var ownerBytes = targetBytecode.apply(owner);
                if (ownerBytes != null) {
                    try {
                        var ownerNode = new ClassNode();
                        new ClassReader(ownerBytes).accept(ownerNode, 0);
                        wrappedOwner = ownerNode;
                    } catch (Throwable t) {
                        // Fall through: the owner is reported as unresolvable below.
                    }
                }
                resolvedOwners.put(owner, wrappedOwner);
            } else {
                wrappedOwner = resolvedOwners.get(owner);
            }
            if (wrappedOwner == null) {
                LOGGER.debug("[NeoFobric] Not widening @WrapOperation handler {}#{}: cannot resolve the owner of {}{}",
                        node.name.replace('/', '.'), handler.name, name, desc);
                continue;
            }
            MethodNode wrapped = null;
            for (var candidate : wrappedOwner.methods) {
                if (candidate.name.equals(name) && candidate.desc.equals(desc)) {
                    wrapped = candidate;
                    break;
                }
            }
            if (wrapped == null) {
                LOGGER.debug("[NeoFobric] Not widening @WrapOperation handler {}#{}: {} does not declare {}{}",
                        node.name.replace('/', '.'), handler.name, wrappedOwner.name.replace('/', '.'), name, desc);
                continue;
            }
            if ((wrapped.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0) {
                LOGGER.debug("[NeoFobric] Not widening @WrapOperation handler {}#{}: {} is an instance method",
                        node.name.replace('/', '.'), handler.name, name);
                continue;
            }
            var wrappedArgs = Type.getArgumentTypes(wrapped.desc);
            if (wrappedArgs.length <= prefix.length) {
                continue;
            }
            var missing = new Type[wrappedArgs.length - prefix.length];
            System.arraycopy(wrappedArgs, prefix.length, missing, 0, missing.length);

            var newArgs = new Type[wrappedArgs.length + 1];
            System.arraycopy(prefix, 0, newArgs, 0, prefix.length);
            System.arraycopy(missing, 0, newArgs, prefix.length, missing.length);
            newArgs[wrappedArgs.length] = args[operationIndex];

            var isStatic = (handler.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
            var oldOperationSlot = isStatic ? 0 : 1;
            for (var param : prefix) {
                oldOperationSlot += param.getSize();
            }
            var addedSlots = 0;
            for (var missingType : missing) {
                addedSlots += missingType.getSize();
            }

            for (var insn : handler.instructions) {
                if (insn instanceof VarInsnNode varInsn && varInsn.var >= oldOperationSlot) {
                    varInsn.var += addedSlots;
                } else if (insn instanceof IincInsnNode iinc && iinc.var >= oldOperationSlot) {
                    iinc.var += addedSlots;
                }
            }
            if (handler.localVariables != null) {
                for (var local : handler.localVariables) {
                    if (local.index >= oldOperationSlot) {
                        local.index += addedSlots;
                    }
                }
            }
            handler.signature = null;
            handler.maxLocals += addedSlots;
            handler.desc = Type.getMethodDescriptor(Type.getReturnType(handler.desc), newArgs);
            // The array the handler passes to Operation.call is built with the old arity; grow it and hand
            // the appended parameters over at the end, where the wrapped method expects them.
            if (!extendOperationCallArray(handler, prefix.length, missing, isStatic, addedSlots)) {
                // Put the method back the way it was: a handler whose call would still have the old arity
                // must not be left half-widened.
                handler.desc = Type.getMethodDescriptor(Type.getReturnType(handler.desc), args);
                handler.maxLocals -= addedSlots;
                for (var insn : handler.instructions) {
                    if (insn instanceof VarInsnNode varInsn && varInsn.var >= oldOperationSlot + addedSlots) {
                        varInsn.var -= addedSlots;
                    } else if (insn instanceof IincInsnNode iinc && iinc.var >= oldOperationSlot + addedSlots) {
                        iinc.var -= addedSlots;
                    }
                }
                LOGGER.debug("[NeoFobric] Not widening @WrapOperation handler {}#{}: its Operation.call could not be found",
                        node.name.replace('/', '.'), handler.name);
                continue;
            }

            LOGGER.info("[NeoFobric] Widened @WrapOperation handler {}#{} for {}.{}{}", node.name.replace('/', '.'),
                    handler.name, wrappedOwner.name.replace('/', '.'), name, wrapped.desc);
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
     * Grows the {@code new Object[n]{...}} a {@code @WrapOperation} handler builds for
     * {@code Operation.call} by the appended parameters. The array is built right before the call, in the
     * straight-line shape javac emits for it.
     */
    private static boolean extendOperationCallArray(MethodNode handler, int prefixLength, Type[] missing,
            boolean handlerIsStatic, int addedSlots) {
        org.objectweb.asm.tree.MethodInsnNode call = null;
        for (var insn : handler.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode methodInsn
                    && OPERATION_OWNER.equals(methodInsn.owner) && "call".equals(methodInsn.name)
                    && OPERATION_CALL_DESC.equals(methodInsn.desc)) {
                call = methodInsn;
                break;
            }
        }
        if (call == null) {
            return false;
        }

        // Walk back to the array construction: ANEWARRAY java/lang/Object, preceded by its size constant.
        org.objectweb.asm.tree.AbstractInsnNode arrayCreation = call.getPrevious();
        while (arrayCreation != null && !(arrayCreation instanceof org.objectweb.asm.tree.TypeInsnNode typeInsn
                && typeInsn.getOpcode() == org.objectweb.asm.Opcodes.ANEWARRAY)) {
            arrayCreation = arrayCreation.getPrevious();
        }
        if (arrayCreation == null) {
            return false;
        }
        var sizeInsn = prevRealInstruction(arrayCreation);
        var size = sizeInsn == null ? null : intConstantValue(sizeInsn);
        if (size == null) {
            return false;
        }
        handler.instructions.set(sizeInsn, intConstant(size + missing.length));

        // The slots the appended parameters live in, taken from the handler's new descriptor: the prefix
        // keeps its positions, the appended parameters follow it.
        var newArgs = Type.getArgumentTypes(handler.desc);
        var appendedSlots = new int[missing.length];
        var slot = handlerIsStatic ? 0 : 1;
        for (var i = 0; i < newArgs.length; i++) {
            if (i >= prefixLength && i < prefixLength + missing.length) {
                appendedSlots[i - prefixLength] = slot;
            }
            slot += newArgs[i].getSize();
        }

        var inserts = new org.objectweb.asm.tree.InsnList();
        for (var i = 0; i < missing.length; i++) {
            inserts.add(new InsnNode(org.objectweb.asm.Opcodes.DUP));
            inserts.add(intConstant(prefixLength + i));
            inserts.add(new VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, appendedSlots[i]));
            inserts.add(new InsnNode(org.objectweb.asm.Opcodes.AASTORE));
        }
        handler.instructions.insertBefore(call, inserts);
        handler.maxStack += 2;   // DUP plus the loaded parameter
        return true;
    }

    private static org.objectweb.asm.tree.AbstractInsnNode prevRealInstruction(org.objectweb.asm.tree.AbstractInsnNode insn) {
        var previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    /** {@return the value of an int constant instruction, or {@code null} when it is not one} */
    private static Integer intConstantValue(org.objectweb.asm.tree.AbstractInsnNode insn) {
        if (insn instanceof InsnNode plain) {
            var opcode = plain.getOpcode();
            if (opcode == org.objectweb.asm.Opcodes.ICONST_M1) {
                return -1;
            }
            if (opcode >= org.objectweb.asm.Opcodes.ICONST_0 && opcode <= org.objectweb.asm.Opcodes.ICONST_5) {
                return opcode - org.objectweb.asm.Opcodes.ICONST_0;
            }
        }
        if (insn instanceof org.objectweb.asm.tree.IntInsnNode intInsn
                && (intInsn.getOpcode() == org.objectweb.asm.Opcodes.BIPUSH
                        || intInsn.getOpcode() == org.objectweb.asm.Opcodes.SIPUSH)) {
            return intInsn.operand;
        }
        if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof Integer value) {
            return value;
        }
        return null;
    }

    private static org.objectweb.asm.tree.AbstractInsnNode intConstant(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(org.objectweb.asm.Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new org.objectweb.asm.tree.IntInsnNode(org.objectweb.asm.Opcodes.BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new org.objectweb.asm.tree.IntInsnNode(org.objectweb.asm.Opcodes.SIPUSH, value);
        }
        return new org.objectweb.asm.tree.LdcInsnNode(value);
    }

    private static String annotationString(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return null;
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String text) {
                return text;
            }
        }
        return null;
    }

    /**
     * {@return the first method descriptor in an annotation, nested annotations and lists included}
     *
     * <p>An injector's target usually sits one level down: {@code @WrapOperation(at = @At(target = "..."))},
     * and MixinExtras puts the same thing inside {@code slice = @Slice(from = @At(...))}. Looking only at
     * the annotation's own {@code target} key finds nothing for those and the whole rewrite quietly skips.
     */
    private static String annotationDescriptor(AnnotationNode annotation) {
        if (annotation.values == null) {
            return null;
        }
        for (var i = 1; i < annotation.values.size(); i += 2) {
            var value = annotation.values.get(i);
            if (value instanceof String text && text.indexOf('(') > 0 && text.indexOf(')') > 0) {
                return text;
            }
            if (value instanceof AnnotationNode nested) {
                var found = annotationDescriptor(nested);
                if (found != null) {
                    return found;
                }
            }
            if (value instanceof List<?> list) {
                for (var element : list) {
                    if (element instanceof String text && text.indexOf('(') > 0 && text.indexOf(')') > 0) {
                        return text;
                    }
                    if (element instanceof AnnotationNode nested) {
                        var found = annotationDescriptor(nested);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
        }
        return null;
    }

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
            "net/fabricmc/fabric/mixin/item/DataComponentMapBuilderMixin",
            // fabric-tag-api-v1's TagFileMixin re-declares the record's {@code remove} component - NeoForge
            // added one with the same erased descriptor and the same "entries to drop from the tag" meaning,
            // so the mixin's own copy costs nothing while its codec modifier is what teaches the tag file the
            // {@code fabric:remove} key. Dropping the whole mixin (which is what happens without this entry)
            // leaves the key unparsed: fabric-tag-api-v1's TagEntryRemovalTests then finds the tag exactly as
            // written, with the entries that were supposed to disappear still in it.
            "net/fabricmc/fabric/mixin/tag/TagFileMixin");

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
     *
     * <p>{@code ItemStackMixin} (fabric-events-interaction-v0) fires {@code ItemEvents.USE_ON} by wrapping
     * the {@code Item#useOn} call inside {@code ItemStack#useOn}. NeoForge moved that call out of reach: the
     * logical client now reaches it through a lambda and the logical server through
     * {@code CommonHooks#onPlaceItemIntoWorld}. The wrap therefore never applies, and a mixin class that
     * fails takes its siblings with it - which here would also kill {@code ItemEvents.USE}. The platform
     * fires the same event from both of its call sites instead
     * ({@link FabricBridgeHooks#fireFabricItemUseOn(Object)}), so only this one handler goes.
     *
     * <p>The rest are the set ForgifiedFabricAPI also drops, for the same reasons: each one anchors on a
     * vanilla fluid, furnace, hopper or bed body that NeoForge rewrote. Fabric's fluid <em>physics</em> still
     * reaches the game - the bridge builds NeoForge fluid types out of the same registry
     * ({@link FabricBridgeHooks#fabricFluidBehavior(Object)}) - but the per-tag enter/exit callbacks those
     * handlers drive, Fabric's stack-aware furnace remainder (which NeoForge now asks for itself) and the
     * hopper hooks onto {@code ItemStorage} (NeoForge's own hopper consults item handlers) do not. The
     * sleeping-direction one is replaced by a hook, since NeoForge's bed support now runs through
     * {@code BlockState#getBedDirection} instead of {@code BedBlock#getBedOrientation}:
     * {@link FabricBridgeHooks#fireFabricModifySleepingDirection(Object, Object, Object)}.
     */
    private static final java.util.Map<String, java.util.Set<String>> STRIP_HANDLER_METHODS = java.util.Map.ofEntries(
            java.util.Map.entry("net/fabricmc/fabric/mixin/client/model/loading/ModelManagerMixin",
                    java.util.Set.of("cancelVanillaDeserialize", "actuallyDeserializeModel", "resolveExtraModels")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/event/interaction/ItemStackMixin",
                    java.util.Set.of("handleUseOnEvent")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/content/registry/fluid/EntityMixin",
                    java.util.Set.of("checkIfInSwimmableFluid", "checkIfUnderSwimmableFluid", "checkIfStandingInSwimmableFluid",
                            "checkCustomFluids", "preventParticlesInFluids")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/content/registry/fluid/LivingEntityMixin",
                    java.util.Set.of("isInCustomFluid", "travelInCustomFluid", "tryOtherFluidsForFluidJumping",
                            "customFluidDrowning", "fixDoubleUpdateForCustomFluids", "jumpInCustomFluid",
                            "swapFluidForShallowCheck", "swapFluidTag")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/item/AbstractFurnaceBlockEntityMixin",
                    java.util.Set.of("getCraftingRemainder")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/transfer/HopperBlockEntityMixin",
                    java.util.Set.of("hookInsert", "hookExtract")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/particle/EntityMixin",
                    java.util.Set.of("modifyBlockStateParticleOption")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/particle/LivingEntityMixin",
                    java.util.Set.of("modifyBlockStateParticleOption")),
            // Both bed hooks go: NeoForge's bed support runs through BlockState#setBedOccupied and
            // BlockState#getBedDirection instead of the BedBlock/LivingEntity calls they anchor on, and the
            // platform fires both events from its own bed code (see FabricBridgeHooks). The rest of the class -
            // the damage and sleep events - applies, and is what Fabric mods actually use.
            java.util.Map.entry("net/fabricmc/fabric/mixin/entity/event/LivingEntityMixin",
                    java.util.Set.of("onGetSleepingDirection", "setOccupiedState")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/entity/event/elytra/LivingEntityMixin",
                    java.util.Set.of("injectElytraCheck")),
            // Both handlers go, and neither is a loss: NeoForge's defaultCraftingReminder already asks the
            // stack-aware ItemStack#getCraftingRemainder, which is the whole point of Fabric's pair. Keeping
            // half of it would be worse than keeping none - the remainder handler reads the stack the capture
            // handler shares with it.
            java.util.Map.entry("net/fabricmc/fabric/mixin/item/CraftingRecipeMixin",
                    java.util.Set.of("captureStack", "getStackRemainder")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/content/registry/FuelValuesMixin",
                    java.util.Set.of("build")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/content/registry/fluid/AbstractBoatMixin",
                    java.util.Set.of("customFluidSupport")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/event/interaction/ServerGamePacketListenerImplMixin",
                    java.util.Set.of("onPickItemFromBlock")),
            // NeoForge fixes MC-232360 itself - it asks BlockState#isEmpty instead of isAir, in both the section
            // and its block counter - so Fabric's redirect has nothing left to correct. ForgifiedFabricAPI has no
            // counterpart for either file.
            java.util.Map.entry("net/fabricmc/fabric/mixin/block/LevelChunkSectionMixin",
                    java.util.Set.of("modifyAirCheck")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/block/ChunkSectionBlockStateCounterMixin",
                    java.util.Set.of("modifyAirCheck")),
            // Same shape: NeoForge's watchdog report prints entire stacks through its own ThreadInfoUtil, which
            // is what Fabric's modifier substituted.
            java.util.Map.entry("net/fabricmc/fabric/mixin/crash/report/info/ServerWatchdogMixin",
                    java.util.Set.of("printEntireThreadDump")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/client/renderer/block/particle/ScreenEffectRendererMixin",
                    java.util.Set.of("onReturnGetInWallBlockState")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/client/renderer/block/render/LevelRendererMixin",
                    java.util.Set.of("cancelCollectParts")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/client/sound/SoundEngineMixin",
                    java.util.Set.of("getStream")),
            // The custom-fluid HUD bubbles, the renderer API's material flag proxy on the extractor path, and the
            // screen API's render hook: each fails on a NeoForge-rewritten body while its own class carries the
            // hooks Fabric mods actually use - ScreenEvents.remove/beforeTick/afterTick and the renderer API's
            // siblings - so dropping the one that cannot bind brings the rest back. What is lost is the fluid
            // bubble overlay, the material flags on that one path, and ScreenEvents' before/after render pair.
            java.util.Map.entry("net/fabricmc/fabric/mixin/content/registry/client/fluid/HudMixin",
                    java.util.Set.of("popTheBubbleForCustomFluids")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/client/renderer/block/render/LevelExtractorMixin",
                    java.util.Set.of("hasMaterialFlagProxy")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/screen/GuiMixin",
                    java.util.Set.of("onExtractGui")),
            // The HUD layer API's wraps of the direct overlay calls: NeoForge's Hud no longer makes them, it
            // registers the same overlays with its GuiLayerManager instead - which is the machinery the bridge's
            // FabricHudLayers already hooks - so Fabric's layer API keeps working and these wraps are obsolete.
            java.util.Map.entry("net/fabricmc/fabric/mixin/client/rendering/HudMixin",
                    java.util.Set.of("wrapAirBar", "wrapArmorBar", "wrapBossHealthOverlay", "wrapChat", "wrapCrosshair",
                            "wrapDemoTimer", "wrapExperienceLevel", "wrapExtractInfoBar", "wrapExtractSpectatorGui",
                            "wrapFoodBar", "wrapHealthBar", "wrapHeldItemTooltip", "wrapHotbar", "wrapMiscOverlays",
                            "wrapMobEffectOverlay", "wrapMountHealth", "wrapOverlayMessage", "wrapPlayerList",
                            "wrapScoreboardSidebar", "wrapSleepOverlay", "wrapSpectatorMenu", "wrapTitleAndSubtitle")),
            // Datagen's tag provider: the argument modifier watches a body NeoForge rewrote. Datagen only.
            java.util.Map.entry("net/fabricmc/fabric/mixin/datagen/TagsProviderMixin",
                    java.util.Set.of("addReplaced")),
            // The menu hook NeoForge moved behind a second openMenu overload: making it bind needs the
            // handler's parameters rewritten as well (which ForgifiedFabricAPI did by hand), and pointing the
            // annotation alone kills the server. Dropping it revives the class's closeContainer redirect, so the
            // extended-menu half stays unimplemented and everything else in the class works.
            java.util.Map.entry("net/fabricmc/fabric/mixin/menu/ServerPlayerMixin",
                    java.util.Set.of("fabric_storeOpenedMenu", "fabric_closeContainerScreenIfAllowed", "fabric_replaceVanillaScreenPacket")),
            // The next round of the same: the item API's client-side block breaking hook and the renderer API's
            // model wrapper remembering the bakery.
            java.util.Map.entry("net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin",
                    java.util.Set.of("fabricItemContinueBlockBreakingInject")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/client/renderer/block/model/SimpleModelWrapperMixin",
                    java.util.Set.of("storeModelBakery")),
            // The fluid renderer kept FA's head hook (custom fluid *appearance* still gets its chance) but this
            // expression modifier watches a vanilla check NeoForge's rewritten tesselate no longer performs.
            // What is lost is FA's say in the "is this face an overlay" decision, not the fluid API itself.
            java.util.Map.entry("net/fabricmc/fabric/mixin/client/rendering/fluid/FluidRendererMixin",
                    java.util.Set.of("modifyNonOverlayCheck")),
            // Both sign screens build their texture with Identifier.parse(...).withPrefix(...) now, so the
            // withDefaultNamespace call these wrap is gone. Known difference: a custom SignType registered through
            // Fabric's API falls back to the minecraft: namespace for the GUI texture instead of its own.
            java.util.Map.entry("net/fabricmc/fabric/mixin/object/builder/client/SignEditScreenMixin",
                    java.util.Set.of("init")),
            java.util.Map.entry("net/fabricmc/fabric/mixin/object/builder/client/HangingSignEditScreenMixin",
                    java.util.Set.of("init")),
            // The moving-block submission of FA's material flags (the same wrap LevelExtractorMixin carried, at
            // the other end of the pipeline). Everything else in this class - the Mesh-taking submit methods that
            // make FRAPI work at all - stays.
            java.util.Map.entry("net/fabricmc/fabric/mixin/client/renderer/submit/SubmitNodeCollectionMixin",
                    java.util.Set.of("hasMaterialFlagProxy")));

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
    /** The patched copy of a jar lives in the mods folder own cache directory, so the loader never mistakes it for a mod. */
    static Path realignedPath(Path jarPath) {
        var fileName = jarPath.getFileName().toString();
        var parent = jarPath.toAbsolutePath().normalize().getParent();
        var modsDir = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
        if (modsDir.equals(parent)) {
            return modsDir.resolve(CACHE_DIR_NAME).resolve(fileName + PATCHED_SUFFIX);
        }
        return jarPath.resolveSibling(fileName + PATCHED_SUFFIX);
    }

    /**
     * {@return a key describing everything that decides the patched copy of a jar}
     *
     * <p>Used by {@link #patchJar} to keep a patched copy across runs: the mod jar itself, the game content
     * (a platform update changes which classes have to be patched) and the bridge (a bridge update changes
     * how). {@code null} means "cannot tell", and nothing is cached then.
     */
    private static String patchCacheStamp(Path jarPath, TargetResolver resolver) {
        try {
            return "v1/" + Files.size(jarPath) + ":" + Files.getLastModifiedTime(jarPath).toMillis()
                    + "/" + resolver.stamp() + "/" + bridgeStamp() + "/" + modSetStamp;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A key for the whole set of mod jars this pass is running over.
     *
     * <p>Needed because one jar's patched copy does not depend on that jar alone: the accessor-collision plan
     * is computed across all of them, so adding or removing a mod can change how the others are patched. The
     * planner runs before the jars are patched and stores the result here.
     */
    private static volatile String modSetStamp = "none";

    /** Records the jar set the next patching pass works on, for {@link #patchCacheStamp}. */
    public static void noteModSet(java.util.Collection<Path> jars) {
        var builder = new StringBuilder();
        var names = new java.util.ArrayList<>(jars);
        names.sort(java.util.Comparator.comparing(Path::toString));
        for (var jar : names) {
            try {
                builder.append(jar.getFileName()).append(':').append(Files.size(jar)).append(';');
            } catch (Throwable t) {
                builder.append(jar.getFileName()).append(":?;");
            }
        }
        modSetStamp = builder.toString();
    }

    private static String bridgeStamp() {
        try {
            var source = MixinJarPatcher.class.getProtectionDomain().getCodeSource();
            var path = source == null || source.getLocation() == null ? null : Path.of(source.getLocation().toURI());
            return path != null && Files.isRegularFile(path)
                    ? Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis()
                    : "exploded";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static Path patchCacheFile(Path jarPath) {
        var patched = realignedPath(jarPath);
        return patched.resolveSibling(patched.getFileName() + ".neofobric-stamp");
    }

    /** {@return the patched copy to use, or {@code null} when this jar has to be patched again} */
    private static Path readPatchCache(Path jarPath, String stamp) {
        if (stamp == null || Boolean.getBoolean("neofobric.noPatchCache")) {
            return null;
        }
        try {
            var file = patchCacheFile(jarPath);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            var text = Files.readString(file).trim();
            if (!text.endsWith(stamp)) {
                return null;
            }
            if (text.startsWith("patched:")) {
                var copy = realignedPath(jarPath);
                return Files.isRegularFile(copy) ? copy : null;
            }
            return jarPath;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writePatchCache(Path jarPath, String stamp, boolean patched) {
        if (stamp == null || Boolean.getBoolean("neofobric.noPatchCache")) {
            return;
        }
        try {
            var file = patchCacheFile(jarPath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, (patched ? "patched:" : "unchanged:") + stamp);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not write the patch cache entry for {}", jarPath, t);
        }
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

        /**
         * {@return a key for the game content this resolver worked against, for the patch cache}
         */
        public String stamp() {
            var cached = contentStamp;
            if (cached != null) {
                return cached;
            }
            var builder = new StringBuilder();
            for (var root : gameContent) {
                try {
                    builder.append(Files.size(root)).append(':').append(Files.getLastModifiedTime(root).toMillis()).append(',');
                } catch (Throwable t) {
                    builder.append("?,");
                }
            }
            contentStamp = builder.toString();
            return contentStamp;
        }

        private volatile String contentStamp;

        /**
         * {@return every class name the platform itself ships}
         *
         * <p>Unlike {@link #apply}, which answers "can this class be resolved from the game content", this is
         * an exact inventory: the shading pass uses it to tell a class the platform really has (which stays
         * the platform's) from one only mods carry.
         */
        public java.util.Set<String> platformClassNames() {
            var known = platformClasses;
            if (known != null) {
                return known;
            }
            var names = new java.util.HashSet<String>();
            for (var root : gameContent) {
                try {
                    if (Files.isDirectory(root)) {
                        try (var walk = Files.walk(root)) {
                            walk.filter(Files::isRegularFile).forEach(file -> {
                                var relative = root.relativize(file).toString().replace('\\', '/');
                                if (relative.endsWith(".class")) {
                                    names.add(relative.substring(0, relative.length() - ".class".length()));
                                }
                            });
                        }
                    } else if (Files.isRegularFile(root)) {
                        try (var contents = JarContents.ofPath(root)) {
                            contents.visitContent((relativePath, resource) -> {
                                if (relativePath.endsWith(".class")) {
                                    names.add(relativePath.substring(0, relativePath.length() - ".class".length()));
                                }
                            });
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.debug("[NeoFobric] Failed to index the classes of {}", root, t);
                }
            }
            platformClasses = names;
            return names;
        }

        private volatile java.util.Set<String> platformClasses;

        /** {@return the platform's own bytes for a class, ignoring the mod jars} */
        public byte[] platformClassBytes(String internalName) {
            return load(internalName);
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
        // Patching is pure bytecode work on one jar, so its result can be kept and reused: the same mod
        // jar, the same game content and the same bridge always produce the same patched copy. Reusing it
        // skips the whole pass - which is the bridge's longest startup phase - and is what makes a restart
        // as fast as the platform's own start. The stamp covers the jar itself, the game content (a
        // platform update changes what has to be patched) and the bridge (a bridge update changes how).
        // Delete the cache directory or pass -Dneofobric.noPatchCache=true to force a rebuild.
        var stamp = patchCacheStamp(jarPath, resolver);
        var cached = readPatchCache(jarPath, stamp);
        if (cached != null) {
            return cached;
        }
        var entries = new ArrayList<String>();
        var patched = new HashMap<String, byte[]>();
        // Every class in this jar. Fabric reaches private Minecraft members from ordinary
        // implementation classes too (ModPackResourcesUtil calls PackMetadataSection.codecForPackType),
        // so the access scan has to look at all of them, not only the mixins.
        var allClasses = new ArrayList<byte[]>();
        // Mixin classes only, for the rewrites below.
        var mixinClasses = new ArrayList<byte[]>();
        try (var zip = new ZipFile(jarPath.toFile())) {
            // Classes of this jar that live in a platform namespace and have to be shaded out of it, because
            // the platform either has no such API at all or a smaller version of it (see the method).
            // Off by default: the pass shades correctly, but for a jar that is nested inside another mod
            // (Fuzs' data extensions ride inside IllagerInvasion) fabric-loader still unpacks and loads the
            // copy from the parent jar, so the shaded classes never reach the classpath and the mod keeps
            // failing on exactly the class this was meant to provide. Finishing that needs the patched nested
            // jar written back into its parent's realigned copy; until then the feature stays behind this
            // switch so it cannot change the behaviour of packs that work.
            var platformShading = Boolean.getBoolean("neofobric.shadePlatformCopies")
                    ? platformNamespaceShadingPlan(zip, resolver)
                    : java.util.Map.<String, String>of();
            if (!platformShading.isEmpty()) {
                LOGGER.info("[NeoFobric] Shading {} platform-namespace class(es) of {} - the platform does not provide "
                        + "those (or provides less), so the jar's own copies are kept under {}: {}",
                        platformShading.size(), jarPath.getFileName(), SHADED_PREFIX,
                        platformShading.keySet().stream().limit(4).map(n -> n + " -> " + SHADED_PREFIX + n)
                                .collect(java.util.stream.Collectors.joining(", "))
                                + (platformShading.size() > 4 ? ", ..." : ""));
            }
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
                if (!platformShading.isEmpty() && !entry.getName().endsWith(".class")) {
                    // Text entries name the shaded classes too: a Fabric class tweaker injects the missing
                    // interface into the game's own lookup class by name, and it is read after this pass, so
                    // it has to move with them or the tweaker asks for a class that is gone.
                    var textBytes = zip.getInputStream(entry).readAllBytes();
                    var text = new String(textBytes, java.nio.charset.StandardCharsets.UTF_8);
                    var rewritten = text;
                    for (var moved : platformShading.entrySet()) {
                        rewritten = rewritten.replace(moved.getKey(), moved.getValue())
                                .replace(moved.getKey().replace('/', '.'), moved.getValue().replace('/', '.'));
                    }
                    if (!rewritten.equals(text)) {
                        patched.put(entry.getName(), rewritten.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        LOGGER.info("[NeoFobric] Moved the shaded classes in {}", entry.getName());
                    }
                }
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                var bytes = zip.getInputStream(entry).readAllBytes();
                if (!platformShading.isEmpty()) {
                    var shadedBytes = remapPlatformNamespaceReferences(bytes, platformShading);
                    if (shadedBytes != null) {
                        var entryInternalName = entry.getName().substring(0, entry.getName().length() - ".class".length());
                        var shadedName = platformShading.get(entryInternalName);
                        if (shadedName != null) {
                            // This class moves: it is a plain implementation class, so no later pass needs to
                            // see it. The writer walks `entries`, so the new name has to be registered and the
                            // old one dropped, or the shaded class never reaches the jar.
                            patched.put(shadedName + ".class", shadedBytes);
                            entries.add(shadedName + ".class");
                            entries.remove(entry.getName());
                            continue;
                        }
                        patched.put(entry.getName(), shadedBytes);
                        bytes = shadedBytes;
                    }
                }
                var versionMoved = renameFabricVersionChannel(bytes, entry.getName());
                if (versionMoved != bytes) {
                    patched.put(entry.getName(), versionMoved);
                }
                // Runs for plain classes too: the tooltip-order scanner is an ordinary implementation class,
                // not a mixin.
                var orderScanRetargeted = retargetVanillaTooltipOrderScan(versionMoved, entry.getName());
                if (orderScanRetargeted != null) {
                    versionMoved = orderScanRetargeted;
                    patched.put(entry.getName(), versionMoved);
                }
                var tagParseRedirected = redirectFabricTagFileParse(versionMoved, entry.getName());
                if (tagParseRedirected != null) {
                    versionMoved = tagParseRedirected;
                    patched.put(entry.getName(), versionMoved);
                }
                var configRegistrationRedirected = redirectFabricModConfigRegistration(versionMoved, entry.getName());
                if (configRegistrationRedirected != null) {
                    versionMoved = configRegistrationRedirected;
                    patched.put(entry.getName(), versionMoved);
                }
                var resourceLookupRedirected = redirectFabricModResourceLookup(versionMoved, jarPath);
                if (resourceLookupRedirected != null) {
                    versionMoved = resourceLookupRedirected;
                    patched.put(entry.getName(), versionMoved);
                }
                var blockStateModelTypesRedirected = redirectFabricBlockStateModelTypes(versionMoved);
                if (blockStateModelTypesRedirected != null) {
                    versionMoved = blockStateModelTypesRedirected;
                    patched.put(entry.getName(), versionMoved);
                }
                var tooltipInjectionsRetargeted = retargetItemStackTooltipInjections(versionMoved, entry.getName());
                if (tooltipInjectionsRetargeted != null) {
                    versionMoved = tooltipInjectionsRetargeted;
                    patched.put(entry.getName(), versionMoved);
                }
                var anvilInjectionsRetargeted = retargetInjectionMethods(versionMoved, entry.getName());
                if (anvilInjectionsRetargeted != null) {
                    versionMoved = anvilInjectionsRetargeted;
                    patched.put(entry.getName(), versionMoved);                }
                var damageHookRetargeted = retargetItemStackDamageHook(versionMoved, entry.getName());
                if (damageHookRetargeted != null) {
                    versionMoved = damageHookRetargeted;
                    patched.put(entry.getName(), versionMoved);
                }
                var chunkRemovalRetargeted = retargetLevelChunkBlockEntityRemoval(versionMoved, entry.getName());
                if (chunkRemovalRetargeted != null) {
                    versionMoved = chunkRemovalRetargeted;
                    patched.put(entry.getName(), versionMoved);
                }
                // Last of the retargets: it reads the anchors the passes above may have rewritten, and it works
                // out where an anchor went from the two classes rather than from a list.
                var movedAnchorsRetargeted = retargetMovedAnchors(versionMoved, entry.getName(), resolver);
                if (movedAnchorsRetargeted != null) {
                    versionMoved = movedAnchorsRetargeted;
                    patched.put(entry.getName(), versionMoved);
                }
                var behaviourLookupGuarded = initialiseCreateBehavioursOnLookup(versionMoved, entry.getName());
                if (behaviourLookupGuarded != null) {
                    versionMoved = behaviourLookupGuarded;
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
                    // A/B diagnostic: -Dneofobric.noTransferBridge=true leaves Fabric's transfer transactions
                    // on Fabric's own stack, so a failure can be attributed to this step or ruled out.
                    if (!Boolean.getBoolean("neofobric.noTransferBridge")) {
                        var bridgedTransactions = bridgeFabricTransferTransactions(plain, entry.getName());
                        if (bridgedTransactions != null) {
                            plain = bridgedTransactions;
                            patched.put(entry.getName(), plain);
                        }
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
                // The chain starts from what the passes above already produced, not from the raw jar bytes:
                // their annotation rewrites belong to the class too, and restarting from the original threw
                // them away whenever a later pass also happened to fire on the same class.
                var realigned = realignMixinTargets(versionMoved, entry.getName());
                var source = realigned != null ? realigned : versionMoved;
                var retargeted = retargetStaleReturnOrdinals(source, entry.getName(), resolver);
                if (retargeted != null) {
                    source = retargeted;
                }
                noteFabricEnumExtensions(source, entry.getName(), resolver);
                var mergedList = mergeRegistryListSwap(source, entry.getName());
                if (mergedList != null) {
                    source = mergedList;
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
                var wrapsWidened = widenWrapOperationHandlers(source, entry.getName(), resolver);
                if (wrapsWidened != null) {
                    source = wrapsWidened;
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
                } else if (realigned != null || stripped != null || handlerStripped != null || retargeted != null || mergedList != null) {
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
            writePatchCache(jarPath, stamp, false);
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
        writePatchCache(jarPath, stamp, true);
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
