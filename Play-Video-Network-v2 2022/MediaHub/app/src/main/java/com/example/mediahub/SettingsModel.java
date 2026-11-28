package com.example.mediahub;

/** Setting keys, allowed values and defaults (no Android classes, so it can be unit-tested). */
final class SettingsModel {
    static final String HIDE = "hide_delay_ms";   // 0 = never hide
    static final String SEEK = "seek_step_ms";
    static final String HW = "hw_decoding";       // 1 / 0
    static final String CACHE = "net_cache_ms";
    static final String NEXT = "auto_next";       // 1 / 0
    static final String LENIENT = "lenient_tls";  // 1 / 0 (default 0)

    static final long[] HIDE_VALUES = {2000L, 3000L, 5000L, 8000L, 10000L, 15000L, 30000L, 0L};
    static final long[] SEEK_VALUES = {5000L, 10000L, 15000L, 30000L, 60000L};
    static final long[] CACHE_VALUES = {1000L, 2000L, 3500L, 5000L, 10000L, 20000L};
    static final long[] BOOL_VALUES = {1L, 0L};

    static final long HIDE_DEF = 5000L, SEEK_DEF = 5000L, CACHE_DEF = 3500L, HW_DEF = 1L, NEXT_DEF = 1L, LENIENT_DEF = 0L;

    /** Index of v in values; falls back to the default's index, then 0. */
    static int indexOf(long[] values, long v, long def) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        for (int i = 0; i < values.length; i++) if (values[i] == def) return i;
        return 0;
    }

    /** Returns v if it is an allowed value, otherwise the default. */
    static long sanitize(long[] values, long v, long def) {
        return values[indexOf(values, v, def)];
    }
}
