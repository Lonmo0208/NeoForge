package net.neoforged.fabricbridge.compbridlnk;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Disables modernfix's {@code attribute_supplier_dedup} mixin, because the platform keeps the vanilla
 * shape of the field it shadows.
 *
 * <p>{@code AttributeSupplier$Builder.builder} is an {@code ImmutableMap.Builder} here - the vanilla
 * type, which Fabric's default attribute registry reads through an accessor to copy a supplier's
 * instances into a fresh builder (see the comment in the AttributeSupplier patch). modernfix declares
 * its {@code @Shadow} for that field as {@code java.util.Map} (the type upstream NeoForge gives it),
 * and a shadow whose descriptor does not match the field never binds: Mixin rejects the whole
 * application with "field builder was not located", which is fatal during bootstrap on any install
 * that has modernfix.
 *
 * <p>One field cannot carry both shapes, and the mixin is a pure memory optimisation (deduplicating
 * equal attribute suppliers), so the lever is modernfix's own options file: its mixin plugin reads
 * {@code config/modernfix-mixins.properties} before deciding to apply a mixin. modernfix rewrites
 * that file on later boots, so this runs before its config is first read on every boot - which is why
 * the call sits in the class-processor link, the earliest bridge code that runs.
 */
public final class ModernfixMixinGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModernfixMixinGuard.class);
    private static final String OPTION = "mixin.perf.attribute_supplier_dedup";
    private static final List<String> OVERRIDE = List.of(
            "",
            "# NeoFobric: the platform keeps the vanilla ImmutableMap.Builder shape of "
                    + "AttributeSupplier$Builder.builder",
            "# (Fabric's default attribute registry reads it through an accessor), so modernfix's Map-typed "
                    + "@Shadow",
            "# for that field never binds and its application crashes the boot. Re-enable only if the field "
                    + "shape ever changes.",
            OPTION + "=false");

    public static void ensure() {
        try {
            var gameDir = FMLPaths.GAMEDIR.get();
            if (!hasModernfix(gameDir.resolve("mods"))) {
                return;
            }
            var configFile = FMLPaths.CONFIGDIR.get().resolve("modernfix-mixins.properties");
            List<String> lines = Files.exists(configFile)
                    ? Files.readAllLines(configFile, StandardCharsets.UTF_8)
                    : List.of();
            var linesOut = new ArrayList<>(lines);
            var active = false;
            for (int i = 0; i < linesOut.size(); i++) {
                var trimmed = linesOut.get(i).stripLeading();
                if (trimmed.startsWith(OPTION) && !trimmed.startsWith("#")) {
                    active = true;
                    if (!trimmed.equals(OPTION + "=false")) {
                        linesOut.set(i, OPTION + "=false");
                    }
                }
            }
            if (active && linesOut.equals(lines)) {
                return; // already disabled - leave the file (and the boot) untouched
            }
            if (!active) {
                linesOut.addAll(OVERRIDE);
            }
            Files.createDirectories(configFile.getParent());
            Files.write(configFile, linesOut, StandardCharsets.UTF_8);
            LOGGER.info("[NeoFobric] Disabled modernfix's attribute supplier dedup mixin - it shadows the field "
                    + "the platform keeps in its vanilla shape for Fabric's accessor");
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not adjust modernfix's mixin options; with modernfix installed the "
                    + "boot may fail on its attribute supplier mixin", t);
        }
    }

    private static boolean hasModernfix(Path modsDir) {
        if (!Files.isDirectory(modsDir)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (var jar : stream) {
                if (jar.getFileName().toString().toLowerCase(Locale.ROOT).contains("modernfix")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // An unreadable mods folder is not ours to report; the guard simply stands down.
        }
        return false;
    }

    private ModernfixMixinGuard() {
    }
}
