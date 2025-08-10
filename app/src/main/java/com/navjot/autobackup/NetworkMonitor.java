package com.navjot.autobackup;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
 * Provides network state, subnet scanning and device discovery capabilities.
 *
 * Key features:
 * <ul>
 *     <li>Detects if device is on Wi‑Fi or hotspot tethering.</li>
 *     <li>Dynamically determines current subnet prefix (e.g. "192.168.43.").</li>
 *     <li>Scans subnet in parallel, for each IP: sends a ping and then looks up its MAC directly from ARP cache.</li>
 *     <li>Caches previous scan results for a short period to avoid redundant scans.</li>
 *     <li>Provides helper to verify specific device reachability.</li>
 * </ul>
 */
public class NetworkMonitor {

    private static final String TAG = "NetworkMonitor";
    private final Context context;

    /** Cached scan results and timestamp */
    private List<DeviceInfo> lastScanDevices = Collections.emptyList();
    private long lastScanTime = 0;
    private static final long CACHE_VALIDITY_MS = 5 * 60 * 1000; // 5 minutes cache

    /** Represents a discovered device: IP + MAC. */
    public static class DeviceInfo {
        public final String ip;
        public final String mac;
        public DeviceInfo(String ip, String mac) { this.ip = ip; this.mac = mac; }
        @Override public String toString() { return ip + " (" + mac + ")"; }
    }

    /** Callback for asynchronous subnet scan. */
    public interface ScanCallback { void onScanCompleted(List<DeviceInfo> devices); }

    public NetworkMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    /** @return true if connected to a Wi‑Fi network. */
    public boolean isOnWifi() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    /**
     * Uses reflection (hidden API) to detect hotspot state.
     * Not guaranteed to work on all devices/versions.
     */
    public boolean isHotspotOn() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            Method method = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (boolean) method.invoke(wm);
        } catch (Exception e) {
            return false;
        }
    }

    /** @return SSID of current Wi‑Fi network (no quotes), or null if not on Wi‑Fi. */
    public String getCurrentSsid() {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm.getConnectionInfo();
        if (info != null && info.getSSID() != null) {
            String ssid = info.getSSID();
            if (ssid.startsWith("\"") && ssid.endsWith("\""))
                ssid = ssid.substring(1, ssid.length() - 1);
            return ssid;
        }
        return null;
    }

    /** @return subnet prefix from device IP, e.g. "192.168.1.", or default if unknown. */
    public String detectSubnetPrefix() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            String ipStr = String.format("%d.%d.%d.%d",
                    (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
            return ipStr.substring(0, ipStr.lastIndexOf('.') + 1);
        } catch (Exception e) {
            return "192.168.43.";
        }
    }

    /**
     * Attempts to reach a given DeviceInfo by pinging and checking ARP.
     * @param dev Device to test.
     * @return true if IP is reachable and ARP MAC matches dev.mac.
     */
    public boolean isDeviceReachable(DeviceInfo dev) {
        try {
            return pingIp(dev.ip, 200) && dev.mac.equalsIgnoreCase(getMacFromArp(dev.ip));
        } catch (Exception e) {
            return false;
        }
    }

    /** Ping the given IP address. */
    private boolean pingIp(String ip, int timeoutMillis) {
        try {
            return InetAddress.getByName(ip).isReachable(timeoutMillis);
        } catch (Exception e) {
            return false;
        }
    }

    /** Look up a MAC address for an IP in the ARP table. */
    private String getMacFromArp(String ip) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            br.readLine(); // skip header
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4 && parts[0].equals(ip) && parts[3].matches("..:..:..:..:..:.."))
                    return parts[3].toUpperCase();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Scan the local subnet asynchronously.
     * The scan runs in parallel threads for speed; each IP is pinged and its MAC retrieved immediately.
     * Results are cached for {@link #CACHE_VALIDITY_MS} ms.
     */
    public void scanSubnetAsync(final ScanCallback callback) {
        long now = System.currentTimeMillis();
        if (now - lastScanTime < CACHE_VALIDITY_MS) {
            Log.i(TAG, "Using cached scan results");
            new Handler(Looper.getMainLooper()).post(() -> callback.onScanCompleted(lastScanDevices));
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<DeviceInfo> found = Collections.synchronizedList(new ArrayList<>());
        String baseIp = detectSubnetPrefix();

        for (int i = 2; i <= 254; i++) {
            final String ip = baseIp + i;
            pool.submit(() -> {
                if (pingIp(ip, 200)) {
                    String mac = getMacFromArp(ip);
                    if (mac != null) {
                        found.add(new DeviceInfo(ip, mac));
                        Log.d(TAG, "Found device: " + ip + " " + mac);
                    }
                }
            });
        }

        pool.shutdown();
        new Thread(() -> {
            try {
                while (!pool.isTerminated()) Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            lastScanDevices = new ArrayList<>(found);
            lastScanTime = System.currentTimeMillis();
            new Handler(Looper.getMainLooper()).post(() -> callback.onScanCompleted(found));
        }).start();
    }
}