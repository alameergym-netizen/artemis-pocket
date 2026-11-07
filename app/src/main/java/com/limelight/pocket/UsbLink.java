package com.limelight.pocket;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.provider.Settings;
import android.os.Build;

import com.limelight.LimeLog;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Streaming over the USB-C cable.
 *
 * Moonlight's protocol runs over IP, so the cable itself becomes the network: with Android's
 * "USB tethering" on, the handheld and the PC share a private point-to-point network (no router,
 * no Wi-Fi, no internet). This class finds that link, finds the PC on it, and remembers which
 * PC (by UUID) lives at the other end so streams prefer the cable whenever it is plugged in.
 */
public final class UsbLink {
    private UsbLink() {}

    public static final int HTTP_PORT = 47989;
    private static final int PROBE_TIMEOUT_MS = 350;
    private static final int SCAN_THREADS = 48;

    private static volatile String activeHostIp;
    private static volatile String activeHostUuid;

    public static final class TetherInfo {
        public final String interfaceName;
        public final Inet4Address localAddress;
        public final int prefixLength;

        TetherInfo(String interfaceName, Inet4Address localAddress, int prefixLength) {
            this.interfaceName = interfaceName;
            this.localAddress = localAddress;
            this.prefixLength = prefixLength;
        }

        public String describe() {
            return interfaceName + " " + localAddress.getHostAddress() + "/" + prefixLength;
        }
    }

    static boolean isUsbInterfaceName(String name) {
        return name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("usb");
    }

    /** Returns the USB tethering interface if the cable link is up, otherwise null. */
    public static TetherInfo findTetherInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            for (NetworkInterface nif : Collections.list(interfaces)) {
                String name = nif.getName();
                if (name == null || !isUsbInterfaceName(name)) {
                    continue;
                }
                try {
                    if (!nif.isUp()) {
                        continue;
                    }
                } catch (SocketException ignored) {
                    // Some kernels refuse the flags query; fall through and trust the address
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        return new TetherInfo(name, (Inet4Address) addr, ia.getNetworkPrefixLength());
                    }
                }
            }
        } catch (SocketException | RuntimeException e) {
            LimeLog.warning("USB link: interface enumeration failed: " + e);
        }
        return null;
    }

    public static boolean isLinkUp() {
        return findTetherInterface() != null;
    }

    /** USB tethering or the ADB bridge: any way of streaming over the cable. */
    public static boolean isAnyUsbLinkUp() {
        return UsbBridge.isConnected() || isLinkUp();
    }

    private static boolean isOnSameSubnet24(byte[] a, byte[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    /** True if the address is on the USB link that is currently up. */
    public static boolean isUsbAddress(String ip) {
        if (ip == null) {
            return false;
        }
        if (UsbBridge.HOST_IP.equals(ip)) {
            return UsbBridge.isConnected();
        }
        TetherInfo info = findTetherInterface();
        if (info == null) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if (!(addr instanceof Inet4Address)) {
                return false;
            }
            return isOnSameSubnet24(addr.getAddress(), info.localAddress.getAddress());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean probe(String ip, int timeoutMs) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, HTTP_PORT), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Looks for a PC answering on the Apollo/Sunshine HTTP port on the USB link.
     * The last known address is tried first, then the whole /24 is probed in parallel.
     * Returns the IP or null. Blocking; call it off the UI thread.
     */
    public static String scanForHost(TetherInfo info, String preferredIp) {
        final byte[] local = info.localAddress.getAddress();
        final String localIp = info.localAddress.getHostAddress();

        if (preferredIp != null && !preferredIp.equals(localIp)) {
            try {
                InetAddress pref = InetAddress.getByName(preferredIp);
                if (pref instanceof Inet4Address && isOnSameSubnet24(pref.getAddress(), local)
                        && probe(preferredIp, 600)) {
                    return preferredIp;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }

        List<Callable<String>> tasks = new ArrayList<>();
        final String prefix = (local[0] & 0xFF) + "." + (local[1] & 0xFF) + "." + (local[2] & 0xFF) + ".";
        for (int i = 1; i < 255; i++) {
            final String ip = prefix + i;
            if (ip.equals(localIp) || ip.equals(preferredIp)) {
                continue;
            }
            tasks.add(() -> {
                if (probe(ip, PROBE_TIMEOUT_MS)) {
                    return ip;
                }
                throw new IOException("closed");
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        try {
            return pool.invokeAny(tasks, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Cable / tethering state --------------------------------------------------------

    /** True while the USB-C port is connected to a host (a PC), whatever mode it's in. */
    public static boolean isUsbCableConnected(Context context) {
        try {
            Intent state = context.registerReceiver(null, new IntentFilter("android.hardware.usb.action.USB_STATE"));
            if (state != null) {
                return state.getBooleanExtra("connected", false);
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    public static final int TETHER_UNKNOWN = 0;
    public static final int TETHER_SUPPORTED = 1;
    public static final int TETHER_UNSUPPORTED = 2;

    /**
     * Asks Android whether this ROM can do USB tethering at all (it has interface patterns for it).
     * Some handheld ROMs strip it out, which also hides the switch in Settings.
     */
    public static int getTetheringSupport(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Object result = ConnectivityManager.class.getMethod("getTetherableUsbRegexs").invoke(cm);
            if (result instanceof String[]) {
                return ((String[]) result).length > 0 ? TETHER_SUPPORTED : TETHER_UNSUPPORTED;
            }
        } catch (Throwable ignored) {
        }
        return TETHER_UNKNOWN;
    }

    /** Opens the system screen with the "USB tethering" switch. */
    public static void openTetherSettings(Activity activity) {
        Intent[] intents = {
                new Intent("android.settings.TETHER_SETTINGS"),
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"),
                new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS),
        };
        for (Intent intent : intents) {
            try {
                activity.startActivity(intent);
                return;
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ---- Active host ------------------------------------------------------------------

    public static void setActiveHost(Context context, String ip, String uuid) {
        activeHostIp = ip;
        activeHostUuid = uuid;
        if (ip != null && uuid != null) {
            PocketPrefs.setLastUsbHost(context, ip, uuid);
        }
    }

    public static void clearActiveHost() {
        activeHostIp = null;
        activeHostUuid = null;
    }

    /**
     * If the given PC is the one at the other end of the cable and the cable is plugged in,
     * returns its USB address so the stream goes over the cable. Otherwise null.
     */
    public static String getUsbAddressFor(Context context, String pcUuid) {
        if (pcUuid == null) {
            return null;
        }
        String ip = activeHostIp;
        String uuid = activeHostUuid;
        if (ip == null) {
            // Fall back to what we saw last time (e.g. the app was restarted while plugged in)
            ip = PocketPrefs.getLastUsbIp(context);
            uuid = PocketPrefs.getLastUsbUuid(context);
        }
        if (ip == null || !pcUuid.equalsIgnoreCase(uuid)) {
            return null;
        }
        return isUsbAddress(ip) ? ip : null;
    }

    /**
     * Android reports "metered" when there is no default network at all, which is exactly the
     * USB-only case. A cable to your own PC is never metered, so don't drop to the metered bitrate.
     */
    public static boolean isStreamMetered(Context context, String host) {
        if (isUsbAddress(host)) {
            return false;
        }
        ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connMgr.getActiveNetwork() == null) {
            return false;
        }
        return connMgr.isActiveNetworkMetered();
    }
}
