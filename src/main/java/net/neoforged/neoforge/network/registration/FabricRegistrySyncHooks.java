/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.network.registration;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a client that Fabric API's registry sync cannot talk to on the server, and tells that player
 * in chat which namespaces Fabric API thought were related.
 *
 * <p>Fabric API's {@code fabric-registry-sync-v0} screens the client during configuration: it asks
 * {@code ServerConfigurationNetworking.canSend(listener, RegistrySyncPayload.ID)}, and when the answer is
 * false it disconnects with "This server requires Fabric API installed on your client!". That test is the
 * right one on a Fabric server, where a peer without the payload type genuinely cannot decode the registry
 * dump - but it is the wrong one here. The client of this server is a <em>NeoForge</em> client: NeoForge
 * syncs the registries its mods add through its own channel, the Fabric payload type is only registered
 * when the peer is a Fabric client, and a server whose Fabric mods add no registry entries has nothing for
 * the Fabric path to carry. Disconnecting on that answer turns "the Fabric route is not needed" into "the
 * client is not allowed in", which is what this hook prevents.
 *
 * <p>The hook replaces the {@code disconnect} call only. The method still returns right after it, so no
 * {@code SyncConfigurationTask} is queued: the connection continues through NeoForge's own configuration
 * handling, and the registry entries the platform and its mods define travel the NeoForge route as they
 * always did.
 *
 * <p>The reason Fabric API built carries up to four registry namespaces it considers related, followed by
 * {@code And N more...}. Configuration is too early for a play-phase packet, so the formatted warning is
 * held per connection and sent as a system chat message by the player-join event. It is deliberately
 * worded as a <em>possible</em> mismatch: the client may have every mod and only lack Fabric's registry
 * route.
 */
@ApiStatus.Internal
public final class FabricRegistrySyncHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/RegistrySync");
    private static final String NAMESPACE_HEADER = "The following registry entry namespaces may be related:";
    private static final String MORE_PREFIX = "And ";
    private static final String MORE_SUFFIX = " more...";

    /** Player UUID -> the chat warning Fabric API's reason was turned into. */
    private static final Map<UUID, String> PENDING_WARNINGS = new ConcurrentHashMap<>();

    /** The bridge installs a test for Fabric mod namespaces; until then every related name is listed. */
    private static volatile Predicate<String> fabricNamespaceTest = namespace -> true;

    static {
        NeoForge.EVENT_BUS.addListener(FabricRegistrySyncHooks::onPlayerLogin);
    }

    private FabricRegistrySyncHooks() {}

    /** Installs the bridge's Fabric-namespace test. A null test restores listing every related name. */
    public static void setFabricNamespaceTest(Predicate<String> test) {
        fabricNamespaceTest = test == null ? namespace -> true : test;
    }

    /**
     * Stands in for {@code ServerConfigurationPacketListenerImpl#disconnect} in
     * {@code RegistrySyncManager.configureClient}: the connection is kept, and the player is warned in
     * chat after joining.
     *
     * @param listener the configuration listener whose disconnect call was replaced
     * @param reason   the component Fabric API built for the disconnect
     */
    public static void keepInsteadOfDisconnecting(ServerConfigurationPacketListenerImpl listener,
            Component reason) {
        // GameProfile is a record in this authlib (9.0.75): the accessors are name()/id(), not the old
        // getName()/getId() beans. getOwner() itself is public on ServerCommonPacketListenerImpl.
        var profile = listener.getOwner();
        var name = profile == null ? null : profile.name();
        var uuid = profile == null ? null : profile.id();
        var reasonText = reason == null ? "" : reason.getString();
        var warning = formatWarning(reasonText);
        LOGGER.warn("[NeoFobric] Fabric API's registry sync cannot reach {} - keeping the connection and"
                + " warning the player in chat. Fabric API's reason was: {}",
                name == null ? "the joining client" : name,
                reasonText.replace('\n', ' '));
        if (uuid != null) {
            PENDING_WARNINGS.put(uuid, warning);
        }
    }

    /** {@return the warning held for that player, removing it, or null when there is none} */
    static String takePendingWarning(UUID playerId) {
        return playerId == null ? null : PENDING_WARNINGS.remove(playerId);
    }

    /** Drops the held warning of a player who never made it to the play phase. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            PENDING_WARNINGS.remove(playerId);
        }
    }

    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        var player = event.getEntity();
        var warning = takePendingWarning(player.getUUID());
        if (warning != null) {
            player.sendSystemMessage(Component.literal(warning));
        }
    }

    /**
     * Turns Fabric API's disconnect reason into the chat warning.
     *
     * <p>The reason has the shape "This server requires ...!\nThe following registry entry namespaces may
     * be related:\n\nname\nname\nAnd N more...\n\nContact ...". Only the namespaces are carried over; the
     * rest of Fabric API's wording is about installing Fabric API, which is not what a NeoForge client
     * needs here.
     */
    static String formatWarning(String reasonText) {
        var names = new ArrayList<String>();
        var more = 0;
        var header = reasonText == null ? -1 : reasonText.indexOf(NAMESPACE_HEADER);
        if (header >= 0) {
            var tail = reasonText.substring(header + NAMESPACE_HEADER.length());
            for (var rawLine : tail.split("\n")) {
                var line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith(MORE_PREFIX) && line.endsWith(MORE_SUFFIX)) {
                    try {
                        more = Integer.parseInt(line.substring(MORE_PREFIX.length(),
                                line.length() - MORE_SUFFIX.length()).trim());
                    } catch (NumberFormatException ignored) {
                        // An unreadable tail is not worth dropping the warning over.
                    }
                    continue;
                }
                if (line.startsWith("This server requires") || line.startsWith("Contact the server")) {
                    continue;
                }
                if (line.matches("[A-Za-z0-9_.-]+")) {
                    names.add(line);
                }
            }
        }

        var fabricNames = names.stream().filter(FabricRegistrySyncHooks::isFabricNamespace).toList();
        var listed = fabricNames.isEmpty() ? names : fabricNames;
        if (listed.isEmpty()) {
            return "你的客户端可能与服务端的 Fabric 注册表内容不一致。"
                    + "如果你确定你的客户端已经安装了相同的模组，请忽视此警告。";
        }

        var message = new StringBuilder("你的客户端可能缺少以下 Fabric 模组：");
        message.append(String.join("，", listed));
        if (more > 0) {
            message.append("（另有 ").append(more).append(" 个未列出）");
        }
        message.append("。如果你确定你的客户端已经安装了缺少的模组，请忽视此警告。");
        return message.toString();
    }

    private static boolean isFabricNamespace(String namespace) {
        try {
            return fabricNamespaceTest.test(namespace);
        } catch (Throwable t) {
            return false;
        }
    }
}
