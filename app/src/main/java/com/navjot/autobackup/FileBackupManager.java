package com.navjot.autobackup;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FileBackupManager {

    private static final String TAG = "FileBackupManager";
    private static final String PREFS_NAME = "BackupPrefs";
    private static final String KEY_UPLOAD_HISTORY = "UploadedFilesHistory";

    private final Context context;
    private final String serverIp, shareName, username, password, domain, remoteDir;
    private final SmbjClient smbClient;
    private final SharedPreferences prefs;

    public FileBackupManager(Context context,
                             String serverIp,
                             String shareName,
                             String username,
                             String password,
                             String domain,
                             String remoteDir) {
        this.context = context.getApplicationContext();
        this.serverIp = serverIp;
        this.shareName = shareName;
        this.username = username;
        this.password = password;
        this.domain = domain;
        this.remoteDir = remoteDir;
        this.smbClient = new SmbjClient();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<DocumentFile> getNewFilesToBackup(List<Uri> folderUris, List<String> extensions) {
        List<DocumentFile> result = new ArrayList<>();
        Set<String> historySet = getUploadHistorySet();

        for (Uri folderUri : folderUris) {
            DocumentFile folder = DocumentFile.fromTreeUri(context, folderUri);
            if (folder == null || !folder.isDirectory()) continue;
            for (DocumentFile file : folder.listFiles()) {
                if (file.isFile() && matchesFilter(file, extensions)) {
                    String key = uniqueFileKey(folderUri, file);
                    if (!historySet.contains(key)) {
                        result.add(file);
                    }
                }
            }
        }
        return result;
    }

    public int backupFiles(List<DocumentFile> files) {
        Set<String> historySet = getUploadHistorySet();
        int successCount = 0;
        for (DocumentFile file : files) {
            boolean success = false;
            int attempt = 0;
            while (!success && attempt < 3) {
                attempt++;
                try (InputStream is = context.getContentResolver().openInputStream(file.getUri())) {
                    if (is == null) {
                        Log.w(TAG, "Cannot open file: " + file.getName());
                        break;
                    }

                    success = smbClient.uploadFile(
                            serverIp,
                            shareName,
                            domain,
                            username,
                            password,
                            remoteDir,
                            file.getName(),
                            is
                    );
                } catch (Exception e) {
                    Log.e(TAG, "Error uploading file " + file.getName() + ": " + e.getMessage(), e);
                }
                if (!success) {
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                }
            }
            if (success) {
                successCount++;
                historySet.add(uniqueFileKeyFromName(file));
                saveUploadHistorySet(historySet);
            }
        }
        return successCount;
    }

    private boolean matchesFilter(DocumentFile file, List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) return true;
        String name = file.getName();
        if (name == null) return false;
        for (String ext : extensions) {
            if (name.toLowerCase().endsWith("." + ext.toLowerCase())) return true;
        }
        return false;
    }

    private String uniqueFileKey(Uri folderUri, DocumentFile file) {
        return folderUri.toString() + "|" + file.getName() + "_" + file.lastModified();
    }

    private String uniqueFileKeyFromName(DocumentFile file) {
        return file.getName() + "_" + file.lastModified();
    }

    private Set<String> getUploadHistorySet() {
        String history = prefs.getString(KEY_UPLOAD_HISTORY, "");
        Set<String> set = new HashSet<>();
        if (!history.isEmpty()) {
            String[] keys = history.split(",");
            for (String key : keys) {
                if (!key.trim().isEmpty()) set.add(key);
            }
        }
        return set;
    }

    private void saveUploadHistorySet(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String key : set) {
            if (sb.length() > 0) sb.append(",");
            sb.append(key);
        }
        prefs.edit().putString(KEY_UPLOAD_HISTORY, sb.toString()).apply();
    }
}
