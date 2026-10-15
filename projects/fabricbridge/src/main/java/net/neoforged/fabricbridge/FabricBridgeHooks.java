/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.minecraft.Hooks;

/**
 * The hand-off points between NeoForge/Minecraft and the fabric-loader runtime.
 *
 * <p>{@link #runFabricEntrypoints()} is called by NeoForge's mod loader
 * ({@code CommonModLoader#begin}) inside the registry-initialization window, where the registries
 * are open, so Fabric mods can register content exactly like they do in a pure Fabric
 * environment. On a client run the whole Fabric window - the entrypoints, the attribute pass
 * behind them and the registry freeze - moves to {@link #attachGameInstance(Object)} instead:
 * Fabric entrypoints may use the client, and Fabric itself runs them from inside the Minecraft
 * constructor. NeoForge hands that window over with {@link #deferFabricWindow(Runnable, Runnable)}.
 * The remaining methods are invoked from small runtime patches the bridge applies to Minecraft
 * classes (game instance hand-off and server branding).
 */
public final class FabricBridgeHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricBridgeHooks.class);

    /** The Fabric window NeoForge handed over, held until the game instance exists. */
    private static volatile DeferredFabricWindow deferredFabricWindow;

    /**
     * The callbacks of a Fabric window handed over by NeoForge: the attribute pass that runs directly
     * behind the Fabric entrypoints, and the registry freeze the entrypoints need kept back.
     */
    private record DeferredFabricWindow(Runnable rebaseAttributes, Runnable freeze) {}

    /**
     * The fluid attributes Fabric's fluid-behaviour registry declares, keyed by the fluid itself. Built on
     * first use; {@link #fabricFluidBehavior(Object)}.
     */
    private static volatile java.util.Map<Object, FabricFluidBehavior> fabricFluidBehaviors;

    private FabricBridgeHooks() {}

    /**
     * The block-removal step the game-mode class would take, carried to the vanilla-shaped call it made.
     *
     * <p>NeoForge's break path removes the block through a private helper of its own, which took the call the
     * vanilla API made - and the comment right beside that code records why it matters: a Fabric mixin injects
     * at that call site, and once it moves the injection fails silently. So the call goes back where vanilla had
     * it, and this carries the platform's own removal to it: {@code Level#removeBlock} runs what the site pushed
     * when the site is the caller, and keeps its ordinary behaviour for every other caller. The value is a
     * {@link java.util.function.BooleanSupplier} because the platform's removal needs the block state, the tool
     * and the drop decision, none of which fit the vanilla two-argument shape.
     */
    private static final ThreadLocal<java.util.function.BooleanSupplier> destroyAction = new ThreadLocal<>();

    public static void pushDestroyAction(java.util.function.BooleanSupplier action) {
        destroyAction.set(action);
    }

    public static void popDestroyAction() {
        destroyAction.remove();
    }

    /** {@return whether the platform's own block removal is waiting behind the vanilla-shaped call} */
    public static boolean hasDestroyAction() {
        return destroyAction.get() != null;
    }

    /**
     * {@return what the platform's block removal answered}
     *
     * <p>Cleared before the action runs, because the platform's removal reaches back into {@code Level#removeBlock}
     * through its own extension path - a beehive's drops do. With the action still waiting there, that inner call
     * would run the whole removal again, and again: the Fabric test suite caught it as a stack overflow
     * ({@code fake_player_tests_test_fake_player_break_beehive}). Cleared, the inner call takes the plain path,
     * which is exactly what it would take without this bridge.
     */
    public static boolean runDestroyAction() {
        var action = destroyAction.get();
        destroyAction.remove();
        return action != null && action.getAsBoolean();
    }

    /**
     * {@return whether the bridge has discovered Fabric mods and should inject itself into the game}
     */
    public static boolean bridgeActive() {
        return FabricBridge.hasFabricMods();
    }

    /**
     * Boots the fabric-loader runtime (if needed) and invokes the Fabric entrypoints
     * ({@code preLaunch}, {@code main} and the side entrypoint), followed by the hooks that belong
     * directly behind them.
     *
     * <p>On datagen and the dedicated server this runs while the registries are unfrozen, right after
     * NeoForge's own registration events. On a client run it runs from {@link #attachGameInstance(Object)}
     * instead - the window {@link #deferFabricWindow(Runnable, Runnable)} takes over - because a Fabric
     * main entrypoint may use the client exactly like a client one does: a Fabric mod reads
     * {@code Minecraft.getInstance()} in its class initialiser, and Fabric itself invokes both
     * entrypoint sets from inside the Minecraft constructor.
     */
    public static void runFabricEntrypoints() {
        if (!bridgeActive()) {
            return;
        }

        FabricBridge.bootIfNeeded();
        FabricBridge.invokeEntrypoints();
        // Name-specific compat links live in the compbridlnk package (see its package docs): handing a Fabric
        // mod's Sodium settings page to the NeoForge build of Sodium is the first of them, and each link is a
        // no-op whenever the mod it links to is absent.
        net.neoforged.fabricbridge.compbridlnk.SodiumConfigApiLink.linkSodiumConfigEntryPoints();
        net.neoforged.fabricbridge.compbridlnk.ModMenuConfigLink.linkModMenuConfigScreens();
        runHooksTheGameReachesTooEarly();
        traceFabricPaths();
    }

    private static void traceFabricPaths() {
        if (!Boolean.getBoolean("neofobric.traceTags")) {
            return;
        }

        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader", false, modsLoader);
            var getInstance = fabricLoader.getMethod("getInstance").invoke(null);
            var optionalContainer = fabricLoader.getMethod("getModContainer", String.class).invoke(getInstance, "minecraft");
            var modContainer = optionalContainer.getClass().getMethod("orElseThrow").invoke(optionalContainer);
            var getRootPaths = Class.forName("net.fabricmc.loader.api.ModContainer", false, modsLoader).getMethod("getRootPaths");
            var findPath = Class.forName("net.fabricmc.loader.api.ModContainer", false, modsLoader).getMethod("findPath", String.class);
            LOGGER.info("[NeoFobric] minecraft mod content roots: {}", getRootPaths.invoke(modContainer));
            LOGGER.info("[NeoFobric] minecraft mod content roots (walked now): {}", BridgedGameProvider.minecraftPaths());
            for (var file : new String[] {
                    "data/minecraft/tags/block/diamond_ores.json",
                    "data/c/tags/block/ores.json",
                    "data/c/tags/block/ores/diamond.json" }) {
                LOGGER.info("[NeoFobric] findPath({}) = {}", file, findPath.invoke(modContainer, file));
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not trace the minecraft mod's paths", e);
        }
    }

    /**
     * Re-runs the Fabric API hooks the game reaches before the Fabric entrypoints have registered their
     * listeners.
     *
     * <p>Fabric API hangs some of its bulk registration work off moments FML gets to while it is still loading
     * mods. fabric-object-builder-api-v1 emits its {@code ModifyDefaultAttribute} event from the head of
     * {@code BuiltInRegistries.freeze()}, and the vanilla bootstrap - which FML runs before the mod
     * initialisers - reaches that freeze long before these entrypoints, so the listener list is still empty at
     * that moment and every modification a Fabric mod registers is skipped ("Pig does not have test
     * attribute"). Replaying the hook now that the listeners exist applies them.
     *
     * <p>Must run after the entrypoints and before NeoForge's attribute pass
     * ({@code CommonHooks.modifyAttributes}), which copies the vanilla map - the one Fabric writes into - over.
     * Repeating it is harmless: the event rebuilds a supplier from the one already registered.
     */
    private static void runHooksTheGameReachesTooEarly() {
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var registry = Class.forName("net.fabricmc.fabric.impl.object.builder.FabricDefaultAttributeRegistryImpl", false, modsLoader);
            registry.getMethod("invokeModify").invoke(null);
        } catch (ClassNotFoundException e) {
            // fabric-object-builder-api-v1 is not in this mod set
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not replay Fabric API's default-attribute modification", e);
        }
    }

    /**
     * Takes the Fabric window over for a client run: the entrypoints, the attribute pass that belongs
     * directly behind them and the registry freeze they need kept back.
     *
     * <p>Fabric mods register content from both entrypoint sets (particle types are the classic case
     * for the client ones), but NeoForge closes the registries while the game is still loading, long
     * before any entrypoints that may use the client can run. Fabric's own registry sync arrives at
     * the same place from the other side: it moves the registry freeze to after the mod initialisers.
     * The bridge therefore takes the window here, has both entrypoint sets run from
     * {@link #attachGameInstance(Object)} ({@link #runFabricEntrypoints()} first, matching Fabric's
     * main-then-client order), and closes it once they are through.
     *
     * <p>The actions are NeoForge's own {@code GameData.freezeData} and
     * {@code CommonHooks.rebaseAttributesAfterFabricEntrypoints}, passed as callbacks to keep this
     * module free of compile-time neoforge dependencies; being lambdas of NeoForge's class loader,
     * they also act on the registries the game actually uses, whatever class loader the bridge
     * itself was defined in.
     *
     * @param freezeAction what NeoForge would have run at the end of its registry window
     * @param rebaseAttributesAction the attribute pass NeoForge runs directly behind the Fabric
     *        entrypoints, so the fabric-written vanilla attribute map is copied over
     * @return whether the window was taken over; {@code false} means the caller keeps it and runs
     *         everything as it always did
     */
    public static boolean deferFabricWindow(Runnable freezeAction, Runnable rebaseAttributesAction) {
        if (!bridgeActive() || Boolean.getBoolean("neofobric.noClientEntrypoints")) {
            return false;
        }

        deferredFabricWindow = new DeferredFabricWindow(rebaseAttributesAction, freezeAction);
        LOGGER.info("[NeoFobric] The Fabric entrypoints and the registries move to the client window");
        return true;
    }

    /**
     * Closes the window {@link #deferFabricWindow(Runnable, Runnable)} took over: drops the intrusive
     * holders a Fabric mod built but never registered, then runs the freeze it held back. Called once
     * both entrypoint sets are through.
     */
    private static void closeDeferredFabricWindow(DeferredFabricWindow fabricWindow) {
        dropUnregisteredIntrusiveHolders();
        fabricWindow.freeze().run();
        LOGGER.info("[NeoFobric] Registries frozen after the Fabric entrypoints");
    }

    /**
     * Drops the intrusive holders a Fabric mod built but never registered, before the registry window
     * closes.
     *
     * <p>Minecraft's registries with intrusive-holder support ({@code BuiltInRegistries.BLOCK},
     * {@code ITEM}, {@code BLOCK_ENTITY_TYPE}, {@code ENTITY_TYPE}, {@code FLUID}) remember every value
     * whose constructor asked for a built-in holder, and {@code MappedRegistry#freeze} refuses to close
     * a registry that still has one - the safety net for a NeoForge mod that forgot to register a value.
     *
     * <p>A Fabric entrypoint that fails halfway (the bridge isolates such failures on purpose, see
     * {@code FabricBridge.invokeEntrypoints}) can leave holders behind exactly like that, and then the
     * safety net turns an already-reported entrypoint error into a fatal crash for the whole game.
     * Twilight Forest does it: its client entrypoint dies on a payload it cannot register yet, and the
     * block entity types built before that never reach the registry. Dropping the leftovers keeps the
     * consequence where the bridge already put it - one skipped entrypoint - instead of killing the
     * game at startup.
     */
    private static void dropUnregisteredIntrusiveHolders() {
        try {
            // Minecraft lives in the game layer, not in this class's own loader.
            var gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, gameLoader)
                    .getField("REGISTRY")
                    .get(null);
            var registryClass = Class.forName("net.minecraft.core.Registry", false, gameLoader);
            var dropped = new java.util.ArrayList<String>();
            for (var registry : (Iterable<?>) (registries instanceof Iterable<?> iterable ? iterable : java.util.List.of())) {
                if (!registryClass.isInstance(registry)) {
                    continue;
                }
                var field = findField(registry.getClass(), "unregisteredIntrusiveHolders");
                if (field == null) {
                    continue;
                }
                field.setAccessible(true);
                if (!(field.get(registry) instanceof java.util.Map<?, ?> holders) || holders.isEmpty()) {
                    continue;
                }
                // Only the values are needed for the report; the keys are the holders themselves.
                for (var value : holders.values()) {
                    dropped.add(String.valueOf(value));
                }
                ((java.util.Map<?, ?>) holders).clear();
            }
            if (!dropped.isEmpty()) {
                LOGGER.warn("[NeoFobric] Dropped {} unregistered intrusive holder(s) left behind by a Fabric"
                        + " entrypoint - those values are not in their registry: {}", dropped.size(), dropped);
            }
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not check the intrusive holders before freezing", t);
        }
    }

    /** {@return the named field on {@code type} or any of its superclasses, or null} */
    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        for (var current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up the hierarchy
            }
        }
        return null;
    }

    /**
     * Registers a blockstate model type with Fabric API's own registry, on behalf of the platform.
     *
     * <p>Fabric's blockstate model types live in its {@code CustomUnbakedBlockStateModel} registry, and mods
     * reach it through that interface's static {@code register}. The platform's own dispatch is fed from the
     * same call (the bridge points it here, see {@code MixinJarPatcher}); keeping Fabric's registry up to
     * date as well means Fabric's own lookups - its composite models and its plugins - still see the type.
     *
     * <p>Reflection because this module cannot compile against Fabric API: the arguments are handed through
     * as objects.
     */
    public static void registerFabricBlockStateModelType(Object id, Object codec) {
        try {
            var gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var api = Class.forName("net.fabricmc.fabric.api.client.model.loading.v1.CustomUnbakedBlockStateModel", false, gameLoader);
            var register = api.getMethod("register",
                    Class.forName("net.minecraft.resources.Identifier", false, gameLoader),
                    Class.forName("com.mojang.serialization.MapCodec", false, gameLoader));
            register.invoke(null, id, codec);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not register the blockstate model type {} with Fabric API", id, t);
        }
    }

    /**
     * Fires Fabric's item-use-on-block event and hands back whatever a listener asked for, or null when
     * nobody overrode the item's own behaviour.
     *
     * <p>Fabric fires this event by wrapping the {@code Item#useOn} call that sits inside
     * {@code ItemStack#useOn}. NeoForge spread that one call site over two places - a lambda in
     * {@code ItemStack#useOn} for the logical client and {@code CommonHooks#onPlaceItemIntoWorld} for the
     * logical server - so Fabric's wrap cannot find an anchor anywhere (and, failing, took the rest of its
     * mixin with it). The platform therefore asks Fabric here, at both call sites, before running the item;
     * the bridge removes Fabric's own wrap (see {@code MixinJarPatcher}).
     *
     * <p>Reflection because this module cannot compile against Fabric API: the context and the result are
     * handed through as objects. Fabric's content layer is an automatic module, so reaching its
     * package-private callback interface is allowed.
     *
     * @return the {@code InteractionResult} a listener returned, or null when none did
     */
    public static Object fireFabricItemUseOn(Object useOnContext) {
        if (!bridgeActive()) {
            return null;
        }
        try {
            var gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var events = Class.forName("net.fabricmc.fabric.api.event.player.ItemEvents", false, gameLoader);
            var event = Class.forName("net.fabricmc.fabric.api.event.Event", false, gameLoader);
            var callback = Class.forName("net.fabricmc.fabric.api.event.player.ItemEvents$UseOnCallback", false, gameLoader);
            var useOn = callback.getMethod("useOn", Class.forName("net.minecraft.world.item.context.UseOnContext", false, gameLoader));
            useOn.setAccessible(true);
            var invoker = event.getMethod("invoker").invoke(events.getField("USE_ON").get(null));
            return useOn.invoke(invoker, useOnContext);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not fire Fabric's item use-on event", t);
            return null;
        }
    }

    /**
     * Offers a pick-block result to Fabric's {@code PlayerPickItemEvents.BLOCK} and returns what should
     * actually be picked. Called from {@code ServerGamePacketListenerImpl#handlePickItemFromBlock}, where the
     * platform computes the vanilla stack and where Fabric's own injection cannot reach any more: NeoForge
     * calls {@code BlockState#getCloneItemStack(BlockPos, LevelReader, boolean, Player)} rather than the
     * three-argument form Fabric's handler wraps.
     *
     * <p>The contract is Fabric's own: a listener returning {@code null} or an empty stack means "keep the
     * vanilla result", anything else replaces it (that is exactly what the injection this replaces did).
     *
     * @param includeDataForEvent the flag the packet carried, which is what Fabric passes on - the platform's
     *                            own copy has already been narrowed by the player's infinite-materials mode
     * @param vanilla             the stack the platform would have picked
     */
    public static Object fireFabricPickItemFromBlock(Object player, Object pos, Object state, boolean includeDataForEvent, Object vanilla) {
        if (!bridgeActive()) {
            return vanilla;
        }
        try {
            var gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var events = Class.forName("net.fabricmc.fabric.api.event.player.PlayerPickItemEvents", false, gameLoader);
            var event = Class.forName("net.fabricmc.fabric.api.event.Event", false, gameLoader);
            var callback = Class.forName("net.fabricmc.fabric.api.event.player.PlayerPickItemEvents$PickItemFromBlock", false, gameLoader);
            var pick = callback.getMethod("onPickItemFromBlock",
                    Class.forName("net.minecraft.server.level.ServerPlayer", false, gameLoader),
                    Class.forName("net.minecraft.core.BlockPos", false, gameLoader),
                    Class.forName("net.minecraft.world.level.block.state.BlockState", false, gameLoader),
                    boolean.class);
            pick.setAccessible(true);
            var invoker = event.getMethod("invoker").invoke(events.getField("BLOCK").get(null));
            var result = pick.invoke(invoker, player, pos, state, includeDataForEvent);
            if (result == null) {
                return vanilla;
            }
            // This class compiles without Minecraft on its own classpath, so "empty means keep vanilla" is
            // asked of the stack itself rather than spelled as an ItemStack call.
            var stackClass = Class.forName("net.minecraft.world.item.ItemStack", false, gameLoader);
            if (Boolean.TRUE.equals(stackClass.getMethod("isEmpty").invoke(result))) {
                return vanilla;
            }
            return result;
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not fire Fabric's pick-block event", t);
            return vanilla;
        }
    }

    /**
     * Asks a model for a material flag the way the renderer API does, and hands back the platform's own answer
     * for every model that does not implement the Fabric interface.
     *
     * <p>The renderer API injects {@code FabricBlockStateModel} as a super-interface of
     * {@code BlockStateModel} and declares a five-argument {@code hasMaterialFlag(level, pos, state, random, flag)}
     * there, beside a {@code materialFlags(...)}. The platform's extraction and moving-block paths call their
     * own four-argument form instead, so a Fabric model's override would never be consulted on those two paths;
     * they ask here now. A model that does not override the five-argument method keeps the platform's answer,
     * which is what the interface's default implementation would have produced anyway.
     *
     * @param vanilla the flag the platform's own lookup returned
     */
    public static boolean fabricMaterialFlag(Object model, int flag, Object level, Object pos, Object state, Object random, boolean vanilla) {
        if (!bridgeActive() || model == null) {
            return vanilla;
        }
        try {
            var gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var modelInterface = Class.forName("net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel", false, gameLoader);
            if (!modelInterface.isInstance(model)) {
                return vanilla;
            }
            var method = model.getClass().getMethod("hasMaterialFlag",
                    Class.forName("net.minecraft.client.renderer.block.BlockAndTintGetter", false, gameLoader),
                    Class.forName("net.minecraft.core.BlockPos", false, gameLoader),
                    Class.forName("net.minecraft.world.level.block.state.BlockState", false, gameLoader),
                    Class.forName("net.minecraft.util.RandomSource", false, gameLoader),
                    int.class);
            if (method.getDeclaringClass() == modelInterface) {
                // Not overridden: the interface's default is the platform's own answer.
                return vanilla;
            }
            return (boolean) method.invoke(model, level, pos, state, random, flag);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not ask {} for its material flag", model, t);
            return vanilla;
        }
    }

    /**
     * Picks the GUI texture for a sign edit screen the way Fabric does, and returns it for the screen to use.
     *
     * <p>A wood type whose name carries a namespace is one a mod registered, and Fabric reads that sign's
     * texture from the mod's own namespace ({@code mymod:textures/gui/signs/custom.png}) rather than from
     * {@code minecraft:}. The platform builds the identifier from {@code Identifier.parse} plus
     * {@code withPrefix} now, so the injection Fabric used could not match any more; the screens ask here
     * instead, and the vanilla identifier they computed themselves is what comes back for every vanilla wood
     * type - this only ever replaces it.
     *
     * @param prefix  the folder the screen uses, e.g. {@code textures/gui/signs/}
     * @param vanilla the identifier the platform would have used
     */
    public static Object fabricSignScreenTexture(Object woodType, String prefix, Object vanilla) {
        if (!bridgeActive() || woodType == null) {
            return vanilla;
        }
        try {
            var name = (String) woodType.getClass().getMethod("name").invoke(woodType);
            var colon = name == null ? -1 : name.indexOf(':');
            if (colon < 0) {
                return vanilla;
            }
            var gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var identifier = Class.forName("net.minecraft.resources.Identifier", false, gameLoader);
            var path = name.substring(colon + 1);
            if (path.endsWith(".png")) {
                path = path.substring(0, path.length() - ".png".length());
            }
            return identifier.getMethod("parse", String.class).invoke(null,
                    name.substring(0, colon) + ":" + prefix + path + ".png");
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not build a namespaced sign texture for {}", woodType, t);
            return vanilla;
        }
    }

    /** {@code SmartBlockEntity}'s lazy-init flag plus {@code initialize} and {@code hasLevel}, resolved once. */
    private static volatile java.lang.reflect.Field createInitialisedField;
    private static volatile java.lang.reflect.Method createHasLevelMethod;
    /** { initialize} is inherited in different places, so it is cached per concrete class. */
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> createInitialiseMethods = new java.util.concurrent.ConcurrentHashMap<>();

    /** Names already reported, so the diagnostic logging below stays quiet. */
    private static final java.util.Set<String> PROBED = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
    public static void ensureCreateBlockEntityInitialised(Object blockEntity) {
        if (!bridgeActive() || blockEntity == null) {
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
            field.setBoolean(blockEntity, true);
            initialise.invoke(blockEntity);
            if (PROBED.add("early" + simpleName)) {
                LOGGER.debug("[NeoFobric] Initialised a block entity from its behaviour lookup, before its first tick: {}", simpleName);
            }
        } catch (Throwable t) {
            if (PROBED.add("failed" + simpleName)) {
                LOGGER.debug("[NeoFobric] Behaviour-lookup initialisation of {} failed", simpleName, t);
            }
        }
    }

    /**
     * Asks Fabric what sleeping direction a sleeping entity should face, and hands back the answer.
     *
     * <p>Fabric fires this event by wrapping the {@code BedBlock#getBedOrientation} call inside
     * {@code LivingEntity#getBedOrientation}. NeoForge's bed support goes through
     * {@code BlockState#isBed}/{@code BlockState#getBedDirection} instead, so that call - and with it Fabric's
     * wrap - is gone; the platform asks here instead, and the bridge removes the wrap (see
     * {@code MixinJarPatcher}). This is the one sleep event a Fabric bed that is not a {@code BedBlock}
     * cannot do without.
     *
     * <p>Reflection because this module cannot compile against Fabric API: the arguments are handed through
     * as objects.
     *
     * @return the direction Fabric decided on, which is the one passed in when nothing modified it
     */
    public static Object fireFabricModifySleepingDirection(Object entity, Object sleepingPos, Object sleepingDirection) {
        if (!bridgeActive()) {
            return sleepingDirection;
        }
        try {
            var gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var events = Class.forName("net.fabricmc.fabric.api.event.entity.v1.EntitySleepEvents", false, gameLoader);
            var event = Class.forName("net.fabricmc.fabric.api.event.Event", false, gameLoader);
            var callback = Class.forName("net.fabricmc.fabric.api.event.entity.v1.EntitySleepEvents$ModifySleepingDirection", false, gameLoader);
            var modify = callback.getMethod("modifySleepDirection",
                    Class.forName("net.minecraft.world.entity.LivingEntity", false, gameLoader),
                    Class.forName("net.minecraft.core.BlockPos", false, gameLoader),
                    Class.forName("net.minecraft.core.Direction", false, gameLoader));
            modify.setAccessible(true);
            var invoker = event.getMethod("invoker").invoke(events.getField("MODIFY_SLEEPING_DIRECTION").get(null));
            return modify.invoke(invoker, entity, sleepingPos, sleepingDirection);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not fire Fabric's sleeping direction event", t);
            return sleepingDirection;
        }
    }

    /**
     * Asks Fabric whether a mod wants to take over writing a bed's occupied state, and reports its answer.
     *
     * <p>Fabric fires this event by redirecting the {@code Level#setBlock} call that used to mark a bed
     * occupied; NeoForge's bed support runs through {@code BlockState#setBedOccupied} instead, so the redirect -
     * and with it the event - is gone. The platform asks here instead, from inside that method, and stops when
     * Fabric says the write is handled (see {@code MixinJarPatcher}, which drops Fabric's redirect).
     *
     * <p>Reflection because this module cannot compile against Fabric API: the arguments are handed through
     * as objects.
     *
     * @return whether a mod handled the write, in which case the platform must not write the state itself
     */
    public static boolean fireFabricSetBedOccupationState(Object sleeper, Object bedPos, Object bedState, boolean occupied) {
        if (!bridgeActive()) {
            return false;
        }
        try {
            var gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var events = Class.forName("net.fabricmc.fabric.api.event.entity.v1.EntitySleepEvents", false, gameLoader);
            var event = Class.forName("net.fabricmc.fabric.api.event.Event", false, gameLoader);
            var callback = Class.forName("net.fabricmc.fabric.api.event.entity.v1.EntitySleepEvents$SetBedOccupationState", false, gameLoader);
            var setOccupied = callback.getMethod("setBedOccupationState",
                    Class.forName("net.minecraft.world.entity.LivingEntity", false, gameLoader),
                    Class.forName("net.minecraft.core.BlockPos", false, gameLoader),
                    Class.forName("net.minecraft.world.level.block.state.BlockState", false, gameLoader),
                    boolean.class);
            setOccupied.setAccessible(true);
            var invoker = event.getMethod("invoker").invoke(events.getField("SET_BED_OCCUPATION_STATE").get(null));
            return Boolean.TRUE.equals(setOccupied.invoke(invoker, sleeper, bedPos, bedState, occupied));
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not fire Fabric's bed occupation event", t);
            return false;
        }
    }

    /**
     * {@return whether this class belongs to a mod that was written for Fabric}
     *
     * <p>Fabric mods live in the game content layer the bridge registers for them
     * ({@link net.neoforged.fabricbridge.discovery.BridgeFabricModLocator#FABRIC_MODULE_NAME}), which is a
     * module of its own inside the game class loader - the one reliable mark that survives a class being
     * handed around as a {@code Class<?>}.
     */
    public static boolean isFabricModClass(Class<?> clazz) {
        var module = clazz.getModule();
        return module != null
                && net.neoforged.fabricbridge.discovery.BridgeFabricModLocator.FABRIC_MODULE_NAME.equals(module.getName());
    }

    /**
     * Names of Fabric's payload type registries, one per protocol and side, inside
     * {@code PayloadTypeRegistryImpl}. A payload type only exists in the registry of the side that
     * registered it, so an absent one (the client registries on a server, say) is simply skipped.
     */
    private static final String[] FABRIC_PAYLOAD_TYPE_REGISTRIES = {
            "SERVERBOUND_CONFIGURATION", "CLIENTBOUND_CONFIGURATION", "SERVERBOUND_PLAY", "CLIENTBOUND_PLAY"
    };

    /** {@code (registry, get(Identifier))} pairs of the registries above, resolved once. */
    private static volatile java.util.List<java.util.function.Predicate<String>> fabricChannelTests;

    /**
     * Fabric's own channel-registration channels. Fabric moves its channel declarations over these two ids,
     * and the class tweaker renames the constants to exactly these values so they no longer collide with
     * NeoForge's {@code minecraft:register}/{@code minecraft:unregister} negotiation (different wire formats
     * under the same ids). Fabric does register payload types for them, so the lookup below would find them
     * anyway - they are listed here as well so the bridge does not depend on that staying true.
     */
    private static final java.util.Set<String> FABRIC_CONTROL_CHANNELS =
            java.util.Set.of("minecraft:fabric_register", "minecraft:fabric_unregister");

    /**
     * {@return whether this channel is one of Fabric's modded payload types}
     *
     * <p>NeoForge refuses to let a mod send a custom payload to a peer that has not negotiated the channel
     * in <em>its</em> registration system, and it dies with
     * {@code "Payload x may not be sent to the client!"}. Fabric has no such membership test: a mod
     * registers its payload type with {@code PayloadTypeRegistry}, then it may send - the peer decodes it as
     * long as it registered the same type, and a receiver is not required for sending at all (Fabric API's
     * context test sends a payload whose handler is registered per connection, which no declaration ever
     * mentions). The set of registered payload types is therefore the right question to ask, and
     * {@code PayloadTypeRegistry} answers it on both sides. Fabric API asserts a payload type before it
     * accepts a receiver for it, so this set covers the receivers too.
     *
     * @param channelName the channel id as {@code namespace:path}
     */
    public static boolean isFabricChannel(String channelName) {
        if (!bridgeActive() || channelName == null) {
            return false;
        }

        if (FABRIC_CONTROL_CHANNELS.contains(channelName)) {
            return true;
        }

        if (channelName.startsWith("minecraft:")) {
            // Vanilla's own channels: Fabric API refuses to let a mod register a payload type in the
            // minecraft namespace, so nothing else can be in here.
            return false;
        }

        for (var isFabricType : fabricChannelTests()) {
            if (isFabricType.test(channelName)) {
                return true;
            }
        }

        // Fabric mods may also send payloads they never announced to fabric-networking at all - polymer
        // moves its handshake as a raw custom payload, exempting its own sends but not the peer's reply -
        // so a channel in the namespace of a loaded Fabric mod counts as a Fabric channel too.
        var colon = channelName.indexOf(':');
        if (colon > 0 && fabricModNamespaces().contains(channelName.substring(0, colon))) {
            return true;
        }
        return false;
    }

    /** {@return the namespaces of the loaded Fabric mods, resolved once} */
    private static java.util.Set<String> fabricModNamespaces() {
        var resolved = fabricNamespaces;
        if (resolved != null) {
            return resolved;
        }

        var namespaces = new java.util.HashSet<String>();
        try {
            for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                var id = mod.getMetadata().getId();

                // NeoFobric: the bridge's own exposure shells (neofobric-platform-<id>.jar, built by
                // BridgeFabricModLocator#exposePlatformMods) are NeoForge mods wearing a fabric.mod.json so
                // that isModLoaded probes answer true. They are not Fabric mods, and counting their
                // namespaces here made isFabricChannel claim every NeoForge mod's own payloads: modernfix's
                // optional load-finished sentinel was routed away from NeoForge's payload handling, its
                // client handler never ran, the integrated server's load suspension was never lifted and
                // all entity ticking stayed frozen for the whole session.
                if (isPlatformExposureShell(mod)) {
                    continue;
                }

                namespaces.add(id);
                // Mod families share a namespace that is the first segment of their ids: polymer-core
                // and polymer-networking send on "polymer", wover-core on "wover", c2me-base on "c2me".
                var dash = id.indexOf('-');
                if (dash > 0) {
                    namespaces.add(id.substring(0, dash));
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not list the Fabric mods for the channel check", t);
        }
        fabricNamespaces = namespaces;
        return namespaces;
    }

    /** {@return whether this mod is one of the bridge's own NeoForge exposure shells} */
    private static boolean isPlatformExposureShell(net.fabricmc.loader.api.ModContainer mod) {
        try {
            for (var path : mod.getOrigin().getPaths()) {
                var name = path.getFileName();
                if (name != null && name.toString().startsWith("neofobric-platform-")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // An origin that cannot list paths is not one of ours.
        }
        return false;
    }

    private static volatile java.util.Set<String> fabricNamespaces;

    private static java.util.List<java.util.function.Predicate<String>> fabricChannelTests() {
        var resolved = fabricChannelTests;
        if (resolved != null) {
            return resolved;
        }

        var tests = new java.util.ArrayList<java.util.function.Predicate<String>>(FABRIC_PAYLOAD_TYPE_REGISTRIES.length);
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var payloadTypeRegistry = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl", false, modsLoader);
            var identifier = Class.forName("net.minecraft.resources.Identifier", false, modsLoader);
            var parseIdentifier = identifier.getMethod("tryParse", String.class);
            var get = payloadTypeRegistry.getMethod("get", identifier);

            for (var fieldName : FABRIC_PAYLOAD_TYPE_REGISTRIES) {
                java.lang.Object registry;
                try {
                    registry = payloadTypeRegistry.getField(fieldName).get(null);
                } catch (NoSuchFieldException e) {
                    continue;
                }
                if (registry == null) {
                    continue;
                }
                tests.add(name -> {
                    try {
                        var id = parseIdentifier.invoke(null, name);
                        return id != null && get.invoke(registry, id) != null;
                    } catch (Throwable t) {
                        return false;
                    }
                });
            }
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not reach Fabric's payload type registries for the channel lookup", t);
        }

        resolved = java.util.List.copyOf(tests);
        fabricChannelTests = resolved;
        return resolved;
    }

    /**
     * Hands the constructed game instance to the fabric-loader runtime and, on the client, runs the
     * Fabric window NeoForge handed over: the main entrypoints, the attribute pass behind them and
     * the client entrypoints, then closes the registries.
     *
     * <p>Both entrypoint sets have to run here rather than in {@link #runFabricEntrypoints()}: the
     * main ones may legitimately use the client (a Fabric mod reads {@code Minecraft.getInstance()}
     * in its class initialiser, Sodium's config builder reads {@code Minecraft.getInstance().getWindow()}),
     * and that point has no game instance yet. They equally cannot wait until after the constructor,
     * because Sodium's {@code Minecraft#setFullscreen} mixin reads the client config <em>during</em>
     * the same constructor - so the entrypoint that registers it must already have run. This
     * insertion point sits after the game instance is assigned and before the window is created,
     * which satisfies all of it; it is also the point fabric-loader itself uses, and it runs the
     * main set first, matching Fabric's order.
     *
     * <p>Because NeoForge's registries stay open until exactly this point on a client run
     * ({@link #deferFabricWindow}), the attribute rebase and the freeze run here too, directly
     * behind the entrypoints.
     */
    public static void attachGameInstance(Object gameInstance) {
        if (!bridgeActive()) {
            return;
        }

        if (!Boolean.getBoolean("neofobric.noPrepareModInit")) {
            try {
                FabricBridge.bootIfNeeded();
                FabricLoaderImpl.INSTANCE.prepareModInit(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get(), gameInstance);
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Failed to attach the game instance to Fabric Loader", t);
            }
        }

        var fabricWindow = deferredFabricWindow;
        if (fabricWindow != null) {
            deferredFabricWindow = null;
            try {
                runFabricEntrypoints();
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Failed to run the Fabric entrypoints", t);
            }
            try {
                fabricWindow.rebaseAttributes().run();
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Failed to rebase the default attributes behind the Fabric entrypoints", t);
            }
        }

        if (!Boolean.getBoolean("neofobric.noClientEntrypoints")) {
            try {
                FabricBridge.invokeClientEntrypoints();
            } catch (Throwable t) {
                LOGGER.error("[NeoFobric] Failed to run the Fabric client entrypoints", t);
            }
        }

        // After the client mods have initialised: Mod Menu fills its mod registry in its own client
        // initialiser, so the update link has to wait for it (unlike the config link, which reads the
        // entrypoint declarations directly and runs with the main set).
        net.neoforged.fabricbridge.compbridlnk.ModMenuUpdateLink.linkModMenuUpdateChecks();

        // The client entrypoints are the last Fabric-side chance to register content, so this is where
        // the registry window that runFabricEntrypoints left open closes again (see deferFabricWindow).
        if (fabricWindow != null) {
            closeDeferredFabricWindow(fabricWindow);
        }
    }

    /**
     * Hands the dedicated server instance to the fabric-loader runtime. Injected into the server
     * main class where the server object is constructed.
     */
    public static void setServerGameInstance(Object gameInstance) {
        if (!bridgeActive()) {
            return;
        }

        try {
            FabricLoaderImpl.INSTANCE.setGameInstance(gameInstance);
        } catch (IllegalStateException e) {
            // Already set; harmless.
            LOGGER.debug("[NeoFobric] Game instance was already set", e);
        } catch (Throwable t) {
            LOGGER.error("[NeoFobric] Failed to set the server game instance on Fabric Loader", t);
        }
    }

    /**
     * Appends {@code fabric} to the game's brand. Injected into
     * {@code MinecraftServer#getServerModName} and {@code ClientBrandRetriever#getClientModName},
     * mirroring fabric-loader's {@code BrandingPatch}.
     */
    public static String insertBranding(String brand) {
        if (!bridgeActive()) {
            return brand;
        }
        return Hooks.insertBranding(brand);
    }

    /** Guards the fallback path so the redirect only consults Fabric once per lookup. */
    private static final ThreadLocal<Boolean> IN_CODEC_LOOKUP = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * {@return Fabric's codec for this payload id, or NeoForge's own lookup when Fabric has none}
     *
     * <p>Fabric and NeoForge both claim {@code minecraft:register}, but with different payload classes -
     * Fabric's {@code RegistrationPayload} versus NeoForge's {@code MinecraftRegisterPayload}. Without
     * this, NeoForge's encoder resolves the id to its own class and the cast fails with
     * {@code ClassCastException}, killing the connection in the configuration phase so that no world can
     * be entered, singleplayer included.
     *
     * <p>The fallback re-enters {@code NetworkRegistry#getCodec}, which has been redirected here; the
     * flag makes that second entry run NeoForge's original logic. Both sides are reached reflectively so
     * this class keeps no compile-time dependency on either, and any failure just falls back to NeoForge.
     */
    public static Object fabricPayloadCodec(Object id, Object protocol, Object flow) {
        if (bridgeActive() && !IN_CODEC_LOOKUP.get()) {
            IN_CODEC_LOOKUP.set(Boolean.TRUE);
            try {
                var fromFabric = lookupFabricCodec(id, protocol, flow);
                if (fromFabric != null) {
                    return fromFabric;
                }
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Fabric payload codec lookup failed for {}", id, t);
            } finally {
                IN_CODEC_LOOKUP.set(Boolean.FALSE);
            }
            // Not a Fabric payload: hand the lookup back to NeoForge, which now runs unredirected.
            return lookupNeoForgeCodec(id, protocol, flow);
        }
        if (bridgeActive() && IN_CODEC_LOOKUP.get()) {
            return lookupNeoForgeCodec(id, protocol, flow);
        }
        return null;
    }

    private static Object lookupNeoForgeCodec(Object id, Object protocol, Object flow) {
        try {
            return Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry")
                    .getMethod("getCodec",
                            Class.forName("net.minecraft.resources.Identifier"),
                            Class.forName("net.minecraft.network.ConnectionProtocol"),
                            Class.forName("net.minecraft.network.protocol.PacketFlow"))
                    .invoke(null, id, protocol, flow);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object lookupFabricCodec(Object id, Object protocol, Object flow) throws Exception {
        var registries = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl");
        var protocolName = String.valueOf(protocol);
        var flowName = String.valueOf(flow);

        String field;
        if (protocolName.endsWith("CONFIGURATION")) {
            field = flowName.endsWith("SERVERBOUND") ? "SERVERBOUND_CONFIGURATION" : "CLIENTBOUND_CONFIGURATION";
        } else if (protocolName.endsWith("PLAY")) {
            field = flowName.endsWith("SERVERBOUND") ? "SERVERBOUND_PLAY" : "CLIENTBOUND_PLAY";
        } else {
            return null;
        }

        var registry = registries.getField(field).get(null);
        var typeAndCodec = registry.getClass()
                .getMethod("get", Class.forName("net.minecraft.resources.Identifier"))
                .invoke(registry, id);
        if (typeAndCodec == null) {
            return null;
        }
        var codec = typeAndCodec.getClass().getMethod("codec").invoke(typeAndCodec);
        // Widen the payload type: it was registered as StreamCodec<B, T>, while NeoForge asks for
        // StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload>.
        return codec.getClass()
                .getMethod("map", java.util.function.Function.class, java.util.function.Function.class)
                .invoke(codec, java.util.function.Function.identity(), java.util.function.Function.identity());
    }

    /**
     * What Fabric's fluid-behaviour registry says about one fluid.
     *
     * <p>Fabric declares a fluid's physics as a {@code FluidBehavior} registered against a fluid tag, and keeps
     * it to itself: nothing in Minecraft reads it. NeoForge reads the same physics off a {@code FluidType}, so
     * a fluid a Fabric mod registered is described here for NeoForge to build that type from.
     *
     * @param behavior             Fabric's own behaviour object, handed back to {@link #moveInFabricFluid} when
     *                             the physics it describes has to run
     * @param tag                  the fluid tag that behaviour was registered against, likewise handed back
     * @param waterLike            whether the fluid was registered as water-like, in which case NeoForge's own
     *                             water type describes it exactly and is reused
     * @param canSwim              whether entities can swim in it
     * @param canDrown             whether entities drown in it
     * @param canPushEntity        whether its current pushes entities along
     * @param supportsBoating      whether boats float on it
     * @param motionScale          how strongly that current pulls, as Fabric spells it
     * @param fallDistanceModifier what it multiplies an entity's fall distance by
     */
    public record FabricFluidBehavior(Object behavior, Object tag, boolean waterLike, boolean canSwim, boolean canDrown,
            boolean canPushEntity, boolean supportsBoating, double motionScale, float fallDistanceModifier) {}

    /**
     * {@return what Fabric's fluid-behaviour registry declares for this fluid, or {@code null} when it
     * declares nothing about it}
     *
     * <p>The lookup is built once from the registry's fluid tags, because Fabric keys the declaration by tag
     * while NeoForge keys everything by fluid: each tracked tag's behavior is copied onto every fluid the tag
     * contains. Fluids whose behavior is a plain {@code FluidBehavior} implementation - anything not built
     * through Fabric's own builder - cannot be described in these terms and are reported as unknown, which
     * leaves them on NeoForge's empty type as before.
     */
    public static FabricFluidBehavior fabricFluidBehavior(Object fluid) {
        var resolved = fabricFluidBehaviors;
        if (resolved == null) {
            resolved = collectFabricFluidBehaviors();
            // An empty result is kept out of the cache: fluid tags are bound by the first data-pack load, and a
            // lookup that ran before that (or while the registry was being filled) must not be remembered.
            if (!resolved.isEmpty()) {
                fabricFluidBehaviors = resolved;
            }
        }
        return resolved.get(fluid);
    }

    private static java.util.Map<Object, FabricFluidBehavior> collectFabricFluidBehaviors() {
        var byFluid = new java.util.IdentityHashMap<Object, FabricFluidBehavior>();
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var registry = Class.forName("net.fabricmc.fabric.api.registry.fluid.EntityFluidInteractionRegistry", false, modsLoader);
            var tagKey = Class.forName("net.minecraft.tags.TagKey", false, modsLoader);
            var getFluidBehavior = registry.getMethod("getFluidBehavior", tagKey);
            var waterLike = Class.forName("net.fabricmc.fabric.api.registry.fluid.FluidBehavior", false, modsLoader)
                    .getField("WATER_LIKE").get(null);
            var fluidRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, modsLoader)
                    .getField("FLUID").get(null);
            var getTagOrEmpty = fluidRegistry.getClass().getMethod("getTagOrEmpty", tagKey);
            var holderValue = Class.forName("net.minecraft.core.Holder", false, modsLoader).getMethod("value");

            for (var tag : (Iterable<?>) registry.getMethod("getCustomInteractableFluids").invoke(null)) {
                var behavior = getFluidBehavior.invoke(null, tag);
                if (behavior == null) {
                    continue;
                }
                var described = describeFabricFluidBehavior(behavior, tag, behavior == waterLike);
                if (described == null) {
                    continue;
                }

                for (var holder : (Iterable<?>) getTagOrEmpty.invoke(fluidRegistry, tag)) {
                    byFluid.put(holderValue.invoke(holder), described);
                }
            }
        } catch (ClassNotFoundException e) {
            // fabric-content-registries-v0 is not in this mod set
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not read Fabric's fluid behaviour registry", e);
        }
        return byFluid;
    }

    private static FabricFluidBehavior describeFabricFluidBehavior(Object behavior, Object tag, boolean waterLike) {
        var type = behavior.getClass();
        try {
            return new FabricFluidBehavior(
                    behavior,
                    tag,
                    waterLike,
                    (boolean) type.getMethod("allowSwimming").invoke(behavior),
                    (boolean) type.getMethod("drowning").invoke(behavior),
                    (double) type.getMethod("flowingPushScale").invoke(behavior) != 0,
                    (boolean) type.getMethod("allowBoats").invoke(behavior),
                    (double) type.getMethod("flowingPushScale").invoke(behavior),
                    (float) type.getMethod("fallDistanceMultiplier").invoke(behavior));
        } catch (NoSuchMethodException e) {
            // Not one of Fabric's own behaviours (its builder's record); nothing to translate.
            return null;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not read a Fabric fluid behaviour", e);
            return null;
        }
    }

    /**
     * Runs the movement Fabric declared for one of its fluids.
     *
     * <p>NeoForge hands movement in a modded fluid to the fluid type ({@code FluidType#move}), and everything a
     * Fabric mod declared about that movement - its gravity multiplier, its speed and slowdown, whether mobs
     * float in it - lives in its behaviour, so the two are joined here: a fluid NeoForge built a type for runs
     * Fabric's method, and the entity is moved exactly as it would be on Fabric.
     *
     * <p>{@code isFalling} and {@code oldY} are the two values Fabric's method takes that NeoForge's hook does
     * not pass. They are recomputed here from the entity at the same point {@code LivingEntity#travelInFluid}
     * computes its own, which is where the hook is called from, so the method sees what it would see there.
     *
     * @return whether Fabric handled the movement
     */
    public static boolean moveInFabricFluid(Object behavior, Object tag, Object entity, Object movementVector,
            double gravity, boolean isFalling, double oldY) {
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var behaviorInterface = Class.forName("net.fabricmc.fabric.api.registry.fluid.FluidBehavior", false, modsLoader);
            // Resolved on the interface rather than on the implementation: Fabric's behaviours are often
            // package-private records or lambdas, and a public method of a non-public class cannot be called
            // from here - only the interface is reachable.
            var travelInFluid = behaviorInterface.getMethod("travelInFluid",
                    Class.forName("net.minecraft.tags.TagKey", false, modsLoader),
                    Class.forName("net.minecraft.world.entity.LivingEntity", false, modsLoader),
                    Class.forName("net.minecraft.world.phys.Vec3", false, modsLoader),
                    double.class,
                    boolean.class,
                    double.class);
            travelInFluid.invoke(behavior, tag, entity, movementVector, gravity, isFalling, oldY);
            return true;
        } catch (ClassNotFoundException e) {
            // fabric-content-registries-v0 is not in this mod set
            return false;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not run a Fabric fluid behaviour for {}", entity, e);
            return false;
        }
    }

    /**
     * {@return the state a Fabric mod registered as this state's stripped form, or {@code null} when the
     * Fabric side has nothing registered for it}
     *
     * <p>Fabric keeps a stripping transformer per block instead of a plain block, because a registration can
     * ask for the original state's properties to be copied. Its own call site sits in
     * {@code AxeItem#getStripped}, a method NeoForge's tree no longer calls, so it has to be asked for the
     * result explicitly.
     */
    public static Object strippedStateFromFabric(Object state) {
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var registry = Class.forName("net.fabricmc.fabric.impl.content.registry.StrippableBlockRegistryImpl", false, modsLoader);
            var blockState = Class.forName("net.minecraft.world.level.block.state.BlockState", false, modsLoader);
            return registry.getMethod("getStrippedBlockState", blockState).invoke(null, state);
        } catch (ClassNotFoundException e) {
            // fabric-content-registries-v0 is not in this mod set
            return null;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not ask Fabric's strippable-block registry for {}", state, e);
            return null;
        }
    }

    /**
     * Applies Fabric API's fuel events to a fuel table NeoForge assembled from its own data map, in Fabric's
     * own order.
     *
     * <p>Fabric fires them from inside {@code FuelValues#vanillaBurnTimes}: the build callbacks add fuels,
     * then vanilla excludes the non-flammable wood tag, then the exclusion callbacks run. NeoForge builds
     * that table from the furnace-fuel data map instead (which is why the exclusions the Fabric side expects
     * are replayed here too), and a Fabric mod has no way to write a data map at runtime - so without this
     * its fuels would never burn and its exclusions never apply.
     *
     * <p>The base smelt time is passed in because Fabric's callbacks scale their values by it and the data
     * map path has no equivalent parameter of its own.
     */
    public static void applyFabricFuelEvents(Object builder, Object registries, Object features, int baseSmeltTime) {
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var events = Class.forName("net.fabricmc.fabric.api.registry.FuelValueEvents", false, modsLoader);
            var context = Class.forName("net.fabricmc.fabric.impl.content.registry.FuelRegistryEventsContextImpl", false, modsLoader)
                    .getConstructor(
                            Class.forName("net.minecraft.core.HolderLookup$Provider", false, modsLoader),
                            Class.forName("net.minecraft.world.flag.FeatureFlagSet", false, modsLoader),
                            int.class)
                    .newInstance(registries, features, baseSmeltTime);

            callEvent(events.getField("BUILD").get(null), "build", builder, context);
            builder.getClass().getMethod("remove", Class.forName("net.minecraft.tags.TagKey", false, modsLoader))
                    .invoke(builder, Class.forName("net.minecraft.tags.ItemTags", false, modsLoader)
                            .getField("NON_FLAMMABLE_WOOD").get(null));
            callEvent(events.getField("EXCLUSIONS").get(null), "buildExclusions", builder, context);
        } catch (ClassNotFoundException e) {
            // fabric-content-registries-v0 is not in this mod set
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not apply Fabric API's fuel events", e);
        }
    }

    private static void callEvent(Object event, String methodName, Object... args) throws ReflectiveOperationException {
        var invoker = event.getClass().getMethod("invoker").invoke(event);
        // Resolved on the callback interface rather than on the implementation: Fabric's events are backed by
        // lambdas, and a public method of a lambda's (non-public) class cannot be called from here.
        for (var type : invoker.getClass().getInterfaces()) {
            for (var method : type.getMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                    method.invoke(invoker, args);
                    return;
                }
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    /** Renders one HUD layer; Fabric's elements are run around it. */
    @FunctionalInterface
    public interface HudRenderCallback {
        void render(Object graphics, Object deltaTracker);
    }
    /** A chain of Fabric HUD elements, ready to render around one layer. */
    @FunctionalInterface
    public interface FabricHudElements {
        void render(Object graphics, Object deltaTracker);
    }

    /**
     * {@return Fabric's own identifier for one of its HUD element ids, or {@code null} when it has no constant
     * with that value}
     *
     * <p>Resolved through Fabric's constants rather than parsed from the string on purpose: the registry is
     * keyed by identity, so a freshly parsed identifier finds nothing.
     */
    private static Object fabricElementId(String id, ClassLoader modsLoader, Class<?> identifierType) throws ReflectiveOperationException {
        var constants = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements", false, modsLoader);
        for (var field : constants.getFields()) {
            var value = field.get(null);
            if (value != null && identifierType.isInstance(value) && value.toString().equals(id)) {
                return value;
            }
        }
        return null;
    }

    /**
     * {@return the Fabric HUD elements registered around one vanilla HUD layer, run around {@code vanilla}, or
     * {@code null} when the Fabric side has nothing registered there}
     *
     * <p>Fabric's HUD API is organised around the vanilla layers: a mod attaches its element before or after
     * one of them, or replaces it, and Fabric wraps the vanilla render call to run them. That wrapping is done
     * by a mixin into {@code Hud#extractRenderState}, which no longer calls the layers individually - NeoForge
     * renders them through {@code GuiLayerManager} - so this side of the bridge hands the same information
     * over one layer at a time instead, and the caller passes the layer's own render in as the element Fabric
     * registered its own around.
     *
     * <p>The vanilla render is wrapped in a proxy implementing Fabric's element interface, so a replacement
     * registered through Fabric's API composes with it the same way it does on the Fabric side.
     */
    public static FabricHudElements fabricHudElements(String fabricElementId, HudRenderCallback vanilla) {
        try {
            var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var identifierType = Class.forName("net.minecraft.resources.Identifier", false, modsLoader);
            var elementType = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement", false, modsLoader);
            var layerType = Class.forName("net.fabricmc.fabric.impl.client.rendering.hud.HudLayer", false, modsLoader);
            var registry = Class.forName("net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl", false, modsLoader);
            var id = fabricElementId(fabricElementId, modsLoader, identifierType);
            if (id == null) {
                return null;
            }
            var root = registry.getMethod("getRoot", identifierType).invoke(null, id);
            if (root == null) {
                return null;
            }

            var vanillaElement = java.lang.reflect.Proxy.newProxyInstance(modsLoader, new Class<?>[] { elementType },
                    (proxy, method, args) -> {
                        if (method.getName().equals("extractRenderState") && args != null && args.length == 2) {
                            vanilla.render(args[0], args[1]);
                        }
                        return null;
                    });

            // A root starts out holding just the vanilla layer, and holds one layer per registered element -
            // so a root whose single layer still hands back exactly the element it was given, and is not
            // removed, has nothing to run. Resolved on the interface: the layers are anonymous classes.
            var layers = (java.util.List<?>) root.getClass().getMethod("layers").invoke(root);
            if (layers.size() == 1) {
                var onlyLayer = layers.get(0);
                var isRemoved = (boolean) layerType.getMethod("isRemoved").invoke(onlyLayer);
                var passedThrough = layerType.getMethod("element", elementType).invoke(onlyLayer, vanillaElement);
                if (!isRemoved && passedThrough == vanillaElement) {
                    return null;
                }
            }

            var extractRenderState = root.getClass().getMethod("extractRenderState",
                    Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor", false, modsLoader),
                    Class.forName("net.minecraft.client.DeltaTracker", false, modsLoader),
                    elementType);
            return (graphics, deltaTracker) -> {
                try {
                    extractRenderState.invoke(root, graphics, deltaTracker, vanillaElement);
                } catch (ReflectiveOperationException e) {
                    LOGGER.warn("[NeoFobric] Could not render Fabric's HUD elements for {}", fabricElementId, e);
                }
            };
        } catch (ClassNotFoundException e) {
            // fabric-rendering-v1 is not in this mod set
            return null;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[NeoFobric] Could not read Fabric's HUD element registry", e);
            return null;
        }
    }

    /**
     * {@return the parts an entity reports, safe to iterate} The platform's {@code Entity#getParts()} returns
     * {@code PartEntity[]}, and NeoForge's own tracking code walks that array whenever
     * {@code isMultipartEntity()} says yes. A Fabric mod's multipart entity overrides the <em>vanilla</em> shape
     * of that method, {@code Entity[]} - which cannot override the platform's, and so is never the method the
     * platform call lands on: it gets the interface default, {@code null}, and reads {@code null.length} into a
     * crash. Twilight Forest's boss spawner walks straight into that on every boss it spawns.
     *
     * <p>So this asks the entity's own class for the vanilla-shaped method by descriptor and returns whatever it
     * holds, as objects; the caller keeps only the parts the platform's type actually accepts. An entity with
     * neither shape, or a throwing one, yields an empty array - the tracking code then registers nothing, which
     * is what a Fabric multipart entity got before this existed, minus the crash.
     */
    public static Object[] entityParts(Object entity) {
        if (!bridgeActive() || entity == null) {
            return EMPTY_PARTS;
        }
        try {
            var gameLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var entityClass = Class.forName("net.minecraft.world.entity.Entity", false, gameLoader);
            for (var type = entity.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    var method = type.getDeclaredMethod("getParts");
                    var parts = method.invoke(entity);
                    if (parts == null) {
                        continue;
                    }
                    if (parts instanceof Object[] array) {
                        return array;
                    }
                } catch (NoSuchMethodException notHere) {
                    // keep walking up
                }
            }
            return EMPTY_PARTS;
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] Could not read the parts of {}", entity, t);
            return EMPTY_PARTS;
        }
    }

    private static final Object[] EMPTY_PARTS = new Object[0];

    /**
     * Keeps a block that a mod maps to two point-of-interest types from failing the class that registers it.
     *
     * <p>Vanilla only runs this check while it builds its own types; NeoForge runs the same registration for
     * every modded POI type, so a mod that maps one block to two types - which vanilla's own
     * {@code TYPE_BY_STATE.put} silently lets the later one win - dies with an {@code IllegalStateException}
     * inside that mod's static initializer. The class is then unusable, and so is everything that touches it:
     * Better End's {@code EndPoiTypes} took its carvers and features with it, and the world's data packs
     * failed to bind. The later type wins here, like vanilla, and the pair is reported once.
     */
    public static void noteDuplicatePoiType(Object blockState, Object previous, Object replacement) {
        if (REPORTED_POI_CONFLICTS.add(String.valueOf(blockState))) {
            LOGGER.warn("[NeoFobric] {} is mapped to two point-of-interest types ({} and {}); keeping the later "
                    + "one, the way vanilla does", blockState, previous, replacement);
        }
    }

    private static final java.util.Set<String> REPORTED_POI_CONFLICTS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Registers the component content types Fabric mods declare by mixing into
     * {@code ComponentSerialization#bootstrap}, which this environment can never apply.
     *
     * <p>Why a mixin into that class cannot work here: FML loads {@code ComponentSerialization} for its own
     * version detection, before the platform's mixin service is ready to build a target index (measured with
     * {@code -verbose:class}: {@code SharedConstants} at 19.051 s drags in the whole component codec chain,
     * while the mixin service only accepts a config from about 20.5 s on). Mixin never revisits a class it
     * has already seen, and the miss is silent in every log. The same limitation is why NeoForge declares its
     * own component type ({@code neoforge:inserting}) through a source patch of that class instead of a
     * mixin - and this is the Fabric side of it, reached from the same patch: the handlers a mod registers
     * for that method are found in its jars and run against the mapper the platform just built.
     *
     * <p>Never throws: a mod whose handler cannot be replayed loses its component type (the mod's own
     * decoding of it then fails, which is what Create's sequenced-assembly recipes did), but the game keeps
     * running.
     */
    public static void registerFabricComponentContents(Object contentTypes) {
        if (contentTypes == null || !bridgeActive() || Boolean.getBoolean("neofobric.noComponentReplay")) {
            return;
        }
        try {
            var handlers = fabricComponentHandlers();
            if (handlers.isEmpty()) {
                return;
            }
            var loader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var callbackInfo = Class.forName("org.spongepowered.asm.mixin.injection.callback.CallbackInfo", false, loader);
            var callback = callbackInfo.getConstructor(String.class, boolean.class).newInstance("bootstrap", false);
            for (var handler : handlers) {
                try {
                    // Static method: the receiver is null, and the handler's own parameters follow it.
                    handler.invoke(null, contentTypes, callback);
                } catch (Throwable t) {
                    LOGGER.error("[NeoFobric] Failed to replay {} for the component types", handler, t);
                }
            }
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("[NeoFobric] Replayed {} Fabric component content registration(s) that Mixin cannot"
                        + " apply to ComponentSerialization; the mapper now holds {} type(s)",
                        handlers.size(), componentTypeIds(contentTypes).size());
            }
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not replay the Fabric component content registrations", t);
        }
    }

    /** The bridge's own loader for the mixin classes it defines, so its classes never go through Mixin. */
    private static volatile ClassLoader replayLoader;
    private static volatile java.util.List<java.lang.reflect.Method> replayedHandlers;

    /** {@return the type ids the mapper currently holds, for logging} */
    private static java.util.List<String> componentTypeIds(Object contentTypes) {
        try {
            var values = contentTypes.getClass().getMethod("values").invoke(contentTypes);
            return values instanceof java.util.Collection<?> collection
                    ? collection.stream().map(value -> String.valueOf(value).split("\\[")[0].trim()).toList()
                    : java.util.List.of(String.valueOf(values));
        } catch (Throwable t) {
            return java.util.List.of("<unreadable: " + t + ">");
        }
    }

    /**
     * {@return the {@code @Inject} handlers Fabric mods declare for
     * {@code ComponentSerialization#bootstrap}, ready to be invoked with that method's own arguments}
     */
    private static java.util.List<java.lang.reflect.Method> fabricComponentHandlers() {
        var cached = replayedHandlers;
        if (cached != null) {
            return cached;
        }
        var found = new java.util.ArrayList<java.lang.reflect.Method>();
        var modsLoader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
        var client = net.neoforged.fml.loading.FMLEnvironment.getDist() == net.neoforged.api.distmarker.Dist.CLIENT;
        for (var entry : net.neoforged.fabricbridge.discovery.FabricJarRegistry.getEntries().entrySet()) {
            var info = entry.getValue();
            var configs = new java.util.ArrayList<>(info.commonMixins());
            configs.addAll(client ? info.clientMixins() : info.serverMixins());
            try (var jar = net.neoforged.fml.jarcontents.JarContents.ofPath(entry.getKey())) {
                for (var configPath : configs) {
                    var configBytes = jar.readFile(configPath);
                    if (configBytes == null) {
                        continue;
                    }
                    var config = com.google.gson.JsonParser
                            .parseString(new String(configBytes, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    var packageName = config.has("package") ? config.get("package").getAsString() : "";
                    for (var key : new String[] { "mixins", "client", "server" }) {
                        if (!config.has(key) || !config.get(key).isJsonArray()) {
                            continue;
                        }
                        for (var element : config.getAsJsonArray(key)) {
                            var mixinName = element.getAsString();
                            var classPath = (mixinName.contains(".") && !packageName.isEmpty()
                                    ? mixinName.replace('.', '/')
                                    : packageName.replace('.', '/') + "/" + mixinName) + ".class";
                            var bytes = jar.readFile(classPath);
                            if (bytes != null) {
                                collectComponentHandlers(bytes, classPath, modsLoader, found);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Could not read the mixin configs of {}", entry.getKey(), t);
            }
        }
        replayedHandlers = found;
        return found;
    }

    /**
     * Adds every {@code @Inject} handler of {@code mixinBytes} that targets
     * {@code ComponentSerialization#bootstrap}, defined in the bridge's own loader.
     */
    private static void collectComponentHandlers(byte[] mixinBytes, String classPath, ClassLoader modsLoader,
            java.util.List<java.lang.reflect.Method> found) {
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(mixinBytes).accept(node, 0);
        if (!targetsComponentSerialization(node)) {
            return;
        }
        var handlers = new java.util.ArrayList<org.objectweb.asm.tree.MethodNode>();
        for (var method : node.methods) {
            var inject = annotation(method.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
            if (inject == null && (inject = annotation(method.visibleAnnotations,
                    "Lorg/spongepowered/asm/mixin/injection/Inject;")) == null) {
                continue;
            }
            var target = annotationValue(inject, "method");
            if (target != null && target.startsWith("bootstrap(")) {
                handlers.add(method);
            }
        }
        if (handlers.isEmpty()) {
            return;
        }
        try {
            var defined = defineInReplayLoader(mixinBytes, node.name, classPath, modsLoader);
            var mapperType = Class.forName("net.minecraft.util.ExtraCodecs$LateBoundIdMapper", false, modsLoader);
            var callbackType = Class.forName("org.spongepowered.asm.mixin.injection.callback.CallbackInfo", false, modsLoader);
            for (var method : handlers) {
                // Create's handler is the shape Mixin requires for an injection into a static method: the
                // target's own parameters followed by the CallbackInfo. Matched on the exact signature -
                // a name-and-arity match also picks up unrelated helpers, and invoking one of those throws
                // "wrong number of arguments" straight out of the platform's codec build.
                try {
                    var candidate = defined.getDeclaredMethod(method.name, mapperType, callbackType);
                    candidate.setAccessible(true);
                    found.add(candidate);
                } catch (NoSuchMethodException e) {
                    LOGGER.warn("[NeoFobric] {} does not carry the handler Mixin would call ({}), skipping it",
                            classPath, method.name);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not prepare the mixin handlers of {}", classPath, t);
        }
    }

    /** {@return whether the mixin class's {@code @Mixin} names {@code ComponentSerialization}} */
    private static boolean targetsComponentSerialization(org.objectweb.asm.tree.ClassNode node) {
        var mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        if (mixin == null && (mixin = annotation(node.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;")) == null) {
            return false;
        }
        for (var value : mixin.values) {
            if (value instanceof java.util.List<?> list) {
                for (var element : list) {
                    if (element instanceof org.objectweb.asm.Type type
                            && "net/minecraft/network/chat/ComponentSerialization".equals(type.getInternalName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static org.objectweb.asm.tree.AnnotationNode annotation(
            java.util.List<org.objectweb.asm.tree.AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return null;
        }
        for (var annotation : annotations) {
            if (descriptor.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    private static String annotationValue(org.objectweb.asm.tree.AnnotationNode annotation, String key) {
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                var value = annotation.values.get(i + 1);
                if (value instanceof java.util.List<?> list && list.size() == 1) {
                    value = list.get(0);
                }
                return value instanceof String text ? text : null;
            }
        }
        return null;
    }

    /**
     * Defines a mixin class in the bridge's own loader, whose parent is the mods loader - so the mixin's
     * references resolve exactly as they would inside the game, while the class itself is never handed to
     * Mixin (which refuses to have a mixin class loaded directly).
     */
    private static Class<?> defineInReplayLoader(byte[] bytes, String internalName, String classPath, ClassLoader modsLoader) {
        var loader = replayLoader;
        if (loader == null) {
            synchronized (FabricBridgeHooks.class) {
                if (replayLoader == null) {
                    replayLoader = new ReplayLoader(modsLoader);
                }
                loader = replayLoader;
            }
        }
        return ((ReplayLoader) loader).defineIfAbsent(internalName.replace('/', '.'), bytes);
    }

    private static final class ReplayLoader extends ClassLoader {
        ReplayLoader(ClassLoader parent) {
            super(parent);
        }

        synchronized Class<?> defineIfAbsent(String name, byte[] bytes) {
            var existing = findLoadedClass(name);
            return existing != null ? existing : defineClass(name, bytes, 0, bytes.length);
        }
    }

    // ---- Fabric's extended menus -------------------------------------------------
    //
    // Fabric's menu API opens a menu *with data*; NeoForge has the same idea - an AdvancedOpenScreenPayload
    // carrying an extra-data buffer, with the menu type expected to answer IContainerFactory.
    // ForgifiedFabricAPI marries the two and never needs Fabric's own three ServerPlayer injections (the ones
    // NeoForge's wider openMenu overload displaced): the server writes Fabric's opening data into NeoForge's
    // buffer, and NeoForge's own client path hands that buffer back to the menu type's three-argument create.
    // These two hooks are that marriage; without them an extended menu reaches the client as a plain vanilla
    // open-screen packet, whose two-argument create Fabric's menu API rejects on purpose - which disconnects
    // the client. Every Fabric mod with a block screen (TechReborn, Create, bclib, ...) uses that API.

    private static final String FABRIC_EXTENDED_MENU_PROVIDER = "net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider";
    private static final String FABRIC_EXTENDED_MENU_TYPE = "net.fabricmc.fabric.api.menu.v1.ExtendedMenuType";

    /**
     * Writes an extended provider's opening data into the platform's extra-data buffer, when the menu is one of
     * Fabric's extended types.
     *
     * <p>Called from the platform's own {@code ServerPlayer#openMenu} right where it builds that buffer.
     * Nothing happens - not even a log line - when Fabric's menu API, or an extended menu, is not in play.
     */
    public static void writeFabricExtendedMenuData(Object provider, Object menu, Object player, Object buffer) {
        try {
            var factory = fabricExtendedProvider(provider);
            var type = menu == null ? null : publicMethod(menu.getClass(), "getType", 0).invoke(menu);
            if (factory == null || menu == null || buffer == null || player == null || !isFabricExtendedMenuType(type)) {
                return;
            }
            LOGGER.debug(
                    "[NeoFobric] Writing an extended menu's opening data: provider={} factory={} type={}",
                    provider.getClass().getName(), factory.getClass().getName(), type.getClass().getName());
            // The lookups stay on the runtime classes, and the method the class actually declares wins: the
            // interesting ones sit in anonymous or lambda-generated classes (TechReborn's factory is a
            // GuiType$1, an encoded codec a StreamCodec$7), whose only declaration is the override itself.
            // publicMethod opens that one when it has to, which the module system allows because mod and game
            // classes live in an unnamed module.
            var data = publicMethod(factory.getClass(), "getScreenOpeningData", 1).invoke(factory, player);
            var codec = publicMethod(type.getClass(), "getStreamCodec", 0).invoke(type);
            publicMethod(codec.getClass(), "encode", 2).invoke(codec, buffer, data);
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not write an extended menu's opening data", t);
        }
    }

    /** {@return the method with that name and arity, made invokable when a hidden class declares it} */
    private static java.lang.reflect.Method publicMethod(Class<?> owner, String name, int arity) throws Exception {
        java.lang.reflect.Method hidden = null;
        for (var method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arity) {
                continue;
            }
            if (java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                return method;
            }
            // An anonymous or lambda implementation declares the override in a class that is not public,
            // and that declaration is the only one getMethods finds; invoking it needs it opened, which is
            // always allowed for a mod or game class because those live in an unnamed module.
            if (hidden == null) {
                hidden = method;
            }
        }
        if (hidden != null && hidden.trySetAccessible()) {
            return hidden;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + "/" + arity);
    }

    /**
     * {@return the menu Fabric's extended opening data describes, or {@code null} when the type is not one}
     *
     * <p>Called from the platform's own advanced-open-screen handler before it falls back to the vanilla
     * create, which for an extended type is the very call Fabric's menu API rejects.
     */
    public static Object createFabricExtendedMenu(Object menuType, int windowId, Object inventory, Object buffer) {
        try {
            if (!isFabricExtendedMenuType(menuType) || buffer == null) {
                return null;
            }
            // Runtime classes only, and the method the class actually declares - the same rule as when writing.
            var codec = publicMethod(menuType.getClass(), "getStreamCodec", 0).invoke(menuType);
            var data = publicMethod(codec.getClass(), "decode", 1).invoke(codec, buffer);
            return publicMethod(menuType.getClass(), "create", 3).invoke(menuType, windowId, inventory, data);
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not create an extended menu", t);
        }
        return null;
    }

    /** {@return the Fabric extended-provider behind a provider, looking through a vanilla SimpleMenuProvider} */
    private static Object fabricExtendedProvider(Object provider) {
        if (provider == null) {
            return null;
        }
        if (typeImplements(provider.getClass(), FABRIC_EXTENDED_MENU_PROVIDER)) {
            return provider;
        }
        // A plain MenuProvider can still hide the real factory in a SimpleMenuProvider's constructor field;
        // the field is found by name because the bridge compiles without the game on its classpath.
        for (var cls = provider.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                var field = cls.getDeclaredField("menuConstructor");
                field.setAccessible(true);
                var inner = field.get(provider);
                if (inner != null && typeImplements(inner.getClass(), FABRIC_EXTENDED_MENU_PROVIDER)) {
                    return inner;
                }
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (Throwable t) {
                LOGGER.debug("[NeoFobric] Could not read a provider's menu factory", t);
                break;
            }
        }
        return null;
    }

    /** {@return whether the value's class, or one of its superclasses, is Fabric's extended menu type} */
    private static boolean isFabricExtendedMenuType(Object type) {
        if (type == null) {
            return false;
        }
        for (var cls = type.getClass(); cls != null; cls = cls.getSuperclass()) {
            if (FABRIC_EXTENDED_MENU_TYPE.equals(cls.getName())) {
                return true;
            }
        }
        return false;
    }

    /** {@return whether the class implements the interface, directly or through its supertypes} */
    private static boolean typeImplements(Class<?> type, String interfaceName) {
        if (type == null) {
            return false;
        }
        if (interfaceName.equals(type.getName())) {
            return true;
        }
        for (var itf : type.getInterfaces()) {
            if (typeImplements(itf, interfaceName)) {
                return true;
            }
        }
        var superclass = type.getSuperclass();
        return superclass != null && !superclass.equals(Object.class) && typeImplements(superclass, interfaceName);
    }

    /** entry name to the Fabric jar that holds it; built on the first miss, which is a rare lookup. */
    private static volatile java.util.Map<String, String> fabricResourceIndex;

    /**
     * {@return the path of the Fabric mod jar holding {@code entry}, or {@code null} when none does}
     *
     * <p>Fabric lets any mod read any other mod's files, because every mod sits on one class path there. Here
     * the Fabric jars are merged into a single game module instead, so a lookup that starts from a
     * <em>platform</em> class cannot reach them at all: WorldWeaver asks
     * {@code MinecraftServer.class.getResourceAsStream("/data/betternether/structure/...")} for its city
     * templates, and BetterNether's own jar is what has them. The bridge searches all of them, which is what
     * {@code FabricResourceHooks} falls back to once the original lookup came back empty.
     */
    public static String fabricModResourceJar(String entry) {
        if (entry == null || entry.isEmpty() || entry.startsWith("/")) {
            return null;
        }
        var index = fabricResourceIndex;
        if (index == null) {
            synchronized (FabricBridgeHooks.class) {
                if (fabricResourceIndex == null) {
                    fabricResourceIndex = indexFabricResources();
                }
                index = fabricResourceIndex;
            }
        }
        return index.get(entry);
    }

    private static java.util.Map<String, String> indexFabricResources() {
        var index = new java.util.HashMap<String, String>();
        for (var path : net.neoforged.fabricbridge.discovery.FabricJarRegistry.getPaths()) {
            try (var jar = net.neoforged.fml.jarcontents.JarContents.ofPath(path)) {
                var jarPath = path.toString();
                jar.visitContent((relativePath, resource) -> {
                    if (!relativePath.endsWith("/")) {
                        // First jar in registration order wins, the same way the class path would answer.
                        index.putIfAbsent(relativePath, jarPath);
                    }
                });
            } catch (Throwable t) {
                // A jar that cannot be read contributes nothing; the others still answer.
            }
        }
        LOGGER.debug("[NeoFobric] Indexed {} resource entries across {} Fabric jar(s)",
                index.size(), net.neoforged.fabricbridge.discovery.FabricJarRegistry.getPaths().size());
        return index;
    }
}
