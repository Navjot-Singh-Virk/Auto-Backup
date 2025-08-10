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
import java.util.Arrays;
import java.util.List;

/**
 * BackupService
 * =============
 * Runs periodic automatic backups in the background.
 */
public class BackupService extends Service {

    private static final String TAG = "BackupService";
    private static final long BACKUP_INTERVAL_MS = 30 * 60 * 1000L;
    public static DeviceManager.DeviceSelectionCallback deviceSelectionCallback;

    private final Handler handler = new Handler();
    private boolean isBackupRunning = false;
    private final Object backupLock = new Object();
    private BackupCoordinator coordinator;

    @Override
    public void onCreate() {
        super.onCreate();
        loadCoordinator();
        handler.post(backupTask);
    }

    /** Initialise BackupCoordinator from saved prefs. */
    private void loadCoordinator() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String user = prefs.getString(MainActivity.KEY_SMB_USER, "yourUsername");
        String pass = prefs.getString(MainActivity.KEY_SMB_PASS, "yourPassword");
        String share = prefs.getString(MainActivity.KEY_SMB_SHARE, "sharedfolder");
        String domain = prefs.getString(MainActivity.KEY_SMB_DOMAIN, "");
        String remoteDir = prefs.getString(MainActivity.KEY_REMOTE_DIR, "");

        coordinator = new BackupCoordinator(
                this,
                user,
                pass,
                domain,
                share,
                remoteDir,
                getBackupFolderUris(),
                getFileFilter()
        );
    }

    /** Load backup folder URIs from prefs. */
    private List<Uri> getBackupFolderUris() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String urisString = prefs.getString(MainActivity.KEY_BACKUP_FOLDERS, "");
        List<Uri> uris = new ArrayList<>();
        if (!urisString.isEmpty()) {
            for (String s : urisString.split(",")) {
                try { uris.add(Uri.parse(s)); } catch (Exception ignored) {}
            }
        }
        return uris;
    }

    /** Load file type filters from prefs. */
    private List<String> getFileFilter() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String types = prefs.getString(MainActivity.KEY_BACKUP_FILE_FILTER, "");
        return types.isEmpty() ?
                new ArrayList<>() :
                new ArrayList<>(Arrays.asList(types.split(",")));
    }

    /** Task runnable that triggers backup periodically. */
    private final Runnable backupTask = new Runnable() {
        @Override
        public void run() {
            if (!hasRequiredPermissions()) {
                Log.w(TAG, "Missing permissions. Skipping auto backup.");
            } else if (!getBackupFolderUris().isEmpty()) {
                startBackupIfIdle();
            }
            handler.postDelayed(this, BACKUP_INTERVAL_MS);
        }
    };

    /** Check if service has minimum permissions to run backup. */
    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.INTERNET) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_NETWORK_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_WIFI_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    /** Start backup run if no other backup is currently executing. */
    private void startBackupIfIdle() {
        synchronized (backupLock) {
            if (isBackupRunning) {
                Log.i(TAG, "Backup already running, skipping cycle.");
                return;
            }
            isBackupRunning = true;
        }

        loadCoordinator();

        coordinator.startBackup(deviceSelectionCallback, msg -> {
            Log.i(TAG, msg);
            // After backup is finished, clear the running flag
            synchronized (backupLock) {
                isBackupRunning = false;
            }
        });
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
