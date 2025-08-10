package com.navjot.autobackup;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MainActivity
 * ============
 * - Handles user UI for folder selection, backup triggers, and device management.
 * - Lets user pick backup folder(s) using SAF.
 * - Starts manual backup to whitelisted device or lets user select.
 * - Shows available devices and allows whitelisting.
 * - Passes dynamic backup folder list to BackupCoordinator for both manual and auto backup.
 */
public class MainActivity extends AppCompatActivity {

    /** Preference file name for persistent storage */
    public static final String PREFS_NAME = "BackupPrefs";
    /** Preference key for folder URIs (single or comma-separated) */
    public static final String KEY_BACKUP_FOLDERS = "backup_folder_uris";

    private Button btnSelectFolder, btnAvailableDevices, btnBackup;
    private TextView txtResult;
    private ConstraintLayout layout;

    private BackupCoordinator coordinator;
    private DeviceManager deviceManager;

    /** SMB configuration (your credentials and share) */
    private final String SMB_USER = "yourUsername";
    private final String SMB_PASS = "yourPassword";
    private final String SMB_DOMAIN = "";
    private final String SMB_SHARE = "sharedfolder";
    private final String REMOTE_DIR = ""; // Relative path on share, blank for root

    private ActivityResultLauncher<Uri> folderPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this); // For modern immersive layouts
        setContentView(R.layout.activity_main);

        // Your XML should have the IDs as shown below
        btnSelectFolder = findViewById(R.id.btnSelectFolder);
        btnAvailableDevices = findViewById(R.id.btnAvailableDevices);
        btnBackup = findViewById(R.id.btnBackup);
        txtResult = findViewById(R.id.txtResult);
        layout = findViewById(R.id.main);

        deviceManager = new DeviceManager(this);
        coordinator = new BackupCoordinator(this, SMB_USER, SMB_PASS, SMB_DOMAIN, SMB_SHARE, REMOTE_DIR, getBackupFolderUris());

        ensurePermissions(); // Request runtime permissions if needed

        initBackupService();

        // SAF folder picker launcher
        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri != null) {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        saveBackupFolderUri(uri);
                        txtResult.setText("Backup folder saved: " + uri.getPath());
                    } else {
                        txtResult.setText("No folder selected.");
                    }
                }
        );

        btnSelectFolder.setOnClickListener(v -> selectBackupFolder());
        btnAvailableDevices.setOnClickListener(v -> showAvailableDevices());
        btnBackup.setOnClickListener(v -> manualBackup());
    }

    /** Launches the SAF folder picker dialog. */
    private void selectBackupFolder() {
        folderPickerLauncher.launch(null);
    }

    /**
     * Save selected backup folder URI in preferences.
     * (Currently supports a single folder; for multiple, use a Set and CSV or JSON as needed.)
     */
    private void saveBackupFolderUri(Uri uri) {
        Set<String> uris = new HashSet<>();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String oldUris = prefs.getString(KEY_BACKUP_FOLDERS, null);
        if (oldUris != null) {
            for (String old : oldUris.split(",")) uris.add(old);
        }
        uris.add(uri.toString());
        prefs.edit().putString(KEY_BACKUP_FOLDERS, String.join(",", uris)).apply();
    }

    /**
     * Loads user-selected SAF backup folder URIs from shared preferences.
     * @return List of folder URIs to scan for backups.
     */
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

    /**
     * Trigger a manual backup.
     * Shows warning if no folder selected.
     */
    private void manualBackup() {
        List<Uri> folders = getBackupFolderUris();
        if (folders.isEmpty()) {
            Toast.makeText(this, "Please select a backup folder first.", Toast.LENGTH_LONG).show();
            return;
        }
        coordinator.setBackupFolderUris(folders);
        coordinator.startBackup(
                deviceManagerDeviceSelectionCallback(),
                status -> runOnUiThread(() -> txtResult.setText(status))
        );
    }

    /**
     * Display a list of currently detected devices and offer to whitelist.
     */
    private void showAvailableDevices() {
        coordinator.setBackupFolderUris(getBackupFolderUris());
        coordinator.scanDevices(devices -> runOnUiThread(() -> {
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
        }));
    }

    /**
     * Callback to handle device selection (for both UI dialogs and background).
     */
    private DeviceManager.DeviceSelectionCallback deviceManagerDeviceSelectionCallback() {
        return (devices, done) -> runOnUiThread(() -> showDeviceSelectionDialog(devices, done));
    }

    /** Presents a dialog letting user choose one device from the provided list. */
    private void showDeviceSelectionDialog(List<NetworkMonitor.DeviceInfo> devices, DeviceManager.DeviceChosenCallback done) {
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

    /**
     * Start the background backup service and wire up device selection callback for UI.
     */
    private void initBackupService() {
        startService(new Intent(this, BackupService.class));
        BackupService.deviceSelectionCallback = deviceManagerDeviceSelectionCallback();
        txtResult.setText("Backup service started.");
    }

    /**
     * Requests dangerous permissions if not already granted.
     */
    private void ensurePermissions() {
        String[] perms = {
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_IMAGES
        };
        boolean needs = false;
        for (String p : perms) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needs = true; break;
            }
        }
        if (needs) ActivityCompat.requestPermissions(this, perms, 1001);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results){
        super.onRequestPermissionsResult(requestCode, perms, results);
        if(requestCode == 1001){
            boolean allGranted = true;
            for(int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            Toast.makeText(this,
                    allGranted ? "Permissions granted." : "Missing permissions, backup may fail.",
                    Toast.LENGTH_LONG).show();
        }
    }
}
