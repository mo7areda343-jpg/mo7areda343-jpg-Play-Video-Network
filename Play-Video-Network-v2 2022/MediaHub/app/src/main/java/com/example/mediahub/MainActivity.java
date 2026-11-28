package com.example.mediahub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
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

/** File browser over every readable storage volume (internal, SD, USB) across Android versions. */
public class MainActivity extends Activity {

    static final Set<String> VIDEO = set("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts",
            "m2ts", "mts", "mpg", "mpeg", "3gp", "3g2", "m4v", "vob", "rm", "rmvb", "ogv", "ogg",
            "divx", "xvid", "asf", "f4v", "dat", "ifo", "iso", "wtv", "dvr-ms");
    static final Set<String> AUDIO = set("mp3", "flac", "wav", "ogg", "m4a", "aac", "wma", "opus", "mka", "amr", "3ga", "ac3", "dts");
    static final Set<String> LISTS = set("m3u", "m3u8", "pls");
    static final Set<String> IMAGE = set("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff", "ico");

    static Set<String> set(String... a) { return new HashSet<String>(Arrays.asList(a)); }

    static String ext(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.0f KB", v);
        v /= 1024;
        if (v < 1024) return String.format(Locale.US, "%.1f MB", v);
        return String.format(Locale.US, "%.2f GB", v / 1024);
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

        adapter = new ArrayAdapter<File>(this, R.layout.item_file, items) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                View v = convert != null ? convert
                        : getLayoutInflater().inflate(R.layout.item_file, parent, false);
                File f = getItem(pos);
                ImageView icon = (ImageView) v.findViewById(R.id.icon);
                TextView name = (TextView) v.findViewById(R.id.name);
                TextView meta = (TextView) v.findViewById(R.id.meta);

                if (current == null) {
                    icon.setImageResource(android.R.drawable.ic_menu_save);
                    name.setText(rootLabel(f));
                    meta.setText(f.getAbsolutePath());
                    return v;
                }
                if (f.getName().equals("..")) {
                    icon.setImageResource(android.R.drawable.ic_menu_revert);
                    name.setText("..");
                    meta.setText("Parent directory");
                    return v;
                }
                if (f.isDirectory()) {
                    icon.setImageResource(android.R.drawable.ic_menu_compass);
                    name.setText(f.getName());
                    String[] ch = f.list();
                    meta.setText(ch == null ? "Folder" : ch.length + " items");
                    return v;
                }
                String e = ext(f);
                int ic = VIDEO.contains(e) || LISTS.contains(e) ? android.R.drawable.ic_media_play
                        : AUDIO.contains(e) ? android.R.drawable.ic_lock_silent_mode_off
                        : IMAGE.contains(e) ? android.R.drawable.ic_menu_gallery
                        : android.R.drawable.ic_menu_agenda;
                icon.setImageResource(ic);
                name.setText(f.getName());
                meta.setText((e.isEmpty() ? "FILE" : e.toUpperCase(Locale.US)) + "  •  " + size(f.length()));
                return v;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                File f = items.get(pos);
                if (current == null) { open(f); return; }
                if (f.getName().equals("..")) { up(); return; }
                if (f.isDirectory()) open(f); else launch(f);
            }
        });

        findViewById(R.id.btn_url).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askUrl(); }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        });

        ensureStoragePermissionsThenLoad();
    }

    private void ensureStoragePermissionsThenLoad() {
        if (Build.VERSION.SDK_INT >= 23) {
            List<String> needed = new ArrayList<String>();
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(android.Manifest.permission.READ_MEDIA_VIDEO);
                }
                if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(android.Manifest.permission.READ_MEDIA_AUDIO);
                }
                if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(android.Manifest.permission.READ_MEDIA_IMAGES);
                }
            } else {
                if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
                }
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), 101);
                return;
            }
        }
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            refresh();
        }
    }

    private void refresh() {
        roots = findRoots();
        if (current == null) showRoots(); else open(current);
    }

    private List<File> findRoots() {
        Map<String, File> found = new LinkedHashMap<String, File>();
        addIfReadable(found, Environment.getExternalStorageDirectory());
        String sec = System.getenv("SECONDARY_STORAGE");
        if (sec != null) for (String p : sec.split(":")) addIfReadable(found, new File(p));
        String emu = System.getenv("EMULATED_STORAGE_TARGET");
        if (emu != null) addIfReadable(found, new File(emu));
        if (Build.VERSION.SDK_INT >= 19) {
            for (File d : getExternalFilesDirs(null)) {
                File r = ascendToVolumeRoot(d);
                if (r != null) addIfReadable(found, r);
            }
        }
        for (String base : new String[]{"/storage", "/mnt", "/mnt/media_rw", "/usb_storage", "/udisk"}) {
            File dir = new File(base);
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (f.isDirectory() && !isSystemMount(f.getName())) {
                    addIfReadable(found, f);
                }
            }
        }
        File emu0 = new File("/storage/emulated/0");
        addIfReadable(found, emu0);
        return new ArrayList<File>(found.values());
    }

    private static boolean isSystemMount(String n) {
        return n.equals("emulated") || n.equals("self") || n.equals("asec") || n.equals("obb")
                || n.equals("secure") || n.equals("shell") || n.equals("runtime") || n.equals("knox-emulated");
    }

    private static void addIfReadable(Map<String, File> map, File f) {
        if (f == null || !f.isDirectory() || !f.canRead()) return;
        File c = canon(f);
        if (!map.containsKey(c.getPath())) map.put(c.getPath(), c);
    }

    private static File canon(File f) {
        try { return f.getCanonicalFile(); } catch (IOException e) { return f; }
    }

    private static File ascendToVolumeRoot(File d) {
        if (d == null) return null;
        File cur = d;
        while (cur.getParentFile() != null) {
            File p = cur.getParentFile();
            if (p.getName().equals("storage") || p.getName().equals("mnt") || p.getName().equals("media_rw")) return cur;
            if (p.getPath().equals("/")) break;
            cur = p;
        }
        return null;
    }

    private String rootLabel(File f) {
        File primary = canon(Environment.getExternalStorageDirectory());
        if (f.equals(primary)) return "Internal Storage";
        return "USB / SD — " + f.getName();
    }

    private void showRoots() {
        current = null;
        pathView.setText("Storage (" + roots.size() + " volumes)");
        items.clear();
        items.addAll(roots);
        adapter.notifyDataSetChanged();
    }

    private void open(File dir) {
        File[] list = dir.listFiles();
        if (list == null) {
            Toast.makeText(this, "Cannot read " + dir.getName(), Toast.LENGTH_SHORT).show();
            return;
        }
        current = dir;
        pathView.setText(dir.getAbsolutePath());
        List<File> dirs = new ArrayList<File>(), files = new ArrayList<File>();
        for (File f : list) {
            if (f.isHidden() || f.getName().startsWith(".")) continue;
            if (f.isDirectory()) dirs.add(f); else files.add(f);
        }
        Comparator<File> byName = new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        };
        Collections.sort(dirs, byName);
        Collections.sort(files, byName);

        items.clear();
        items.add(new File(dir, ".."));
        items.addAll(dirs);
        items.addAll(files);
        adapter.notifyDataSetChanged();
    }

    private void up() {
        if (current == null) return;
        for (File r : roots) {
            if (r.equals(current)) { showRoots(); return; }
        }
        File p = current.getParentFile();
        if (p != null && p.canRead()) open(p); else showRoots();
    }

    private void launch(File f) {
        String e = ext(f);
        if (IMAGE.contains(e)) {
            startActivity(new Intent(this, ImageActivity.class).putExtra("path", f.getAbsolutePath()));
        } else if (VIDEO.contains(e) || AUDIO.contains(e) || LISTS.contains(e)) {
            startActivity(new Intent(this, PlayerActivity.class).putExtra("uri", Uri.fromFile(f).toString()));
        } else {
            // Open any other file type via system viewer or fallback to media player if needed
            try {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(Uri.fromFile(f), "*/*");
                viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(Intent.createChooser(viewIntent, f.getName()));
            } catch (ActivityNotFoundException ex) {
                startActivity(new Intent(this, PlayerActivity.class).putExtra("uri", Uri.fromFile(f).toString()));
            }
        }
    }

    private void askUrl() {
        final EditText input = new EditText(this);
        input.setHint(R.string.url_hint);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.network_stream)
                .setView(input)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String u = input.getText().toString().trim();
                        if (!u.isEmpty()) {
                            if (!u.contains("://")) u = "http://" + u;
                            startActivity(new Intent(MainActivity.this, PlayerActivity.class).putExtra("uri", u));
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (current != null) up(); else super.onBackPressed();
    }
}
