package com.navjot.autobackup;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * FileBackupManager
 * ================
 * - Scans user-selected folders (SAF DocumentFile URIs) for backup-eligible files.
 * - Skips files already uploaded unless they are new or changed.
 * - Uploads files to remote PC using SmbjClient with retry.
 */
public class FileBackupManager {

    private static final String TAG = "FileBackupManager";
    private static final String PREFS_NAME = "BackupPrefs";
    private static final String KEY_UPLOAD_HISTORY = "UploadedFilesHistory";

    private final Context context;
    private final String serverIp, shareName, username, password, domain, remoteDir;
    private final SmbjClient smbClient;
    private final SharedPreferences prefs;

    /**
     * Constructor for a backup batch.
     * @param context  - Android context
     * @param serverIp - Target PC IP for SMB
     * @param shareName - SMB share name
     * @param username, password, domain - SMB credentials
     * @param remoteDir - Path on share
     */
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

    /**
     * Scans all provided SAF folder URIs for files not yet backed up (by name+timestamp).
     * @param folderUris List of user-selected backup folders (SAF URIs)
     * @return List of DocumentFiles to back up
     */
    public List<DocumentFile> getNewFilesToBackup(List<Uri> folderUris) {
        List<DocumentFile> result = new ArrayList<>();
        String history = prefs.getString(KEY_UPLOAD_HISTORY, "");
        for (Uri folderUri : folderUris) {
            DocumentFile folder = DocumentFile.fromTreeUri(context, folderUri);
            if (folder == null || !folder.isDirectory()) continue;
            for (DocumentFile file : folder.listFiles()) {
                if (file.isFile()) {
                    String key = fileKey(file);
                    if (!history.contains(key)) {
                        result.add(file);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Attempts to upload each DocumentFile up to 3 times, updating upload history on success.
     * @param files List of DocumentFiles to upload.
     */
    public void backupFiles(List<DocumentFile> files) {
        String history = prefs.getString(KEY_UPLOAD_HISTORY, "");
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
                    Log.i(TAG, "Retry upload " + (attempt+1) + ": " + file.getName());
                }
            }
            if (success) {
                history += fileKey(file) + ",";
                prefs.edit().putString(KEY_UPLOAD_HISTORY, history).apply();
                Log.i(TAG, "Uploaded file: " + file.getName());
            } else {
                Log.e(TAG, "Failed to upload file after retries: " + file.getName());
            }
        }
    }

    /** Returns a unique key for backup history based on filename and lastModified */
    private String fileKey(DocumentFile file) {
        return file.getName() + "_" + file.lastModified();
    }
}
