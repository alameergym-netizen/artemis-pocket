package com.limelight.pocket;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.limelight.R;
import com.limelight.pocket.ui.StreamMenuView;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Connection test: the link to the PC (USB-C, Wi-Fi band, signal, link speed), the ping and its
 * jitter, a recommended bitrate and the tips that matter for that link. Shown as a console panel.
 */
public final class ConnectionTest {
    private ConnectionTest() {}

    public static void run(final ConsoleActivity activity, final String host, final int port,
                           final boolean usb, final String pcName) {
        Toast.makeText(activity, R.string.pocket_nettest_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            long[] samples = new long[6];
            int ok = 0;
            for (int i = 0; i < samples.length; i++) {
                Socket socket = new Socket();
                long start = System.nanoTime();
                try {
                    socket.connect(new InetSocketAddress(host, port), 1000);
                    samples[ok++] = System.nanoTime() - start;
                } catch (IOException ignored) {
                } finally {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    break;
                }
            }
            final int count = ok;
            final long[] got = samples;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) {
                    show(activity, usb, pcName, got, count);
                }
            });
        }, "ConnectionTest").start();
    }

    private static String ms(double v) {
        return v < 1 ? "<1 ms" : Math.round(v) + " ms";
    }

    private static void show(final Context context, boolean usb, String pcName, long[] samples, int count) {
        final ConsoleActivity activity = (ConsoleActivity) context;
        List<StreamMenuView.Item> items = new ArrayList<>();
        List<String> tips = new ArrayList<>();
        int recommendedKbps;

        items.add(StreamMenuView.Item.header(context.getString(R.string.pocket_nettest_link)));
        if (usb) {
            items.add(info(context.getString(R.string.pocket_nettest_type), context.getString(R.string.pocket_link_usb)));
            recommendedKbps = 60000;
        }
        else {
            WifiInfo wifi = null;
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                //noinspection deprecation
                wifi = wm != null ? wm.getConnectionInfo() : null;
            } catch (RuntimeException ignored) {
            }
            if (wifi != null && wifi.getNetworkId() != -1) {
                int freq = wifi.getFrequency();
                String band = freq >= 5925 ? "6 GHz" : (freq >= 4900 ? "5 GHz" : "2.4 GHz");
                items.add(info(context.getString(R.string.pocket_nettest_type), "Wi-Fi " + band
                        + (freq > 0 ? "  ·  " + freq + " MHz" : "")));
                int rssi = wifi.getRssi();
                int quality = rssi > -55 ? R.string.pocket_nettest_excellent : rssi > -65 ? R.string.pocket_nettest_good
                        : rssi > -72 ? R.string.pocket_nettest_fair : R.string.pocket_nettest_weak;
                items.add(info(context.getString(R.string.pocket_nettest_signal), rssi + " dBm  ·  " + context.getString(quality)));
                int link = wifi.getLinkSpeed();
                if (link > 0) {
                    items.add(info(context.getString(R.string.pocket_nettest_speed), link + " Mbps"));
                }
                int configured = PreferenceManager.getDefaultSharedPreferences(context).getInt("seekbar_bitrate_kbps", 40000);
                boolean wasAdaptive = PocketPrefs.adaptiveStream(context);
                recommendedKbps = Math.max(PocketAdaptive.MIN_KBPS, link > 0 ? link * 1000 * 45 / 100 : configured);
                if (rssi < -75) {
                    recommendedKbps = Math.min(recommendedKbps, 20000);
                }
                else if (rssi < -67) {
                    recommendedKbps = Math.min(recommendedKbps, 40000);
                }
                recommendedKbps = Math.min(recommendedKbps, 150000) / 1000 * 1000;
                if (freq > 0 && freq < 4900) {
                    tips.add(context.getString(R.string.pocket_tip_24ghz));
                }
                if (rssi < -67) {
                    tips.add(context.getString(R.string.pocket_tip_signal));
                }
                if (!wasAdaptive) {
                    tips.add(context.getString(R.string.pocket_tip_adaptive));
                }
            }
            else {
                items.add(info(context.getString(R.string.pocket_nettest_type), context.getString(R.string.pocket_link_wifi)));
                recommendedKbps = 30000;
            }
        }

        items.add(StreamMenuView.Item.header(pcName != null ? pcName : "PC"));
        if (count == 0) {
            items.add(info(context.getString(R.string.pocket_nettest_ping), context.getString(R.string.pocket_nettest_no_reply)));
            tips.add(context.getString(usb ? R.string.pocket_tip_usb_bridge : R.string.pocket_tip_pc_awake));
        }
        else {
            double best = Double.MAX_VALUE;
            double sum = 0;
            for (int i = 0; i < count; i++) {
                double v = samples[i] / 1e6;
                best = Math.min(best, v);
                sum += v;
            }
            double avg = sum / count;
            double jitter = 0;
            for (int i = 0; i < count; i++) {
                jitter += Math.abs(samples[i] / 1e6 - avg);
            }
            jitter /= count;
            items.add(info(context.getString(R.string.pocket_nettest_ping), ms(best) + "  ·  "
                    + context.getString(R.string.pocket_nettest_avg, ms(avg))));
            items.add(info(context.getString(R.string.pocket_nettest_jitter), ms(jitter)));
            if (count < 6) {
                items.add(info(context.getString(R.string.pocket_nettest_lost), (6 - count) + " / 6"));
            }
            if (avg > 15 || jitter > 8) {
                tips.add(context.getString(R.string.pocket_tip_latency));
            }
        }

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isPowerSaveMode()) {
            tips.add(context.getString(R.string.pocket_tip_power_save));
        }

        items.add(StreamMenuView.Item.header(context.getString(R.string.pocket_nettest_result)));
        final int recommended = recommendedKbps;
        if (usb) {
            items.add(info(context.getString(R.string.pocket_nettest_recommended), (recommended / 1000) + " Mbps"));
        }
        else {
            items.add(StreamMenuView.Item.action(context.getString(R.string.pocket_nettest_use, recommended / 1000), () -> {
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putInt("seekbar_bitrate_kbps", recommended).apply();
                PocketPrefs.setAdaptiveCap(context, 0);
                Toast.makeText(context, context.getString(R.string.pocket_nettest_saved, recommended / 1000),
                        Toast.LENGTH_SHORT).show();
            }));
        }
        if (tips.isEmpty()) {
            tips.add(context.getString(R.string.pocket_tip_all_good));
        }
        for (String tip : tips) {
            items.add(StreamMenuView.Item.repeatable("•  " + tip, null));
        }
        activity.showConsolePanel(context.getString(R.string.pocket_nettest), pcName, null, items);
    }

    private static StreamMenuView.Item info(String title, String value) {
        return StreamMenuView.Item.repeatable(title, null).withValue(value);
    }
}
