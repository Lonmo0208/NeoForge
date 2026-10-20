/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.network.registration;

import java.util.function.Predicate;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * The platform-side half of the Fabric channel decode hand-off.
 *
 * <p>Fabric's networking module prepends its own handler to vanilla's payload decode, but that handler
 * only runs while the payload is still an id. NeoForge's fallback answers "unknown channel" for every id
 * its registry does not know, which includes every channel a Fabric mod registered; the bridge therefore
 * throws from the vanilla decode path for exactly those ids, giving Fabric's prepended handler the chance
 * to decode with its own codec.
 *
 * <p>The condition lives in the bridge (it is the side that knows which namespaces and payload types
 * belong to Fabric); this class only holds the predicate, so the patched vanilla class never has to
 * reference the bridge module directly.
 */
@ApiStatus.Internal
public final class FabricChannelDecodeHooks {
    private static volatile Predicate<Identifier> fabricChannelTest = id -> false;

    private FabricChannelDecodeHooks() {}

    /** Installs the bridge's Fabric-channel test. A null test restores the pass-through behaviour. */
    public static void setFabricChannelTest(Predicate<Identifier> test) {
        fabricChannelTest = test == null ? id -> false : test;
    }

    /** {@return whether the payload id belongs to a Fabric channel and should be decoded by Fabric} */
    public static boolean letFabricDecode(Identifier channel) {
        var test = fabricChannelTest;
        return channel != null && test.test(channel);
    }
}
