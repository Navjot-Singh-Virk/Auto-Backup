package com.navjot.autobackup;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NetworkMonitor
 * --------------
 * Handles detection of network state and scanning devices on subnet.
 */
public class NetworkMonitor {

    private final Context context;

    public NetworkMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Checks if device is currently connected to Wi-Fi. */
    public boolean isOnWifi() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    /** Checks if Wi-Fi hotspot (tethering) is active. */
    public boolean isHotspotOn() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            Method method = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(wm);
        } catch (Exception e) {
            return false;
        }
    }

    /** Detects the subnet prefix from current Wi-Fi IP (e.g., "192.168.1."). */
    public String detectSubnetPrefix() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            String s = String.format("%d.%d.%d.%d",
                    (ip & 0xff),
                    (ip >> 8 & 0xff),
                    (ip >> 16 & 0xff),
                    (ip >> 24 & 0xff)
            );
            return s.substring(0, s.lastIndexOf('.') + 1);
        } catch (Exception e) {
            return "192.168.0.";
        }
    }

    /** Pings an IP address with a given timeout in milliseconds. */
    public boolean pingIp(String ip, int timeout) {
        try {
            return InetAddress.getByName(ip).isReachable(timeout);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reads the MAC address for an IP from the ARP cache.
     * Returns MAC address in uppercase if found, or null.
     */
    public String getMacFromArp(String ip) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
            // Skip header line
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4 && parts[0].equals(ip) && parts[3].matches("..:..:..:..:..:..")) {
                    return parts[3].toUpperCase();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Checks if a given device is reachable by ping and MAC comparison. */
    public boolean isDeviceReachable(DeviceInfo device) {
        try {
            boolean reachable = InetAddress.getByName(device.ip).isReachable(200);
            String mac = getMacFromArp(device.ip);
            return reachable && device.mac.equalsIgnoreCase(mac);
        } catch (Exception e) {
            return false;
        }
    }

    /** Checks if a DeviceManager.LastChosenDevice is reachable; overload for convenience. */
    public boolean isDeviceReachable(DeviceManager.LastChosenDevice device) {
        if (device == null || device.ip == null || device.mac == null) return false;
        try {
            boolean reachable = InetAddress.getByName(device.ip).isReachable(200);
            String macFromArp = getMacFromArp(device.ip);
            return reachable && device.mac.equalsIgnoreCase(macFromArp);
        } catch (Exception e) {
            return false;
        }
    }

    /** Holds device information with IP and MAC address. */
    public static class DeviceInfo {
        public final String ip, mac;
        public DeviceInfo(String ip, String mac) { this.ip = ip; this.mac = mac; }
        @Override public String toString() { return ip + " (" + mac + ")"; }
    }

    /** Callback to receive discovered devices. */
    public interface ScanCallback {
        void onScanCompleted(List<DeviceInfo> devices);
    }

    /** Callback to report scan progress for progress bar updates. */
    public interface ScanProgressCallback {
        void onProgress(String statusMessage, int current, int total);
    }

    /**
     * Scan the subnet asynchronously and report progress per IP.
     */
    public void scanSubnetAsync(ScanProgressCallback progressCb, final ScanCallback callback) {
        String baseIp = detectSubnetPrefix();
        int total = 253; // Scanning .2 to .254
        List<DeviceInfo> found = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(20);
        for (int i = 2; i <= 254; i++) {
            final int currentIndex = i - 1;
            String ip = baseIp + i;
            pool.submit(() -> {
                if (progressCb != null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            progressCb.onProgress("Scanning " + ip, currentIndex, total));
                }
                if (pingIp(ip, 200)) {
                    String mac = getMacFromArp(ip);
                    if (mac != null) found.add(new DeviceInfo(ip, mac));
                }
            });
        }
        // Only call shutdown after all tasks submitted
        pool.shutdown();
        new Thread(() -> {
            try {
                while (!pool.isTerminated()) Thread.sleep(50);
            } catch (InterruptedException ignored) {}
            new Handler(Looper.getMainLooper()).post(() -> callback.onScanCompleted(found));
        }).start();
    }

    /** Original scan method for backward compat (no progress bar) */
    public void scanSubnetAsync(final ScanCallback callback) {
        scanSubnetAsync(null, callback);
    }
}
