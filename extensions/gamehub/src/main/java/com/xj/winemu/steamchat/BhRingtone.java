package com.xj.winemu.steamchat;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Incoming-call ringtone + vibrate engine for the in-game Steam chat overlay.
 *
 * <p>A ringtone selection is a token stored by {@link BhSteamChatController}:
 * <ul>
 *   <li>{@code "silent"} — no sound (the call box still pops)</li>
 *   <li>{@code "synth:<classic|chime|trill|beep>"} — code-generated tones via
 *       {@link ToneGenerator}, no bundled files</li>
 *   <li>{@code "asset:<file.mp3>"} — a bundled tone from
 *       {@code assets/bh_ringtones} (baked in by steamChatRingtonesAssetPatch)</li>
 *   <li>{@code "uri:<content-uri>"} — a user-picked MP3 (persistable URI)</li>
 * </ul>
 *
 * <p>Plays on the media stream so it's audible over game audio. {@link #startRing}
 * loops until {@link #stop}; {@link #preview} plays once (≤5s) for the settings
 * screen. All playback is reference-guarded by a session counter so a stop()
 * cancels any in-flight async prepare.
 */
public final class BhRingtone {

    private static final String TAG = "BhSteamChat";
    private static final String ASSET_DIR = "bh_ringtones";

    /** Synthesized tone ids, in menu order. */
    public static final String[] SYNTH_IDS = { "classic", "chime", "trill", "beep" };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static MediaPlayer player;
    private static ToneGenerator tone;
    private static Runnable toneLoop;
    private static Vibrator vibrator;
    private static int session;          // bumped on every start/stop to cancel async starts
    private static float currentVolume = 1f;  // 0..1, applied to media + synth

    private BhRingtone() {}

    // ── labels / listing (for the settings UI) ──────────────────────────────

    public static String[] bundledFiles(Context ctx) {
        try {
            String[] a = ctx.getAssets().list(ASSET_DIR);
            return a != null ? a : new String[0];
        } catch (Throwable t) { return new String[0]; }
    }

    public static String labelForToken(Context ctx, String token) {
        if (token == null || token.equals("silent")) return "Silent";
        if (token.startsWith("synth:")) return synthLabel(token.substring(6));
        if (token.startsWith("asset:")) return labelForFile(token.substring(6));
        if (token.startsWith("uri:")) return "Custom MP3";
        return token;
    }

    public static String synthLabel(String id) {
        if ("classic".equals(id)) return "Classic";
        if ("chime".equals(id)) return "Chime";
        if ("trill".equals(id)) return "Trill";
        if ("beep".equals(id)) return "Soft beep";
        return id;
    }

    /** "super_mario_brothers.mp3" → "Super Mario Brothers". */
    public static String labelForFile(String file) {
        String n = file == null ? "" : file;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replace('_', ' ').trim();
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == ' ') { cap = true; sb.append(c); continue; }
            if (cap && Character.isLetter(c)) { c = Character.toUpperCase(c); cap = false; }
            sb.append(c);
        }
        return sb.toString();
    }

    // ── ringing (looping) ───────────────────────────────────────────────────

    public static synchronized void startRing(Context ctx, String token, boolean vibrate, float volume) {
        stop();
        final int s = ++session;
        currentVolume = clampVol(volume);
        if (vibrate) startVibrate(ctx);
        if (token == null || token.equals("silent")) return;
        if (token.startsWith("synth:")) { startSynth(token.substring(6)); return; }
        playMedia(ctx, token, true, s);
    }

    /** Play a selection (looping, no vibrate) for the settings preview; stops on
     *  the next {@link #stop} (the settings ▶/■ play-pause toggle). */
    public static synchronized void preview(Context ctx, String token, float volume) {
        stop();
        final int s = ++session;
        currentVolume = clampVol(volume);
        if (token == null || token.equals("silent")) return;
        if (token.startsWith("synth:")) startSynth(token.substring(6));
        else playMedia(ctx, token, true, s);
    }

    /** Live volume change (0..1) — affects the currently-playing media tone; synth
     *  volume is fixed at start so it applies to the next play. */
    public static synchronized void setVolume(float volume) {
        currentVolume = clampVol(volume);
        if (player != null) { try { player.setVolume(currentVolume, currentVolume); } catch (Throwable ignore) {} }
    }

    private static float clampVol(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    private static void playMedia(final Context ctx, final String token, final boolean loop, final int s) {
        new Thread(new Runnable() { public void run() {
            MediaPlayer mp = new MediaPlayer();
            try {
                if (Build.VERSION.SDK_INT >= 21) {
                    mp.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                } else {
                    mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                }
                if (token.startsWith("asset:")) {
                    String file = token.substring(6);
                    AssetFileDescriptor afd = null;
                    try { afd = ctx.getAssets().openFd(ASSET_DIR + "/" + file); } catch (Throwable ignore) {}
                    if (afd != null) {
                        mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                        try { afd.close(); } catch (Throwable ignore) {}
                    } else {
                        File f = cacheAsset(ctx, file);   // fallback if asset is compressed
                        if (f == null) { mp.release(); return; }
                        mp.setDataSource(f.getAbsolutePath());
                    }
                } else if (token.startsWith("uri:")) {
                    mp.setDataSource(ctx, Uri.parse(token.substring(4)));
                } else { mp.release(); return; }
                mp.setLooping(loop);
                mp.prepare();
                synchronized (BhRingtone.class) {
                    if (session != s) { mp.release(); return; }
                    player = mp;
                    try { mp.setVolume(currentVolume, currentVolume); } catch (Throwable ignore) {}
                    if (!loop) mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        public void onCompletion(MediaPlayer m) {
                            synchronized (BhRingtone.class) {
                                if (player == m) { try { m.release(); } catch (Throwable ignore) {} player = null; }
                            }
                        }
                    });
                    mp.start();
                }
            } catch (Throwable t) {
                Log.w(TAG, "ringtone play failed: " + token, t);
                try { mp.release(); } catch (Throwable ignore) {}
            }
        }}).start();
    }

    private static File cacheAsset(Context ctx, String file) {
        try {
            File out = new File(ctx.getCacheDir(), "bh_rt_" + file);
            if (!out.exists() || out.length() == 0) {
                InputStream in = ctx.getAssets().open(ASSET_DIR + "/" + file);
                OutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.close(); in.close();
            }
            return out;
        } catch (Throwable t) { return null; }
    }

    // ── synthesized tones (looping via a Handler) ───────────────────────────

    private static void startSynth(final String id) {
        try {
            int vol = (int) (currentVolume * 100);
            if (vol < 1) vol = 1; if (vol > 100) vol = 100;
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, vol);
        } catch (Throwable t) { tone = null; return; }
        final int type, dur, interval;
        if ("chime".equals(id))      { type = ToneGenerator.TONE_PROP_BEEP2;  dur = 350;  interval = 2000; }
        else if ("trill".equals(id)) { type = ToneGenerator.TONE_PROP_BEEP2;  dur = 140;  interval = 260; }
        else if ("beep".equals(id))  { type = ToneGenerator.TONE_PROP_BEEP;   dur = 200;  interval = 1500; }
        else                         { type = ToneGenerator.TONE_SUP_RINGTONE; dur = 1200; interval = 3000; } // classic
        toneLoop = new Runnable() {
            public void run() {
                try { if (tone != null) tone.startTone(type, dur); } catch (Throwable ignore) {}
                MAIN.postDelayed(this, interval);
            }
        };
        MAIN.post(toneLoop);
    }

    // ── vibrate (looping) ───────────────────────────────────────────────────

    private static void startVibrate(Context ctx) {
        try {
            vibrator = (Vibrator) ctx.getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) { vibrator = null; return; }
            long[] pattern = { 0, 600, 800 };   // buzz 600ms, pause 800ms, repeat
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Throwable t) { vibrator = null; }
    }

    // ── stop everything ─────────────────────────────────────────────────────

    public static synchronized void stop() {
        session++;
        if (toneLoop != null) { MAIN.removeCallbacks(toneLoop); toneLoop = null; }
        if (tone != null) { try { tone.stopTone(); } catch (Throwable ignore) {} try { tone.release(); } catch (Throwable ignore) {} tone = null; }
        if (player != null) { try { player.stop(); } catch (Throwable ignore) {} try { player.release(); } catch (Throwable ignore) {} player = null; }
        if (vibrator != null) { try { vibrator.cancel(); } catch (Throwable ignore) {} vibrator = null; }
    }
}
