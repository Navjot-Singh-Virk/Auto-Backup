package com.navjot.autobackup;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DeviceManager
 * -------------
 * Handles:
 *  - Whitelisted target MAC addresses (trusted PCs)
 *  - Caching the last selected device to allow headless automatic backups
 */
public class DeviceManager {

    private static final String PREFS_NAME = "BackupPrefs";
    private static final String KEY_WHITELISTED_MACS = "WhitelistedMACs";
    private static final String KEY_LAST_IP = "LastChosenIP";
    private static final String KEY_LAST_MAC = "LastChosenMAC";

    private final Context context;

    /** Container for last chosen device, if available. */
    public static class LastChosenDevice extends NetworkMonitor.DeviceInfo {
        public LastChosenDevice(String ip, String mac) { super(ip, mac); }
    }

    public DeviceManager(Context ctx) { this.context = ctx.getApplicationContext(); }

    /** @return Set of whitelisted MAC addresses. */
    public Set<String> getWhitelistedMacs() {
        SharedPreferences prefs = getPrefs();
        return new HashSet<>(prefs.getStringSet(KEY_WHITELISTED_MACS, new HashSet<>()));
    }

    /** Returns true if MAC is in whitelist. */
    public boolean isWhitelisted(String mac) {
        return getWhitelistedMacs().contains(mac.toUpperCase());
    }

    /** Adds a MAC to whitelist. */
    public void addToWhitelist(String mac) {
        Set<String> macs = getWhitelistedMacs();
        macs.add(mac.toUpperCase());
        saveWhitelist(macs);
    }

    /** Filters device list to whitelisted ones. */
    public List<NetworkMonitor.DeviceInfo> getWhitelistedDevices(List<NetworkMonitor.DeviceInfo> all) {
        List<NetworkMonitor.DeviceInfo> filtered = new ArrayList<>();
        Set<String> wlist = getWhitelistedMacs();
        for (NetworkMonitor.DeviceInfo dev : all) {
            if (wlist.contains(dev.mac.toUpperCase())) filtered.add(dev);
        }
        return filtered;
    }

    /** Store a last chosen device for headless reuse. */
    public void cacheLastChosenDevice(NetworkMonitor.DeviceInfo dev) {
        SharedPreferences prefs = getPrefs();
        prefs.edit()
                .putString(KEY_LAST_IP, dev.ip)
                .putString(KEY_LAST_MAC, dev.mac.toUpperCase())
                .apply();
    }

    /** @return last chosen device or null if none cached. */
    public LastChosenDevice getLastChosenDevice() {
        SharedPreferences prefs = getPrefs();
        String ip = prefs.getString(KEY_LAST_IP, null);
        String mac = prefs.getString(KEY_LAST_MAC, null);
        if (ip != null && mac != null) return new LastChosenDevice(ip, mac);
        return null;
    }

    private SharedPreferences getPrefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void saveWhitelist(Set<String> macs) {
        getPrefs().edit().putStringSet(KEY_WHITELISTED_MACS, macs).apply();
    }

    /** Callback for asking the user to choose a device from a list. */
    public interface DeviceSelectionCallback {
        void onSelectDevice(List<NetworkMonitor.DeviceInfo> allDevices,
                            DeviceChosenCallback done);
    }

    /** Callback for delivering the chosen device from UI to backend. */
    public interface DeviceChosenCallback {
        void onDeviceChosen(NetworkMonitor.DeviceInfo chosenDevice);
    }
}
