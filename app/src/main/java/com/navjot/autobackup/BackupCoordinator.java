package com.navjot.autobackup;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import java.util.ArrayList;
import java.util.List;

/**
 * BackupCoordinator
 * ================
 * Orchestrates network scan, device selection, and file backup.
 * Unified for both manual and automatic backups.
 *
 * Handles: device whitelist, last device caching, folder list, user callbacks.
 */
public class BackupCoordinator {

    private static final String TAG = "BackupCoordinator";

    private final Context context;
    private final NetworkMonitor networkMonitor;
    private final DeviceManager deviceManager;
    private String username, password, domain, shareName, remoteDir;
    private List<Uri> backupFolderUris;

    /**
     * Callback interface for reporting status to UI or logs.
     */
    public interface BackupStatusCallback {
        void onStatus(String message);
    }

    /**
     * Constructor.
     * @param context      Context for operations
     * @param username     SMB username
     * @param password     SMB password
     * @param domain       SMB domain (optional, "" for workgroup)
     * @param shareName    SMB share name on PC
     * @param remoteDir    Directory on share
     * @param backupFolders List of folder URIs to back up (may be null)
     */
    public BackupCoordinator(Context context,
                             String username,
                             String password,
                             String domain,
                             String shareName,
                             String remoteDir,
                             List<Uri> backupFolders) {
        this.context = context.getApplicationContext();
        this.username = username;
        this.password = password;
        this.domain = domain;
        this.shareName = shareName;
        this.remoteDir = remoteDir;
        this.backupFolderUris = backupFolders != null ? backupFolders : new ArrayList<>();
        this.networkMonitor = new NetworkMonitor(context);
        this.deviceManager = new DeviceManager(context);
    }

    /**
     * Replace the backup folders list (user may have changed folder selections).
     */
    public void setBackupFolderUris(List<Uri> folders) {
        this.backupFolderUris = (folders != null) ? folders : new ArrayList<>();
    }

    /**
     * Triggers a backup process with device whitelist, user selection UI and all folder logic.
     * @param selectionCallback Used for UI-based device picking. Set to null for background-only flow.
     * @param statusCallback    Notifies status for UI/logs. Can be null for silent background operation.
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

        // Use cached last device from previous successful backup if reachable.
        DeviceManager.LastChosenDevice lastChosen = deviceManager.getLastChosenDevice();
        if (lastChosen != null && networkMonitor.isDeviceReachable(lastChosen)) {
            logStatus(statusCallback, "Using last chosen device " + lastChosen.ip);
            runBackup(lastChosen.ip, statusCallback);
            return;
        }

        // Else: run a network scan to find devices.
        logStatus(statusCallback, "Scanning subnet for devices...");
        networkMonitor.scanSubnetAsync(devices -> {
            List<NetworkMonitor.DeviceInfo> whitelisted = deviceManager.getWhitelistedDevices(devices);
            if (whitelisted.size() == 1) {
                deviceManager.cacheLastChosenDevice(whitelisted.get(0));
                runBackup(whitelisted.get(0).ip, statusCallback);
            } else {
                if (selectionCallback != null) {
                    // User must pick which device, or whitelist one.
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
            }
        });
    }

    /** Actually performs the file backup to a known good target IP. */
    private void runBackup(String ip, BackupStatusCallback statusCallback) {
        logStatus(statusCallback, "Starting backup to " + ip + "...");
        FileBackupManager fbm = new FileBackupManager(context, ip, shareName, username, password, domain, remoteDir);
        List<DocumentFile> files = fbm.getNewFilesToBackup(backupFolderUris);
        if (files.isEmpty()) {
            logStatus(statusCallback, "No new or changed files to backup.");
        } else {
            fbm.backupFiles(files);
            logStatus(statusCallback, "Backup complete to " + ip);
        }
    }

    /** Simple log+UI callback utility. */
    private void logStatus(BackupStatusCallback cb, String msg) {
        Log.i(TAG, msg);
        if (cb != null) cb.onStatus(msg);
    }

    /** Allows the UI to just scan for devices, not do backup (for available-devices listing) */
    public void scanDevices(NetworkMonitor.ScanCallback callback) {
        networkMonitor.scanSubnetAsync(callback);
    }
}
