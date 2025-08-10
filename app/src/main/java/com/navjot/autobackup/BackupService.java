package com.navjot.autobackup;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * BackupService
 * ============
 * Background Android Service.
 * - Triggers automatic backups on a timer (30 min)
 * - Loads user-selected folders from shared prefs and runs via BackupCoordinator
 * - Avoids running more than one backup at a time
 */
public class BackupService extends Service {

    private static final String TAG = "BackupService";
    private static final long BACKUP_INTERVAL_MS = 30 * 60 * 1000L; // 30 minutes
    public static DeviceManager.DeviceSelectionCallback deviceSelectionCallback;

    private final Handler handler = new Handler();
    private final Object backupLock = new Object();
    private boolean isBackupRunning = false;

    private BackupCoordinator coordinator;

    // SMB shares and credentials — comes from your app/settings
    private final String SMB_USER = "yourUsername";
    private final String SMB_PASS = "yourPassword";
    private final String SMB_DOMAIN = "";
    private final String SMB_SHARE = "sharedfolder";
    private final String REMOTE_DIR = "";

    /** Loads all user-selected folder URIs from prefs for backup. */
    private List<Uri> getBackupFolderUris() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String urisString = prefs.getString(MainActivity.KEY_BACKUP_FOLDERS, "");
        List<Uri> uris = new ArrayList<>();
        if (!urisString.isEmpty()) {
            for (String s : urisString.split(",")) {
                try { uris.add(Uri.parse(s)); } catch (Throwable ignored) {}
            }
        }
        return uris;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        coordinator = new BackupCoordinator(this, SMB_USER, SMB_PASS, SMB_DOMAIN, SMB_SHARE, REMOTE_DIR, getBackupFolderUris());
        handler.post(backupTask);
    }

    private final Runnable backupTask = new Runnable() {
        @Override
        public void run() {
            if (!hasRequiredPermissions()) {
                Log.w(TAG, "Missing permissions. Skipping automatic backup.");
            } else {
                startBackupIfIdle();
            }
            handler.postDelayed(this, BACKUP_INTERVAL_MS);
        }
    };

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.INTERNET) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_NETWORK_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_WIFI_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    private void startBackupIfIdle() {
        synchronized (backupLock) {
            if (isBackupRunning) {
                Log.i(TAG,"Backup already running, skipping this cycle.");
                return;
            }
            isBackupRunning = true;
        }
        coordinator.setBackupFolderUris(getBackupFolderUris());
        coordinator.startBackup(deviceSelectionCallback, msg -> Log.i(TAG, msg));
        synchronized (backupLock) {
            isBackupRunning = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(backupTask);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
