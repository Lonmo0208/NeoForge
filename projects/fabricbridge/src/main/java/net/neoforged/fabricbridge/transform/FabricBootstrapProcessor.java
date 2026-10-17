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
    private static final String BUILT_IN_REGISTRIES_CLASS = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String CLIENT_BRAND_RETRIEVER_CLASS = "net/minecraft/client/ClientBrandRetriever";
    private static final String SERVER_MAIN_CLASS = "net/minecraft/server/Main";
    private static final String DEDICATED_SERVER_CLASS = "net/minecraft/server/dedicated/DedicatedServer";
    private static final String NETWORK_REGISTRY_CLASS = "net/neoforged/neoforge/network/registration/NetworkRegistry";
    /**
     * The first game class either dist loads, and therefore where {@link #forceMixinPreparationIfPending}
     * gets its first chance to run.
     */
    private static final String SHARED_CONSTANTS_CLASS = "net/minecraft/SharedConstants";

    /** {@code NetworkRegistry#getCodec(Identifier, ConnectionProtocol, PacketFlow)}. */
    private static final String GET_CODEC_DESC =
            "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/network/ConnectionProtocol;"
                    + "Lnet/minecraft/network/protocol/PacketFlow;)Lnet/minecraft/network/codec/StreamCodec;";

    private static boolean mixinRegistrationDone;
    /** Set once Mixin has really prepared the Fabric configs; see {@link #forceMixinPreparationIfPending}. */
    private static volatile boolean mixinPrepared;
    /** Consecutive failed forced-preparation attempts, for escalation out of the DEBUG retry noise. */
    private static int forcedPreparationFailures;

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
        // Same reason for the earliest possible moment: modernfix's mixin plugin reads its options file
        // when its config is first selected, and the option this writes must be in place by then.
        net.neoforged.fabricbridge.ModernfixMixinGuard.ensure();
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
            case MINECRAFT_CLASS, MINECRAFT_SERVER_CLASS, BUILT_IN_REGISTRIES_CLASS, CLIENT_BRAND_RETRIEVER_CLASS, SERVER_MAIN_CLASS,
                    DEDICATED_SERVER_CLASS, NETWORK_REGISTRY_CLASS, SHARED_CONSTANTS_CLASS -> true;
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
        // The pipeline hands us game classes from here on, which is exactly when Mixin can be asked to
        // prepare the Fabric configs - and the last moment before the game's own class loading starts.
        forceMixinPreparationIfPending();
        var node = context.node();
        var changed = switch (node.name) {
            case MINECRAFT_CLASS -> injectClientGameInstanceHandoff(node);
            case MINECRAFT_SERVER_CLASS -> wrapBranding(node, "getServerModName");
            case BUILT_IN_REGISTRIES_CLASS -> injectBootstrapFreeze(node);
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
     * Injects the deferred registry freeze into {@code BuiltInRegistries#bootStrap}, directly in front
     * of its own {@code freeze()} call.
     *
     * <p>The registry window the bridge keeps open for the Fabric entrypoints (see
     * {@code FabricBridgeHooks#deferFabricWindow}) used to close right behind the entrypoints, inside
     * the Minecraft-constructor hand-off. That is too early: Fabric API's registry sync re-runs
     * {@code bootStrap()} from its own mixin on that same constructor, and mods register late content
     * inside that second bootstrap - ViaFabricPlus hangs its particle-type registration behind
     * {@code bootStrap}'s {@code createContents()} call. Freezing behind the entrypoints handed those
     * registrations "Registry is already frozen". The hand-off now only arms the freeze; this injection
     * runs it inside the second bootstrap, after {@code createContents()} (so the late registrations
     * are in) and before {@code freeze()} (so the registries are still open for the shell-mod
     * RegisterEvent pass that runs here too) - the last registry-writing step of client startup.
     *
     * <p>The first bootstrap never reaches this code: Fabric's registry-sync redirects the
     * {@code BuiltInRegistries.bootStrap()} call inside {@code Bootstrap.bootStrap()} to
     * {@code createContents()} only, so this method body - and the hook with it - first runs from the
     * registry-sync mixin's own deferred call. On the datagen and dedicated-server paths nothing is
     * armed (the window was never taken over) and the hook is a no-op.
     */
    private static boolean injectBootstrapFreeze(ClassNode node) {
        for (var method : node.methods) {
            if (!"bootStrap".equals(method.name) || !"()V".equals(method.desc)) {
                continue;
            }
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && "freeze".equals(call.name)
                        && "()V".equals(call.desc)
                        && node.name.equals(call.owner)) {
                    method.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "net/neoforged/fabricbridge/FabricBridgeHooks",
                            "freezeRegistriesAfterBootstrap",
                            "()V",
                            false));
                    LOGGER.info("[NeoFobric] Registry freeze armed behind the Fabric entrypoints now runs in front of BuiltInRegistries.bootStrap's own freeze");
                    return true;
                }
            }
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
            // fabric-registry-sync-v0 used to be skipped here: it defers the vanilla registry freeze to
            // after mod initialization (BootstrapMixin redirects bootStrap() to createContents, then
            // MainMixin/MinecraftMixin run bootStrap() again), and NeoForge's single freeze made that
            // second pass die on an already closed registry. Both halves are fixed now - the root registry
            // stays open, and BuiltInRegistries.freeze() skips registries that are closed already - and it
            // has to be registered: its ListenableRegistry/RegistryAttribute layer is what
            // RegistryEntryAddedCallback, FabricRegistryBuilder, the menu API and the recipe API stand on.
            // Without it they throw "Unsupported registry" / "registry not marked as modded".
            // fabric-model-loading-api-v1 also used to be skipped whole: its
            // BlockStateModelUnbakedMixin replaces the codecs of BlockStateModel.Unbaked.<clinit> and
            // bypasses NeoForge's custom-model dispatch, so every blockstate model failed to decode.
            // That one mixin is now dropped by name (see MixinConflictFilter
            // DEFAULT_SKIPPED_MIXIN_CLASSES) and the config is registered again: its plugin machinery
            // is what puts Fabric mods' extra models into the bakery - without it Create's
            // dynamically rendered parts (partial models, create:block/flywheel/block) silently fall
            // back to the missing model and draw black-and-magenta.
            // Fabric's creative tab module ships its own page switcher for the creative inventory: the
            // client mixin adds previous/next arrows and filters tab visibility by its own page model,
            // right next to the page arrows and filtering the host loader already does - the screen ends up
            // with two switchers that disagree about which tabs are on the current page. NeoForge's paging
            // is the one that stays (it is where every tab lives in this setup), and the Fabric API methods
            // of that interface are routed onto it by MixinJarPatcher#bridgeCreativeTabApi. Only the client
            // config is dropped: the common one carries the tab registration API Fabric mods actually use.
            "fabric-creative-tab-api-v1.client.mixins.json");

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
        // A config path can be declared by more than one jar: Fabric API's own modules ship them, and
        // Sodium, Iris and friends bundle copies of the very same modules. Only the first copy may be
        // registered - the composite module takes the first jar's classes, so registering a second (often
        // older) copy of the config would leave Mixin owning a package with a list that does not match the
        // classes loaded from it. An accessor declared only in the first copy then cannot be applied, and
        // loading it dies with "Illegal classload request for accessor mixin ... The mixin is missing from
        // <config>.mixins.json which owns package ...". Twilight Forest goes through Fabric API's atlas
        // registry and hits exactly that when Sodium's bundled Fabric API differs from the standalone one.
        var registeredConfigPaths = new java.util.HashSet<String>();
        // Parent configs already fed to the mixin service this pass (a config path shared by many children
        // must only be fed once; the service tolerates repeats, but this keeps the log honest).
        var fedParentConfigs = new java.util.HashSet<String>();
        // Iterate the registry directly: all Fabric jars live in one composite game library,
        // so the loading mod list does not carry one entry per Fabric mod.
        for (var entry : net.neoforged.fabricbridge.discovery.FabricJarRegistry.getEntries().entrySet()) {
            var info = entry.getValue();

            // fabric-loader does not load a mod whose environment is the other side, so its mixins never
            // apply there - and a client-only module's mixins are written against classes that side does
            // not have. Fabric's screen module is the visible case: registering its config on a dedicated
            // server makes Mixin reach for net.minecraft.client.gui.screens.Screen, which the dist cleaner
            // has already taken away, so every such config costs an ERROR and two WARNs and gains nothing.
            var environment = info.environment();
            if (environment != null && !environment.isBlank() && !"*".equals(environment)
                    && !(client ? "client" : "server").equals(environment)) {
                LOGGER.debug("[NeoFobric] Skipping the mixin configs of {} - its environment is \"{}\", not this {} side",
                        info.modId(), environment, client ? "client" : "server");
                continue;
            }

            for (var configPath : info.mixinConfigsFor(client)) {
                if (!registeredConfigPaths.add(configPath)) {
                    LOGGER.info("[NeoFobric] Ignoring the duplicate of mixin config {} from {} - another jar provides"
                            + " it, and that one's classes are the ones in the game layer", configPath, info.modId());
                    continue;
                }
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
                        configContent = relaxMixinConfig(configContent, client, configPath);
                        configContent = MixinConflictFilter.filter(configContent, jar, info.modId(), client);
                        // A config may build on a "parent" config - a settings carrier no mod declares as
                        // one of its own; feed those into the service too (see feedParentMixinConfigs).
                        feedParentMixinConfigs(fmlMixinService, configContent, jar, fedParentConfigs);
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
     * Pins a Fabric mixin config to {@code DEFAULT}, the environment FML's mixin service runs in.
     *
     * <p>Mixin decides which environment a config belongs to when it reads the file: an
     * {@code "environment"} selector if present, and otherwise whatever {@code MixinEnvironment} happens to
     * be current at that moment. A config is only ever selected - and therefore only ever <em>prepared</em>,
     * the step that builds its target index - in the environment it belongs to. Our configs are registered
     * at {@code link()} time, so an unpinned one would belong to a phase the platform has already left,
     * {@code select()} would decline it, and nothing would prepare it until the phase came back around.
     */
    private static byte[] pinMixinEnvironment(byte[] content) {
        try {
            var text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
            var root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            var current = root.get("environment");
            if (current != null && "DEFAULT".equals(current.getAsString())) {
                return content;
            }
            root.addProperty("environment", "DEFAULT");
            return root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not pin a mixin config to the DEFAULT environment", t);
            return content;
        }
    }

    /**
     * Feeds the parent mixin configs a config builds on into FML's mixin service.
     *
     * <p>A "parent" config is a settings carrier that no mod declares as one of its own configs - in a pure
     * Fabric install the mixin service simply reads it out of the jar by name when a child names it. Here
     * the Fabric jars are not on the by-name-visible classpath while the configs are registered (the game
     * layer is built after this point), so the service only knows the configs it was fed; a child naming a
     * parent nobody fed then fails with
     *
     * <pre>Error initialising parent mixin config c2me.mixins.json of c2me-base.mixins.json: The specified
     * resource 'c2me.mixins.json' was invalid or could not be read</pre>
     *
     * which is how c2me's 22 module configs - all parented to {@code c2me.mixins.json} in its base jar -
     * lost every one of their mixins. The parent is only fed, never registered: pure Fabric does not
     * register it either, it just resolves it as a resource.
     */
    private static void feedParentMixinConfigs(FMLMixinService service, byte[] configContent,
            net.neoforged.fml.jarcontents.JarContents jar, java.util.Set<String> fed) {
        try {
            var parent = parentConfigOf(configContent);
            for (var depth = 0; parent != null && depth < 4 && fed.add(parent); depth++) {
                var parentContent = findConfigResource(jar, parent);
                if (parentContent == null) {
                    LOGGER.warn("[NeoFobric] Mixin config parent {} is referenced but exists in no Fabric jar", parent);
                    return;
                }
                // Pinned like a registered config: Mixin reads the parent while the child is created at
                // this same phase, so an unpinned parent would land in the phase the platform has left.
                parentContent = pinMixinEnvironment(parentContent);
                service.addMixinConfigContent(parent, parentContent);
                parent = parentConfigOf(parentContent);
            }
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not feed a mixin config parent", t);
        }
    }

    /** {@return the "parent" resource a mixin config names, or null} */
    private static String parentConfigOf(byte[] configContent) {
        try {
            var text = new String(configContent, java.nio.charset.StandardCharsets.UTF_8);
            var parent = com.google.gson.JsonParser.parseString(text).getAsJsonObject().get("parent");
            return parent == null || parent.isJsonNull() ? null : parent.getAsString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@return the named config resource} Looked up in the jar that declares the child first - c2me keeps
     * the parent in its base jar while the children live in their own module jars - and then across every
     * Fabric jar, because the two need not share a jar at all.
     */
    private static byte[] findConfigResource(net.neoforged.fml.jarcontents.JarContents primary, String path) {
        try {
            var content = primary.readFile(path);
            if (content != null) {
                return content;
            }
        } catch (Throwable ignored) {
            // Fall through to the scan.
        }
        for (var other : net.neoforged.fabricbridge.discovery.FabricJarRegistry.getEntries().keySet()) {
            try (var jar = net.neoforged.fml.jarcontents.JarContents.ofPath(other)) {
                var content = jar.readFile(path);
                if (content != null) {
                    return content;
                }
            } catch (Throwable ignored) {
                // The next jar may have it.
            }
        }
        return null;
    }

    /**
     * Makes Mixin select and prepare the just-registered configs, and retries until it takes.
     *
     * <p>Why: Mixin reads a config lazily - "Preparing {@code <config>} (N)" only happens when it next
     * selects, and nothing selects before the platform's own mixin phase transitions. A class that is
     * defined before its config was prepared <em>silently</em> keeps no mixins from that config: Mixin never
     * revisits a loaded class and the miss is invisible in every log.
     *
     * <p>That is what cost us Create's {@code ComponentSerializationMixin}. The platform's first game class
     * is {@code SharedConstants}, whose static initialiser pulls in the whole component codec chain;
     * {@code ComponentSerialization} was therefore defined at 20.769 s while {@code create.mixins.json} was
     * only prepared around 21.0 s (measured with {@code -verbose:class} plus Mixin's own log). Create's
     * component type never came into existence, so its sequenced-assembly recipes failed to decode with
     * {@code No matching codec found} and the world would not load - surfacing three layers away as
     * "Failed to load level data or datapacks".
     *
     * <p>Neither a class load nor a phase change can trigger the select here (the platform has already mixed
     * classes, so {@code MixinProcessor.checkSelect} stays quiet), and at {@code link()} time it is not
     * possible either: Mixin rejects every config with "Service initialisation incomplete, launch plugin was
     * not created" until the platform's mixin facade has run. So this is attempted from
     * {@code processClass}, on the first game class the transformation pipeline hands over - which is after
     * that facade, and before the class's own static initialiser drags in everything else.
     *
     * <p>The phase is moved to {@code DEFAULT} first (reflectively - {@code gotoPhase} is package-private,
     * and the platform's own facade calls it the same way) so the current environment is the one every
     * Fabric config is pinned to by {@link #pinMixinEnvironment}. Both the phase change and the select are
     * the same operations the platform performs on its phase transitions, only earlier.
     */
    static void forceMixinPreparationIfPending() {
        if (mixinPrepared || Boolean.getBoolean("neofobric.noForcedMixinPreparation")) {
            return;
        }
        // Mixin reaches for a target class's bytecode *while it prepares a config*, and that request runs
        // the whole transformation pipeline - this method included. Selecting from inside that would nest
        // a second select into the platform's own, which prepares the same configs twice and leaves every
        // mixin of theirs without a pending validation state (Mixin then fails them one by one, on every
        // application, for as many classes as it is asked about).
        if (insideMixin()) {
            return;
        }
        try {
            var environmentClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
            var phaseClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment$Phase");
            var gotoPhase = environmentClass.getDeclaredMethod("gotoPhase", phaseClass);
            gotoPhase.setAccessible(true);
            gotoPhase.invoke(null, phaseClass.getField("DEFAULT").get(null));

            var environment = environmentClass.getMethod("getCurrentEnvironment").invoke(null);
            var processorClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinProcessor");

            var service = MixinService.getService();
            var transformer = service.getClass().getMethod("getMixinTransformer").invoke(service);
            Object processor = null;
            for (Class<?> type = transformer.getClass(); type != null && processor == null; type = type.getSuperclass()) {
                for (var field : type.getDeclaredFields()) {
                    if (processorClass.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        processor = field.get(transformer);
                        break;
                    }
                }
            }
            if (processor == null) {
                throw new IllegalStateException("Mixin's transformer (" + transformer.getClass().getName()
                        + ") carries no MixinProcessor");
            }

            var select = processorClass.getDeclaredMethod("select", environmentClass);
            select.setAccessible(true);
            select.invoke(processor, environment);
            forcedPreparationFailures = 0;
            var pending = Mixins.getConfigs().size();
            if (pending == 0) {
                mixinPrepared = true;
                LOGGER.info("[NeoFobric] Made Mixin prepare the Fabric mixin configs before the game started");
            } else {
                LOGGER.debug("[NeoFobric] Mixin kept {} Fabric mixin config(s) pending, will retry", pending);
            }
        } catch (Throwable t) {
            // Expected on the very first attempts, before the platform's mixin facade has run. A run of
            // failures that does not stop, however, means every Fabric mixin config stays unprepared -
            // and an unprepared config does not apply, silently. That must not stay hidden in DEBUG.
            var failures = ++forcedPreparationFailures;
            if (failures == 64) {
                LOGGER.error("[NeoFobric] Mixin refused to prepare the Fabric configs {} times in a row - "
                        + "Fabric mixins stay unapplied unless this recovers. Report this with the log.", failures, t);
            } else {
                LOGGER.debug("[NeoFobric] Mixin is not ready to prepare the Fabric configs yet, will retry", t);
            }
        }
    }

    /** {@return whether Mixin's own machinery is on this thread's stack} */
    private static boolean insideMixin() {
        for (var frame : new Throwable().getStackTrace()) {
            if (frame.getClassName().startsWith("org.spongepowered.asm.mixin.")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rewrites a Fabric mixin config: folds the dist sections into {@code "mixins"}, and makes the config
     * lenient - {@code "required": false} on the config and {@code "defaultRequire": 0} in every
     * {@code injectors} block.
     *
     * <p>The folding exists because FML's mixin service runs the configs in
     * {@code MixinEnvironment.DEFAULT}: the {@code "client"} and {@code "server"} arrays of a Fabric mixin
     * config are then never applied, on either dist. That silently disabled whole features - fabric-api's
     * gametest module starts its test server from a {@code server} block mixin, so the server looked like a
     * plain server and no test ever ran, and its client-block mixins were attempted on a server instead.
     * The section matching the running dist is therefore moved into {@code "mixins"} and the other one is
     * dropped.
     *
     * <p>NeoForge reshapes some Minecraft internals (event hooks replacing vanilla call sites,
     * refactored renderer interfaces, ...), so not every Fabric mixin finds its injection target
     * in this environment. Strict configs would turn any such mismatch into a hard game crash;
     * lenient ones log a warning and skip just that injection, letting the game run while the
     * affected loader-side integrations are implemented.
     */
    private static byte[] relaxMixinConfig(byte[] content, boolean client, String configName) {
        var folded = foldDistSections(content, client, configName);
        if (folded != null) {
            content = folded;
        }

        content = pinMixinEnvironment(content);

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

    /**
     * Moves the {@code "client"} or {@code "server"} array of a mixin config into {@code "mixins"} -
     * whichever matches the running dist - and removes the other one.
     *
     * @return the rewritten config, or {@code null} when there is nothing to fold
     */
    private static byte[] foldDistSections(byte[] content, boolean client, String configName) {
        com.google.gson.JsonObject root;
        try {
            root = com.google.gson.JsonParser.parseString(new String(content, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Throwable t) {
            // Not JSON, or not an object: leave it to the lenient rewrite below.
            return null;
        }

        var wantedKey = client ? "client" : "server";
        var unwantedKey = client ? "server" : "client";
        if (!root.has(wantedKey) && !root.has(unwantedKey)) {
            return null;
        }

        var mixins = root.getAsJsonArray("mixins");
        if (mixins == null) {
            mixins = new com.google.gson.JsonArray();
            root.add("mixins", mixins);
        }
        var wanted = root.getAsJsonArray(wantedKey);
        var moved = 0;
        if (wanted != null) {
            for (var entry : wanted) {
                var name = entry.getAsString();
                var present = false;
                for (var existing : mixins) {
                    if (existing.isJsonPrimitive() && name.equals(existing.getAsString())) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    mixins.add(name);
                    moved++;
                }
            }
        }
        root.remove(wantedKey);
        root.remove(unwantedKey);

        LOGGER.info("[NeoFobric] Folded the {} section of {} into \"mixins\" ({} entries); FML's mixin service"
                + " runs configs in the DEFAULT environment, where dist sections never apply", wantedKey, configName, moved);
        return new com.google.gson.Gson().toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
     * {@return the {@code Thread.currentThread()} call the client hand-off goes in front of}
     *
     * <p>Same instruction fabric-loader's {@code EntrypointPatch} picks on 1.19.4+: the last
     * {@code currentThread()} call before the "Backend library" log line in the constructor. Fabric API's
     * registry sync hangs its deferred registry freeze on that very call through its own mixin, which is
     * why the bridge's entrypoints have to be in front of it (see the anchor comment in
     * {@link #injectClientGameInstanceHandoff}). Callers insert <em>before</em> the returned instruction -
     * Mixin's injection at the same call would otherwise end up ahead of them.
     */
    private static AbstractInsnNode findCurrentThreadCall(MethodNode method) {
        AbstractInsnNode result = null;
        for (var insn : method.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value
                    && (value.startsWith("Backend library") || value.startsWith("LWJGL Version"))) {
                break;
            }
            if (insn instanceof MethodInsnNode methodInsn && methodInsn.getOpcode() == Opcodes.INVOKESTATIC
                    && methodInsn.owner.equals("java/lang/Thread") && methodInsn.name.equals("currentThread")
                    && methodInsn.desc.equals("()Ljava/lang/Thread;")) {
                result = methodInsn;
            }
        }
        return result;
    }

    /**
     * {@return the instruction that constructs the options object in the Minecraft constructor} Fabric's
     * key mapping API refuses to register a key binding once the options are initialised, and the object
     * is fully built before it is stored in its field - so the hook has to sit in front of the constructor
     * call itself, not in front of the assignment.
     */
    private static AbstractInsnNode findBeforeOptionsConstruction(MethodNode method) {
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode methodInsn && methodInsn.getOpcode() == Opcodes.INVOKESPECIAL
                    && methodInsn.name.equals("<init>")
                    && methodInsn.owner.equals("net/minecraft/client/Options")) {
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

        // Anchor inside the constructor, directly in front of `this.gameThread = Thread.currentThread()`.
        //
        // That is the point fabric-loader itself starts the client entrypoints from (its EntrypointPatch
        // moves the hook "just before the Thread.currentThread() call" on 1.19.4+), and the spot carries
        // two independent constraints:
        //
        // 1. The registry freeze. Fabric API's registry sync performs its deferred registry freeze from a
        //    mixin on that same instruction, and entrypoints do register content (Fabric's particle test
        //    mod registers a particle type). A mixin on that instruction lands ahead of code inserted here
        //    by this transformer, so anchoring anywhere later loses that race and the registering mod is
        //    handed "Registry is already frozen"; the registry window NeoForge keeps open for these
        //    entrypoints (see FabricBridgeHooks#deferFabricWindow) is closed behind them.
        // 2. The state the entrypoints read. `Minecraft.getInstance()` has to work (the instance is
        //    assigned well before this point) - both entrypoint sets run from here, the way fabric-loader
        //    runs them, and a Fabric main entrypoint may read the client too (voxy-extra reads it in its
        //    class initialiser) - the options must not exist yet (Fabric's key mapping API
        //    refuses to register a key binding once they are initialised), and Fabric mod mixins that read
        //    client state during this constructor - Sodium's Minecraft#setFullscreen hook reads its config
        //    while the window is created - all run after it. A null window is tolerated (Sodium's config
        //    builder stores it without touching it), a null instance is not.
        //
        // The later anchors below are fallbacks for a Minecraft whose constructor does not contain that
        // call in a recognisable place any more.
        var insertionPoint = findCurrentThreadCall(target);
        var anchor = "before-current-thread";
        // Mixin injects Fabric API's registry-sync freeze callback at that same call and its insertion
        // ends up ahead of anything placed directly after it, so this one goes in front of the call.
        var insertBefore = insertionPoint != null;

        if (insertionPoint == null) {
            insertBefore = false;
            insertionPoint = findBeforeOptionsConstruction(target);
            anchor = "before-options";
        }
        if (insertionPoint == null) {
            insertionPoint = findFieldAssignment(target, "options");
            anchor = "options-field";
        }
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
            // No anchor means no attachGameInstance hand-off: the Fabric entrypoints never run and the
            // registry window never freezes - the game would boot with Fabric content missing and open
            // registries. Upstream drift (a reshaped constructor) must not degrade into a single warning.
            LOGGER.error("[NeoFobric] Could not find an injection point in the Minecraft constructor - "
                    + "Fabric entrypoints will not run and the registries will not freeze. This is a bridge bug; report it with the log.");
            return false;
        }
        LOGGER.info("[NeoFobric] Client hand-off anchored at {} in the Minecraft constructor", anchor);

        var instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS_CLASS, "attachGameInstance", "(Ljava/lang/Object;)V", false));
        if (insertBefore) {
            target.instructions.insertBefore(insertionPoint, instructions);
        } else {
            target.instructions.insert(insertionPoint, instructions);
        }

        // The Fabric entrypoints are invoked from attachGameInstance, i.e. here, while the
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
