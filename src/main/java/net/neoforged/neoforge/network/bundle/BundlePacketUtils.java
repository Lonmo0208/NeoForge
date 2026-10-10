/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.network.bundle;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for dealing with {@link net.minecraft.network.protocol.BundlePacket}s.
 */
@ApiStatus.Internal
public class BundlePacketUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(BundlePacketUtils.class);

    private BundlePacketUtils() {
        throw new IllegalStateException("Tried to create utility class!");
    }

    public static <T extends PacketListener> List<Packet<? super T>> flatten(Iterable<Packet<? super T>> packets) {
        final List<Packet<? super T>> result = new ArrayList<>();
        packets.forEach(packet -> {
            if (packet instanceof BundlePacket<? super T> innerBundle) {
                result.addAll(flatten(innerBundle.subPackets()));
            } else {
                result.add(packet);
            }
        });
        if (result.isEmpty()) {
            // NeoFobric: a bundle that flattens down to nothing cannot be sent - vanilla's
            // PacketBundleUnpacker refuses to produce zero messages and takes the connection down with
            // "PacketBundleUnpacker must produce at least one message". Name the culprit here, while the
            // connection is still up. Fabric API's own flatten drops empty inner bundles; an empty
            // top-level bundle is a bug on either loader.
            LOGGER.warn("Flattened a bundle down to nothing, its sender is putting an empty bundle on the wire");
        }
        return result;
    }
}
