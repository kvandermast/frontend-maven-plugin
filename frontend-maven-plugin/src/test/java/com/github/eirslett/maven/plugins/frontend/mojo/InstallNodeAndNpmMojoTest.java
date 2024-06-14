package com.github.eirslett.maven.plugins.frontend.mojo;

import com.github.eirslett.maven.plugins.frontend.lib.FrontendPluginFactory;
import com.github.eirslett.maven.plugins.frontend.lib.InstallationException;
import com.github.eirslett.maven.plugins.frontend.lib.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallNodeAndNpmMojoTest {
    @Test
    void test01() throws InstallationException {
        File workingDirectory = new File(".");
        File installDirectory = new File(workingDirectory, "install");

        FrontendPluginFactory factory = new FrontendPluginFactory(workingDirectory, installDirectory);

        factory.getNodeInstaller(new ProxyConfig(Collections.emptyList()))
                .setNodeVersion("provided")
                .setNodeDownloadRoot("https://code.europa.eu/ecgalaxy/amazonlinux2-nodejs/-/package_files/8253/download")
                .setNpmVersion(null)
                .setNodeDownloadHash("03f102cf2e109a89f85831b22209a9ae27e70693c4da766787cd508b200bc87a")
                .install();

        assertTrue(Files.exists(Paths.get("./install/node")));
    }

}
