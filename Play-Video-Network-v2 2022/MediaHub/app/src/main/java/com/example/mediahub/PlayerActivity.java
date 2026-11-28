package com.example.mediahub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * VLC-based player tuned for ARM Cortex-A9 + Mali-450 (Android 4.3, API 18).
 * Single-row bottom bar with:
 * - Video seek / jump to specific time
 * - Video speed control
 * - Jump to next time step / item
 * - Media & stream info dialog
 * - Playlist / display list picker (M3U channels or folder media list)
 */
public class PlayerActivity extends Activity {

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout layout;
    private View overlay;
    private TextView titleView, tNow, tTot;
    private SeekBar seek;
    private ImageButton btnPlay;
    private Button btnJump, btnSpeed, btnNextTime, btnInfo, btnChannels;
    private boolean dragging;
    private final Handler h = new Handler();

    private final List<M3uPlaylistParser.ChannelItem> playlist = new ArrayList<M3uPlaylistParser.ChannelItem>();
    private int currentChannelIndex = 0;
    private volatile boolean destroyed = false;

    private Uri currentPlayingUri;
    private String currentPlayingTitle;
    private boolean triedSoftwareFallback = false;

    private final float[] speeds = new float[]{0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private int speedIndex = 2; // 1.0x default

    private final long[] nextTimeStepsMs = new long[]{30000L, 60000L, 300000L}; // +30s, +1m, +5m
    private int nextTimeStepIdx = 1; // +1m default

    private final MediaPlayer.ScaleType[] scaleTypes = new MediaPlayer.ScaleType[]{
            MediaPlayer.ScaleType.SURFACE_BEST_FIT,
            MediaPlayer.ScaleType.SURFACE_FILL,
            MediaPlayer.ScaleType.SURFACE_16_9,
            MediaPlayer.ScaleType.SURFACE_4_3
    };
    private int scaleIndex = 0;

    private final Runnable hide = new Runnable() {
        @Override public void run() { overlay.setVisibility(View.GONE); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null) {
                long len = player.getLength();
                long t = player.getTime();
                if (len > 0) {
                    seek.setMax((int) (len / 1000));
                    if (!dragging) seek.setProgress((int) (t / 1000));
                } else {
                    seek.setMax(100);
                    seek.setProgress(0);
                }
                tNow.setText(fmt(t));
                tTot.setText(len > 0 ? fmt(len) : "LIVE");
                btnPlay.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            }
            h.postDelayed(this, 500);
        }
    };

    static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    static String speedLabel(float r) {
        return String.format(Locale.US, "%.2fx", r);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        layout = (VLCVideoLayout) findViewById(R.id.video_layout);
        overlay = findViewById(R.id.overlay);
        titleView = (TextView) findViewById(R.id.title);
        tNow = (TextView) findViewById(R.id.t_now);
        tTot = (TextView) findViewById(R.id.t_tot);
        seek = (SeekBar) findViewById(R.id.seek);
        btnPlay = (ImageButton) findViewById(R.id.btn_play);
        btnJump = (Button) findViewById(R.id.btn_jump);
        btnSpeed = (Button) findViewById(R.id.btn_speed);
        btnNextTime = (Button) findViewById(R.id.btn_next_time);
        btnInfo = (Button) findViewById(R.id.btn_info);
        btnChannels = (Button) findViewById(R.id.btn_channels);

        Uri uri = getIntent().getData();
        if (uri == null && getIntent().getStringExtra("uri") != null) {
            uri = Uri.parse(getIntent().getStringExtra("uri"));
        }
        if (uri == null) { finish(); return; }

        ArrayList<String> opts = new ArrayList<String>();
        opts.add("--network-caching=" + Prefs.networkCachingMs(this));
        opts.add("--file-caching=1500");
        opts.add("--live-caching=" + Prefs.networkCachingMs(this));
        opts.add("--http-reconnect");
        opts.add("--avcodec-fast");
        opts.add("--avcodec-skiploopfilter=2");
        opts.add("--drop-late-frames");
        opts.add("--skip-frames");
        opts.add("--sub-autodetect-file");

        libVLC = new LibVLC(this, opts);
        player = new MediaPlayer(libVLC);
        player.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event e) {
                if (e.type == MediaPlayer.Event.EncounteredError) {
                    if (!triedSoftwareFallback && currentPlayingUri != null) {
                        triedSoftwareFallback = true;
                        playSingleUriInternal(currentPlayingUri, currentPlayingTitle, false);
                    } else {
                        Toast.makeText(PlayerActivity.this, "Cannot play this media", Toast.LENGTH_SHORT).show();
                    }
                } else if (e.type == MediaPlayer.Event.EndReached) {
                    h.post(new Runnable() {
                        @Override public void run() {
                            if (destroyed) return;
                            if (Prefs.autoNext(PlayerActivity.this) && currentChannelIndex < playlist.size() - 1) playChannel(currentChannelIndex + 1);
                            else finish();
                        }
                    });
                }
            }
        });
        player.attachViews(layout, null, false, false);

        findViewById(R.id.root).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (overlay.getVisibility() == View.VISIBLE) overlay.setVisibility(View.GONE); else showOverlay();
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        btnJump.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showJumpToTimeDialog(); }
        });
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleSpeed(); }
        });
        btnNextTime.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { jumpToNextTimeStep(); }
        });
        btnInfo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMediaInfoDialog(); }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(PlayerActivity.this, SettingsActivity.class)); }
        });
        btnChannels.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDisplayListDialog(); }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null && player.getLength() > 0) {
                    player.setTime(p * 1000L);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; showOverlay(); }
        });

        populateFolderFallbackListIfLocal(uri);
        startSource(uri);
        showOverlay();
        h.post(tick);
    }

    private void populateFolderFallbackListIfLocal(Uri uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getPath() == null) return;
        File currentFile = new File(uri.getPath());
        if (M3uPlaylistParser.isM3uPathOrUrl(currentFile.getAbsolutePath())) return;
        File parent = currentFile.getParentFile();
        if (parent == null || !parent.isDirectory()) return;
        File[] files = parent.listFiles();
        if (files == null) return;

        List<File> mediaFiles = new ArrayList<File>();
        for (File f : files) {
            if (f.isFile() && !f.getName().startsWith(".")) {
                String e = MainActivity.ext(f);
                if (MainActivity.VIDEO.contains(e) || MainActivity.AUDIO.contains(e)) {
                    mediaFiles.add(f);
                }
            }
        }
        Collections.sort(mediaFiles, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        playlist.clear();
        for (int i = 0; i < mediaFiles.size(); i++) {
            File mf = mediaFiles.get(i);
            playlist.add(new M3uPlaylistParser.ChannelItem(mf.getName(), Uri.fromFile(mf).toString()));
            if (mf.getAbsolutePath().equals(currentFile.getAbsolutePath())) {
                currentChannelIndex = i;
            }
        }
    }

    private void startSource(final Uri uri) {
        final String raw = "file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null
                ? uri.getPath()
                : uri.toString();

        if (M3uPlaylistParser.isM3uPathOrUrl(raw)) {
            M3uPlaylistParser.setLenientTls(Prefs.lenientTls(this));
            titleView.setText(R.string.loading_playlist);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!M3uPlaylistParser.hasExtraTrust()) {
                            try {
                                InputStream ca = getAssets().open("cacerts.pem");
                                try {
                                    M3uPlaylistParser.setExtraTrust(TrustBundle.composite(TrustBundle.parse(ca)));
                                } finally {
                                    ca.close();
                                }
                            } catch (Exception ignored) {
                                // no bundle: the device's own certificates are used
                            }
                        }
                        final List<M3uPlaylistParser.ChannelItem> parsed = M3uPlaylistParser.load(raw);
                        if (destroyed) return;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (destroyed) return;
                                if (parsed.isEmpty()) {
                                    playSingleUri(uri, null);
                                } else {
                                    playlist.clear();
                                    playlist.addAll(parsed);
                                    playChannel(0);
                                }
                            }
                        });
                    } catch (final Exception ex) {
                        if (destroyed) return;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (destroyed) return;
                                if (ex instanceof javax.net.ssl.SSLException) {
                                    Toast.makeText(PlayerActivity.this, R.string.ssl_failed, Toast.LENGTH_LONG).show();
                                }
                                playSingleUri(uri, null);
                            }
                        });
                    }
                }
            }).start();
        } else {
            playSingleUri(uri, null);
        }
    }

    private void playChannel(int index) {
        if (index < 0 || index >= playlist.size() || player == null) return;
        currentChannelIndex = index;
        M3uPlaylistParser.ChannelItem item = playlist.get(index);
        String label = "(" + (index + 1) + "/" + playlist.size() + ") " + item.title;
        Uri targetUri = item.url.startsWith("/") ? Uri.fromFile(new File(item.url)) : Uri.parse(item.url);
        playSingleUri(targetUri, label);
        showOverlay();
    }

    private void playSingleUri(Uri uri, String customTitle) {
        triedSoftwareFallback = false;
        currentPlayingUri = uri;
        currentPlayingTitle = customTitle;
        playSingleUriInternal(uri, customTitle, Prefs.hwDecoding(this));
    }

    private void playSingleUriInternal(Uri uri, String customTitle, boolean useHwDecoder) {
        if (player == null || libVLC == null || uri == null) return;
        String t = customTitle;
        if (t == null || t.isEmpty()) {
            t = uri.getLastPathSegment();
            if (t == null) t = uri.getHost();
            t = (t == null ? uri.toString() : Uri.decode(t));
        }
        titleView.setText(t);

        player.stop();
        Media m = new Media(libVLC, uri);
        m.setHWDecoderEnabled(useHwDecoder, false);
        player.setMedia(m);
        m.release();
        player.setRate(speeds[speedIndex]);
        player.play();
    }

    private void jumpToNextTimeStep() {
        if (player == null) return;
        long len = player.getLength();
        long step = nextTimeStepsMs[nextTimeStepIdx];
        if (len > 0) {
            long target = Math.min(len - 1000, player.getTime() + step);
            player.setTime(Math.max(0, target));
            String stepLabel = (step == 30000L) ? "+30s" : (step == 60000L ? "+1m" : "+5m");
            Toast.makeText(this, getString(R.string.next_time) + " (" + stepLabel + ")", Toast.LENGTH_SHORT).show();
        } else if (playlist.size() > 1) {
            int nextIdx = (currentChannelIndex + 1) % playlist.size();
            playChannel(nextIdx);
        }
        showOverlay();
    }

    private void showJumpToTimeDialog() {
        if (player == null) return;
        final long len = player.getLength();
        if (len <= 0) {
            Toast.makeText(this, "Seek not available for live stream", Toast.LENGTH_SHORT).show();
            return;
        }
        h.removeCallbacks(hide);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, pad / 2);

        TextView info = new TextView(this);
        info.setText("Current: " + fmt(player.getTime()) + "  /  Total: " + fmt(len));
        info.setPadding(0, 0, 0, pad / 2);
        container.addView(info);

        final EditText input = new EditText(this);
        input.setHint(R.string.jump_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        input.setText(fmt(player.getTime()));
        input.selectAll();
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.jump_to_time)
                .setView(container)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        long targetMs = parseTimeToMs(input.getText().toString().trim());
                        if (targetMs < 0) {
                            Toast.makeText(PlayerActivity.this, R.string.invalid_time, Toast.LENGTH_SHORT).show();
                        } else {
                            if (targetMs > len) targetMs = len - 1000;
                            if (targetMs < 0) targetMs = 0;
                            player.setTime(targetMs);
                        }
                        showOverlay();
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showOverlay();
                    }
                })
                .show();
    }

    static long parseTimeToMs(String text) {
        if (text == null || text.isEmpty()) return -1;
        try {
            String[] parts = text.split(":");
            long totalSec = 0;
            if (parts.length == 3) {
                long h = Long.parseLong(parts[0].trim());
                long m = Long.parseLong(parts[1].trim());
                long s = Long.parseLong(parts[2].trim());
                if (h < 0 || m < 0 || m >= 60 || s < 0 || s >= 60) return -1;
                totalSec = h * 3600 + m * 60 + s;
            } else if (parts.length == 2) {
                long m = Long.parseLong(parts[0].trim());
                long s = Long.parseLong(parts[1].trim());
                if (m < 0 || s < 0 || s >= 60) return -1;
                totalSec = m * 60 + s;
            } else if (parts.length == 1) {
                totalSec = Long.parseLong(parts[0].trim());
                if (totalSec < 0) return -1;
            } else {
                return -1;
            }
            return totalSec * 1000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void cycleSpeed() {
        if (player == null) return;
        speedIndex = (speedIndex + 1) % speeds.length;
        float r = speeds[speedIndex];
        player.setRate(r);
        btnSpeed.setText(speedLabel(r));
        showOverlay();
    }

    private void showMediaInfoDialog() {
        if (player == null) return;
        h.removeCallbacks(hide);

        long len = player.getLength();
        long t = player.getTime();
        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(titleView.getText()).append("\n");
        sb.append("Time: ").append(fmt(t)).append(" / ").append(len > 0 ? fmt(len) : "LIVE").append("\n");
        sb.append("Speed: ").append(speedLabel(speeds[speedIndex])).append("\n");
        sb.append("Decoder: ").append(triedSoftwareFallback ? "Software (SW)" : "Hardware (HW)").append("\n");
        sb.append("List items: ").append(playlist.size()).append("\n");
        if (currentPlayingUri != null) {
            sb.append("URI: ").append(currentPlayingUri.toString());
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.info_label)
                .setMessage(sb.toString())
                .setNeutralButton(R.string.aspect, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        scaleIndex = (scaleIndex + 1) % scaleTypes.length;
                        player.setVideoScale(scaleTypes[scaleIndex]);
                        showOverlay();
                    }
                })
                .setPositiveButton(R.string.tracks, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showTracksDialog();
                    }
                })
                .setNegativeButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showOverlay();
                    }
                })
                .show();
    }

    private void showTracksDialog() {
        if (player == null) return;
        final MediaPlayer.TrackDescription[] audioTracks = player.getAudioTracks();
        final MediaPlayer.TrackDescription[] spuTracks = player.getSpuTracks();

        final List<String> labels = new ArrayList<String>();
        final List<Integer> ids = new ArrayList<Integer>();
        final List<Boolean> isAudio = new ArrayList<Boolean>();

        if (audioTracks != null) {
            for (MediaPlayer.TrackDescription td : audioTracks) {
                labels.add("Audio: " + td.name);
                ids.add(td.id);
                isAudio.add(true);
            }
        }
        if (spuTracks != null) {
            for (MediaPlayer.TrackDescription td : spuTracks) {
                labels.add("Subtitle: " + td.name);
                ids.add(td.id);
                isAudio.add(false);
            }
        }

        if (labels.isEmpty()) {
            Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.tracks)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (isAudio.get(which)) {
                            player.setAudioTrack(ids.get(which));
                        } else {
                            player.setSpuTrack(ids.get(which));
                        }
                        showOverlay();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDisplayListDialog() {
        if (playlist.isEmpty()) {
            Toast.makeText(this, R.string.empty_playlist, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[playlist.size()];
        for (int i = 0; i < playlist.size(); i++) {
            names[i] = (i + 1) + ". " + playlist.get(i).title;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.channels)
                .setSingleChoiceItems(names, currentChannelIndex, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        playChannel(which);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showOverlay() {
        overlay.setVisibility(View.VISIBLE);
        h.removeCallbacks(hide);
        long delay = Prefs.hideDelayMs(this);
        if (delay > 0) h.postDelayed(hide, delay);   // 0 = controls stay visible
    }

    private void togglePlay() {
        if (player.isPlaying()) player.pause(); else player.play();
        showOverlay();
    }

    private void seekBy(long ms) {
        if (player.getLength() <= 0) return;
        player.setTime(Math.max(0, player.getTime() + ms));
        showOverlay();
    }

    private boolean resumeOnStart = false;
    private boolean rightDown = false, leftDown = false;
    private boolean rightLongPressTriggered = false;
    private long lastRightAccelMs = 0L;

    private boolean leftLongPressTriggered = false;
    private long lastLeftAccelMs = 0L;

    private final Runnable rightLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            rightLongPressTriggered = true;
            accelerateVideo();
        }
    };

    private final Runnable leftLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            leftLongPressTriggered = true;
            accelerateVideo();
        }
    };

    private void accelerateVideo() {
        if (player == null) return;
        if (speedIndex < speeds.length - 1) {
            speedIndex++;
        } else {
            speedIndex = 2; // wrap back to 1.0x after max speed
        }
        float r = speeds[speedIndex];
        player.setRate(r);
        btnSpeed.setText(speedLabel(r));
        Toast.makeText(this, "Speed: " + speedLabel(r), Toast.LENGTH_SHORT).show();
        showOverlay();
    }

    private void moveRightFiveSeconds() {
        if (player == null) return;
        long len = player.getLength();
        if (len > 0) {
            long target = Math.min(Math.max(0L, len - 1000L), player.getTime() + Prefs.seekStepMs(this));
            player.setTime(target);
            Toast.makeText(this, "+" + (Prefs.seekStepMs(this) / 1000) + "s (" + fmt(target) + ")", Toast.LENGTH_SHORT).show();
        }
        showOverlay();
    }

    private void rewindVideoBackward() {
        if (player == null) return;
        long len = player.getLength();
        if (len > 0) {
            long target = Math.max(0L, player.getTime() - Prefs.seekStepMs(this));
            player.setTime(target);
            Toast.makeText(this, "-" + (Prefs.seekStepMs(this) / 1000) + "s (" + fmt(target) + ")", Toast.LENGTH_SHORT).show();
        }
        showOverlay();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (player == null) return super.onKeyDown(code, ev);
        boolean dpad = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (dpad && overlay.getVisibility() == View.VISIBLE) {
            showOverlay();                       // controls visible: let the D-pad move focus between buttons
            return super.onKeyDown(code, ev);
        }
        if (dpad && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            showOverlay();                       // controls hidden: UP/DOWN brings them back
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (overlay.getVisibility() != View.VISIBLE) {
                    togglePlay();
                    return true;
                }
                return super.onKeyDown(code, ev);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (ev.getRepeatCount() == 0) {
                    rightDown = true;
                    rightLongPressTriggered = false;
                    h.removeCallbacks(rightLongPressRunnable);
                    h.postDelayed(rightLongPressRunnable, 550L);
                } else if (ev.getRepeatCount() >= 2) {
                    long now = System.currentTimeMillis();
                    if (now - lastRightAccelMs > 650L) {
                        rightLongPressTriggered = true;
                        lastRightAccelMs = now;
                        accelerateVideo();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (ev.getRepeatCount() == 0) {
                    leftDown = true;
                    leftLongPressTriggered = false;
                    h.removeCallbacks(leftLongPressRunnable);
                    h.postDelayed(leftLongPressRunnable, 550L);
                } else if (ev.getRepeatCount() >= 2) {
                    long now = System.currentTimeMillis();
                    if (now - lastLeftAccelMs > 650L) {
                        leftLongPressTriggered = true;
                        lastLeftAccelMs = now;
                        accelerateVideo();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                showMediaInfoDialog();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
                return true;
            default:
                return super.onKeyDown(code, ev);
        }
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        if ((code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) && rightDown) {
            rightDown = false;
            h.removeCallbacks(rightLongPressRunnable);
            if (!rightLongPressTriggered) {
                moveRightFiveSeconds();
            }
            rightLongPressTriggered = false;
            return true;
        }
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND) && leftDown) {
            leftDown = false;
            h.removeCallbacks(leftLongPressRunnable);
            if (!leftLongPressTriggered) {
                rewindVideoBackward();
            }
            leftLongPressTriggered = false;
            return true;
        }
        return super.onKeyUp(code, ev);
    }

    @Override
    protected void onStop() {
        super.onStop();
        resumeOnStart = player != null && player.isPlaying();
        if (player != null) player.pause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (resumeOnStart && player != null) player.play();   // e.g. back from the settings screen
        resumeOnStart = false;
        showOverlay();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            player.stop();
            player.detachViews();
            player.release();
            player = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
    }
}
