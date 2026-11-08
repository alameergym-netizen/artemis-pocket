package com.limelight.pocket;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.limelight.LimeLog;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Microphone passthrough: the handheld's mic goes to the PC through Artemis Pocket PC, which
 * plays it into a virtual cable (VB-Audio "CABLE Input") so games and Discord can use
 * "CABLE Output" as their microphone. 48 kHz mono, 20 ms packets, echo cancellation on so the
 * game sound from the handheld's speakers doesn't come back through the mic.
 */
public final class PocketMic {

    public static final int STATE_STARTED = 1;
    public static final int STATE_STOPPED = 2;
    public static final int STATE_NO_HELPER = 3;
    public static final int STATE_NOT_PAIRED = 4;
    public static final int STATE_NO_PERMISSION = 5;
    public static final int STATE_FAILED = 6;

    public interface Listener {
        /** detail: the PC playback device for STATE_STARTED. Called on the main thread. */
        void onMicState(int state, String detail);
    }

    private static final int RATE = 48000;
    private static final int FRAME_BYTES = RATE / 50 * 2; // 20 ms, mono, 16 bit

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile boolean muted;
    private volatile PcCompanion.Session session;
    private Thread thread;

    public boolean isRunning() {
        return running;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() {
        return muted;
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void start(Context context, final String host, final String pcUuid, final Listener listener) {
        if (running) {
            return;
        }
        if (!hasPermission(context)) {
            post(listener, STATE_NO_PERMISSION, null);
            return;
        }
        final Context app = context.getApplicationContext();
        running = true;
        muted = false;
        thread = new Thread(() -> runMic(app, host, pcUuid, listener), "PocketMic");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public void stop() {
        running = false;
        PcCompanion.Session s = session;
        if (s != null) {
            s.close();
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void post(final Listener listener, final int state, final String detail) {
        if (listener != null) {
            main.post(() -> listener.onMicState(state, detail));
        }
    }

    @SuppressLint("MissingPermission")
    private void runMic(Context context, String host, String pcUuid, Listener listener) {
        AudioRecord record = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        AutomaticGainControl agc = null;
        int endState = STATE_STOPPED;
        try {
            PcCompanion.Session s = PcCompanion.open(context, host, pcUuid);
            if (s == null) {
                endState = STATE_NO_HELPER;
                return;
            }
            session = s;
            if (!s.authed) {
                endState = STATE_NOT_PAIRED;
                return;
            }
            String reply = s.call("MIC " + RATE + " 1");
            if (reply == null || !reply.startsWith("OK")) {
                endState = STATE_FAILED;
                return;
            }
            String device = reply.length() > 3 ? reply.substring(3) : "";
            s.setReadTimeout(0);

            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME_BYTES * 4));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                endState = STATE_FAILED;
                return;
            }
            int sessionId = record.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId);
                if (aec != null) {
                    aec.setEnabled(true);
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId);
                if (ns != null) {
                    ns.setEnabled(true);
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId);
                if (agc != null) {
                    agc.setEnabled(true);
                }
            }
            record.startRecording();
            post(listener, STATE_STARTED, device);

            OutputStream out = s.rawOut();
            byte[] frame = new byte[FRAME_BYTES];
            byte[] silence = new byte[FRAME_BYTES];
            while (running) {
                int got = 0;
                while (got < FRAME_BYTES && running) {
                    int n = record.read(frame, got, FRAME_BYTES - got);
                    if (n <= 0) {
                        throw new IOException("mic read " + n);
                    }
                    got += n;
                }
                out.write(muted ? silence : frame, 0, FRAME_BYTES);
            }
        } catch (IOException | RuntimeException e) {
            if (running) {
                LimeLog.warning("Mic passthrough stopped: " + e);
                endState = STATE_FAILED;
            }
        } finally {
            running = false;
            if (record != null) {
                try {
                    record.stop();
                } catch (RuntimeException ignored) {
                }
                record.release();
            }
            if (aec != null) {
                aec.release();
            }
            if (ns != null) {
                ns.release();
            }
            if (agc != null) {
                agc.release();
            }
            PcCompanion.Session s = session;
            session = null;
            if (s != null) {
                s.close();
            }
            post(listener, endState, null);
        }
    }
}
