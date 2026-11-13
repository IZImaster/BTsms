package com.izimaster.btsms;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * App settings. Stored in "device protected" storage so they can be read right after
 * the TC52 boots, even before anyone unlocks the screen.
 */
public final class Settings {

    private static final String FILE = "btsms_settings";

    /** Storage that is available before the first unlock after boot. Moves old data over once. */
    public static Context storage(Context ctx) {
        Context app = ctx.getApplicationContext();
        Context dp = app.createDeviceProtectedStorageContext();
        SharedPreferences flags = dp.getSharedPreferences("btsms_flags", Context.MODE_PRIVATE);
        if (!flags.getBoolean("migrated", false)) {
            try { dp.moveSharedPreferencesFrom(app, "btsms"); } catch (Exception ignored) { }
            flags.edit().putBoolean("migrated", true).apply();
        }
        return dp;
    }

    private static SharedPreferences prefs(Context ctx) {
        return storage(ctx).getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- password (only a SHA-256 hash of it is stored)

    public static boolean hasPassword(Context ctx) {
        return !prefs(ctx).getString("pw", "").isEmpty();
    }

    public static String passwordHash(Context ctx) {
        return prefs(ctx).getString("pw", "");
    }

    public static void setPassword(Context ctx, String pw) {
        prefs(ctx).edit().putString("pw", pw == null || pw.isEmpty() ? "" : sha256(pw)).apply();
    }

    public static boolean checkPassword(Context ctx, String pw) {
        return !hasPassword(ctx) || sha256(pw == null ? "" : pw).equals(passwordHash(ctx));
    }

    // ---- start on boot

    public static boolean startOnBoot(Context ctx) {
        return prefs(ctx).getBoolean("boot", true);
    }

    public static void setStartOnBoot(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("boot", on).apply();
    }

    // ---- remembered "sharing on/off" (so a reboot restores the last state)

    public static boolean sharingWanted(Context ctx) {
        return prefs(ctx).getBoolean("sharing", true);
    }

    public static void setSharingWanted(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("sharing", on).apply();
    }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private Settings() { }
}
