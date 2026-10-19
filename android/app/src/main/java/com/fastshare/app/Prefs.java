package com.fastshare.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

public final class Prefs {
    private static final String NAME = "fastshare";
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String getUser(Context c) {
        String u = sp(c).getString("user", "share");
        return (u == null || u.isEmpty()) ? "share" : u;
    }

    public static void setUser(Context c, String u) {
        sp(c).edit().putString("user", u).apply();
    }

    public static String getPassword(Context c) {
        SharedPreferences prefs = sp(c);
        String p = prefs.getString("pass", null);
        if (p == null || p.isEmpty()) {
            p = randomPassword();
            prefs.edit().putString("pass", p).apply();
        }
        return p;
    }

    public static void setPassword(Context c, String p) {
        sp(c).edit().putString("pass", p).apply();
    }

    private static String randomPassword() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder result = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return result.toString();
    }

    public static int getPort(Context c) {
        int p = sp(c).getInt("port", 8000);
        return (p < 1024 || p > 65535) ? 8000 : p;
    }

    public static void setPort(Context c, int p) {
        sp(c).edit().putInt("port", p).apply();
    }

    public static boolean getBoot(Context c) {
        return sp(c).getBoolean("boot", true);
    }

    public static void setBoot(Context c, boolean b) {
        sp(c).edit().putBoolean("boot", b).apply();
    }

    // Last "Connect" target so the user types it once.
    public static String getConnHost(Context c) {
        return sp(c).getString("conn_host", "");
    }

    public static String getConnUser(Context c) {
        String u = sp(c).getString("conn_user", "");
        return u == null ? "" : u;
    }

    public static String getConnPass(Context c) {
        String p = sp(c).getString("conn_pass", "");
        return p == null ? "" : p;
    }

    public static void setConn(Context c, String host, String user, String pass) {
        sp(c).edit().putString("conn_host", host).putString("conn_user", user)
                .putString("conn_pass", pass).apply();
    }

    public static String getDevicePassword(Context c, String ip) {
        String p = sp(c).getString("dev_pass_" + ip, "");
        if (p != null && !p.isEmpty()) return p;
        String connHost = getConnHost(c);
        if (connHost != null && (connHost.equals(ip) || connHost.startsWith(ip + ":"))) {
            return getConnPass(c);
        }
        return "";
    }

    public static void setDevicePassword(Context c, String ip, String pass) {
        sp(c).edit().putString("dev_pass_" + ip, pass).apply();
    }

    // First-run permission prompts (asked once, rows stay for manual retry).
    public static boolean getSetupFiles(Context c) {
        return sp(c).getBoolean("setup_files", false);
    }

    public static void setSetupFiles(Context c, boolean b) {
        sp(c).edit().putBoolean("setup_files", b).apply();
    }

    public static boolean getSetupNotif(Context c) {
        return sp(c).getBoolean("setup_notif", false);
    }

    public static void setSetupNotif(Context c, boolean b) {
        sp(c).edit().putBoolean("setup_notif", b).apply();
    }
}
