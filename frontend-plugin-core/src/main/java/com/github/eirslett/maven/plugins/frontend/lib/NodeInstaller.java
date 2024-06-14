package com.github.eirslett.maven.plugins.frontend.lib;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;

public class NodeInstaller {

    public static final String INSTALL_PATH = "/node";
    public static final String NODE_MODULES_PATH = "node_modules";
    public static final String VERSION_PROVIDED = "provided";

    private static final Object LOCK = new Object();
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeInstaller.class);
    private static final String EXCEPTION_COULD_NOT_INSTALL_NODE = "Could not install Node";
    private static final String EXCEPTION_COULD_NOT_DOWNLOAD_NODE_JS = "Could not download Node.js";
    private static final String EXCEPTION_COULD_NOT_EXTRACT_THE_NODE_ARCHIVE = "Could not extract the Node archive";
    public static final String NODE_WINDOWS_EXECUTABLE = "node.exe";

    private String npmVersion;
    private String nodeVersion;
    private String nodeDownloadRoot;
    private String userName;
    private String password;
    private String nodeDownloadHash;

    private final InstallConfig config;
    private final ArchiveExtractor archiveExtractor;
    private final FileDownloader fileDownloader;

    NodeInstaller(InstallConfig config, ArchiveExtractor archiveExtractor, FileDownloader fileDownloader) {
        this.config = config;
        this.archiveExtractor = archiveExtractor;
        this.fileDownloader = fileDownloader;
    }

    public NodeInstaller setNodeVersion(String nodeVersion) {
        this.nodeVersion = nodeVersion;
        return this;
    }

    public NodeInstaller setNodeDownloadRoot(String nodeDownloadRoot) {
        this.nodeDownloadRoot = nodeDownloadRoot;
        return this;
    }

    public NodeInstaller setNpmVersion(String npmVersion) {
        this.npmVersion = npmVersion;
        return this;
    }

    public NodeInstaller setUserName(String userName) {
        this.userName = userName;
        return this;
    }

    public NodeInstaller setPassword(String password) {
        this.password = password;
        return this;
    }

    public NodeInstaller setNodeDownloadHash(String hash) {
        this.nodeDownloadHash = hash;
        return this;
    }

    private boolean npmProvided() throws InstallationException {
        if (this.npmVersion != null) {
            if (VERSION_PROVIDED.equals(this.npmVersion)) {
                if (!VERSION_PROVIDED.equals(this.nodeVersion) && Integer.parseInt(this.nodeVersion.replace("v", "").split("[.]")[0]) < 4) {
                    throw new InstallationException("NPM version is '" + this.npmVersion
                        + "' but Node didn't include NPM prior to v4.0.0");
                }
                return true;
            }
            return false;
        }
        return false;
    }

    public void install() throws InstallationException {
        // use static lock object for a synchronized block
        synchronized (LOCK) {
            if (this.nodeDownloadRoot == null || this.nodeDownloadRoot.isEmpty()) {
                this.nodeDownloadRoot = this.config.getPlatform().getNodeDownloadRoot();
            }
            if (!nodeIsAlreadyInstalled()) {
                LOGGER.info("Installing node version {}", this.nodeVersion);
                if (!this.nodeVersion.startsWith("v")) {
                    LOGGER.warn("Node version does not start with naming convention 'v'.");
                }
                if(VERSION_PROVIDED.equals(this.nodeVersion)) {
                    installProvidedNode();
                } else if (this.config.getPlatform().isWindows()) {
                    startNodeInstallationForWindows();
                } else {
                    installNodeDefault();
                }

            }
        }
    }

    private void startNodeInstallationForWindows() throws InstallationException {
        if (npmProvided()) {
            installNodeWithNpmForWindows();
        } else {
            installNodeForWindows();
        }
    }

    private boolean nodeIsAlreadyInstalled() {
        try {
            NodeExecutorConfig executorConfig = new InstallNodeExecutorConfig(this.config);
            File nodeFile = executorConfig.getNodePath();
            if (nodeFile.exists()) {
                final String version =
                    new NodeExecutor(executorConfig, Collections.singletonList("--version"), null).executeAndGetResult(LOGGER);

                if (version.equals(this.nodeVersion)) {
                    LOGGER.info("Node {} is already installed.", version);
                    return true;
                } else {
                    LOGGER.info("Node {} was installed, but we need version {}", version,
                        this.nodeVersion);
                    return false;
                }
            } else {
                return false;
            }
        } catch (ProcessExecutionException e) {
            LOGGER.warn("Unable to determine current node version: {}", e.getMessage());
            return false;
        }
    }

    private void installNodeDefault() throws InstallationException {
        try {
            final String longNodeFilename =
                this.config.getPlatform().getLongNodeFilename(this.nodeVersion, false);
            String downloadUrl = this.nodeDownloadRoot
                + this.config.getPlatform().getNodeDownloadFilename(this.nodeVersion, false);

            startNodeInstallation(downloadUrl, longNodeFilename);

        } catch (IOException e) {
            throw new InstallationException(EXCEPTION_COULD_NOT_INSTALL_NODE, e);
        } catch (DownloadException | NoSuchAlgorithmException e) {
            throw new InstallationException(EXCEPTION_COULD_NOT_DOWNLOAD_NODE_JS, e);
        } catch (ArchiveExtractionException e) {
            throw new InstallationException(EXCEPTION_COULD_NOT_EXTRACT_THE_NODE_ARCHIVE, e);
        }
    }

    private void installProvidedNode() throws InstallationException {
        try {
            final String longNodeFilename =
                this.config.getPlatform().getLongNodeFilename(this.nodeVersion, false);
            String downloadUrl = this.nodeDownloadRoot;

            startNodeInstallation(downloadUrl, longNodeFilename);

        } catch (IOException e) {
            throw new InstallationException(EXCEPTION_COULD_NOT_INSTALL_NODE, e);
        } catch (DownloadException | NoSuchAlgorithmException e) {
            throw new InstallationException(EXCEPTION_COULD_NOT_DOWNLOAD_NODE_JS, e);
        } catch (ArchiveExtractionException e) {
            throw new InstallationException(EXCEPTION_COULD_NOT_EXTRACT_THE_NODE_ARCHIVE, e);
        }
    }

    private void startNodeInstallation(String downloadUrl, String longNodeFilename) throws DownloadException, IOException, NoSuchAlgorithmException, ArchiveExtractionException, InstallationException {
        String classifier = this.config.getPlatform().getNodeClassifier(this.nodeVersion);

        File tmpDirectory = getTempDirectory();

        CacheDescriptor cacheDescriptor = new CacheDescriptor("node", this.nodeVersion, classifier,
                this.config.getPlatform().getArchiveExtension());

        File archive = this.config.getCacheResolver().resolve(cacheDescriptor);

        downloadFileIfMissing(downloadUrl, archive, this.userName, this.password);

        try {
            extractFile(archive, tmpDirectory);
        } catch (ArchiveExtractionException e) {
            if (e.getCause() instanceof EOFException) {
                // https://github.com/eirslett/frontend-maven-plugin/issues/794
                // The downloading was probably interrupted and archive file is incomplete:
                // delete it to retry from scratch
                LOGGER.error("The archive file {} is corrupted and will be deleted. "
                        + "Please try the build again.", archive.getPath());
                Files.delete(archive.toPath());
                FileUtils.deleteDirectory(tmpDirectory);
            }

            throw e;
        }

        // Search for the node binary
        File nodeBinary = locateNodeBinary(tmpDirectory, longNodeFilename);

        File destinationDirectory = getInstallDirectory();

        File destination = new File(destinationDirectory, "node");
        LOGGER.info("Copying node binary from {} to {}", nodeBinary, destination);
        if (destination.exists() && !destination.delete()) {
            throw new InstallationException("Could not install Node: Was not allowed to delete " + destination);
        }
        try {
            Files.move(nodeBinary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new InstallationException("Could not install Node: Was not allowed to rename "
                    + nodeBinary + " to " + destination);
        }

        if (!destination.setExecutable(true, false)) {
            throw new InstallationException(
                    "Could not install Node: Was not allowed to make " + destination + " executable.");
        }

        if (npmProvided()) {
            File tmpNodeModulesDir = new File(tmpDirectory,
                    longNodeFilename + File.separator + "lib" + File.separator + NODE_MODULES_PATH);
            File nodeModulesDirectory = new File(destinationDirectory, NODE_MODULES_PATH);
            File npmDirectory = new File(nodeModulesDirectory, "npm");
            if(tmpNodeModulesDir.exists())
                FileUtils.copyDirectory(tmpNodeModulesDir, nodeModulesDirectory);

            LOGGER.info("Extracting NPM");
            // create a copy of the npm scripts next to the node executable
            for (String script : Arrays.asList("npm", "npm.cmd")) {
                File scriptFile = new File(npmDirectory, "bin" + File.separator + script);
                if (scriptFile.exists() && scriptFile.setExecutable(true)) {
                    LOGGER.debug("Enabled executable at {}", scriptFile.getAbsolutePath());
                }
            }
        }

        deleteTempDirectory(tmpDirectory);

        LOGGER.info("Installed node locally.");
    }

    private void verifyNodeDownloadHash(File archive) throws IOException, NoSuchAlgorithmException {
        if (null != this.nodeDownloadHash && !this.nodeDownloadHash.trim().isEmpty()) {
            byte[] b = Files.readAllBytes(archive.toPath());
            byte[] hashBytes = MessageDigest.getInstance("SHA-256").digest(b);
            String computedHash = DatatypeConverter.printHexBinary(hashBytes);

            if (!nodeDownloadHash.equalsIgnoreCase(computedHash)) {
                LOGGER.warn("SHA-256 hash does not match expected hash. Expected '{}', got '{}'",
                        this.nodeDownloadHash, computedHash);

                throw new IOException("SHA-256 hash does not match expected hash");
            }
        }
    }

    private static File locateNodeBinary(File tmpDirectory, String longNodeFilename) throws IOException {
        File nodeBinary =
            new File(tmpDirectory, longNodeFilename + File.separator + "bin" + File.separator + "node");
        if (!nodeBinary.exists()) {
            Optional<Path> path = Files.walk(tmpDirectory.toPath())
                    .filter(f -> {
                        File file = f.toFile();

                        return file.isFile()
                                && file.getName().equals("node");
                    })
                    .findFirst();

            if(path.isPresent()) {
                nodeBinary = path.get().toFile();
            } else {
                throw new FileNotFoundException(
                        "Could not find the downloaded Node.js binary in " + nodeBinary);
            }
        }
        return nodeBinary;
    }

    private void installNodeWithNpmForWindows() throws InstallationException {
        try {
            final String longNodeFilename =
                this.config.getPlatform().getLongNodeFilename(this.nodeVersion, true);
            String downloadUrl = this.nodeDownloadRoot
                + this.config.getPlatform().getNodeDownloadFilename(this.nodeVersion, true);
            String classifier = this.config.getPlatform().getNodeClassifier(this.nodeVersion);

            File tmpDirectory = getTempDirectory();

            CacheDescriptor cacheDescriptor = new CacheDescriptor("node", this.nodeVersion, classifier,
                this.config.getPlatform().getArchiveExtension());

            File archive = this.config.getCacheResolver().resolve(cacheDescriptor);

            downloadFileIfMissing(downloadUrl, archive, this.userName, this.password);

            extractFile(archive, tmpDirectory);

            // Search for the node binary
            File nodeBinary = new File(tmpDirectory, longNodeFilename + File.separator + NODE_WINDOWS_EXECUTABLE);
            if (!nodeBinary.exists()) {
                throw new FileNotFoundException(
                    "Could not find the downloaded Node.js binary in " + nodeBinary);
            } else {
                File destinationDirectory = getInstallDirectory();

                File destination = new File(destinationDirectory, NODE_WINDOWS_EXECUTABLE);
                LOGGER.info("Copying node binary from {} to {}", nodeBinary, destination);
                try {
                    Files.move(nodeBinary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new InstallationException("Could not install Node: Was not allowed to rename "
                        + nodeBinary + " to " + destination);
                }

                if (VERSION_PROVIDED.equals(this.npmVersion)) {
                    File tmpNodeModulesDir =
                        new File(tmpDirectory, longNodeFilename + File.separator + NODE_MODULES_PATH);
                    File nodeModulesDirectory = new File(destinationDirectory, NODE_MODULES_PATH);
                    FileUtils.copyDirectory(tmpNodeModulesDir, nodeModulesDirectory);
                }
                deleteTempDirectory(tmpDirectory);

                LOGGER.info("Installed node locally.");
            }
        } catch (IOException e) {
            throw new InstallationException(EXCEPTION_COULD_NOT_INSTALL_NODE, e);
        } catch (DownloadException | NoSuchAlgorithmException e) {
            throw new InstallationException(EXCEPTION_COULD_NOT_DOWNLOAD_NODE_JS, e);
        } catch (ArchiveExtractionException e) {
            throw new InstallationException(EXCEPTION_COULD_NOT_EXTRACT_THE_NODE_ARCHIVE, e);
        }

    }

    private void installNodeForWindows() throws InstallationException {
        final String downloadUrl = this.nodeDownloadRoot
            + this.config.getPlatform().getNodeDownloadFilename(this.nodeVersion, false);
        try {
            File destinationDirectory = getInstallDirectory();

            File destination = new File(destinationDirectory, NODE_WINDOWS_EXECUTABLE);

            String classifier = this.config.getPlatform().getNodeClassifier(this.nodeVersion);

            CacheDescriptor cacheDescriptor =
                new CacheDescriptor("node", this.nodeVersion, classifier, "exe");

            File binary = this.config.getCacheResolver().resolve(cacheDescriptor);

            downloadFileIfMissing(downloadUrl, binary, this.userName, this.password);

            LOGGER.info("Copying node binary from {} to {}", binary, destination);
            FileUtils.copyFile(binary, destination);

            LOGGER.info("Installed node locally.");
        } catch (DownloadException e) {
            throw new InstallationException("Could not download Node.js from: " + downloadUrl, e);
        } catch (IOException e) {
            throw new InstallationException("Could not install Node.js", e);
        }
    }

    private File getTempDirectory() {
        File tmpDirectory = new File(getInstallDirectory(), "tmp");
        if (!tmpDirectory.exists()) {
            LOGGER.debug("Creating temporary directory {}", tmpDirectory);
            tmpDirectory.mkdirs();
        }
        return tmpDirectory;
    }

    private File getInstallDirectory() {
        File installDirectory = new File(this.config.getInstallDirectory(), INSTALL_PATH);
        if (!installDirectory.exists()) {
            LOGGER.debug("Creating install directory {}", installDirectory);
            installDirectory.mkdirs();
        }
        return installDirectory;
    }

    private void deleteTempDirectory(File tmpDirectory) throws IOException {
        if (tmpDirectory != null && tmpDirectory.exists()) {
            LOGGER.debug("Deleting temporary directory {}", tmpDirectory);
            FileUtils.deleteDirectory(tmpDirectory);
        }
    }

    private void extractFile(File archive, File destinationDirectory) throws ArchiveExtractionException, IOException, NoSuchAlgorithmException {
        LOGGER.info("Unpacking {} into {}", archive, destinationDirectory);
        verifyNodeDownloadHash(archive);
        this.archiveExtractor.extract(archive.getPath(), destinationDirectory.getPath());
    }

    private void downloadFileIfMissing(String downloadUrl, File destination, String userName, String password)
        throws DownloadException {
        if (!destination.exists()) {
            downloadFile(downloadUrl, destination, userName, password);
        }
    }

    private void downloadFile(String downloadUrl, File destination, String userName, String password)
        throws DownloadException {
        LOGGER.info("Downloading {} to {}", downloadUrl, destination);
        this.fileDownloader.download(downloadUrl, destination.getPath(), userName, password);
    }
}
