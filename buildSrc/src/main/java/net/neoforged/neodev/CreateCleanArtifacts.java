package net.neoforged.neodev;

import javax.inject.Inject;
import net.neoforged.nfrtgradle.CreateMinecraftArtifacts;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;

abstract class CreateCleanArtifacts extends CreateMinecraftArtifacts {
    /**
     * The unmodified downloaded client jar.
     */
    @OutputFile
    abstract RegularFileProperty getRawClientJar();

    @OutputFile
    abstract RegularFileProperty getCleanClientJar();

    /**
     * The unmodified downloaded server jar.
     */
    @OutputFile
    abstract RegularFileProperty getRawServerJar();

    @OutputFile
    abstract RegularFileProperty getCleanServerJar();

    @OutputFile
    abstract RegularFileProperty getCleanJoinedJar();

    @Inject
    public CreateCleanArtifacts() {
        getAdditionalResults().put("node.downloadClient.output.output", getRawClientJar().getAsFile());
        getAdditionalResults().put("node.stripClient.output.output", getCleanClientJar().getAsFile());
        getAdditionalResults().put("node.downloadServer.output.output", getRawServerJar().getAsFile());
        getAdditionalResults().put("node.stripServer.output.output", getCleanServerJar().getAsFile());
        getAdditionalResults().put("vanillaDeobfuscated", getCleanJoinedJar().getAsFile());
    }
}
