package com.limelight.pocket;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Talks to "Artemis Pocket PC" (pc-setup/ArtemisPocket-PC.ps1), the small helper that runs on
 * the gaming PC next to Apollo. It gives the handheld what the streaming protocol can't:
 * real PC power control, the PC's status and microphone passthrough.
 *
 * Protocol (TCP 47110, one text line per message):
 *   client  "APC1 <token or ->"          server "OK <authed 0|1> <pc name>"
 *   "PING" -> "PONG"   "TOKEN" -> "TOKEN <hex>" (only through the USB cable)
 *   "PAIR <pin>" -> "TOKEN <hex>" | "ERR pin" | "ERR locked"
 *   "STATUS" -> "STATUS cpu=.. ram=.. up=.. bat=.. ac=.."   "POWER <action>" -> "OK" | "ERR .."
 *   "MIC 48000 1" -> "OK <device>" then raw 16-bit PCM until the connection closes.
 * Over the USB cable (adb reverse to 127.0.0.1) the PC trusts the handheld; over Wi-Fi it needs
 * the token, which the handheld picks up by itself the first time it streams over the cable,
 * or by typing the PIN shown in the PC window.
 */
public final class PcCompanion {
    private PcCompanion() {}

    public static final int PORT = 47110;
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 5000;

    public interface Callback {
        /** reply is null when the PC helper couldn't be reached. Called on the main thread. */
        void onReply(String reply);
    }

    public static final class Session implements Closeable {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        public final boolean authed;
        public final String pcName;

        private Session(Socket socket, boolean authed, String pcName) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.authed = authed;
            this.pcName = pcName;
        }

        public String call(String line) throws IOException {
            send(line);
            return readLine();
        }

        public void send(String line) throws IOException {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        public String readLine() throws IOException {
            return PcCompanion.readLine(in);
        }

        public OutputStream rawOut() {
            return out;
        }

        public void setReadTimeout(int ms) throws IOException {
            socket.setSoTimeout(ms);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Opens a session (blocking; never on the main thread). Null when the helper isn't there. */
    public static Session open(Context context, String host, String pcUuid) {
        if (host == null) {
            return null;
        }
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            String token = PocketPrefs.getCompanionToken(context, pcUuid);
            OutputStream out = socket.getOutputStream();
            out.write(("APC1 " + (token != null && !token.isEmpty() ? token : "-") + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String hello = readLine(socket.getInputStream());
            if (hello == null || !hello.startsWith("OK ")) {
                socket.close();
                return null;
            }
            String[] parts = hello.split(" ", 3);
            boolean authed = parts.length > 1 && "1".equals(parts[1]);
            String name = parts.length > 2 ? parts[2] : "";
            return new Session(socket, authed, name);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    /** Runs one command (blocking). Returns the reply line, or null if unreachable. */
    public static String run(Context context, String host, String pcUuid, String command) {
        Session session = open(context, host, pcUuid);
        if (session == null) {
            return null;
        }
        // The helper only runs while someone is signed in to Windows: remember that it answers
        PocketPrefs.markCompanionSeen(context, pcUuid);
        try {
            if (!session.authed && !command.startsWith("PAIR") && !command.equals("PING")) {
                return "ERR auth";
            }
            String reply = session.call(command);
            if (reply != null && reply.startsWith("TOKEN ")) {
                PocketPrefs.setCompanionToken(context, pcUuid, reply.substring(6).trim());
            }
            return reply;
        } catch (IOException e) {
            return null;
        } finally {
            session.close();
        }
    }

    public static void runAsync(final Context context, final String host, final String pcUuid,
                                final String command, final Callback callback) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final String reply = run(app, host, pcUuid, command);
            if (callback != null) {
                main.post(() -> callback.onReply(reply));
            }
        }, "PocketCompanion").start();
    }

    /**
     * Through the USB cable the PC hands over its token (the cable is the proof of trust), so
     * the same PC can be controlled over Wi-Fi later without typing a PIN.
     */
    public static void syncTokenOverUsb(final Context context, final String pcUuid) {
        if (pcUuid == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String reply = run(app, UsbBridge.HOST_IP, pcUuid, "TOKEN");
            LimeLog.info("Artemis Pocket PC over USB: " + (reply == null ? "not running" : "token synced"));
        }, "PocketCompanionSync").start();
    }

    /** Where to find the helper for a stream host: the same address (127.0.0.1 over USB). */
    public static String hostFor(String streamHost) {
        return streamHost;
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < 1024) {
            int b = in.read();
            if (b < 0) {
                return line.size() > 0 ? line.toString("UTF-8") : null;
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        return line.toString("UTF-8");
    }
}
