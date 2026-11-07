package com.limelight.pocket;

import com.limelight.LimeLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * USB-C streaming for handhelds whose Android has USB tethering removed (e.g. Retroid ROMs).
 *
 * The PC runs ArtemisPocket-USB-Bridge, which uses "adb reverse" so that on the handheld
 * 127.0.0.1:47984/47989/48010 (Apollo's TCP ports) reach the PC over the cable, and
 * 127.0.0.1:47100 reaches the bridge's tunnel. ADB can only carry TCP, so the stream's UDP
 * traffic (video 47998, control 47999, audio 48000) is relayed here: we listen on those UDP
 * ports on 127.0.0.1, wrap each datagram in a small frame and send it through the tunnel;
 * the PC side unwraps it and sends it to Apollo, and the replies come back the same way.
 *
 * Frame: [1 byte channel][2 bytes length, big endian][payload]
 */
public final class UsbBridge {
    private UsbBridge() {}

    public static final String HOST_IP = "127.0.0.1";
    public static final int TUNNEL_PORT = 47100;
    private static final int[] UDP_PORTS = {47998, 47999, 48000, 48002};
    private static final byte[] HELLO = {'A', 'P', 'B', '1'};

    private static final Object lock = new Object();
    private static volatile Socket tunnel;
    private static volatile OutputStream tunnelOut;
    private static DatagramSocket[] udpSockets;
    private static final SocketAddress[] clientAddresses = new SocketAddress[UDP_PORTS.length];
    private static volatile boolean probing;

    public static boolean isConnected() {
        Socket s = tunnel;
        return s != null && s.isConnected() && !s.isClosed();
    }

    /**
     * Connects to the PC bridge if it's reachable through ADB. Blocking (well under a second
     * when nothing is there); call it off the UI thread. Returns true if the bridge is up.
     */
    public static boolean ensureConnected() {
        if (isConnected()) {
            return true;
        }
        synchronized (lock) {
            if (isConnected()) {
                return true;
            }
            Socket socket = new Socket();
            try {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(HOST_IP, TUNNEL_PORT), 400);
                socket.setSoTimeout(1500);
                InputStream in = socket.getInputStream();
                byte[] hello = new byte[HELLO.length];
                readFully(in, hello, HELLO.length);
                for (int i = 0; i < HELLO.length; i++) {
                    if (hello[i] != HELLO[i]) {
                        throw new IOException("Not an Artemis Pocket bridge");
                    }
                }
                socket.setSoTimeout(0);
                socket.setReceiveBufferSize(4 * 1024 * 1024);
                socket.setSendBufferSize(1024 * 1024);

                if (!startUdpRelays()) {
                    throw new IOException("Could not open the stream ports on the handheld");
                }

                tunnelOut = socket.getOutputStream();
                tunnel = socket;
                startTunnelReader(socket, in);
                LimeLog.info("USB bridge connected");
                return true;
            } catch (IOException | RuntimeException e) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                return false;
            }
        }
    }

    /** Probes in the background without blocking the caller. */
    public static void probeAsync() {
        if (probing || isConnected()) {
            return;
        }
        probing = true;
        Thread t = new Thread(() -> {
            try {
                ensureConnected();
            } finally {
                probing = false;
            }
        }, "USB bridge probe");
        t.setDaemon(true);
        t.start();
    }

    private static void closeTunnel(Socket socket) {
        synchronized (lock) {
            if (tunnel == socket) {
                tunnel = null;
                tunnelOut = null;
                LimeLog.info("USB bridge disconnected");
            }
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- UDP side (handheld <-> Moonlight core) --------------------------------------------

    private static boolean startUdpRelays() {
        if (udpSockets != null) {
            return true;
        }
        DatagramSocket[] sockets = new DatagramSocket[UDP_PORTS.length];
        try {
            InetAddress loopback = InetAddress.getByName(HOST_IP);
            for (int i = 0; i < UDP_PORTS.length; i++) {
                DatagramSocket ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(loopback, UDP_PORTS[i]));
                ds.setReceiveBufferSize(2 * 1024 * 1024);
                ds.setSendBufferSize(4 * 1024 * 1024);
                sockets[i] = ds;
            }
        } catch (IOException e) {
            LimeLog.warning("USB bridge: cannot bind UDP relay ports: " + e);
            for (DatagramSocket ds : sockets) {
                if (ds != null) {
                    ds.close();
                }
            }
            return false;
        }
        udpSockets = sockets;
        for (int i = 0; i < sockets.length; i++) {
            final int channel = i;
            final DatagramSocket ds = sockets[i];
            Thread t = new Thread(() -> udpReceiveLoop(channel, ds), "USB bridge UDP " + UDP_PORTS[i]);
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
        }
        return true;
    }

    private static void udpReceiveLoop(int channel, DatagramSocket ds) {
        byte[] buffer = new byte[65535 + 3];
        DatagramPacket packet = new DatagramPacket(buffer, 3, 65535);
        while (true) {
            try {
                packet.setData(buffer, 3, 65535);
                ds.receive(packet);
            } catch (SocketException e) {
                return;
            } catch (IOException e) {
                continue;
            }
            clientAddresses[channel] = packet.getSocketAddress();
            OutputStream out = tunnelOut;
            if (out == null) {
                continue; // bridge not connected: drop
            }
            int len = packet.getLength();
            buffer[0] = (byte) channel;
            buffer[1] = (byte) (len >> 8);
            buffer[2] = (byte) len;
            try {
                synchronized (out) {
                    out.write(buffer, 0, len + 3);
                }
            } catch (IOException e) {
                Socket s = tunnel;
                if (s != null) {
                    closeTunnel(s);
                }
            }
        }
    }

    // ---- Tunnel side (handheld <-> PC) -------------------------------------------------------

    private static void startTunnelReader(final Socket socket, final InputStream in) {
        Thread t = new Thread(() -> {
            byte[] header = new byte[3];
            byte[] payload = new byte[65535];
            DatagramPacket packet = new DatagramPacket(payload, 0);
            try {
                while (true) {
                    readFully(in, header, 3);
                    int channel = header[0] & 0xFF;
                    int len = ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
                    readFully(in, payload, len);
                    DatagramSocket[] sockets = udpSockets;
                    if (sockets == null || channel >= sockets.length) {
                        continue;
                    }
                    SocketAddress client = clientAddresses[channel];
                    if (client == null) {
                        continue; // Moonlight hasn't talked on this port yet
                    }
                    packet.setData(payload, 0, len);
                    packet.setSocketAddress(client);
                    try {
                        sockets[channel].send(packet);
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException e) {
                closeTunnel(socket);
            }
        }, "USB bridge tunnel");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private static void readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new IOException("Bridge closed");
            }
            offset += read;
        }
    }
}
