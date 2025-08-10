package com.navjot.autobackup;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import java.util.ArrayList;
import java.util.List;

/**
 * BackupCoordinator
 * =================
 * Orchestrates backup process:
 *  - Validates folders and connectivity.
 *  - Scans for devices using NetworkMonitor.
 *  - Handles whitelist/last-known device with DeviceManager.
 *  - Invokes FileBackupManager with file filtering.
 *  - Supports UI progress bar updates via scanDevicesWithProgress.
 */
public class BackupCoordinator {

    private static final String TAG = "BackupCoordinator";
    private final Context context;
    private final NetworkMonitor networkMonitor;
    private final DeviceManager deviceManager;
    private String username, password, domain, shareName, remoteDir;
    private List<Uri> backupFolderUris;
    private List<String> fileFilter;

    /** Callback for progress/status reporting to UI or logs. */
    public interface BackupStatusCallback {
        void onStatus(String message);
    }

    public BackupCoordinator(Context context,
                             String username,
                             String password,
                             String domain,
                             String shareName,
                             String remoteDir,
                             List<Uri> backupFolders,
                             List<String> fileFilter) {
        this.context = context.getApplicationContext();
        this.username = username;
        this.password = password;
        this.domain = domain;
        this.shareName = shareName;
        this.remoteDir = remoteDir;
        this.backupFolderUris = backupFolders != null ? backupFolders : new ArrayList<>();
        this.fileFilter = fileFilter != null ? fileFilter : new ArrayList<>();
        this.networkMonitor = new NetworkMonitor(context);
        this.deviceManager = new DeviceManager(context);
    }

    public void setBackupFolderUris(List<Uri> folders) {
        this.backupFolderUris = folders != null ? folders : new ArrayList<>();
    }

    public void setFileFilter(List<String> fileTypes) {
        this.fileFilter = fileTypes != null ? fileTypes : new ArrayList<>();
    }

    public void setSmbParams(String user, String pass, String dom, String share, String remoteDir) {
        this.username = user;
        this.password = pass;
        this.domain = dom;
        this.shareName = share;
        this.remoteDir = remoteDir;
    }

    /**
     * Start the backup process using whitelist, scan and selection callback as appropriate.
     */
    public void startBackup(DeviceManager.DeviceSelectionCallback selectionCallback,
                            BackupStatusCallback statusCallback) {
        if (backupFolderUris.isEmpty()) {
            logStatus(statusCallback, "No backup folders selected. Aborting backup.");
            return;
        }
        if (!(networkMonitor.isOnWifi() || networkMonitor.isHotspotOn())) {
            logStatus(statusCallback, "Not connected to Wi-Fi or hotspot. Skipping backup.");
            return;
        }

        // Try last chosen device first
        DeviceManager.LastChosenDevice lastChosen = deviceManager.getLastChosenDevice();
        if (lastChosen != null && networkMonitor.isDeviceReachable(lastChosen)) {
            logStatus(statusCallback, "Using last chosen device " + lastChosen.ip);
            runBackup(lastChosen.ip, statusCallback);
            return;
        }

        // Otherwise scan for devices (no progress UI here)
        logStatus(statusCallback, "Scanning subnet for devices...");
        networkMonitor.scanSubnetAsync(devices -> {
            List<NetworkMonitor.DeviceInfo> whitelisted = deviceManager.getWhitelistedDevices(devices);
            if (whitelisted.size() == 1) {
                deviceManager.cacheLastChosenDevice(whitelisted.get(0));
                runBackup(whitelisted.get(0).ip, statusCallback);
            } else if (selectionCallback != null) {
                selectionCallback.onSelectDevice(devices, chosen -> {
                    if (chosen != null) {
                        deviceManager.addToWhitelist(chosen.mac);
                        deviceManager.cacheLastChosenDevice(chosen);
                        runBackup(chosen.ip, statusCallback);
                    } else {
                        logStatus(statusCallback, "Backup canceled: no device selected.");
                    }
                });
            } else {
                logStatus(statusCallback, "Multiple/no whitelisted devices & no UI; skipping backup.");
            }
        });
    }

    /**
     * Internal method to perform file backup to specified device IP.
     */
    private void runBackup(String ip, BackupStatusCallback statusCallback) {
        logStatus(statusCallback, "Starting backup to " + ip + "...");
        FileBackupManager fbm = new FileBackupManager(context, ip, shareName, username, password, domain, remoteDir);
        List<DocumentFile> files = fbm.getNewFilesToBackup(backupFolderUris, fileFilter);
        if (files.isEmpty()) {
            logStatus(statusCallback, "No new/changed files to backup.");
        } else {
            int successCount = fbm.backupFiles(files);
            logStatus(statusCallback, "Backup complete to " + ip +
                    " (" + successCount + "/" + files.size() + " files uploaded)");
            BackupNotifier.notifyResult(context, successCount, files.size(), ip);
        }
    }

    private void logStatus(BackupStatusCallback cb, String msg) {
        Log.i(TAG, msg);
        if (cb != null) cb.onStatus(msg);
    }

    /** Original scan method (no progress reporting). */
    public void scanDevices(NetworkMonitor.ScanCallback callback) {
        networkMonitor.scanSubnetAsync(callback);
    }

    /**
     * New: Scan method with progress reporting.
     * Pass in a NetworkMonitor.ScanProgressCallback to update a UI ProgressBar.
     */
    public void scanDevicesWithProgress(NetworkMonitor.ScanProgressCallback progressCb,
                                        NetworkMonitor.ScanCallback callback) {
        networkMonitor.scanSubnetAsync(progressCb, callback);
    }
}
