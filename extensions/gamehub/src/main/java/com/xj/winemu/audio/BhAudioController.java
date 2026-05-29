package com.xj.winemu.audio;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * BhAudioController — global "Recording-compatible audio" toggle.
 *
 * GameHub/BannerHub's PulseAudio sink ({@code module-aaudio-sink}) opens its
 * AAudio output stream in LOW_LATENCY performance mode by default, which the
 * framework grants as an MMAP stream. MMAP streams bypass the AudioFlinger
 * mixer that Android's MediaProjection {@code AudioPlaybackCapture}
 * (screen-record "internal audio") taps — so game audio plays through the
 * speaker but is SILENT in screen recordings. (The ALSA driver uses a legacy
 * mixed {@code AudioTrack} and is captured fine.)
 *
 * When this toggle is ON we append {@code " pm=0"} to the
 * {@code module-aaudio-sink} load line. The module computes
 * {@code AAudioStreamBuilder_setPerformanceMode(pm + 10)}, so {@code pm=0} →
 * {@code AAUDIO_PERFORMANCE_MODE_NONE} (10) → no low-latency request → no
 * MMAP → the stream sits on the normal mixer → captured by screen recording.
 *
 * <p>GLOBAL, not per-game: one switch for every container (toggle on before
 * recording, off after). Default OFF = stock low-latency behaviour, zero
 * regression for users who don't record. Trade-off when ON: slightly higher
 * audio latency (the cost of leaving the MMAP fast path).
 *
 * <p>Storage + lazy Context bootstrap mirror {@code BhGpuSpoofController}.
 */
public final class BhAudioController {

    private static final String TAG = "BhAudio";

    public static final String GLOBAL_PREFS_FILE  = "bh_audio_prefs";
    public static final String KEY_RECORDING_MODE = "bh_audio_recording_mode";

    private static final String PM_NONE_SUFFIX = " pm=0";

    private static volatile BhAudioController INSTANCE;

    private Context appContext;

    public static BhAudioController getInstance() {
        BhAudioController i = INSTANCE;
        if (i == null) {
            synchronized (BhAudioController.class) {
                i = INSTANCE;
                if (i == null) {
                    i = new BhAudioController();
                    INSTANCE = i;
                }
            }
        }
        return i;
    }

    /** Called by BhAudioSettingsActivity; harmless if the lazy bootstrap already ran. */
    public void init(Context ctx) {
        if (ctx != null && this.appContext == null) {
            this.appContext = ctx.getApplicationContext();
        }
    }

    public boolean isRecordingMode() {
        Context ctx = ctxOrNull();
        if (ctx == null) return false;
        try {
            return ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
                      .getBoolean(KEY_RECORDING_MODE, false);
        } catch (Throwable t) {
            Log.w(TAG, "isRecordingMode read failed", t);
            return false;
        }
    }

    public void setRecordingMode(boolean enabled) {
        Context ctx = ctxOrNull();
        if (ctx == null) {
            Log.w(TAG, "setRecordingMode: no context");
            return;
        }
        try {
            ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY_RECORDING_MODE, enabled).apply();
            Log.i(TAG, "recording-compatible audio = " + enabled);
        } catch (Throwable t) {
            Log.w(TAG, "setRecordingMode write failed", t);
        }
    }

    /**
     * Invoked from the patched PulseAudioComponent config builder with the raw
     * {@code "load-module module-aaudio-sink"} line. Returns it with
     * {@code " pm=0"} appended when the toggle is ON, otherwise unchanged.
     *
     * <p>FAIL-SAFE: any error, missing context, or already-present {@code pm=}
     * returns the input untouched (= stock behaviour) — a bug here can never
     * break audio, only fail to enable recording mode.
     */
    public static String configLine(String original) {
        try {
            if (original == null) return null;
            if (original.contains("pm=")) return original;
            if (!getInstance().isRecordingMode()) return original;
            return original + PM_NONE_SUFFIX;
        } catch (Throwable t) {
            Log.w(TAG, "configLine failed; leaving config unchanged", t);
            return original;
        }
    }

    private Context ctxOrNull() {
        ensureContext();
        return appContext;
    }

    private void ensureContext() {
        if (appContext != null) return;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Context) {
                appContext = ((Context) app).getApplicationContext();
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureContext failed", t);
        }
    }
}
