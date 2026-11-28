package com.example.mediahub;
public class ST {
    static int fail = 0;
    static void eq(String n, Object g, Object w) { boolean ok = String.valueOf(g).equals(String.valueOf(w)); if (!ok) fail++; System.out.println((ok?"PASS ":"FAIL ")+n+(ok?"":" got="+g+" want="+w)); }
    public static void main(String[] a) {
        long[][] all = {SettingsModel.HIDE_VALUES, SettingsModel.SEEK_VALUES, SettingsModel.CACHE_VALUES, SettingsModel.BOOL_VALUES};
        long[] defs = {SettingsModel.HIDE_DEF, SettingsModel.SEEK_DEF, SettingsModel.CACHE_DEF, SettingsModel.HW_DEF};
        for (int i = 0; i < all.length; i++) {
            boolean has = false; for (long v : all[i]) if (v == defs[i]) has = true;
            eq("default " + i + " is an allowed value", has, true);
        }
        eq("stored value kept", SettingsModel.sanitize(SettingsModel.HIDE_VALUES, 8000, 5000), 8000);
        eq("never (0) is allowed", SettingsModel.sanitize(SettingsModel.HIDE_VALUES, 0, 5000), 0);
        eq("garbage falls back to default", SettingsModel.sanitize(SettingsModel.HIDE_VALUES, 1234567, 5000), 5000);
        eq("negative falls back", SettingsModel.sanitize(SettingsModel.SEEK_VALUES, -5, 5000), 5000);
        eq("cache default 3500", SettingsModel.sanitize(SettingsModel.CACHE_VALUES, 999, 3500), 3500);
        eq("bool garbage -> default on", SettingsModel.sanitize(SettingsModel.BOOL_VALUES, 7, 1), 1);
        eq("indexOf default of unknown", SettingsModel.indexOf(SettingsModel.SEEK_VALUES, 3, 5000), 0);
        eq("indexOf exact", SettingsModel.indexOf(SettingsModel.SEEK_VALUES, 30000, 5000), 3);
        eq("hide max step in ms fits int", SettingsModel.CACHE_VALUES[SettingsModel.CACHE_VALUES.length-1] <= Integer.MAX_VALUE, true);
        System.out.println(fail == 0 ? "ALL SETTINGS TESTS PASSED" : fail + " FAILED");
    }
}
