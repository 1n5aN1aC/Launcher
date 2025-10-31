/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skcraft.concurrency.DefaultProgress;
import com.skcraft.concurrency.ProgressFilter;
import com.skcraft.concurrency.ProgressObservable;
import com.skcraft.launcher.Instance;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.LauncherException;
import com.skcraft.launcher.install.Installer;
import com.skcraft.launcher.model.minecraft.ReleaseList;
import com.skcraft.launcher.model.minecraft.Version;
import com.skcraft.launcher.model.minecraft.VersionManifest;
import com.skcraft.launcher.model.modpack.Manifest;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.util.HttpRequest;
import com.skcraft.launcher.util.SharedLocale;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.java.Log;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

import static com.skcraft.launcher.util.HttpRequest.url;

@Log
public class Updater extends BaseUpdater implements Callable<Instance>, ProgressObservable {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Installer installer;
    private final Launcher launcher;
    private final Instance instance;

    @Getter @Setter
    private boolean online;

    private List<URL> librarySources = new ArrayList<URL>();
    private List<URL> assetsSources = new ArrayList<URL>();

    private ProgressObservable progress = new DefaultProgress(-1, SharedLocale.tr("instanceUpdater.preparingUpdate"));

    public Updater(@NonNull Launcher launcher, @NonNull Instance instance) {
        super(launcher);

        this.installer = new Installer(launcher.getInstallerDir());
        this.launcher = launcher;
        this.instance = instance;

        // Check if custom sources should be tried first
        boolean customFirst = "true".equalsIgnoreCase(
            launcher.getProperties().getProperty("customSourcesFirst", "false"));

        String customLibrariesUrl = launcher.getProperties().getProperty("customLibrariesSource");
        String customAssetsUrl = launcher.getProperties().getProperty("customAssetsSource");

        if (customFirst) {
            // Custom sources first, Microsoft as fallback
            if (customLibrariesUrl != null && !customLibrariesUrl.trim().isEmpty()) {
                librarySources.add(HttpRequest.url(customLibrariesUrl.trim()));
                log.info("Added custom libraries source (primary): " + customLibrariesUrl);
            }
            librarySources.add(launcher.propUrl("librariesSource"));

            if (customAssetsUrl != null && !customAssetsUrl.trim().isEmpty()) {
                assetsSources.add(HttpRequest.url(customAssetsUrl.trim()));
                log.info("Added custom assets source (primary): " + customAssetsUrl);
            }
            assetsSources.add(launcher.propUrl("assetsSource"));
        } else {
            // Microsoft first, custom sources as fallback (recommended)
            librarySources.add(launcher.propUrl("librariesSource"));
            if (customLibrariesUrl != null && !customLibrariesUrl.trim().isEmpty()) {
                librarySources.add(HttpRequest.url(customLibrariesUrl.trim()));
                log.info("Added custom libraries fallback source: " + customLibrariesUrl);
            }

            assetsSources.add(launcher.propUrl("assetsSource"));
            if (customAssetsUrl != null && !customAssetsUrl.trim().isEmpty()) {
                assetsSources.add(HttpRequest.url(customAssetsUrl.trim()));
                log.info("Added custom assets fallback source: " + customAssetsUrl);
            }
        }
    }

    @Override
    public Instance call() throws Exception {
        log.info("Checking for an update for '" + instance.getName() + "'...");

        // Force the directory to be created
        instance.getContentDir();

        boolean updateRequired = !instance.isInstalled();
        boolean updateDesired = (instance.isUpdatePending() || updateRequired);
        boolean updateCapable = (instance.getManifestURL() != null);

        if (!online && updateRequired) {
            log.info("Can't update " + instance.getTitle() + " because offline");
            String message = SharedLocale.tr("updater.updateRequiredButOffline");
            throw new LauncherException("Update required but currently offline", message);
        }

        if (updateDesired && !updateCapable) {
            if (updateRequired) {
                log.info("Update required for " + instance.getTitle() + " but there is no manifest");
                String message = SharedLocale.tr("updater.updateRequiredButNoManifest");
                throw new LauncherException("Update required but no manifest", message);
            } else {
                log.info("Can't update " + instance.getTitle() + ", but update is not required");
                return instance; // Can't update
            }
        }

        if (updateDesired) {
            log.info("Updating " + instance.getTitle() + "...");
            update(instance);
        } else {
            log.info("No update found for " + instance.getTitle());
        }

        return instance;
    }

    /**
     * Check whether the package manifest contains an embedded version manifest,
     * otherwise we'll have to download the one for the given Minecraft version.
     *
     * BACKWARDS COMPATIBILITY:
     * Old manifests have an embedded version manifest without the minecraft JARs list present.
     * If we find a manifest without that jar list, fetch the newer copy from launchermeta and use the list from that.
     * We can't just replace the manifest outright because library versions might differ and that screws up Runner.
     */
    private VersionManifest readVersionManifest(Manifest manifest) throws IOException, InterruptedException {
        VersionManifest version = manifest.getVersionManifest();
        
        // Build version manifest URL list respecting customSourcesFirst setting
        List<URL> versionManifestUrls = new ArrayList<URL>();
        String customVersionManifestUrl = launcher.getProperties().getProperty("customVersionManifestUrl");
        boolean customFirst = "true".equalsIgnoreCase(
            launcher.getProperties().getProperty("customSourcesFirst", "false"));
        
        if (customFirst && customVersionManifestUrl != null && !customVersionManifestUrl.trim().isEmpty()) {
            // Custom source first, Microsoft as fallback
            try {
                versionManifestUrls.add(url(customVersionManifestUrl.trim()));
                log.info("Added custom version manifest source (primary): " + customVersionManifestUrl);
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to parse custom version manifest URL", e);
            }
            versionManifestUrls.add(url(launcher.getProperties().getProperty("versionManifestUrl")));
        } else {
            // Microsoft first, custom source as fallback
            versionManifestUrls.add(url(launcher.getProperties().getProperty("versionManifestUrl")));
            if (customVersionManifestUrl != null && !customVersionManifestUrl.trim().isEmpty()) {
                try {
                    versionManifestUrls.add(url(customVersionManifestUrl.trim()));
                    log.info("Added custom version manifest fallback source: " + customVersionManifestUrl);
                } catch (Exception e) {
                    log.log(Level.WARNING, "Failed to parse custom version manifest URL", e);
                }
            }
        }

        if (version == null) {
            version = fetchVersionManifest(versionManifestUrls, manifest, launcher);
        }

        if (version.getDownloads().isEmpty()) {
            // Backwards compatibility hack
            VersionManifest otherManifest = fetchVersionManifest(versionManifestUrls, manifest, launcher);

            version.setDownloads(otherManifest.getDownloads());
            version.setAssetIndex(otherManifest.getAssetIndex());
        }

        mapper.writeValue(instance.getVersionPath(), version);
        return version;
    }

    private static VersionManifest fetchVersionManifest(URL url, Manifest manifest, Launcher launcher) throws IOException, InterruptedException {
        ReleaseList releases = HttpRequest.get(url)
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asJson(ReleaseList.class);

        Version relVersion = releases.find(manifest.getGameVersion());
        
        // Build version JSON URLs respecting customSourcesFirst setting
        List<URL> versionJsonUrls = new ArrayList<URL>();
        String customVersionsUrl = launcher.getProperties().getProperty("customVersionsSource");
        boolean customFirst = "true".equalsIgnoreCase(
            launcher.getProperties().getProperty("customSourcesFirst", "false"));
        
        if (customFirst && customVersionsUrl != null && !customVersionsUrl.trim().isEmpty()) {
            // Custom source first, Microsoft as fallback
            try {
                String versionJsonName = manifest.getGameVersion() + ".json";
                URL customVersionJsonUrl = new URL(customVersionsUrl.trim() + versionJsonName);
                versionJsonUrls.add(customVersionJsonUrl);
                log.info("Added custom version JSON source (primary): " + customVersionJsonUrl);
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to construct custom version JSON URL", e);
            }
            versionJsonUrls.add(url(relVersion.getUrl()));
        } else {
            // Microsoft first, custom source as fallback
            versionJsonUrls.add(url(relVersion.getUrl()));
            if (customVersionsUrl != null && !customVersionsUrl.trim().isEmpty()) {
                try {
                    String versionJsonName = manifest.getGameVersion() + ".json";
                    URL customVersionJsonUrl = new URL(customVersionsUrl.trim() + versionJsonName);
                    versionJsonUrls.add(customVersionJsonUrl);
                    log.info("Added custom version JSON fallback source: " + customVersionJsonUrl);
                } catch (Exception e) {
                    log.log(Level.WARNING, "Failed to construct custom version JSON URL", e);
                }
            }
        }
        
        return fetchVersionManifest(versionJsonUrls, manifest, launcher);
    }

    private List<URL> buildAssetIndexUrls(VersionManifest version) {
        List<URL> assetIndexUrls = new ArrayList<URL>();
        
        // Check if version has an asset index
        if (version.getAssetIndex() == null) {
            log.info("No asset index for version: " + version.getId());
            return assetIndexUrls; // Return empty list for legacy versions
        }
        
        String customAssetIndexesUrl = launcher.getProperties().getProperty("customAssetIndexesSource");
        boolean customFirst = "true".equalsIgnoreCase(
            launcher.getProperties().getProperty("customSourcesFirst", "false"));
        
        if (customFirst && customAssetIndexesUrl != null && !customAssetIndexesUrl.trim().isEmpty()) {
            // Custom source first, Microsoft as fallback
            try {
                String assetIndexName = version.getAssetId() + ".json";
                URL customAssetIndexUrl = new URL(customAssetIndexesUrl.trim() + assetIndexName);
                assetIndexUrls.add(customAssetIndexUrl);
                log.info("Added custom asset index source (primary): " + customAssetIndexUrl);
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to construct custom asset index URL", e);
            }
            assetIndexUrls.add(url(version.getAssetIndex().getUrl()));
        } else {
            // Microsoft first, custom source as fallback
            assetIndexUrls.add(url(version.getAssetIndex().getUrl()));
            if (customAssetIndexesUrl != null && !customAssetIndexesUrl.trim().isEmpty()) {
                try {
                    String assetIndexName = version.getAssetId() + ".json";
                    URL customAssetIndexUrl = new URL(customAssetIndexesUrl.trim() + assetIndexName);
                    assetIndexUrls.add(customAssetIndexUrl);
                    log.info("Added custom asset index fallback source: " + customAssetIndexUrl);
                } catch (Exception e) {
                    log.log(Level.WARNING, "Failed to construct custom asset index URL", e);
                }
            }
        }
        
        return assetIndexUrls;
    }

    private static VersionManifest fetchVersionManifest(List<URL> urls, Manifest manifest, Launcher launcher) throws IOException, InterruptedException {
        IOException lastException = null;
        
        for (URL url : urls) {
            try {
                log.info("Trying version manifest URL: " + url);
                return fetchVersionManifest(url, manifest, launcher);
            } catch (IOException e) {
                lastException = e;
                log.log(Level.WARNING, "Failed to fetch version manifest from " + url + ", trying next source", e);
            }
        }
        
        // If all sources failed, throw the last exception
        if (lastException != null) {
            throw lastException;
        } else {
            throw new IOException("No version manifest URLs provided");
        }
    }

    /**
     * Update the given instance.
     *
     * @param instance the instance
     * @throws IOException thrown on I/O error
     * @throws InterruptedException thrown on interruption
     * @throws ExecutionException thrown on execution error
     */
    protected void update(Instance instance) throws Exception {
        // Mark this instance as local
        instance.setLocal(true);
        Persistence.commitAndForget(instance);

        // Read manifest
        log.info("Reading package manifest...");
        progress = new DefaultProgress(-1, SharedLocale.tr("instanceUpdater.readingManifest"));
        Manifest manifest = installPackage(installer, instance);

        // Update instance from manifest
        manifest.update(instance);

        // Read version manifest
        log.info("Reading version manifest...");
        progress = new DefaultProgress(-1, SharedLocale.tr("instanceUpdater.readingVersion"));
        VersionManifest version = readVersionManifest(manifest);

        progress = new DefaultProgress(-1, SharedLocale.tr("instanceUpdater.buildingDownloadList"));

        // Install the .jar
        File jarPath = launcher.getJarPath(version);
        VersionManifest.Artifact clientJar = version.getDownloads().get("client");
        URL originalJarSource = url(clientJar.getUrl());
        
        // Build JAR source list respecting customSourcesFirst setting
        List<URL> jarSources = new ArrayList<URL>();
        String customVersionsUrl = launcher.getProperties().getProperty("customVersionsSource");
        boolean customFirst = "true".equalsIgnoreCase(
            launcher.getProperties().getProperty("customSourcesFirst", "false"));
        
        if (customFirst && customVersionsUrl != null && !customVersionsUrl.trim().isEmpty()) {
            // Custom source first, Microsoft as fallback
            try {
                String jarFileName = version.getId() + "-client.jar";
                URL customJarUrl = new URL(customVersionsUrl.trim() + jarFileName);
                jarSources.add(customJarUrl);
                log.info("Added custom JAR source (primary): " + customJarUrl);
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to construct custom JAR URL", e);
            }
            jarSources.add(originalJarSource);
        } else {
            // Microsoft first, custom source as fallback
            jarSources.add(originalJarSource);
            if (customVersionsUrl != null && !customVersionsUrl.trim().isEmpty()) {
                try {
                    String jarFileName = version.getId() + "-client.jar";
                    URL customJarUrl = new URL(customVersionsUrl.trim() + jarFileName);
                    jarSources.add(customJarUrl);
                    log.info("Added custom JAR fallback source: " + customJarUrl);
                } catch (Exception e) {
                    log.log(Level.WARNING, "Failed to construct custom JAR URL", e);
                }
            }
        }
        
        log.info("JAR at " + jarPath.getAbsolutePath() + ", sources: " + jarSources);
        installJar(installer, clientJar, jarPath, jarSources.get(0), jarSources.size() > 1 ? jarSources.subList(1, jarSources.size()) : null);

        // Download libraries
        log.info("Enumerating libraries to download...");

        URL url = manifest.getLibrariesUrl();
        if (url != null) {
            log.info("Added library source: " + url);
            librarySources.add(0, url);
        }

        progress = new DefaultProgress(-1, SharedLocale.tr("instanceUpdater.collectingLibraries"));
        installLibraries(installer, manifest, launcher.getLibrariesDir(), librarySources);

        // Download assets
        log.info("Enumerating assets to download...");
        progress = new DefaultProgress(-1, SharedLocale.tr("instanceUpdater.collectingAssets"));
        
        // Build asset index URLs with custom source fallback
        List<URL> assetIndexUrls = buildAssetIndexUrls(version);
        installAssets(installer, version, assetIndexUrls, assetsSources);

        log.info("Executing download phase...");
        progress = ProgressFilter.between(installer.getDownloader(), 0, 0.98);
        installer.download();

        log.info("Executing install phase...");
        progress = ProgressFilter.between(installer, 0.98, 1);
        installer.execute(launcher);

        installer.executeLate(launcher);

        log.info("Completing...");
        complete();

        // Update the instance's information
        log.info("Writing instance information...");
        instance.setVersion(manifest.getVersion());
        instance.setUpdatePending(false);
        instance.setInstalled(true);
        instance.setLocal(true);
        Persistence.commitAndForget(instance);

        log.log(Level.INFO, instance.getName() +
                " has been updated to version " + manifest.getVersion() + ".");
    }

    @Override
    public double getProgress() {
        return progress.getProgress();
    }

    @Override
    public String getStatus() {
        return progress.getStatus();
    }


}
