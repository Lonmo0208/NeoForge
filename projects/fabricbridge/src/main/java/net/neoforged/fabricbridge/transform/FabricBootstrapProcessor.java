/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.transform;

import java.io.IOException;
import java.util.List;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.mixin.FMLMixinService;
import net.neoforged.fabricbridge.FabricBridge;
import net.neoforged.fabricbridge.discovery.FabricJarRegistry;
import net.neoforged.neoforgespi.transformation.ClassProcessor;
import net.neoforged.neoforgespi.transformation.ProcessorName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The FML {@link ClassProcessor} through which the bridge integrates itself:
 *
 * <ul>
 * <li>{@link #link} registers the mixin configs of all discovered Fabric mods into FML's mixin
 * pipeline, before any game class is transformed. This is what makes Fabric mods' mixins apply
 * to Minecraft classes.</li>
 * <li>{@link #processClass} applies the small runtime patches to Minecraft classes that
 * fabric-loader would normally apply itself through its {@code GamePatch}es: handing the game
 * instance over ({@code Minecraft.<init>} / the server main class) and appending the
 * {@code fabric} brand ({@code getServerModName}/{@code getClientModName}).</li>
 * </ul>
 */
public final class FabricBootstrapProcessor implements ClassProcessor {
    static {
        // A Fabric mixin may declare instance <em>field initialisers</em>; sponge-mixin then has to
        // guess where to place them inside the target's constructor. Its default heuristic
        // (Initialiser.InjectionMode.DEFAULT) picks the last INVOKESPECIAL <init> of the target class
        // or its superclass. NeoForge's patched constructors can contain a nested "new Target(...)"
        // inside a loop, so the initialiser ends up <em>inside the loop body</em> and never runs when
        // that loop is skipped - leaving the field null (real crash: fabric-resource-loader-v1's
        // PackMixin.parentsPredicate, null NPE in Pack.fabric$parentsEnabled).
        //
        // Initialiser.InjectionMode.SAFE skips the heuristic entirely and always inserts the
        // initialisers directly after the super() call. The option is read lazily
        // (MixinEnvironment$Option.getEnumValue -> System.getProperty) on every initialiser
        // application, i.e. long after this class is initialised and before any game class is
        // transformed, and the value is compared with toUpperCase(Locale.ROOT), so the literal
        // below is exactly right. On by default; "neofobric.initialiserInjectionSafe=false" restores
        // sponge-mixin's DEFAULT heuristic for A/B diagnosis only - it brings the Pack.parentsPredicate
        // NPE back, so never ship with it disabled.
        if (Boolean.parseBoolean(System.getProperty("neofobric.initialiserInjectionSafe", "true"))) {
            System.setProperty("mixin.initialiserInjectionMode", "SAFE");
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricBootstrapProcessor.class);

    private static final String HOOKS_CLASS = "net/neoforged/fabricbridge/FabricBridgeHooks";

    private static final String MINECRAFT_CLASS = "net/minecraft/client/Minecraft";
    private static final String MINECRAFT_SERVER_CLASS = "net/minecraft/server/MinecraftServer";
    private static final String CLIENT_BRAND_RETRIEVER_CLASS = "net/minecraft/client/ClientBrandRetriever";
    private static final String SERVER_MAIN_CLASS = "net/minecraft/server/Main";
    private static final String DEDICATED_SERVER_CLASS = "net/minecraft/server/dedicated/DedicatedServer";
    private static final String NETWORK_REGISTRY_CLASS = "net/neoforged/neoforge/network/registration/NetworkRegistry";

    /** {@code NetworkRegistry#getCodec(Identifier, ConnectionProtocol, PacketFlow)}. */
    private static final String GET_CODEC_DESC =
            "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/network/ConnectionProtocol;"
                    + "Lnet/minecraft/network/protocol/PacketFlow;)Lnet/minecraft/network/codec/StreamCodec;";

    private static boolean mixinRegistrationDone;

    @Override
    public ProcessorName name() {
        return new ProcessorName("neofobric", "fabric_bootstrap");
    }

    @Override
    public OrderingHint orderingHint() {
        return OrderingHint.EARLY;
    }

    @Override
    public void link(LinkContext context) {
        // Register the bridged fabric launcher as early as possible: Minecraft's own early
        // hooks (registry-sync's server Main mixin) query fabric-loader's launcher long before
        // the full loader boot runs in CommonModLoader.
        net.neoforged.fabricbridge.FabricBridge.ensureLauncherRegistered();
        // Provide the bytecode source for conflict pre-filtering before any registration.
        net.neoforged.fabricbridge.transform.MixinConflictFilter.install(context);
        if (mixinRegistrationDone) {
            return;
        }
        mixinRegistrationDone = true;
        registerFabricMixinConfigs();
    }

    @Override
    public boolean handlesClass(SelectionContext context) {
        if (!FabricBridge.hasFabricMods()) {
            return false;
        }
        return switch (context.type().getInternalName()) {
            case MINECRAFT_CLASS, MINECRAFT_SERVER_CLASS, CLIENT_BRAND_RETRIEVER_CLASS, SERVER_MAIN_CLASS,
                    DEDICATED_SERVER_CLASS, NETWORK_REGISTRY_CLASS -> true;
            default -> false;
        };
    }

    /*
     * MixinExtras is deliberately NOT initialised here any more.
     *
     * NeoForge embeds mixinextras-neoforge through jarJar and lets Mixin load it, so
     * com.llamalad7.mixinextras.MixinExtrasBootstrap is already defined by the time the bridge runs.
     * Every attempt to load it again - MixinService's class provider (defineClass) or Class.forName
     * (ModuleClassLoader.readerToClass) - aborts with
     * "LinkageError: attempted duplicate class definition for MixinExtrasBootstrap", and that failure
     * then disables every @WrapOperation/@WrapMethod in the process. The call was making things
     * worse, not better: it never once succeeded in the whole debugging session.
     */

    @Override
    public ComputeFlags processClass(TransformationContext context) {
        if (Boolean.getBoolean("neofobric.noClassPatch")) {
            return ComputeFlags.NO_REWRITE;
        }
        var node = context.node();
        var changed = switch (node.name) {
            case MINECRAFT_CLASS -> injectClientGameInstanceHandoff(node);
            case MINECRAFT_SERVER_CLASS -> wrapBranding(node, "getServerModName");
            case CLIENT_BRAND_RETRIEVER_CLASS -> wrapBranding(node, "getClientModName");
            case SERVER_MAIN_CLASS -> injectServerGameInstanceHandoff(node);
            case NETWORK_REGISTRY_CLASS -> injectFabricCodecFallback(node);
            default -> false;
        };

        if (changed) {
            context.audit("neofobric.fabric_bootstrap", "injected fabric bridge hooks");
        }
        return changed ? ComputeFlags.SIMPLE_REWRITE : ComputeFlags.NO_REWRITE;
    }

    /**
     * Makes NeoForge ask Fabric first when it looks up a payload codec.
     *
     * <p>Both sides claim {@code minecraft:register} but with different payload classes: Fabric sends
     * its own {@code RegistrationPayload}, while NeoForge's encoder resolves the id to its
     * {@code MinecraftRegisterPayload} and casts - the connection then dies with
     * {@code ClassCastException: RegistrationPayload cannot be cast to MinecraftRegisterPayload} in the
     * configuration phase, which is what stops a client entering any world, singleplayer included.
     *
     * <p>Injecting here rather than replacing NeoForge's registration keeps NeoForge in charge of
     * everything Fabric does not know about: the call returns {@code null} unless Fabric registered a
     * codec for that exact id in that protocol and direction, so ids only NeoForge handles (including
     * every NeoForge mod payload) keep NeoForge's codec.
     */
    /**
     * Redirects NeoForge's payload-codec lookup through the bridge.
     *
     * <p>Both sides claim {@code minecraft:register} but with different payload classes: Fabric sends its
     * own {@code RegistrationPayload}, while NeoForge's encoder resolves the id to its
     * {@code MinecraftRegisterPayload} and casts - the connection then dies with
     * {@code ClassCastException} in the configuration phase, so no world can be entered, singleplayer
     * included.
     *
     * <p>The whole method is replaced by one call, deliberately without any branch. Injecting a branch
     * here aborts class loading with
     * {@code VerifyError: Expecting a stackmap frame at branch target} - FML writes the transformed class
     * without recomputing stack map frames, so any new jump target needs a frame that does not exist.
     * The hook consults Fabric first and falls back to NeoForge's own lookup by calling this method
     * again, guarded by a re-entrancy flag so the second entry runs the original NeoForge logic.
     */
    private static boolean injectFabricCodecFallback(ClassNode node) {
        // Disabled by default. Redirecting getCodec made NeoForge encode its own
        // MinecraftUnregisterPayload through a different codec, and the connection aborted with
        // "MinecraftUnregisterPayload cannot be cast to DiscardedPayload". It is also no longer needed:
        // Fabric stops sending its own register/unregister packets entirely (see
        // NeoFobricClassTweakerProcessor#suppressFabricChannelPackets), so the payload-id conflict this
        // was meant to resolve cannot arise. Kept behind a flag for a Fabric mod payload that needs it.
        if (!Boolean.getBoolean("neofobric.codecRedirect")) {
            return false;
        }
        for (var method : node.methods) {
            if (!"getCodec".equals(method.name) || !GET_CODEC_DESC.equals(method.desc)) {
                continue;
            }
            var body = new InsnList();
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            body.add(new VarInsnNode(Opcodes.ALOAD, 1));
            body.add(new VarInsnNode(Opcodes.ALOAD, 2));
            // The hook is declared with Object parameters and an Object return so the bridge needs no
            // compile-time dependency on either loader; the stack types still line up because every
            // argument is a reference.
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "net/neoforged/fabricbridge/FabricBridgeHooks",
                    "fabricPayloadCodec",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    false));
            body.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/network/codec/StreamCodec"));
            body.add(new InsnNode(Opcodes.ARETURN));

            method.instructions.clear();
            method.tryCatchBlocks = new java.util.ArrayList<>();
            method.localVariables = null;
            method.instructions.add(body);
            method.maxStack = 3;
            method.maxLocals = 3;
            LOGGER.info("[NeoFobric] Redirected NetworkRegistry#getCodec through the Fabric payload lookup");
            return true;
        }
        return false;
    }

    /**
     * Registers every Fabric mod's mixin config with FML's mixin service, mirroring what
     * {@code FabricMixinBootstrap} does in a pure Fabric environment. Runs once during class
     * processor linking, i.e. before the first game class is transformed.
     */
    /**
     * Mixin configs that must not be registered, because applying them stops the integrated server
     * from finishing world generation.
     *
     * <p>{@code fabric-networking-api-v1} is the culprit: with it registered the world load stalls
     * right after {@code 准备生成区域中：16%} and the server shuts down without any exception, while
     * skipping it lets the same save load in ~2s. Bisection showed it is not one mixin but the
     * combination - keeping only the accessors loads fine, and dropping any of the trailing eleven
     * plain mixins breaks it again, which points at a cumulative interaction rather than a single
     * bad injection. Override with {@code -Dneofobric.forcedMixinConfigs=<substring>} when working on
     * a real fix, or {@code -Dneofobric.registerAllMixinConfigs=true} to register everything.
     */
    /**
     * Mixin configs that must not be registered at all.
     *
     * <p>Currently empty: the one entry this used to hold ({@code fabric-networking-api-v1}) is now
     * handled far more precisely by dropping only the four payload mixins that clash with NeoForge,
     * which keeps the rest of Fabric's networking working. See
     * {@code MixinConflictFilter.DEFAULT_SKIPPED_MIXIN_CLASSES}. Override with
     * {@code -Dneofobric.registerAllMixinConfigs=true} to register everything.
     */
    private static final List<String> DEFAULT_SKIPPED_MIXIN_CONFIGS = List.of(
            // Rewrites the registry lifecycle into Fabric's two-phase freeze: BootstrapMixin redirects
            // bootStrap() to createContents() so the freeze is deferred, and MinecraftMixin/MainMixin
            // call bootStrap() again after mod init to finish it. NeoForge freezes the registries once,
            // during startup, so that second pass hits "Registry is already frozen" from
            // bindBootstrappedTagsToEmpty and the game never gets past initialisation. NeoForge runs its
            // own registry synchronisation, so the mechanism is duplicated here; dropping the config
            // leaves the normal single freeze.
            "fabric-registry-sync-v0",
            // Its client mixins rewrite BlockStateModel / ModelLoader after NeoForge has already patched
            // them, and every vanilla model then fails with
            // "Failed to load model minecraft:models/block/<name>.json" from ModelManager - the game draws
            // black-and-magenta placeholder cubes for every block and item. NeoForge's model system has the
            // same extension points, so dropping Fabric's copy restores model loading.
            "fabric-model-loading-api-v1");

    private static void registerFabricMixinConfigs() {
        if (!FabricBridge.hasFabricMods()) {
            return;
        }
        if (Boolean.getBoolean("neofobric.noFabricMixins")) {
            LOGGER.warn("[NeoFobric] Fabric mixin registration disabled (neofobric.noFabricMixins) - diagnostic only");
            return;
        }

        IMixinService mixinService = MixinService.getService();
        if (!(mixinService instanceof FMLMixinService fmlMixinService)) {
            LOGGER.warn("[NeoFobric] FML's mixin service is not active ({}), Fabric mods will not have their mixins applied", mixinService);
            return;
        }

        var loader = FMLLoader.getCurrentOrNull();
        if (loader == null) {
            return;
        }

        var client = FMLEnvironment.getDist() == Dist.CLIENT;
        var registered = 0;
        // Iterate the registry directly: all Fabric jars live in one composite game library,
        // so the loading mod list does not carry one entry per Fabric mod.
        for (var entry : net.neoforged.fabricbridge.discovery.FabricJarRegistry.getEntries().entrySet()) {
            var info = entry.getValue();

            for (var configPath : info.mixinConfigsFor(client)) {
                // A/B diagnosis: -Dneofobric.skipMixinConfigs=<substring> skips matching configs.
                // -Dneofobric.onlyMixinConfigs=<substring> registers ONLY matching configs.
                var skip = System.getProperty("neofobric.skipMixinConfigs");
                if (skip != null && !skip.isBlank() && configPath.contains(skip)) {
                    LOGGER.warn("[NeoFobric] Skipping mixin config {} (diagnostic filter)", configPath);
                    continue;
                }
                var only = System.getProperty("neofobric.onlyMixinConfigs");
                if (only != null && !only.isBlank() && !configPath.contains(only)) {
                    continue;
                }
                if (!Boolean.getBoolean("neofobric.registerAllMixinConfigs")
                        && !Boolean.getBoolean("neofobric.noDefaultMixinSkips")
                        && DEFAULT_SKIPPED_MIXIN_CONFIGS.stream().anyMatch(configPath::contains)) {
                    var forced = System.getProperty("neofobric.forcedMixinConfigs");
                    if (forced == null || forced.isBlank() || !configPath.contains(forced)) {
                        LOGGER.warn("[NeoFobric] Skipping mixin config {} - it stops world generation from completing", configPath);
                        continue;
                    }
                }
                byte[] configContent;
                try (var jar = net.neoforged.fml.jarcontents.JarContents.ofPath(entry.getKey())) {
                    configContent = jar.readFile(configPath);
                    if (configContent != null) {
                        configContent = relaxMixinConfig(configContent);
                        configContent = MixinConflictFilter.filter(configContent, jar, info.modId(), client);
                    }
                } catch (Exception e) {
                    configContent = null;
                }
                if (configContent == null) {
                    LOGGER.warn("[NeoFobric] Fabric mod {} declares mixin config {}, which does not exist in its jar", info.modId(), configPath);
                    continue;
                }

                try {
                    fmlMixinService.addMixinConfigContent(configPath, configContent);
                    Mixins.addConfiguration(configPath);

                    // Tag the config the same way fabric-loader does, so FML's mixin behaviour
                    // versioning applies to it. Guarded: several Fabric mods can ship the same config
                    // path (a mod bundles a Fabric API module), and decorating an already decorated
                    // config throws - which used to abort the whole registration of that config.
                    for (var config : Mixins.getConfigs()) {
                        if (config.getName().equals(configPath)) {
                            try {
                                config.getConfig().decorate(FabricUtil.KEY_MOD_ID, info.modId());
                                config.getConfig().decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_LATEST);
                            } catch (IllegalArgumentException alreadyDecorated) {
                                LOGGER.debug("[NeoFobric] Mixin config {} was already tagged by another Fabric mod", configPath);
                            }
                        }
                    }
                    registered++;
                } catch (Throwable t) {
                    LOGGER.error("[NeoFobric] Failed to register mixin config {} from Fabric mod {}", configPath, info.modId(), t);
                }
            }
        }

        if (registered > 0) {
            LOGGER.info("[NeoFobric] Registered {} Fabric mixin config(s)", registered);
        }
    }

    /**
     * Rewrites a Fabric mixin config to be lenient: {@code "required": false} on the config and
     * {@code "defaultRequire": 0} in every {@code injectors} block.
     *
     * <p>NeoForge reshapes some Minecraft internals (event hooks replacing vanilla call sites,
     * refactored renderer interfaces, ...), so not every Fabric mixin finds its injection target
     * in this environment. Strict configs would turn any such mismatch into a hard game crash;
     * lenient ones log a warning and skip just that injection, letting the game run while the
     * affected loader-side integrations are implemented.
     */
    private static byte[] relaxMixinConfig(byte[] content) {
        var text = new String(content, java.nio.charset.StandardCharsets.UTF_8);

        var relaxed = text
                // Config and per-injector "required" flags.
                .replaceAll("(\"required\"\\s*:\\s*)true", "$1false")
                // Existing defaultRequire values become 0.
                .replaceAll("(\"defaultRequire\"\\s*:\\s*)-?\\d+", "$10")
                // Injectors blocks without a defaultRequire get one inserted.
                .replaceAll("(\"injectors\"\\s*:\\s*\\{)(?![^{}]*\"defaultRequire\")", "$1\"defaultRequire\":0,");

        if (relaxed.equals(text)) {
            return content;
        }

        // Validate that the rewritten text is still valid JSON; fall back to the original otherwise.
        try (var reader = new net.fabricmc.loader.impl.lib.gson.JsonReader(
                new java.io.InputStreamReader(new java.io.ByteArrayInputStream(relaxed.getBytes(java.nio.charset.StandardCharsets.UTF_8)), java.nio.charset.StandardCharsets.UTF_8))) {
            reader.setLenient(true);
            skipFully(reader);
        } catch (IOException e) {
            LOGGER.warn("[NeoFobric] Failed to relax a mixin config, registering it as-is", e);
            return content;
        }
        return relaxed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void skipFully(net.fabricmc.loader.impl.lib.gson.JsonReader reader) throws IOException {
        while (true) {
            if (reader.peek() == net.fabricmc.loader.impl.lib.gson.JsonToken.END_DOCUMENT) {
                return;
            }
            reader.skipValue();
        }
    }

    /**
     * Finds the instruction that stores into the named instance field of the class under
     * transformation.
     *
     * <p>Used as a stable anchor in the Minecraft constructor: the field a client needs (its window,
     * its options) is a far more durable place to hook than a log message, which upstream texts
     * change freely between versions.
     *
     * @return the instruction storing into the field, or {@code null}
     */
    private static AbstractInsnNode findFieldAssignment(MethodNode method, String fieldName) {
        for (var insn : method.instructions) {
            if (insn instanceof FieldInsnNode fieldInsn && fieldInsn.getOpcode() == Opcodes.PUTFIELD
                    && fieldInsn.name.equals(fieldName)) {
                return insn;
            }
        }
        return null;
    }

    /**
     * @return the last {@code RETURN} of the method, i.e. the point where every field is assigned
     */
    private static AbstractInsnNode lastReturn(MethodNode method) {
        AbstractInsnNode result = null;
        for (var insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                result = insn;
            }
        }
        return result;
    }

    /**
     * Injects {@code FabricBridgeHooks.attachGameInstance(this)} into the Minecraft constructor,
     * right after the "Backend library" log call (the same spot fabric-loader uses) or, failing
     * that, right after the super constructor call.
     */
    private static boolean injectClientGameInstanceHandoff(ClassNode node) {
        MethodNode target = null;
        for (var method : node.methods) {
            if (method.name.equals("<init>") && (target == null || method.instructions.size() > target.instructions.size())) {
                target = method;
            }
        }
        if (target == null) {
            LOGGER.warn("[NeoFobric] Could not find the Minecraft constructor, Fabric mods will not receive the game instance");
            return false;
        }

        // Anchor inside the constructor only, and as early as is safe.
        //
        // The client hand-off has to complete before the client reaches the point where Fabric mod
        // mixins read the client state: Sodium's Minecraft#setFullscreen hook reads its config while
        // the window is being set up, which is roughly a fifth of the way into this constructor. So
        // the hand-off is anchored right after the window field is assigned - the instance and the
        // window both exist by then, and it is still well ahead of that hook. Anchoring at the end
        // of the constructor is too late; anchoring before the window exists makes the entrypoints
        // see a null window.
        // Anchor inside the constructor, as early as the hand-off is valid.
        //
        // The constraint is a race against Fabric mod mixins that read client state during this very
        // constructor: Sodium's Minecraft#setFullscreen hook reads its config while the window is
        // being created, and in current Minecraft the window field is only assigned around
        // instruction 840 of ~1960 - i.e. *after* that hook has already run. Anchoring at the window
        // assignment or at the end of the constructor is therefore always too late.
        //
        // The Minecraft instance and its options are what is needed, and the options field is
        // assigned at roughly instruction 370, comfortably ahead of the window setup. A null window
        // is tolerated by the entrypoints that matter (Sodium's config builder stores the window but
        // does not touch it), while a null instance is not - so this is the earliest safe point.
        var insertionPoint = findFieldAssignment(target, "options");
        var anchor = "options-field";
        if (insertionPoint == null) {
            insertionPoint = findFieldAssignment(target, "window");
            anchor = "window-field";
        }
        if (insertionPoint == null) {
            insertionPoint = lastReturn(target);
            anchor = "constructor-end";
        }
        if (insertionPoint == null) {
            insertionPoint = findBackendLibraryLogInsn(target);
            anchor = "backend-library-log";
        }
        if (insertionPoint == null) {
            // Last resort: directly after the super constructor call.
            for (var insn : target.instructions) {
                if (insn instanceof MethodInsnNode methodInsn && methodInsn.getOpcode() == Opcodes.INVOKESPECIAL
                        && methodInsn.name.equals("<init>") && !methodInsn.owner.equals(node.name)) {
                    insertionPoint = insn;
                    anchor = "super-call";
                    break;
                }
            }
        }
        if (insertionPoint == null) {
            LOGGER.warn("[NeoFobric] Could not find an injection point in the Minecraft constructor");
            return false;
        }
        LOGGER.info("[NeoFobric] Client hand-off anchored at {} in the Minecraft constructor", anchor);

        var instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CLASS, "attachGameInstance", "(Ljava/lang/Object;)V", false));
        target.instructions.insert(insertionPoint, instructions);

        // Fabric's client entrypoints are invoked from attachGameInstance, i.e. here, while the
        // Minecraft instance is still being constructed. That ordering is required, not incidental:
        // Sodium's Minecraft#setFullscreen mixin reads the client config during this same
        // constructor, so the entrypoint that registers that config has to run first. Running them
        // after construction (or after the first screen) is too late and crashes the client.
        return true;
    }

    /**
     * Finds the instruction that logs the "Backend library" message in the Minecraft constructor.
     */
    private static AbstractInsnNode findBackendLibraryLogInsn(MethodNode method) {
        for (var insn : method.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value && value.startsWith("Backend library")) {
                // Insert after the actual log invocation that follows the LDC.
                var next = insn.getNext();
                while (next != null && !(next instanceof MethodInsnNode)) {
                    next = next.getNext();
                }
                if (next != null) {
                    return next;
                }
            }
        }
        return null;
    }

    /**
     * Wraps every return value of the given brand method with {@code FabricBridgeHooks#insertBranding}.
     */
    private static boolean wrapBranding(org.objectweb.asm.tree.ClassNode node, String methodName) {
        var changed = false;
        for (var method : node.methods) {
            if (!method.name.equals(methodName) || !method.desc.endsWith(")Ljava/lang/String;")) {
                continue;
            }

            var instructions = method.instructions;
            for (var insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.ARETURN) {
                    instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CLASS, "insertBranding",
                            "(Ljava/lang/String;)Ljava/lang/String;", false));
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Injects {@code FabricBridgeHooks.setServerGameInstance(server)} into the server main class:
     * fabric-loader hooks the synthetic method that constructs the dedicated server, we hook the
     * same spot when it directly returns the constructed server.
     */
    private static boolean injectServerGameInstanceHandoff(ClassNode node) {
        for (var method : node.methods) {
            if (method.name.equals("main") || !method.desc.endsWith(")L" + DEDICATED_SERVER_CLASS + ";")) {
                continue;
            }

            for (var insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode methodInsn)
                        || methodInsn.getOpcode() != Opcodes.INVOKESPECIAL
                        || !methodInsn.owner.equals(DEDICATED_SERVER_CLASS)
                        || !methodInsn.name.equals("<init>")) {
                    continue;
                }

                // Only apply for the simple `return new DedicatedServer(...)` pattern.
                var next = nextRealInsn(insn);
                if (next != null && next.getOpcode() == Opcodes.ARETURN) {
                    var instructions = new InsnList();
                    instructions.add(new InsnNode(Opcodes.DUP));
                    instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CLASS, "setServerGameInstance", "(Ljava/lang/Object;)V", false));
                    method.instructions.insertBefore(next, instructions);
                    return true;
                }
            }
        }

        LOGGER.debug("[NeoFobric] No game instance hand-off point found in the server main class");
        return false;
    }

    private static AbstractInsnNode nextRealInsn(AbstractInsnNode insn) {
        var next = insn.getNext();
        while (next != null && (next instanceof org.objectweb.asm.tree.LabelNode || next instanceof FrameNode
                || next instanceof org.objectweb.asm.tree.LineNumberNode)) {
            next = next.getNext();
        }
        return next;
    }
}
