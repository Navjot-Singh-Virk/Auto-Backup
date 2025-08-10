package com.navjot.autobackup;

import android.Manifest;
import android.content.Intent;
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

    public static final String PREFS_NAME = "BackupPrefs";
    public static final String KEY_SMB_USER = "smb_user";
    public static final String KEY_SMB_PASS = "smb_pass";
    public static final String KEY_SMB_SHARE = "smb_share";
    public static final String KEY_SMB_DOMAIN = "smb_domain";
    public static final String KEY_REMOTE_DIR = "remote_dir";
    public static final String KEY_BACKUP_FOLDERS = "backup_folder_uris";
    public static final String KEY_BACKUP_FILE_FILTER = "backup_file_filter";

    private static final int REQUEST_PICK_FOLDER = 101;

    private ListView listFolders;
    private LinearLayout layoutScanProgress;
    private ProgressBar progressScan;
    private TextView txtScanStatus, txtResult;

    private ArrayList<Uri> backupFolders = new ArrayList<>();
    private ArrayAdapter<String> folderAdapter;

    private BackupCoordinator coordinator;
    private DeviceManager deviceManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        deviceManager = new DeviceManager(this);

        // Find views
        Button btnSelectFolder = findViewById(R.id.btnSelectFolder);
        Button btnRemoveFolder = findViewById(R.id.btnRemoveFolder);
        Button btnFileFilter = findViewById(R.id.btnFileFilter);
        Button btnCredentials = findViewById(R.id.btnCredentials);
        Button btnAvailableDevices = findViewById(R.id.btnAvailableDevices);
        Button btnBackup = findViewById(R.id.btnBackup);
        listFolders = findViewById(R.id.listFolders);
        layoutScanProgress = findViewById(R.id.layoutScanProgress);
        progressScan = findViewById(R.id.progressScan);
        txtScanStatus = findViewById(R.id.txtScanStatus);
        txtResult = findViewById(R.id.txtResult);

        // Load SMB preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        coordinator = new BackupCoordinator(
                this,
                prefs.getString(KEY_SMB_USER, ""),
                prefs.getString(KEY_SMB_PASS, ""),
                prefs.getString(KEY_SMB_DOMAIN, ""),
                prefs.getString(KEY_SMB_SHARE, ""),
                prefs.getString(KEY_REMOTE_DIR, ""),
                getBackupFolderUrisFromPrefs(),
                getFileFilterFromPrefs()
        );

        // Load and display folders in ListView
        loadBackupFolders();
        listFolders.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // Button actions
        btnSelectFolder.setOnClickListener(v -> onAddBackupFolder());
        btnRemoveFolder.setOnClickListener(v -> onRemoveSelectedFolder());
        btnFileFilter.setOnClickListener(v -> onConfigureFileTypes());
        btnCredentials.setOnClickListener(v -> onEditSMBCredentials());
        btnAvailableDevices.setOnClickListener(v -> showAvailableDevices());
        btnBackup.setOnClickListener(v -> manualBackup());

        // Permissions request
        ensurePermissions();
    }

    /** === Folder Handling === */

    private void loadBackupFolders() {
        backupFolders = new ArrayList<>(getBackupFolderUrisFromPrefs());
        refreshFolderListUI();
    }

    private void refreshFolderListUI() {
        List<String> labels = new ArrayList<>();
        for (Uri uri : backupFolders) {
            labels.add(uri.toString());
        }
        folderAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, labels);
        listFolders.setAdapter(folderAdapter);
    }

    private void onAddBackupFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_PICK_FOLDER);
    }

    private void onRemoveSelectedFolder() {
        int pos = listFolders.getCheckedItemPosition();
        if (pos != ListView.INVALID_POSITION && pos < backupFolders.size()) {
            backupFolders.remove(pos);
            saveBackupFoldersToPrefs();
            refreshFolderListUI();
            listFolders.clearChoices();
        }
    }

    private void saveBackupFoldersToPrefs() {
        StringBuilder sb = new StringBuilder();
        for (Uri u : backupFolders) {
            if (sb.length() > 0) sb.append(",");
            sb.append(u.toString());
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_BACKUP_FOLDERS, sb.toString())
                .apply();
    }

    private List<Uri> getBackupFolderUrisFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String urisString = prefs.getString(KEY_BACKUP_FOLDERS, "");
        List<Uri> uris = new ArrayList<>();
        if (!urisString.isEmpty()) {
            for (String s : urisString.split(",")) {
                try { uris.add(Uri.parse(s)); } catch (Exception ignored) {}
            }
        }
        return uris;
    }

    /** === File Filter Handling === */

    private void onConfigureFileTypes() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String existing = prefs.getString(KEY_BACKUP_FILE_FILTER, "");

        EditText input = new EditText(this);
        input.setText(existing);
        input.setHint("jpg,png,docx...");

        new AlertDialog.Builder(this)
                .setTitle("Set File Type Filter (comma separated)")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit().putString(KEY_BACKUP_FILE_FILTER,
                            input.getText().toString().trim().replaceAll("\\s+", "")).apply();
                    Toast.makeText(this, "File filter saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<String> getFileFilterFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String types = prefs.getString(KEY_BACKUP_FILE_FILTER, "");
        return types.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(types.split(",")));
    }

    /** === SMB Credentials Handling === */

    private void onEditSMBCredentials() {
        View dlgView = getLayoutInflater().inflate(R.layout.dialog_smb_credentials, null);

        EditText edtUser = dlgView.findViewById(R.id.edtUser);
        EditText edtPass = dlgView.findViewById(R.id.edtPass);
        EditText edtShare = dlgView.findViewById(R.id.edtShare);
        EditText edtDomain = dlgView.findViewById(R.id.edtDomain);
        EditText edtRemoteDir = dlgView.findViewById(R.id.edtRemoteDir);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        edtUser.setText(prefs.getString(KEY_SMB_USER, ""));
        edtPass.setText(prefs.getString(KEY_SMB_PASS, ""));
        edtShare.setText(prefs.getString(KEY_SMB_SHARE, ""));
        edtDomain.setText(prefs.getString(KEY_SMB_DOMAIN, ""));
        edtRemoteDir.setText(prefs.getString(KEY_REMOTE_DIR, ""));

        new AlertDialog.Builder(this)
                .setTitle("Edit SMB Credentials")
                .setView(dlgView)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit()
                            .putString(KEY_SMB_USER, edtUser.getText().toString())
                            .putString(KEY_SMB_PASS, edtPass.getText().toString())
                            .putString(KEY_SMB_SHARE, edtShare.getText().toString())
                            .putString(KEY_SMB_DOMAIN, edtDomain.getText().toString())
                            .putString(KEY_REMOTE_DIR, edtRemoteDir.getText().toString())
                            .apply();
                    Toast.makeText(this, "SMB credentials saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** === Device Scan & Backup === */

    private void showAvailableDevices() {
        layoutScanProgress.setVisibility(View.VISIBLE);
        coordinator.scanDevicesWithProgress(
                (status, cur, tot) -> runOnUiThread(() -> {
                    txtScanStatus.setText(status);
                    progressScan.setMax(tot);
                    progressScan.setProgress(cur);
                }),
                devices -> runOnUiThread(() -> {
                    layoutScanProgress.setVisibility(View.GONE);
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

    private void showDeviceSelectionDialog(List<NetworkMonitor.DeviceInfo> devices,
                                           DeviceManager.DeviceChosenCallback done) {
        String[] items = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            NetworkMonitor.DeviceInfo di = devices.get(i);
            items[i] = di.ip + " (" + di.mac + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Select Device")
                .setItems(items, (dialog, which) -> done.onDeviceChosen(devices.get(which)))
                .setNegativeButton("Cancel", (dialog, which) -> done.onDeviceChosen(null))
                .show();
    }

    private void manualBackup() {
        coordinator.setBackupFolderUris(new ArrayList<>(backupFolders));
        coordinator.setFileFilter(getFileFilterFromPrefs());
        coordinator.startBackup((devices, done) -> runOnUiThread(() -> showDeviceSelectionDialog(devices, done)),
                status -> runOnUiThread(() -> txtResult.setText(status)));
    }

    /** === Misc === */

    private void ensurePermissions() {
        String[] perms = {
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE
        };
        ActivityCompat.requestPermissions(this, perms, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null && !backupFolders.contains(uri)) {
                backupFolders.add(uri);
                saveBackupFoldersToPrefs();
                refreshFolderListUI();
            }
        }
    }
}
