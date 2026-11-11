package com.limelight.pocket.device;

import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.view.Display;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.ui.StreamMenuView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "This handheld": its chip (named the way people know it: Snapdragon 8 Gen 2 ...), GPU, what its
 * video decoders can do (the codecs and sizes a stream can use), the screen's refresh rates,
 * memory and how warm it is, and what Pocket Nexus tunes for that chip.
 */
public final class DeviceInfoPanel {
    private DeviceInfoPanel() {}

    /** Qualcomm and MediaTek model numbers, as they're sold. */
    static String chipName(String maker, String model) {
        if (model == null || model.isEmpty() || Build.UNKNOWN.equalsIgnoreCase(model)) {
            return null;
        }
        String m = model.toUpperCase(Locale.ROOT);
        String[][] known = {
                {"SM8750", "Snapdragon 8 Elite"}, {"SM8650", "Snapdragon 8 Gen 3"}, {"SM8635", "Snapdragon 8s Gen 3"},
                {"SM8550", "Snapdragon 8 Gen 2"}, {"QCS8550", "Snapdragon 8 Gen 2"}, {"SM8475", "Snapdragon 8+ Gen 1"},
                {"SM8450", "Snapdragon 8 Gen 1"}, {"SM8350", "Snapdragon 888"}, {"SM8250", "Snapdragon 865 / 870"},
                {"SM8150", "Snapdragon 855"}, {"SM7675", "Snapdragon 7+ Gen 3"}, {"SM7550", "Snapdragon 7 Gen 3"},
                {"SM7475", "Snapdragon 7+ Gen 2"}, {"SM7450", "Snapdragon 7 Gen 1"}, {"SM7325", "Snapdragon 778G"},
                {"SM6375", "Snapdragon 695"}, {"SM6225", "Snapdragon 680"}, {"SM6115", "Snapdragon 662"},
                {"G3X", "Snapdragon G3x"}, {"MT6989", "Dimensity 9300"}, {"MT6985", "Dimensity 9200"},
                {"MT6983", "Dimensity 9000"}, {"MT6895", "Dimensity 8100"}, {"MT6893", "Dimensity 1200"},
                {"MT6891", "Dimensity 1100"}, {"MT6877", "Dimensity 900 / 1080"}, {"MT8183", "Helio G90T"},
                {"T618", "Unisoc T618"}, {"T610", "Unisoc T610"}, {"T820", "Unisoc T820"},
        };
        for (String[] k : known) {
            if (m.startsWith(k[0])) {
                return k[1];
            }
        }
        return (maker != null ? maker + " " : "") + model;
    }

    /** The chip's name for a settings row ("Snapdragon 8 Gen 2"), or the board name. */
    public static String chipLabel() {
        String maker = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Build.SOC_MANUFACTURER : null;
        String model = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Build.SOC_MODEL : null;
        String chip = chipName(maker, model);
        return chip != null ? chip : Build.HARDWARE;
    }

    static boolean isQualcomm(String maker, String hardware) {
        String s = ((maker != null ? maker : "") + " " + (hardware != null ? hardware : "")).toLowerCase(Locale.ROOT);
        return s.contains("qualcomm") || s.contains("qti") || s.contains("qcom");
    }

    static boolean isMediaTek(String maker, String hardware) {
        String s = ((maker != null ? maker : "") + " " + (hardware != null ? hardware : "")).toLowerCase(Locale.ROOT);
        return s.contains("mediatek") || s.startsWith("mt") || s.contains(" mt");
    }

    public static void show(ConsoleActivity a) {
        List<StreamMenuView.Item> items = new ArrayList<>();
        String maker = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Build.SOC_MANUFACTURER : null;
        String model = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Build.SOC_MODEL : null;
        String chip = chipName(maker, model);
        items.add(StreamMenuView.Item.header(a.getString(R.string.pocket_info_chip)));
        items.add(StreamMenuView.Item.info(a.getString(R.string.pocket_info_device), Build.MANUFACTURER + " " + Build.MODEL));
        items.add(StreamMenuView.Item.info(a.getString(R.string.pocket_info_processor), chip != null ? chip : Build.HARDWARE));
        String gpu = com.limelight.preferences.GlPreferences.readPreferences(a).glRenderer;
        if (gpu != null && !gpu.isEmpty()) {
            items.add(StreamMenuView.Item.info(a.getString(R.string.pocket_info_gpu), gpu.replace("(TM)", "").trim()));
        }
        ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            items.add(StreamMenuView.Item.info(a.getString(R.string.pocket_info_memory), Formatter.formatShortFileSize(a, mi.totalMem)));
        }
        items.add(StreamMenuView.Item.info("Android", Build.VERSION.RELEASE + "  (API " + Build.VERSION.SDK_INT + ")"));

        items.add(StreamMenuView.Item.header(a.getString(R.string.pocket_info_video)));
        String[][] codecs = {{"video/av01", "AV1"}, {"video/hevc", "HEVC"}, {"video/avc", "H.264"}};
        for (String[] c : codecs) {
            items.add(StreamMenuView.Item.info(c[1], decoderLine(a, c[0])));
        }

        items.add(StreamMenuView.Item.header(a.getString(R.string.pocket_info_screen)));
        Display display = a.getWindowManager().getDefaultDisplay();
        Set<String> rates = new LinkedHashSet<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Display.Mode mode : display.getSupportedModes()) {
                rates.add(Math.round(mode.getRefreshRate()) + " Hz");
            }
        }
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        display.getRealMetrics(dm);
        items.add(StreamMenuView.Item.info(a.getString(R.string.pocket_info_resolution), dm.widthPixels + " × " + dm.heightPixels));
        if (!rates.isEmpty()) {
            items.add(StreamMenuView.Item.info(a.getString(R.string.pocket_info_refresh), android.text.TextUtils.join(", ", rates)));
        }
        String hdr = a.getString(R.string.pocket_off);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && display.isHdr()) {
            hdr = a.getString(R.string.pocket_on);
        }
        items.add(StreamMenuView.Item.info("HDR", hdr));

        items.add(StreamMenuView.Item.header(a.getString(R.string.pocket_info_heat)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                int st = pm.getCurrentThermalStatus();
                int[] labels = {R.string.pocket_heat_none, R.string.pocket_heat_light, R.string.pocket_heat_moderate,
                        R.string.pocket_heat_severe, R.string.pocket_heat_critical, R.string.pocket_heat_critical,
                        R.string.pocket_heat_critical};
                items.add(StreamMenuView.Item.info(a.getString(R.string.pocket_info_thermal), a.getString(labels[Math.max(0, Math.min(6, st))])));
            }
        }
        items.add(StreamMenuView.Item.info(a.getString(R.string.pocket_info_battery), DevicePanel.batteryLine(a)));

        items.add(StreamMenuView.Item.header(a.getString(R.string.pocket_info_tuned)));
        String hw = Build.HARDWARE;
        if (isQualcomm(maker, hw)) {
            items.add(StreamMenuView.Item.note(a.getString(R.string.pocket_info_tuned_qcom)));
        }
        else if (isMediaTek(maker, hw)) {
            items.add(StreamMenuView.Item.note(a.getString(R.string.pocket_info_tuned_mtk)));
        }
        items.add(StreamMenuView.Item.note(a.getString(R.string.pocket_info_tuned_all)));
        PocketSounds.play(a, PocketSounds.OPEN);
        a.showConsolePanel(a.getString(R.string.pocket_info_title), chip != null ? chip : Build.MODEL, null, items);
    }

    /** What the hardware decoders of this type can do: the largest size and frame rate, 10-bit. */
    private static String decoderLine(Context c, String mime) {
        int best = 0;
        int bestFps = 0;
        boolean tenBit = false;
        boolean found = false;
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                boolean hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? info.isHardwareAccelerated()
                        : !info.getName().toLowerCase(Locale.ROOT).startsWith("omx.google")
                        && !info.getName().toLowerCase(Locale.ROOT).startsWith("c2.android");
                if (!hardware) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mime)) {
                        continue;
                    }
                    found = true;
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities v = caps.getVideoCapabilities();
                    if (v != null) {
                        int[][] sizes = {{3840, 2160}, {2560, 1440}, {1920, 1080}, {1280, 720}};
                        for (int[] sz : sizes) {
                            if (v.isSizeSupported(sz[0], sz[1])) {
                                int fps = 0;
                                for (int f : new int[] {144, 120, 90, 60, 30}) {
                                    if (v.areSizeAndRateSupported(sz[0], sz[1], f)) {
                                        fps = f;
                                        break;
                                    }
                                }
                                if (sz[1] > best || (sz[1] == best && fps > bestFps)) {
                                    best = sz[1];
                                    bestFps = fps;
                                }
                                break;
                            }
                        }
                    }
                    for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                        if ((mime.equals("video/hevc") && pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                                || (mime.equals("video/av01") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                                && pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10)) {
                            tenBit = true;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (!found) {
            return c.getString(R.string.pocket_info_no_decoder);
        }
        String size = best >= 2160 ? "4K" : (best > 0 ? best + "p" : "?");
        return size + (bestFps > 0 ? " @ " + bestFps + " fps" : "") + (tenBit ? "  ·  10-bit HDR" : "");
    }
}
