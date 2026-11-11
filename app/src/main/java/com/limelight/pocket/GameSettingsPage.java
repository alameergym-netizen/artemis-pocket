package com.limelight.pocket;

import android.content.Context;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.pocket.ui.StreamMenuView;

import java.util.ArrayList;
import java.util.List;

/**
 * A game's own stream settings (bitrate, frame rate, resolution, frame generation), as a page of
 * the console panel. The same page everywhere a game is: the library, the home screen's row and
 * the PC games.
 */
public final class GameSettingsPage {
    private GameSettingsPage() {}

    /** onReset: runs after "back to the normal settings" (may be null). */
    public static List<StreamMenuView.Item> build(final Context ctx, final String uuid, final int appId,
                                                  final String gameName, final Runnable onReset) {
        List<StreamMenuView.Item> page = new ArrayList<>();

        page.add(StreamMenuView.Item.value(ctx.getString(R.string.pocket_sm_bitrate),
                bitrateLabel(ctx, GameProfile.bitrateKbps(ctx, uuid, appId)), direction -> {
                    int[] values = GameProfile.BITRATES_MBPS;
                    int i = GameProfile.indexOf(values, GameProfile.bitrateKbps(ctx, uuid, appId) / 1000);
                    i = Math.max(0, Math.min(values.length - 1, i + direction));
                    GameProfile.setBitrateKbps(ctx, uuid, appId, values[i] * 1000);
                    return bitrateLabel(ctx, values[i] * 1000);
                }));

        page.add(StreamMenuView.Item.value(ctx.getString(R.string.pocket_sm_fps),
                fpsLabel(ctx, GameProfile.fps(ctx, uuid, appId)), direction -> {
                    int[] values = GameProfile.FPS;
                    int i = GameProfile.indexOf(values, GameProfile.fps(ctx, uuid, appId));
                    i = Math.max(0, Math.min(values.length - 1, i + direction));
                    GameProfile.setFps(ctx, uuid, appId, values[i]);
                    return fpsLabel(ctx, values[i]);
                }));

        page.add(StreamMenuView.Item.value(ctx.getString(R.string.pocket_gs_resolution),
                resolutionLabel(ctx, GameProfile.resolution(ctx, uuid, appId)), direction -> {
                    String[] values = GameProfile.RESOLUTIONS;
                    int i = GameProfile.indexOf(values, GameProfile.resolution(ctx, uuid, appId));
                    i = Math.max(0, Math.min(values.length - 1, i + direction));
                    GameProfile.setResolution(ctx, uuid, appId, values[i]);
                    return resolutionLabel(ctx, values[i]);
                }));

        page.add(StreamMenuView.Item.value(ctx.getString(R.string.pocket_sm_framegen),
                frameGenLabel(ctx, GameProfile.frameGen(ctx, uuid, appId)), direction -> {
                    int mode = GameProfile.frameGen(ctx, uuid, appId);
                    int n = GameProfile.FRAME_GEN_MODES;
                    mode = (mode + (direction > 0 ? 1 : n - 1)) % n;
                    GameProfile.setFrameGen(ctx, uuid, appId, mode);
                    return frameGenLabel(ctx, mode);
                }));

        page.add(StreamMenuView.Item.action(ctx.getString(R.string.pocket_gs_reset), () -> {
            GameProfile.clear(ctx, uuid, appId);
            Toast.makeText(ctx, R.string.pocket_gs_reset_done, Toast.LENGTH_SHORT).show();
            if (onReset != null) {
                onReset.run();
            }
        }));
        page.add(StreamMenuView.Item.repeatable(ctx.getString(R.string.pocket_gs_note, gameName), null));
        return page;
    }

    private static String defaultLabel(Context ctx) {
        return ctx.getString(R.string.pocket_gs_default);
    }

    public static String bitrateLabel(Context ctx, int kbps) {
        return kbps <= 0 ? defaultLabel(ctx) : (kbps / 1000) + " Mbps";
    }

    public static String fpsLabel(Context ctx, int fps) {
        return fps <= 0 ? defaultLabel(ctx) : fps + " FPS";
    }

    public static String resolutionLabel(Context ctx, String res) {
        int[] wh = GameProfile.parseResolution(res);
        return wh == null ? defaultLabel(ctx) : wh[0] + "×" + wh[1];
    }

    public static String frameGenLabel(Context ctx, int mode) {
        if (mode == GameProfile.FRAME_GEN_ON) {
            return ctx.getString(R.string.pocket_on);
        }
        if (mode == GameProfile.FRAME_GEN_OFF) {
            return ctx.getString(R.string.pocket_off);
        }
        if (mode == GameProfile.FRAME_GEN_LSFG) {
            return ctx.getString(R.string.pocket_framegen_lsfg_short);
        }
        return defaultLabel(ctx);
    }
}
