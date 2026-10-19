package net.neoforged.fabricbridge.compbridlnk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Architectury's named compatibility helper.
 *
 * <p>Architectury's plugin injects its common code into a mod's jar and registers content from there;
 * the registrations arrive through the Fabric entrypoints, which the bridge runs after the platform's
 * first RegisterEvent pass. Jars built with that plugin are recognised here so discovery can register a
 * shell FML mod for them and the platform can re-post the RegisterEvent pass afterwards (see
 * {@code BridgeFabricModLocator} and {@code GameData.postRegisterEventsForShellMods}).
 */
public final class ArchitecturyCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/Compat/Architectury");

    private ArchitecturyCompat() {}

    /** {@return whether the jar was built with Architectury's plugin (its common jar injection, or the API itself)} */
    public static boolean usesArchitectury(Path jarPath) {
        try (var zip = new ZipFile(jarPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var name = entries.nextElement().getName();
                if (name.startsWith("dev/architectury/") || name.startsWith("architectury_inject")) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOGGER.debug("[NeoFobric] Could not scan {} for Architectury classes", jarPath, e);
        }
        return false;
    }
}
