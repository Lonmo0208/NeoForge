package net.neoforged.fabricbridge.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins the pure helpers the jar patcher decides with.
 *
 * <p>Every case here is a shape that has been wrong once: a descriptor reader that dropped primitives
 * shifted every parameter index after the first one, a member rename that only matched a whole string
 * missed the same member written without its owner, and an anchor spelling that was parsed as one long
 * name left the owner null so the retarget could never see it. Those mistakes cost end-to-end runs; here
 * they cost a millisecond.
 */
class MixinJarPatcherTest {
    private static final String RENAMED_OLD =
            "placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;"
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Z"
                    + "Lnet/minecraft/util/ProblemReporter;)V";

    @Test
    void countsEveryParameterKind() {
        assertEquals(0, MixinJarPatcher.countParameters("()V"));
        assertEquals(1, MixinJarPatcher.countParameters("(I)V"));
        assertEquals(2, MixinJarPatcher.countParameters("(Ljava/lang/String;I)Z"));
        // Primitives are parameters too - dropping them was the bug that shifted every later index.
        assertEquals(3, MixinJarPatcher.countParameters("(JDF)V"));
        assertEquals(1, MixinJarPatcher.countParameters("(Z)Ljava/lang/Object;"));
        // Arrays count once, whatever their depth.
        assertEquals(2, MixinJarPatcher.countParameters("([I[[Ljava/lang/String;)V"));
        assertEquals(1, MixinJarPatcher.countParameters("(Lnet/minecraft/core/BlockPos;)V"));
        // A bare name (no parameter list) reads as none instead of running off the string.
        assertEquals(0, MixinJarPatcher.countParameters("tick"));
        assertEquals(0, MixinJarPatcher.countParameters(""));
    }

    @Test
    void renamesTheMemberOnEitherSpelling() {
        // A bare selector and a call target that carries its owner both resolve - the tail match.
        assertTrue(MixinJarPatcher.renamedMember(RENAMED_OLD).startsWith("addEntitiesToWorld("));
        assertTrue(MixinJarPatcher.renamedMember(
                        "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;" + RENAMED_OLD)
                .startsWith("Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;"
                        + "addEntitiesToWorld("));
        // Everything else falls through to the parameter-list table.
        assertNull(MixinJarPatcher.renamedMember("tick()V"));
    }

    @Test
    void rewritesTheParameterListWhenThePlatformWidenedTheCall() {
        var old = "run(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;"
                + "Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;)V";
        assertEquals(
                "run(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;"
                        + "Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;"
                        + "Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/ResourceKey;)V",
                MixinJarPatcher.replacementFor(old, Map.of()));
        // A rename is honoured through the same entry point.
        assertTrue(MixinJarPatcher.replacementFor(RENAMED_OLD, Map.of()).startsWith("addEntitiesToWorld("));
        // A scoped table is consulted for the same parameter list, and the caller's map wins when both know
        // the shape.
        var params = "(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;"
                + "Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;)";
        assertEquals("run(IX)V", MixinJarPatcher.replacementFor("run" + params + "V", Map.of(params, "(IX)")));
        // Unknown shapes stay untouched.
        assertNull(MixinJarPatcher.replacementFor("tick()V", Map.of()));
        assertNull(MixinJarPatcher.replacementFor("tick", Map.of()));
    }

    @Test
    void parsesEveryAnchorSpellingMixinAccepts() {
        var owned = MixinJarPatcher.parseAnchorMember(
                "Lnet/minecraft/world/level/block/Block;getBlock()Lnet/minecraft/world/level/block/Block;");
        assertEquals("net/minecraft/world/level/block/Block", owned.owner());
        assertEquals("getBlock", owned.name());
        assertEquals("()Lnet/minecraft/world/level/block/Block;", owned.desc());

        var bare = MixinJarPatcher.parseAnchorMember("getBlock()Lnet/minecraft/world/level/block/Block;");
        assertNull(bare.owner());
        assertEquals("getBlock", bare.name());

        // The dotted spelling was invisible before: the whole prefix became the member name.
        var dotted = MixinJarPatcher.parseAnchorMember(
                "net/minecraft/client/renderer/block/ModelBlockRenderer.<init>(ZZLnet/minecraft/client/color/block/BlockColors;)V");
        assertEquals("net/minecraft/client/renderer/block/ModelBlockRenderer", dotted.owner());
        assertEquals("<init>", dotted.name());
        assertEquals("(ZZLnet/minecraft/client/color/block/BlockColors;)V", dotted.desc());

        var field = MixinJarPatcher.parseAnchorMember("Lnet/minecraft/world/item/ItemStack;trustedNormals:Z");
        assertEquals("net/minecraft/world/item/ItemStack", field.owner());
        assertEquals("trustedNormals", field.name());
        assertEquals("Z", field.desc());

        assertNull(MixinJarPatcher.parseAnchorMember(""));
    }

    @Test
    void readsHandlerParametersWithoutLosingThePrimitives() {
        var handler = new MethodNode(Opcodes.ACC_PRIVATE, "handler", "(IDLjava/lang/String;)V", null, null);
        assertEquals(List.of("I", "D", "Ljava/lang/String;"), MixinJarPatcher.parametersFor(handler));
        // A handler with no parameter annotations carries no sugar, so no index is skipped.
        assertFalse(MixinJarPatcher.hasSugar(handler, 0));
        assertFalse(MixinJarPatcher.declaresSugar(null));
    }
}
