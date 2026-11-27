package com.example.mediahub;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Image viewer; LEFT/RIGHT (remote D-pad) moves between images in the same folder. */
public class ImageActivity extends Activity {

    private final List<File> images = new ArrayList<File>();
    private int index;
    private ImageView view;
    private TextView caption;
    private Bitmap bitmap;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_image);
        view = (ImageView) findViewById(R.id.image);
        caption = (TextView) findViewById(R.id.caption);

        File start = new File(getIntent().getStringExtra("path"));
        File[] fs = start.getParentFile() == null ? null : start.getParentFile().listFiles();
        if (fs != null) {
            for (File f : fs) if (MainActivity.IMAGE.contains(MainActivity.ext(f))) images.add(f);
        }
        if (images.isEmpty()) images.add(start);
        Collections.sort(images, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        index = Math.max(0, images.indexOf(start));
        show();
    }

    private void show() {
        final File f = images.get(index);
        caption.setText((index + 1) + "/" + images.size() + "  " + f.getName());
        new AsyncTask<Void, Void, Bitmap>() {
            @Override protected Bitmap doInBackground(Void... v) { return decode(f, 1920); }
            @Override protected void onPostExecute(Bitmap bm) {
                if (bm == null) { caption.setText(caption.getText() + "  (cannot decode)"); return; }
                view.setImageBitmap(bm);
                if (bitmap != null) bitmap.recycle();
                bitmap = bm;
            }
        }.execute();
    }

    /** Down-samples so large photos do not exhaust memory on the box. */
    static Bitmap decode(File f, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int s = 1;
        while (o.outWidth / s > maxSide || o.outHeight / s > maxSide) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && index < images.size() - 1) { index++; show(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && index > 0) { index--; show(); return true; }
        return super.onKeyDown(code, ev);
    }
}
