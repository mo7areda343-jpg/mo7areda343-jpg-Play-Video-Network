package com.example.mediahub;

import android.content.Context;
import android.content.SharedPreferences;

/** Reads the user's playback settings (always validated, so a bad stored value can never break playback). */
final class Prefs {
    private static final String FILE = "mediahub_prefs";

    static SharedPreferences sp(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    /** Milliseconds before the controls disappear; 0 = never. */
    static long hideDelayMs(Context c) {
        return SettingsModel.sanitize(SettingsModel.HIDE_VALUES, sp(c).getLong(SettingsModel.HIDE, SettingsModel.HIDE_DEF), SettingsModel.HIDE_DEF);
    }
    static long seekStepMs(Context c) {
        return SettingsModel.sanitize(SettingsModel.SEEK_VALUES, sp(c).getLong(SettingsModel.SEEK, SettingsModel.SEEK_DEF), SettingsModel.SEEK_DEF);
    }
    static int networkCachingMs(Context c) {
        return (int) SettingsModel.sanitize(SettingsModel.CACHE_VALUES, sp(c).getLong(SettingsModel.CACHE, SettingsModel.CACHE_DEF), SettingsModel.CACHE_DEF);
    }
    static boolean hwDecoding(Context c) {
        return SettingsModel.sanitize(SettingsModel.BOOL_VALUES, sp(c).getLong(SettingsModel.HW, SettingsModel.HW_DEF), SettingsModel.HW_DEF) == 1L;
    }
    static boolean autoNext(Context c) {
        return SettingsModel.sanitize(SettingsModel.BOOL_VALUES, sp(c).getLong(SettingsModel.NEXT, SettingsModel.NEXT_DEF), SettingsModel.NEXT_DEF) == 1L;
    }
    static boolean lenientTls(Context c) {
        return SettingsModel.sanitize(SettingsModel.BOOL_VALUES, sp(c).getLong(SettingsModel.LENIENT, SettingsModel.LENIENT_DEF), SettingsModel.LENIENT_DEF) == 1L;
    }
}
