package com.navjot.autobackup;

import android.util.Log;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;

/**
 * SmbjClient
 * ==========
 * Handles SMB file uploads, now supporting SAF InputStream sources.
 */
public class SmbjClient {

    private static final String TAG = "SmbjClient";
    /**
     * Uploads data from input stream to SMB share.
     */
    public boolean uploadFile(String serverIp,
                              String shareName,
                              String domain,
                              String username,
                              String password,
                              String remoteDir,
                              String remoteFileName,
                              InputStream inputStream) {
        SMBClient client = new SMBClient();
        try (Connection connection = client.connect(serverIp)) {
            AuthenticationContext ac = new AuthenticationContext(username, password.toCharArray(), domain);
            try (Session session = connection.authenticate(ac)) {
                try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                    String remotePath = (remoteDir == null || remoteDir.isEmpty())
                            ? remoteFileName
                            : remoteDir + "/" + remoteFileName;
                    try (File remoteFile = share.openFile(
                            remotePath,
                            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
                            null, null,
                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                            null);
                         OutputStream os = remoteFile.getOutputStream()) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = inputStream.read(buf)) != -1) {
                            os.write(buf, 0, len);
                        }
                        os.flush();
                        Log.i(TAG, "SMB upload successful: " + remoteFileName);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SMB upload failed for: " + remoteFileName + " → " + e.getMessage(), e);
            return false;
        }
    }
}
