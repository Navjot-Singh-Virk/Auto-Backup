package com.navjot.autobackup;

import android.Manifest;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // ==== BEGIN CONSTANTS USABLE FROM OTHER CLASSES ====
    public static final String PREFS_NAME = "BackupPrefs";
    public static final String KEY_SMB_USER = "smb_user";
    public static final String KEY_SMB_PASS = "smb_pass";
    public static final String KEY_SMB_SHARE = "smb_share";
    public static final String KEY_SMB_DOMAIN = "smb_domain";
    public static final String KEY_REMOTE_DIR = "remote_dir";
    public static final String KEY_BACKUP_FOLDERS = "backup_folder_uris";
    public static final String KEY_BACKUP_FILE_FILTER = "backup_file_filter";
    // ==== END CONSTANTS ====

    private LinearLayout layoutScanProgress;
    private ProgressBar progressScan;
    private TextView txtScanStatus, txtResult;
    private Button btnAvailableDevices, btnBackup;

    // You may have more fields for folder selection, etc.
    private BackupCoordinator coordinator;
    private DeviceManager deviceManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        layoutScanProgress = findViewById(R.id.layoutScanProgress);
        progressScan = findViewById(R.id.progressScan);
        txtScanStatus = findViewById(R.id.txtScanStatus);
        txtResult = findViewById(R.id.txtResult);
        btnAvailableDevices = findViewById(R.id.btnAvailableDevices);
        btnBackup = findViewById(R.id.btnBackup);

        deviceManager = new DeviceManager(this);

        // Load SMB/user preferences for coordinator (can be updated from UI)
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String smbUser = prefs.getString(KEY_SMB_USER, "yourUsername");
        String smbPass = prefs.getString(KEY_SMB_PASS, "yourPassword");
        String smbShare = prefs.getString(KEY_SMB_SHARE, "sharedfolder");
        String smbDomain = prefs.getString(KEY_SMB_DOMAIN, "");
        String remoteDir = prefs.getString(KEY_REMOTE_DIR, "");

        coordinator = new BackupCoordinator(
                this,
                smbUser,
                smbPass,
                smbDomain,
                smbShare,
                remoteDir,
                getBackupFolderUris(),
                getFileFilter()
        );

        btnAvailableDevices.setOnClickListener(v -> showAvailableDevices());
        btnBackup.setOnClickListener(v -> manualBackup());

        ensurePermissions();
    }

    /** Show and update progress bar and status text. */
    private void showProgressUI(String msg, int current, int total) {
        runOnUiThread(() -> {
            txtScanStatus.setText(msg);
            progressScan.setMax(total);
            progressScan.setProgress(current);
            layoutScanProgress.setVisibility(View.VISIBLE);
        });
    }

    /** Hide the scan progress UI. */
    private void hideProgressUI() {
        runOnUiThread(() -> layoutScanProgress.setVisibility(View.GONE));
    }

    /** Device scan and UI for listing/choosing devices, with progress bar updates. */
    private void showAvailableDevices() {
        showProgressUI("Starting scan...", 0, 253);
        coordinator.scanDevicesWithProgress(
                (status, cur, tot) -> showProgressUI(status, cur, tot),
                devices -> runOnUiThread(() -> {
                    hideProgressUI();
                    if (devices.isEmpty()) {
                        txtResult.setText("No devices found.");
                        return;
                    }
                    List<NetworkMonitor.DeviceInfo> whitelisted = deviceManager.getWhitelistedDevices(devices);
                    if (whitelisted.size() == 1) {
                        txtResult.setText("One whitelisted device:\n" + whitelisted.get(0));
                    } else {
                        showDeviceSelectionDialog(devices, chosen -> {
                            if (chosen != null) {
                                deviceManager.addToWhitelist(chosen.mac);
                                txtResult.setText("Whitelisted:\n" + chosen);
                            }
                        });
                    }
                })
        );
    }

    /** Example manual backup trigger, can be customized further. */
    private void manualBackup() {
        List<Uri> folders = getBackupFolderUris();
        coordinator.setBackupFolderUris(folders);
        coordinator.setFileFilter(getFileFilter());
        coordinator.startBackup(deviceManagerDeviceSelectionCallback(),
                status -> runOnUiThread(() -> txtResult.setText(status))
        );
    }

    /** Device selection callback UI helper. */
    private DeviceManager.DeviceSelectionCallback deviceManagerDeviceSelectionCallback() {
        return (devices, done) -> runOnUiThread(() -> showDeviceSelectionDialog(devices, done));
    }

    private void showDeviceSelectionDialog(List<NetworkMonitor.DeviceInfo> devices,
                                           DeviceManager.DeviceChosenCallback done) {
        if (devices.isEmpty()) { done.onDeviceChosen(null); return; }
        String[] items = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            NetworkMonitor.DeviceInfo di = devices.get(i);
            items[i] = di.ip + " (" + di.mac + ")" + (deviceManager.isWhitelisted(di.mac) ? " [Whitelisted]" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Select Device")
                .setItems(items, (dialog, which) -> done.onDeviceChosen(devices.get(which)))
                .setNegativeButton("Cancel", (dialog, which) -> done.onDeviceChosen(null))
                .show();
    }

    /** Loads user-selected backup folder URIs from prefs. */
    private List<Uri> getBackupFolderUris() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String urisString = prefs.getString(KEY_BACKUP_FOLDERS, null);
        List<Uri> uris = new ArrayList<>();
        if (urisString != null && !urisString.trim().isEmpty()) {
            for (String s : urisString.split(",")) {
                try { uris.add(Uri.parse(s)); } catch (Throwable ignored) {}
            }
        }
        return uris;
    }

    /** Loads file type filter from prefs. */
    private List<String> getFileFilter() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String types = prefs.getString(KEY_BACKUP_FILE_FILTER, "");
        return types.isEmpty() ?
                new ArrayList<>() :
                new ArrayList<>(Arrays.asList(types.split(",")));
    }

    /** Request basic network permissions at runtime. */
    private void ensurePermissions() {
        String[] perms = {
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE
                // Add any others needed for folder access, etc.
        };
        ActivityCompat.requestPermissions(this, perms, 1);
    }
}
