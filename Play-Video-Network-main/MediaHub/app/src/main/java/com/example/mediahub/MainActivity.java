package com.example.mediahub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** File browser over every readable storage volume (internal, SD, USB). */
public class MainActivity extends Activity {

    static final Set<String> VIDEO = set("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts",
            "m2ts", "mpg", "mpeg", "3gp", "m4v", "vob", "rmvb", "ogv", "divx");
    static final Set<String> AUDIO = set("mp3", "flac", "wav", "ogg", "m4a", "aac", "wma", "opus", "mka");
    static final Set<String> LISTS = set("m3u", "m3u8", "pls");
    static final Set<String> IMAGE = set("jpg", "jpeg", "png", "gif", "bmp", "webp");

    static Set<String> set(String... a) { return new HashSet<String>(Arrays.asList(a)); }

    static String ext(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    private final List<File> items = new ArrayList<File>();
    private List<File> roots = new ArrayList<File>();
    private File current;               // null = list of storage roots
    private ArrayAdapter<File> adapter;
    private TextView pathView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        pathView = (TextView) findViewById(R.id.path);
        ListView list = (ListView) findViewById(R.id.list);

        adapter = new ArrayAdapter<File>(this, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int p, View v, ViewGroup g) {
                TextView t = (TextView) super.getView(p, v, g);
                File f = getItem(p);
                String name = current == null ? f.getAbsolutePath() : f.getName();
                t.setText((f.isDirectory() ? "[DIR]  " : "") + name);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                onPick(items.get(pos));
            }
        });

        findViewById(R.id.btn_url).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askUrl(); }
        });
        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRoots(); }
        });
        showRoots();
    }

    // ---------- storage ----------
    private List<File> findRoots() {
        Map<String, File> m = new LinkedHashMap<String, File>();
        addRoot(m, Environment.getExternalStorageDirectory());
        for (String base : new String[]{"/storage", "/mnt"}) {
            File[] fs = new File(base).listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName();
                if (n.equals("asec") || n.equals("obb") || n.equals("secure") || n.equals("shell")
                        || n.equals("runtime") || n.equals("self") || n.equals("emulated")) continue;
                addRoot(m, f);
            }
        }
        return new ArrayList<File>(m.values());
    }

    private void addRoot(Map<String, File> m, File f) {
        if (f == null || !f.isDirectory() || !f.canRead()) return;
        String[] l = f.list();
        if (l == null || l.length == 0) return;
        try { m.put(f.getCanonicalPath(), f); } catch (IOException e) { m.put(f.getAbsolutePath(), f); }
    }

    private void showRoots() {
        current = null;
        roots = findRoots();
        items.clear();
        items.addAll(roots);
        adapter.notifyDataSetChanged();
        pathView.setText("Storage (" + roots.size() + ")");
    }

    private void open(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) { Toast.makeText(this, "Cannot read " + dir, Toast.LENGTH_LONG).show(); return; }
        current = dir;
        items.clear();
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory() || isMedia(f)) items.add(f);
        }
        Collections.sort(items, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        adapter.notifyDataSetChanged();
        pathView.setText(dir.getAbsolutePath());
    }

    static boolean isMedia(File f) {
        String e = ext(f);
        return VIDEO.contains(e) || AUDIO.contains(e) || LISTS.contains(e) || IMAGE.contains(e);
    }

    private void onPick(File f) {
        if (f.isDirectory()) { open(f); return; }
        String e = ext(f);
        if (IMAGE.contains(e)) {
            startActivity(new Intent(this, ImageActivity.class).putExtra("path", f.getAbsolutePath()));
        } else {
            startActivity(new Intent(this, PlayerActivity.class).putExtra("uri", Uri.fromFile(f).toString()));
        }
    }

    @Override
    public void onBackPressed() {
        if (current == null) { super.onBackPressed(); return; }
        File p = current.getParentFile();
        if (roots.contains(current) || p == null || !p.canRead()) showRoots(); else open(p);
    }

    // ---------- web links ----------
    private void askUrl() {
        final EditText in = new EditText(this);
        in.setHint(R.string.url_hint);
        in.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_url)
                .setView(in)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String u = in.getText().toString().trim();
                        if (u.length() == 0) return;
                        if (!u.contains("://")) u = "http://" + u;
                        startActivity(new Intent(MainActivity.this, PlayerActivity.class).putExtra("uri", u));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
