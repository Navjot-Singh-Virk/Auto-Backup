package com.navjot.autobackup;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public class BackupNotifier {

    private static final String CHANNEL_ID = "backup_status";
    public static void notifyResult(Context ctx, int success, int total, String ip) {
        createChannel(ctx);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Backup Complete")
                .setContentText("Uploaded " + success + "/" + total + " files to " + ip)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(1001, builder.build());
    }

    private static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Backup Status",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
