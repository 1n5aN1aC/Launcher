package com.skcraft.launcher.builder;

import com.google.common.collect.Sets;
import com.skcraft.launcher.model.minecraft.*;
import com.skcraft.launcher.util.HttpRequest;
import lombok.extern.java.Log;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static com.skcraft.launcher.util.HttpRequest.url;

/**
 * Tool to mirror Minecraft assets and libraries from Microsoft's servers.
 * 
 * Usage: java -jar launcher-builder.jar mirror --versions 1.20.1,1.19.4 --output /path/to/mirror
 */
@Log
public class AssetMirror {

    private static final String VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final String ASSETS_BASE = "https://resources.download.minecraft.net/";
    
    private final File outputDir;
    private final Set<String> downloadedFiles = Sets.newConcurrentHashSet();
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    
    // Rate limiting
    private static final long DOWNLOAD_DELAY_MS = 50; // 50ms between downloads
    
    public AssetMirror(File outputDir) {
        this.outputDir = outputDir;
    }
    
    public void mirrorVersions(List<String> versions) throws Exception {
        log.info("Starting mirror of versions: " + versions);
        
        // Get version manifest
        ReleaseList releases = HttpRequest.get(url(VERSION_MANIFEST_URL))
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asJson(ReleaseList.class);
        
        // Mirror the version manifest itself
        mirrorVersionManifest(releases);
        
        // Process each requested version
        for (String versionId : versions) {
            Version version = releases.find(versionId);
            if (version == null) {
                log.warning("Version not found: " + versionId);
                continue;
            }
            
            log.info("Processing version: " + versionId);
            mirrorVersion(version);
        }
        
        // Wait for all downloads to complete
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
        
        log.info("Mirror complete. Downloaded " + downloadedFiles.size() + " unique files.");
    }
    
    private void mirrorVersion(Version version) throws Exception {
        // Download version manifest
        VersionManifest versionManifest = HttpRequest.get(url(version.getUrl()))
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asJson(VersionManifest.class);
        
        // Mirror the individual version JSON file
        mirrorVersionJson(version);
        
        // Mirror client JAR
        mirrorClientJar(versionManifest);
        
        // Mirror libraries
        mirrorLibraries(versionManifest);
        
        // Mirror assets
        mirrorAssets(versionManifest);
    }
    
    private void mirrorVersionManifest(ReleaseList releases) {
        File targetFile = new File(outputDir, "version_manifest.json");
        
        executor.submit(() -> {
            try {
                downloadFile(url(VERSION_MANIFEST_URL), targetFile, null);
                log.info("Downloaded version manifest: version_manifest.json");
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to download version manifest", e);
            }
        });
    }
    
    private void mirrorVersionJson(Version version) {
        // Extract filename from URL - typically in format like "a1b2c3d4.json" 
        String url = version.getUrl();
        String filename = version.getId() + ".json";
        
        File versionsDir = new File(outputDir, "versions");
        File targetFile = new File(versionsDir, filename);
        
        executor.submit(() -> {
            try {
                downloadFile(url(url), targetFile, null);
                log.info("Downloaded version JSON: " + filename);
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to download version JSON: " + filename, e);
            }
        });
    }
    
    private void mirrorClientJar(VersionManifest versionManifest) {
        VersionManifest.Artifact clientJar = versionManifest.getDownloads().get("client");
        if (clientJar != null) {
            String jarName = versionManifest.getId() + "-client.jar";
            File targetFile = new File(new File(outputDir, "versions"), jarName);
            
            executor.submit(() -> {
                try {
                    downloadFile(url(clientJar.getUrl()), targetFile, clientJar.getHash());
                    log.info("Downloaded client JAR: " + jarName);
                } catch (Exception e) {
                    log.log(Level.WARNING, "Failed to download client JAR: " + jarName, e);
                }
            });
        }
    }
    
    private void mirrorLibraries(VersionManifest versionManifest) {
        for (Library library : versionManifest.getLibraries()) {
            library.ensureDownloadsExist();
            
            for (Library.Artifact artifact : library.getDownloads().getAllArtifacts()) {
                if (artifact.getUrl() != null && artifact.getPath() != null) {
                    File targetFile = new File(new File(outputDir, "libraries"), artifact.getPath());
                    
                    executor.submit(() -> {
                        try {
                            downloadFile(url(artifact.getUrl()), targetFile, artifact.getSha1());
                            log.info("Downloaded library: " + artifact.getPath());
                        } catch (Exception e) {
                            log.log(Level.WARNING, "Failed to download library: " + artifact.getPath(), e);
                        }
                    });
                }
            }
        }
    }
    
    private void mirrorAssets(VersionManifest versionManifest) throws Exception {
        if (versionManifest.getAssetIndex() == null) {
            log.info("No asset index for version: " + versionManifest.getId());
            return;
        }
        
        // Download and save asset index
        String assetIndexContent = HttpRequest.get(url(versionManifest.getAssetIndex().getUrl()))
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asString("UTF-8");
        
        // Save asset index to indexes/ directory
        File indexesDir = new File(outputDir, "indexes");
        indexesDir.mkdirs();
        File assetIndexFile = new File(indexesDir, versionManifest.getAssetId() + ".json");
        
        executor.submit(() -> {
            try {
                Files.write(assetIndexFile.toPath(), assetIndexContent.getBytes("UTF-8"));
                log.info("Saved asset index: " + assetIndexFile.getName());
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to save asset index: " + assetIndexFile.getName(), e);
            }
        });
        
        // Parse asset index for downloading individual assets
        AssetsIndex assetIndex = HttpRequest.get(url(versionManifest.getAssetIndex().getUrl()))
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asJson(AssetsIndex.class);
        
        // Download all assets
        for (Map.Entry<String, Asset> entry : assetIndex.getObjects().entrySet()) {
            Asset asset = entry.getValue();
            String hash = asset.getHash();
            String assetPath = String.format("%s/%s", hash.substring(0, 2), hash);
            File targetFile = new File(new File(outputDir, "assets"), assetPath);
            
            executor.submit(() -> {
                try {
                    URL assetUrl = new URL(ASSETS_BASE + assetPath);
                    downloadFile(assetUrl, targetFile, hash);
                    log.info("Downloaded asset: " + entry.getKey() + " -> " + assetPath);
                } catch (Exception e) {
                    log.log(Level.WARNING, "Failed to download asset: " + entry.getKey(), e);
                }
            });
        }
    }
    
    private void downloadFile(URL sourceUrl, File targetFile, String expectedHash) throws Exception {
        String filePath = targetFile.getAbsolutePath();
        
        // Skip if already downloaded
        if (downloadedFiles.contains(filePath)) {
            return;
        }
        
        // Skip if file exists and hash matches
        if (targetFile.exists() && expectedHash != null) {
            try {
                String existingHash = com.skcraft.launcher.util.FileUtils.getShaHash(targetFile);
                if (expectedHash.equals(existingHash)) {
                    downloadedFiles.add(filePath);
                    return;
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to check hash for existing file: " + filePath, e);
            }
        }
        
        // Rate limiting
        Thread.sleep(DOWNLOAD_DELAY_MS);
        
        // Create parent directories
        targetFile.getParentFile().mkdirs();
        
        // Download file
        File tempFile = new File(targetFile.getAbsolutePath() + ".tmp");
        try {
            HttpRequest.get(sourceUrl)
                    .execute()
                    .expectResponseCode(200)
                    .saveContent(tempFile);
            
            // Verify hash if provided
            if (expectedHash != null) {
                String actualHash = com.skcraft.launcher.util.FileUtils.getShaHash(tempFile);
                if (!expectedHash.equals(actualHash)) {
                    throw new IOException("Hash mismatch for " + sourceUrl + 
                            ". Expected: " + expectedHash + ", Got: " + actualHash);
                }
            }
            
            // Move to final location
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            downloadedFiles.add(filePath);
            
        } finally {
            tempFile.delete();
        }
    }
    
    public static void main(String[] args) {
        if (args.length < 4 || !args[0].equals("mirror")) {
            System.err.println("Usage: java AssetMirror mirror --versions 1.20.1,1.19.4 --output /path/to/mirror");
            System.exit(1);
        }
        
        String versionsArg = null;
        String outputPath = null;
        
        for (int i = 1; i < args.length; i += 2) {
            if (args[i].equals("--versions") && i + 1 < args.length) {
                versionsArg = args[i + 1];
            } else if (args[i].equals("--output") && i + 1 < args.length) {
                outputPath = args[i + 1];
            }
        }
        
        if (versionsArg == null || outputPath == null) {
            System.err.println("Both --versions and --output are required");
            System.exit(1);
        }
        
        List<String> versions = Arrays.asList(versionsArg.split(","));
        File outputDir = new File(outputPath);
        
        try {
            AssetMirror mirror = new AssetMirror(outputDir);
            mirror.mirrorVersions(versions);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}