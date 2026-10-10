/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.event;

import com.mojang.logging.LogUtils;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.resource.ContextAwareReloadListener;
import net.neoforged.neoforge.resource.ListenerKey;
import net.neoforged.neoforge.resource.VanillaServerListeners;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The main ResourceManager is recreated on each reload, just after {@link ReloadableServerResources}'s creation.
 *
 * The event is fired on each reload and lets modders add their own ReloadListeners, for server-side resources.
 * The event is fired on the {@link NeoForge#EVENT_BUS}
 */
public class AddServerReloadListenersEvent extends SortedReloadListenerEvent {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ReloadableServerResources serverResources;
    private final RegistryAccess registryAccess;
    private final Map<ListenerKey<?>, PreparableReloadListener> retainedListeners;

    @ApiStatus.Internal
    public AddServerReloadListenersEvent(
            ReloadableServerResources serverResources,
            RegistryAccess registryAccess,
            Map<ListenerKey<?>, PreparableReloadListener> retainedListeners) {
        super(serverResources.listeners(), AddServerReloadListenersEvent::lookupName);
        this.serverResources = serverResources;
        this.registryAccess = registryAccess;
        this.retainedListeners = retainedListeners;
    }

    /// Adds a new [`reload listener`][PreparableReloadListener] to the resource manager and retains it in the [ReloadableServerResources]
    /// for later access through [ReloadableServerResources#getListener(ListenerKey)()].
    ///
    /// Unless explicitly specified, this listener will run after all vanilla listeners, in the order it was registered.
    ///
    /// @param key      The resource location that identifies the reload listener for dependency sorting and lookup.
    /// @param listener The listener to add.
    ///
    /// @throws IllegalArgumentException if another listener with that key was already registered.
    public <T extends PreparableReloadListener> void addRetainedListener(ListenerKey<T> key, T listener) {
        this.addListener(key.getListenerId(), listener);
        this.retainedListeners.put(key, listener);
    }

    /**
     * @return The {@link ReloadableServerResources} being reloaded.
     */
    public ReloadableServerResources getServerResources() {
        return serverResources;
    }

    /**
     * This context object holds data relevant to the current reload, such as staged tags.
     * 
     * @return The condition context for the currently active reload.
     */
    public ICondition.IContext getConditionContext() {
        return serverResources.getConditionContext();
    }

    /// Provides access to the loaded registries associated with these server resources.
    /// All built-in and dynamic registries are loaded and frozen by this point.
    ///
    /// @apiNote The returned [RegistryAccess] does NOT provide access to the tags loaded during the active reload.
    /// To resolve tags the [HolderLookup.Provider] provided via [ContextAwareReloadListener#getRegistryLookup()]
    /// must be used instead.
    ///
    /// @return The [RegistryAccess] context for the currently active reload.
    ///
    /// @deprecated Use [ContextAwareReloadListener#getRegistryLookup()] instead
    @Deprecated(forRemoval = true, since = "26.1.2")
    public RegistryAccess getRegistryAccess() {
        return registryAccess;
    }

    private static Identifier lookupName(PreparableReloadListener listener) {
        Identifier key = VanillaServerListeners.getNameForClass(listener.getClass());
        if (key == null) {
            var bridged = bridgedListenerKey(listener.getClass());
            if (bridged != null) {
                // A Fabric mod added this listener with a mixin into the resource manager, which is how
                // reload listeners are contributed on that side; there is no vanilla-style name for it.
                // Handing it a synthetic key keeps it in the ordering graph instead of aborting the load.
                LOGGER.debug("Giving the bridged Fabric reload listener {} the key {}", listener, bridged);
                return bridged;
            }
            if (listener.getClass().getPackageName().startsWith("net.minecraft")) {
                throw new IllegalArgumentException("A key for the reload listener " + listener + " was not provided in VanillaServerListeners!");
            } else {
                throw new IllegalArgumentException("A non-vanilla reload listener " + listener + " was added via mixin before the AddReloadListenerEvent! Mod-added listeners must go through the event.");
            }
        }
        return key;
    }

    /**
     * Module name of the Fabric composite module the NeoFobric bridge merges all Fabric mods into. Kept
     * as a literal because NeoForge core does not compile against the bridge
     * (see {@code BridgeFabricModLocator.FABRIC_MODULE_NAME}, which this has to match).
     */
    private static final String BRIDGED_FABRIC_MODULE = "neofobric.fabric.mods";

    /**
     * {@return a stable key for a reload listener that comes from the bridged Fabric module, or null if
     * the class does not belong to it} Only classes of the Fabric composite module are accepted: the
     * mixin check above stays in force for everything else.
     */
    @ApiStatus.Internal
    public static @Nullable Identifier bridgedListenerKey(Class<?> listenerClass) {
        if (!BRIDGED_FABRIC_MODULE.equals(listenerClass.getModule().getName())) {
            return null;
        }
        var path = listenerClass.getName().toLowerCase(java.util.Locale.ROOT)
                .replace('$', '.');
        return Identifier.fromNamespaceAndPath("neofobric", "bridged/" + path);
    }
}
