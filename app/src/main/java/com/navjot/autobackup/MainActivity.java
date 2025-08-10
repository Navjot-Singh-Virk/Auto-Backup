package com.navjot.autobackup;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.documentfile.provider.DocumentFile;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "BackupPrefs";
    public static final String KEY_BACKUP_FOLDERS = "backup_folder_uris";
    public static final String KEY_BACKUP_FILE_FILTER = "backup_file_filter";
    public static final String KEY_SMB_USER = "smb_user";
    public static final String KEY_SMB_PASS = "smb_pass";
    public static final String KEY_SMB_SHARE = "smb_share";
    public static final String KEY_SMB_DOMAIN = "smb_domain";
    public static final String KEY_REMOTE_DIR = "remote_dir";

    private Button btnSelectFolder, btnAvailableDevices, btnBackup, btnRemoveFolder, btnFileFilter, btnCredentials;
    private TextView txtResult;
    private ListView listFolders;

    private BackupCoordinator coordinator;
    private DeviceManager deviceManager;
    private ArrayAdapter<String> folderAdapter;
    private List<Uri> currentFolders = new ArrayList<>();

    private ActivityResultLauncher<Uri> folderPickerLauncher;

    // SMB values loaded at runtime
    private String smbUser, smbPass, smbShare, smbDomain, remoteDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        btnSelectFolder = findViewById(R.id.btnSelectFolder);
        btnAvailableDevices = findViewById(R.id.btnAvailableDevices);
        btnBackup = findViewById(R.id.btnBackup);
        btnRemoveFolder = findViewById(R.id.btnRemoveFolder);
        btnFileFilter = findViewById(R.id.btnFileFilter);
        btnCredentials = findViewById(R.id.btnCredentials);
        listFolders = findViewById(R.id.listFolders);
        txtResult = findViewById(R.id.txtResult);

        deviceManager = new DeviceManager(this);

        loadSmbPrefs();
        coordinator = new BackupCoordinator(this, smbUser, smbPass, smbDomain, smbShare, remoteDir, getBackupFolderUris(), getFileFilter());

        ensurePermissions();
        refreshFolderList();

        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri != null) {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        saveBackupFolderUri(uri);
                        txtResult.setText("Backup folder added: " + uri.getPath());
                        refreshFolderList();
                        coordinator.setBackupFolderUris(getBackupFolderUris());
                    } else {
                        txtResult.setText("No folder selected.");
                    }
                });

        btnSelectFolder.setOnClickListener(v -> folderPickerLauncher.launch(null));
        btnAvailableDevices.setOnClickListener(v -> showAvailableDevices());
        btnBackup.setOnClickListener(v -> manualBackup());
        btnRemoveFolder.setOnClickListener(v -> promptRemoveFolder());
        btnFileFilter.setOnClickListener(v -> promptFileFilter());
        btnCredentials.setOnClickListener(v -> promptCredentials());

        listFolders.setOnItemClickListener((parent, view, pos, id) -> {
            // Long-click disables
        });

        // Device selection callback ensures proper whitelisting.
        BackupService.deviceSelectionCallback = deviceManagerDeviceSelectionCallback();

        txtResult.setText("Backup service ready. Folders selected: " + currentFolders.size());
    }

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
        btnBackup.setEnabled(!needs);
        if (needs) ActivityCompat.requestPermissions(this, perms, 1001);
    }

    /* Load SMB creds and backup settings from prefs (or defaults) */
    private void loadSmbPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        smbUser = prefs.getString(KEY_SMB_USER, "yourUsername");
        smbPass = prefs.getString(KEY_SMB_PASS, "yourPassword");
        smbShare = prefs.getString(KEY_SMB_SHARE, "sharedfolder");
        smbDomain = prefs.getString(KEY_SMB_DOMAIN, "");
        remoteDir = prefs.getString(KEY_REMOTE_DIR, "");
    }

    private void promptCredentials() {
        // Demo dialog; replace with secured entry or Keystore logic
        View form = getLayoutInflater().inflate(R.layout.dialog_smb_credentials, null, false);
        EditText editUser = form.findViewById(R.id.editSmbUser);
        EditText editPass = form.findViewById(R.id.editSmbPass);
        EditText editShare = form.findViewById(R.id.editSmbShare);
        EditText editDomain = form.findViewById(R.id.editSmbDomain);
        EditText editRemoteDir = form.findViewById(R.id.editRemoteDir);
        editUser.setText(smbUser); editPass.setText(smbPass); editShare.setText(smbShare);
        editDomain.setText(smbDomain); editRemoteDir.setText(remoteDir);
        new AlertDialog.Builder(this)
                .setTitle("SMB Credentials")
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                    e.putString(KEY_SMB_USER, editUser.getText().toString());
                    e.putString(KEY_SMB_PASS, editPass.getText().toString());
                    e.putString(KEY_SMB_SHARE, editShare.getText().toString());
                    e.putString(KEY_SMB_DOMAIN, editDomain.getText().toString());
                    e.putString(KEY_REMOTE_DIR, editRemoteDir.getText().toString());
                    e.apply();
                    loadSmbPrefs();
                    coordinator.setSmbParams(smbUser, smbPass, smbDomain, smbShare, remoteDir);
                    Toast.makeText(this, "SMB credentials updated!", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshFolderList() {
        currentFolders = getBackupFolderUris();
        List<String> items = new ArrayList<>();
        for (Uri folder : currentFolders) items.add(folder.toString());
        folderAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        listFolders.setAdapter(folderAdapter);
    }

    private void saveBackupFolderUri(Uri uri) {
        Set<String> uris = new HashSet<>(getFolderUriStrings());
        uris.add(uri.toString());
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_BACKUP_FOLDERS, String.join(",", uris)).apply();
    }

    private void removeBackupFolderUri(String uriStr) {
        Set<String> uris = new HashSet<>(getFolderUriStrings());
        uris.remove(uriStr);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_BACKUP_FOLDERS, String.join(",", uris)).apply();
    }

    private Set<String> getFolderUriStrings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String urisString = prefs.getString(KEY_BACKUP_FOLDERS, "");
        Set<String> uris = new HashSet<>();
        if (!urisString.isEmpty())
            uris.addAll(Arrays.asList(urisString.split(",")));
        return uris;
    }

    private List<Uri> getBackupFolderUris() {
        Set<String> uriStrs = getFolderUriStrings();
        List<Uri> uris = new ArrayList<>();
        for (String s : uriStrs) {
            try { uris.add(Uri.parse(s)); } catch (Throwable ignored) {}
        }
        return uris;
    }

    private void promptRemoveFolder() {
        List<String> items = new ArrayList<>();
        for (Uri folder : getBackupFolderUris()) items.add(folder.toString());
        if (items.isEmpty()) {
            Toast.makeText(this, "No backup folders to remove.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Remove Backup Folder")
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    removeBackupFolderUri(items.get(which));
                    refreshFolderList();
                    coordinator.setBackupFolderUris(getBackupFolderUris());
                }).show();
    }

    private void manualBackup() {
        List<Uri> folders = getBackupFolderUris();
        if (folders.isEmpty()) {
            Toast.makeText(this, "Please select a backup folder first.", Toast.LENGTH_LONG).show();
            return;
        }
        coordinator.setBackupFolderUris(folders);
        coordinator.setFileFilter(getFileFilter());
        coordinator.startBackup(deviceManagerDeviceSelectionCallback(),
                status -> runOnUiThread(() -> txtResult.setText(status)));
    }

    private void showAvailableDevices() {
        coordinator.setBackupFolderUris(getBackupFolderUris());
        coordinator.setFileFilter(getFileFilter());
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

    private DeviceManager.DeviceSelectionCallback deviceManagerDeviceSelectionCallback() {
        return (devices, done) -> runOnUiThread(() -> showDeviceSelectionDialog(devices, done));
    }

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

    private void promptFileFilter() {
        String[] allTypes = {"jpg","png","pdf","docx","txt","mp4"};
        boolean[] checked = new boolean[allTypes.length];
        List<String> existing = getFileFilter();
        for (int i = 0; i < allTypes.length; i++) {
            checked[i] = existing.contains(allTypes[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Select File Types to Backup")
                .setMultiChoiceItems(allTypes, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save", (dialog, which) -> {
                    List<String> result = new ArrayList<>();
                    for (int i = 0; i < allTypes.length; i++) if (checked[i]) result.add(allTypes[i]);
                    SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                    e.putString(KEY_BACKUP_FILE_FILTER, String.join(",", result));
                    e.apply();
                    coordinator.setFileFilter(result);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private List<String> getFileFilter() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String types = prefs.getString(KEY_BACKUP_FILE_FILTER, "");
        List<String> result = new ArrayList<>();
        if (!types.isEmpty()) result.addAll(Arrays.asList(types.split(",")));
        return result;
    }
}
