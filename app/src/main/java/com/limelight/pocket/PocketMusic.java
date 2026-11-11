package com.limelight.pocket;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * Music behind the console's screens, like a console's home: a calm, endless piece made on the
 * handheld (soft pads, a few bells, a low note, a room), or a track of your own on a loop. It
 * plays only while a Pocket Nexus screen is in front (never during a game or a stream), fades
 * in and out, and stays quiet when another app is already playing music.
 */
public final class PocketMusic {
    private PocketMusic() {}

    public static final String KEY_MODE = "pocket_music";
    public static final String KEY_VOLUME = "pocket_music_volume";
    public static final String KEY_URI = "pocket_music_uri";
    public static final String MODE_OFF = "off";
    public static final String MODE_AMBIENT = "ambient";
    public static final String MODE_FILE = "file";
    public static final String[] MODES = {MODE_OFF, MODE_AMBIENT, MODE_FILE};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static int shown;
    private static Engine engine;
    private static MediaPlayer player;
    private static final Runnable STOP = PocketMusic::stopNow;

    public static String mode(Context c) {
        return PocketPrefs.prefs(c).getString(KEY_MODE, MODE_AMBIENT);
    }

    public static void setMode(Context c, String mode) {
        PocketPrefs.prefs(c).edit().putString(KEY_MODE, mode).apply();
        restart(c);
    }

    /** 0..100. */
    public static int volume(Context c) {
        return PocketPrefs.prefs(c).getInt(KEY_VOLUME, 30);
    }

    public static void setVolume(Context c, int v) {
        PocketPrefs.prefs(c).edit().putInt(KEY_VOLUME, Math.max(0, Math.min(100, v))).apply();
        float g = gain(c);
        if (engine != null) {
            engine.level = g;
        }
        if (player != null) {
            try {
                player.setVolume(g, g);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static void setTrack(Context c, Uri uri) {
        PocketPrefs.prefs(c).edit().putString(KEY_URI, uri != null ? uri.toString() : null).apply();
        restart(c);
    }

    private static float gain(Context c) {
        float v = volume(c) / 100f;
        return v * v; // loudness feels linear this way
    }

    /** A console screen came to the front. */
    public static void screenShown(Context c) {
        app = c.getApplicationContext();
        shown++;
        MAIN.removeCallbacks(STOP);
        start();
    }

    /** It went away (the next one may come right after: wait a moment before stopping). */
    public static void screenHidden() {
        shown = Math.max(0, shown - 1);
        if (shown == 0) {
            MAIN.removeCallbacks(STOP);
            MAIN.postDelayed(STOP, 700);
        }
    }

    private static void restart(Context c) {
        app = c.getApplicationContext();
        stopNow();
        if (shown > 0) {
            start();
        }
    }

    private static void start() {
        Context c = app;
        if (c == null || engine != null || player != null) {
            return;
        }
        String mode = mode(c);
        if (MODE_OFF.equals(mode) || volume(c) == 0) {
            return;
        }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isMusicActive()) {
            return; // someone's listening to something else
        }
        if (MODE_FILE.equals(mode)) {
            String uri = PocketPrefs.prefs(c).getString(KEY_URI, null);
            if (uri == null) {
                return;
            }
            try {
                MediaPlayer p = new MediaPlayer();
                p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                p.setDataSource(c, Uri.parse(uri));
                p.setLooping(true);
                final float g = gain(c);
                p.setVolume(0f, 0f);
                p.setOnPreparedListener(mp -> {
                    mp.start();
                    fadePlayer(mp, 0f, g, 1200);
                });
                p.prepareAsync();
                player = p;
            } catch (Exception e) {
                player = null;
            }
            return;
        }
        engine = new Engine(gain(c));
        engine.start();
    }

    private static void fadePlayer(final MediaPlayer p, final float from, final float to, final long ms) {
        final long t0 = android.os.SystemClock.uptimeMillis();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (p != player) {
                    return;
                }
                float k = Math.min(1f, (android.os.SystemClock.uptimeMillis() - t0) / (float) ms);
                float v = from + (to - from) * k;
                try {
                    p.setVolume(v, v);
                } catch (RuntimeException ignored) {
                    return;
                }
                if (k < 1f) {
                    MAIN.postDelayed(this, 30);
                }
            }
        });
    }

    private static void stopNow() {
        if (engine != null) {
            engine.finish();
            engine = null;
        }
        if (player != null) {
            final MediaPlayer p = player;
            player = null;
            try {
                p.stop();
            } catch (RuntimeException ignored) {
            }
            p.release();
        }
    }

    // ---- The generated music ------------------------------------------------------------------

    /** Makes the music sample by sample on a thread of its own and plays it. */
    static final class Engine extends Thread {
        private static final int RATE = 44100;
        private static final int TABLE = 4096;
        private static final float[] SINE = new float[TABLE];
        private static final float[] WARM = new float[TABLE];

        static {
            for (int i = 0; i < TABLE; i++) {
                double ph = 2 * Math.PI * i / TABLE;
                SINE[i] = (float) Math.sin(ph);
                double w = 0;
                for (int h = 1; h <= 9; h++) {
                    w += Math.sin(ph * h) / Math.pow(h, 1.45);
                }
                WARM[i] = (float) (w * 0.55);
            }
        }

        volatile float level;
        private volatile boolean stopping;
        private float fade;
        private final Random rnd = new Random();

        // Harmony: chords as semitones from the key's root (maj9, m9, maj7#11, sus2)
        private static final int[][] CHORDS = {{0, 4, 7, 11, 14}, {9, 12, 16, 19, 23}, {5, 9, 12, 16, 18}, {7, 9, 14, 17, 21},
                {2, 5, 9, 12, 16}, {4, 7, 11, 14, 19}};
        private static final int[][] PROGRESSIONS = {{0, 1, 2, 3}, {0, 4, 2, 3}, {0, 5, 1, 2}, {1, 2, 0, 3}};
        private final int key;
        private final int[] progression;
        private int chord = -1;
        private long sample;
        private static final int CHORD_SAMPLES = (int) (RATE * 9.6);

        // Pad voices (two detuned oscillators each), crossfaded from chord to chord
        private static final int PAD = 5;
        private final float[] padFreq = new float[PAD * 2];
        private final float[] padTarget = new float[PAD * 2];
        private final float[] padPhaseA = new float[PAD * 2];
        private final float[] padPhaseB = new float[PAD * 2];
        private final float[] padLp = new float[2];
        private float padAmp;

        // Bells
        private static final int BELLS = 8;
        private final float[] bellFreq = new float[BELLS];
        private final float[] bellPhase = new float[BELLS];
        private final float[] bellPhase2 = new float[BELLS];
        private final float[] bellAmp = new float[BELLS];
        private final float[] bellPan = new float[BELLS];
        private int nextBell;
        private float bellDecay;

        // Low note
        private float subFreq;
        private float subTarget;
        private float subPhase;

        // Room (a small Freeverb: combs then allpasses, per side)
        private final float[][] combs = new float[8][];
        private final int[] combPos = new int[8];
        private final float[] combLp = new float[8];
        private final float[][] alls = new float[4][];
        private final int[] allPos = new int[4];
        private final float[] echoL = new float[(int) (RATE * 0.43)];
        private final float[] echoR = new float[(int) (RATE * 0.57)];
        private int echoPosL;
        private int echoPosR;

        Engine(float level) {
            super("PocketMusic");
            this.level = level;
            setDaemon(true);
            key = 48 + new int[] {0, 2, 3, 5, 7, 9}[rnd.nextInt(6)] - 12 * (rnd.nextInt(3) == 0 ? 1 : 0) + 12;
            progression = PROGRESSIONS[rnd.nextInt(PROGRESSIONS.length)];
            int[] combLen = {1557, 1617, 1491, 1422, 1580, 1640, 1514, 1445};
            for (int i = 0; i < 8; i++) {
                combs[i] = new float[combLen[i] + (i >= 4 ? 23 : 0)];
            }
            int[] allLen = {556, 441, 579, 464};
            for (int i = 0; i < 4; i++) {
                alls[i] = new float[allLen[i]];
            }
            bellDecay = (float) Math.exp(-1.0 / (RATE * 0.9));
        }

        private static float midi(float m) {
            return (float) (440.0 * Math.pow(2, (m - 69) / 12.0));
        }

        private void nextChord() {
            chord = (chord + 1) % progression.length;
            int[] c = CHORDS[progression[chord]];
            for (int v = 0; v < PAD; v++) {
                float f = midi(key - 12 + c[v % c.length]);
                padTarget[v * 2] = f * 0.9995f;
                padTarget[v * 2 + 1] = f * 1.0006f;
                if (padFreq[v * 2] == 0) {
                    padFreq[v * 2] = padTarget[v * 2];
                    padFreq[v * 2 + 1] = padTarget[v * 2 + 1];
                }
            }
            subTarget = midi(key - 24 + c[0]);
            if (subFreq == 0) {
                subFreq = subTarget;
            }
        }

        private void bell() {
            int[] c = CHORDS[progression[Math.max(0, chord)]];
            int i = nextBell;
            nextBell = (nextBell + 1) % BELLS;
            bellFreq[i] = midi(key + c[rnd.nextInt(c.length)] + (rnd.nextInt(3) == 0 ? 12 : 0));
            bellPhase[i] = 0;
            bellPhase2[i] = 0;
            bellAmp[i] = 0.09f + rnd.nextFloat() * 0.06f;
            bellPan[i] = 0.2f + rnd.nextFloat() * 0.6f;
        }

        private float comb(int i, float in) {
            float[] buf = combs[i];
            int p = combPos[i];
            float out = buf[p];
            combLp[i] = out * 0.7f + combLp[i] * 0.3f;
            buf[p] = in + combLp[i] * 0.86f;
            combPos[i] = (p + 1) % buf.length;
            return out;
        }

        private float allpass(int i, float in) {
            float[] buf = alls[i];
            int p = allPos[i];
            float b = buf[p];
            float out = -in + b;
            buf[p] = in + b * 0.5f;
            allPos[i] = (p + 1) % buf.length;
            return out;
        }

        void finish() {
            stopping = true;
        }

        @Override
        public void run() {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                return;
            }
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track;
            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(min * 4, 16384))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                track.play();
            } catch (RuntimeException e) {
                return;
            }
            final int frames = 1024;
            short[] out = new short[frames * 2];
            nextChord();
            int bellIn = RATE;
            try {
                while (true) {
                    float targetFade = stopping ? 0f : 1f;
                    for (int f = 0; f < frames; f++) {
                        // Fades: in over 2.5 s, out over 0.8 s
                        if (fade < targetFade) {
                            fade = Math.min(targetFade, fade + 1f / (RATE * 2.5f));
                        }
                        else if (fade > targetFade) {
                            fade = Math.max(0f, fade - 1f / (RATE * 0.8f));
                        }
                        if (sample % CHORD_SAMPLES == 0 && sample > 0) {
                            nextChord();
                        }
                        float pos = (sample % CHORD_SAMPLES) / (float) CHORD_SAMPLES;
                        // The pad breathes with each chord (swells in, eases before the next)
                        float breath = pos < 0.25f ? pos / 0.25f : (pos > 0.85f ? 0.55f + 0.45f * (1f - pos) / 0.15f : 1f);
                        padAmp += (breath * 0.11f - padAmp) * 0.00005f;

                        float l = 0;
                        float r = 0;
                        for (int v = 0; v < PAD * 2; v++) {
                            padFreq[v] += (padTarget[v] - padFreq[v]) * 0.00004f;
                            padPhaseA[v] += padFreq[v] / RATE;
                            if (padPhaseA[v] >= 1f) {
                                padPhaseA[v] -= 1f;
                            }
                            float s = WARM[(int) (padPhaseA[v] * TABLE) & (TABLE - 1)];
                            if ((v & 1) == 0) {
                                l += s;
                                r += s * 0.6f;
                            }
                            else {
                                r += s;
                                l += s * 0.6f;
                            }
                        }
                        // Warm it (a slowly moving low-pass)
                        float sweep = 0.035f + 0.02f * (float) Math.sin(sample / (double) RATE * 0.21);
                        padLp[0] += (l - padLp[0]) * sweep;
                        padLp[1] += (r - padLp[1]) * sweep;
                        l = padLp[0] * padAmp;
                        r = padLp[1] * padAmp;

                        // The low note
                        subFreq += (subTarget - subFreq) * 0.00004f;
                        subPhase += subFreq / RATE;
                        if (subPhase >= 1f) {
                            subPhase -= 1f;
                        }
                        float sub = SINE[(int) (subPhase * TABLE) & (TABLE - 1)] * 0.07f;
                        l += sub;
                        r += sub;

                        // Bells now and then
                        if (--bellIn <= 0) {
                            bell();
                            bellIn = (int) (RATE * (0.9f + rnd.nextFloat() * 1.8f));
                        }
                        for (int b = 0; b < BELLS; b++) {
                            if (bellAmp[b] < 0.0005f) {
                                continue;
                            }
                            bellPhase[b] += bellFreq[b] / RATE;
                            if (bellPhase[b] >= 1f) {
                                bellPhase[b] -= 1f;
                            }
                            bellPhase2[b] += bellFreq[b] * 3.01f / RATE;
                            if (bellPhase2[b] >= 1f) {
                                bellPhase2[b] -= 1f;
                            }
                            float s = (SINE[(int) (bellPhase[b] * TABLE) & (TABLE - 1)]
                                    + 0.18f * SINE[(int) (bellPhase2[b] * TABLE) & (TABLE - 1)]) * bellAmp[b];
                            bellAmp[b] *= bellDecay;
                            l += s * (1f - bellPan[b]);
                            r += s * bellPan[b];
                        }

                        // Echoes
                        float el = echoL[echoPosL];
                        float er = echoR[echoPosR];
                        echoL[echoPosL] = r * 0.5f + el * 0.32f;
                        echoR[echoPosR] = l * 0.5f + er * 0.32f;
                        echoPosL = (echoPosL + 1) % echoL.length;
                        echoPosR = (echoPosR + 1) % echoR.length;
                        l += el * 0.35f;
                        r += er * 0.35f;

                        // Room
                        float in = (l + r) * 0.12f;
                        float wl = comb(0, in) + comb(1, in) + comb(2, in) + comb(3, in);
                        float wr = comb(4, in) + comb(5, in) + comb(6, in) + comb(7, in);
                        wl = allpass(1, allpass(0, wl));
                        wr = allpass(3, allpass(2, wr));
                        l = l * 0.8f + wl * 0.42f;
                        r = r * 0.8f + wr * 0.42f;

                        float g = level * fade;
                        int sl = (int) (Math.max(-1f, Math.min(1f, l * g)) * 32767);
                        int sr = (int) (Math.max(-1f, Math.min(1f, r * g)) * 32767);
                        out[f * 2] = (short) sl;
                        out[f * 2 + 1] = (short) sr;
                        sample++;
                    }
                    track.write(out, 0, out.length);
                    if (stopping && fade <= 0f) {
                        break;
                    }
                }
            } catch (RuntimeException ignored) {
            } finally {
                try {
                    track.stop();
                } catch (RuntimeException ignored) {
                }
                track.release();
            }
        }
    }
}
