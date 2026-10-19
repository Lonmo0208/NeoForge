package net.neoforged.fabricbridge.compbridlnk;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.FieldNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polymer's named compatibility fixes.
 *
 * <p>Polymer's {@code InternalClientRegistry.clearTabs} resets the open creative screen's page by
 * reflection: it walks the screen's declared fields for a name containing {@code currentPage} and
 * writes {@code setInt(null, 0)} - a static int on pure Fabric. This platform's
 * {@code CreativeModeInventoryScreen} carries the page as an instance field of
 * {@code CreativeTabsScreenPage} instead, so the write throws, the teardown reports an error, and the
 * reload that follows a settings change (ViaFabricPlus's GUI among them) stalls on the render thread.
 * A static int companion field satisfies the reflection exactly - same name fragment, static, int -
 * and nobody reads it.
 */
public final class PolymerCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat/Polymer");

    /** The screen whose fields polymer's teardown reflects over. */
    public static final String CREATIVE_SCREEN = "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";

    private PolymerCompat() {}

    /**
     * Adds the static int page cursor polymer's tab teardown writes reflectively.
     *
     * <p>Polymer's loop takes the FIRST declared field whose name contains {@code currentPage} and
     * stops, so the companion must sit ahead of the platform's own instance field.
     *
     * @return whether the class was changed
     */
    public static boolean giveCreativeScreenPageCursor(ClassNode node) {
        if (!node.name.equals(CREATIVE_SCREEN)
                || node.fields.stream().anyMatch(f -> f.name.equals("neofobric$currentPage"))) {
            return false;
        }
        var cursor = new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "neofobric$currentPage", "I", null, 0);
        node.fields.add(0, cursor);
        LOGGER.info("[NeoFobric] Gave the creative inventory screen a static int page cursor for polymer's teardown");
        return true;
    }

    /** {@return whether {@code entryName} is a class this compat module needs to see} */
    public static boolean handlesClass(String internalName) {
        return CREATIVE_SCREEN.equals(internalName);
    }
}
