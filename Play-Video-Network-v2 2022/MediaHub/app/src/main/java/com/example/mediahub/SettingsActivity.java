package com.example.mediahub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Locale;

/** Settings screen: every row opens a short list of choices (works with the TV remote). */
public class SettingsActivity extends Activity {

    private static final class Opt {
        final String key;
        final int titleRes, hintRes;
        final long[] values;
        final long def;
        final boolean bool;
        Opt(String key, int titleRes, int hintRes, long[] values, long def, boolean bool) {
            this.key = key; this.titleRes = titleRes; this.hintRes = hintRes;
            this.values = values; this.def = def; this.bool = bool;
        }
    }

    private final Opt[] opts = new Opt[]{
            new Opt(SettingsModel.HIDE, R.string.st_hide_title, R.string.st_hide_hint, SettingsModel.HIDE_VALUES, SettingsModel.HIDE_DEF, false),
            new Opt(SettingsModel.SEEK, R.string.st_seek_title, R.string.st_seek_hint, SettingsModel.SEEK_VALUES, SettingsModel.SEEK_DEF, false),
            new Opt(SettingsModel.NEXT, R.string.st_next_title, R.string.st_next_hint, SettingsModel.BOOL_VALUES, SettingsModel.NEXT_DEF, true),
            new Opt(SettingsModel.HW, R.string.st_hw_title, R.string.st_hw_hint, SettingsModel.BOOL_VALUES, SettingsModel.HW_DEF, true),
            new Opt(SettingsModel.LENIENT, R.string.st_tls_title, R.string.st_tls_hint, SettingsModel.BOOL_VALUES, SettingsModel.LENIENT_DEF, true),
            new Opt(SettingsModel.CACHE, R.string.st_cache_title, R.string.st_cache_hint, SettingsModel.CACHE_VALUES, SettingsModel.CACHE_DEF, false),
    };

    private ArrayAdapter<Opt> adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        ListView list = (ListView) findViewById(R.id.list);
        adapter = new ArrayAdapter<Opt>(this, R.layout.item_setting, Arrays.asList(opts)) {
            @Override
            public View getView(int p, View v, ViewGroup g) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_setting, g, false);
                Opt o = getItem(p);
                ((TextView) v.findViewById(R.id.st_title)).setText(o.titleRes);
                ((TextView) v.findViewById(R.id.st_value)).setText(label(o, current(o)));
                ((TextView) v.findViewById(R.id.st_hint)).setText(o.hintRes);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) { choose(opts[pos]); }
        });
    }

    private long current(Opt o) {
        return o.values[SettingsModel.indexOf(o.values, Prefs.sp(this).getLong(o.key, o.def), o.def)];
    }

    private String label(Opt o, long v) {
        if (o.bool) return getString(v == 1L ? R.string.on : R.string.off);
        if (v == 0L) return getString(R.string.never);
        double s = v / 1000.0;
        String n = (s == Math.floor(s)) ? String.valueOf((long) s) : String.format(Locale.US, "%.1f", s);
        return n + " " + getString(R.string.unit_s);
    }

    private void choose(final Opt o) {
        int checked = SettingsModel.indexOf(o.values, current(o), o.def);
        CharSequence[] labels = new CharSequence[o.values.length];
        for (int i = 0; i < labels.length; i++) labels[i] = label(o, o.values[i]);
        new AlertDialog.Builder(this)
                .setTitle(o.titleRes)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Prefs.sp(SettingsActivity.this).edit().putLong(o.key, o.values[which]).apply();
                        d.dismiss();
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
