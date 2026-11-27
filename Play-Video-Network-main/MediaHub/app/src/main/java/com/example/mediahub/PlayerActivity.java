package com.example.mediahub;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/** Plays local files and web links (http/https/rtsp/rtmp/m3u8...) through libVLC. */
public class PlayerActivity extends Activity {

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout layout;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        layout = (VLCVideoLayout) findViewById(R.id.video_layout);

        Uri uri = getIntent().getData();
        if (uri == null && getIntent().getStringExtra("uri") != null) {
            uri = Uri.parse(getIntent().getStringExtra("uri"));
        }
        if (uri == null) { finish(); return; }

        ArrayList<String> opts = new ArrayList<String>();
        opts.add("--network-caching=3000");
        opts.add("--http-reconnect");
        opts.add("--no-drop-late-frames");   // keep it smooth on slow CPUs; remove if audio drifts
        libVLC = new LibVLC(this, opts);
        player = new MediaPlayer(libVLC);
        player.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event e) {
                if (e.type == MediaPlayer.Event.EncounteredError) {
                    Toast.makeText(PlayerActivity.this, "Cannot play this media", Toast.LENGTH_LONG).show();
                } else if (e.type == MediaPlayer.Event.EndReached) {
                    finish();
                }
            }
        });
        player.attachViews(layout, null, false, false);

        Media m = new Media(libVLC, uri);
        m.setHWDecoderEnabled(true, false);   // hardware decode if the SoC supports it, else software
        player.setMedia(m);
        m.release();
        player.play();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (player == null) return super.onKeyDown(code, ev);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (player.isPlaying()) player.pause(); else player.play();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                player.setTime(player.getTime() + 10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                player.setTime(Math.max(0, player.getTime() - 10000));
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
                return true;
            default:
                return super.onKeyDown(code, ev);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.stop();
            player.detachViews();
            player.release();
        }
        if (libVLC != null) libVLC.release();
    }
}
