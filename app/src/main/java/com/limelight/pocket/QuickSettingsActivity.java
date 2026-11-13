package com.limelight.pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.limelight.BuildConfig;
import com.limelight.KeyboardAccessibilityService;
import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.StreamSettings;
import com.limelight.pocket.ui.PocketUi;

import java.util.ArrayList;
import java.util.List;

/**
 * The console settings screen: the handful of options people actually change, laid out for a
 * controller (tabs on L1/R1, rows on the D-pad, values on left/right). Everything else lives in
 * the full Artemis settings, one row away under "Advanced".
 */
public class QuickSettingsActivity extends ConsoleActivity {

    @Override
    protected boolean allowsAutoResume() {
        return false;
    }


    private interface IndexGetter { int get(); }
    private interface IndexSetter { void set(int index); }
    private interface BoolGetter { boolean get(); }
    private interface BoolSetter { void set(boolean value); }
    private interface TextGetter { String get(); }

    private static final int TAB_STREAM = 0;
    private static final int TAB_BUTTONS = 1;
    private static final int TAB_CONTROLS = 2;
    private static final int TAB_CONNECTION = 3;
    private static final int TAB_SYSTEM = 4;

    private static final int[] BITRATES_MBPS = {5, 10, 15, 20, 25, 30, 40, 50, 60, 80, 100, 120, 150};
    private static final int[] DEADZONES = {0, 2, 3, 4, 5, 6, 8, 10, 15, 20};

    private final List<TextView> tabs = new ArrayList<>();
    private final List<Runnable> rowRefreshers = new ArrayList<>();
    private LinearLayout rowsContainer;
    private ScrollView rowsScroll;
    private int currentTab = TAB_STREAM;
    private boolean capturingHotkey;
    // What the capture panel is assigning: the menu button, or which key is Back / Home
    private static final int CAPTURE_MENU = 0;
    private static final int CAPTURE_BACK = 1;
    private static final int CAPTURE_HOME = 2;
    private static final int CAPTURE_M2 = 3;
    private int captureTarget = CAPTURE_MENU;
    private int swallowKeyUp = -1;
    private long lastCaptureEventTime = -1;
    private int lastCaptureKeyCode = -1;
    private final KeyboardAccessibilityService.KeyTestListener serviceCapture = event ->
            runOnUiThread(() -> {
                if (capturingHotkey) {
                    handleCaptureKey(event);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_settings);

        rowsContainer = findViewById(R.id.pocketRows);
        rowsScroll = findViewById(R.id.pocketRowsScroll);

        LinearLayout tabBar = findViewById(R.id.pocketTabs);
        int[] tabNames = {R.string.pocket_tab_stream, R.string.pocket_tab_buttons, R.string.pocket_tab_controls,
                R.string.pocket_tab_connection, R.string.pocket_tab_system};
        for (int i = 0; i < tabNames.length; i++) {
            final int index = i;
            TextView tab = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            lp.bottomMargin = dp(4);
            tab.setLayoutParams(lp);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setPaddingRelative(dp(14), 0, dp(10), 0);
            tab.setText(tabNames[i]);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tab.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
            tab.setTextColor(ContextCompat.getColorStateList(this, R.color.pocket_tab_text));
            tab.setBackground(com.limelight.pocket.ui.PocketTheme.tabGlide(this));
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    PocketUi.tick(this);
                }
                if (hasFocus && currentTab != index) {
                    selectTab(index, true);
                }
            });
            tab.setOnClickListener(v -> {
                selectTab(index, false);
                focusFirstRow();
            });
            tabBar.addView(tab);
            tabs.add(tab);
        }

        setHints(hintA(this, R.string.pocket_hint_select),
                hintB(this, R.string.pocket_hint_back),
                hintShoulders(this, R.string.pocket_hint_tabs));

        // One lit highlight glides between tabs, and one between rows, as focus moves
        int accent = com.limelight.pocket.ui.PocketTheme.accent(this);
        com.limelight.pocket.ui.FocusGlider.install(tabBar, accent, 100 * getResources().getDisplayMetrics().density);
        com.limelight.pocket.ui.FocusGlider.install(rowsContainer, com.limelight.pocket.ui.PocketTheme.withAlpha(accent, 0xF2),
                14 * getResources().getDisplayMetrics().density);

        int startTab = getIntent().getIntExtra("tab", TAB_STREAM);
        selectTab(startTab, true);
        tabs.get(startTab).requestFocus();
        ConsoleActivity.reveal(findViewById(R.id.pocketSettingsBody), 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Values may have changed in the advanced screen or system settings
        refreshRows();
    }

    @Override
    protected void onMenuButton() {
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (capturingHotkey) {
            endHotkeyCapture();
        }
    }

    @Override
    protected boolean onShoulder(int direction) {
        int next = Math.max(0, Math.min(tabs.size() - 1, currentTab + direction));
        if (next != currentTab) {
            selectTab(next, true);
            tabs.get(next).requestFocus();
        }
        return true;
    }

    @Override
    protected void onStatusTick() {
        if (currentTab == TAB_CONNECTION) {
            refreshRows();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (capturingHotkey) {
            handleCaptureKey(event);
            return true;
        }
        if (swallowKeyUp >= 0 && event.getKeyCode() == swallowKeyUp) {
            // The release (and duplicate press) of the key that was just captured
            if (event.getAction() == KeyEvent.ACTION_UP) {
                swallowKeyUp = -1;
                return true;
            }
            if (event.getEventTime() == lastCaptureEventTime || event.getRepeatCount() > 0) {
                return true;
            }
            swallowKeyUp = -1; // a fresh press: handle it normally
        }
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                PocketSounds.play(this, PocketSounds.BACK);
                View focus = getCurrentFocus();
                if (focus != null && focus.getParent() == rowsContainer) {
                    tabs.get(currentTab).requestFocus();
                }
                else {
                    finish();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- Tabs ---------------------------------------------------------------------------

    private void selectTab(int index, boolean animate) {
        int from = currentTab;
        currentTab = index;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).setSelected(i == index);
        }
        rowsContainer.removeAllViews();
        rowRefreshers.clear();
        switch (index) {
            case TAB_STREAM:
                buildStreamTab();
                break;
            case TAB_BUTTONS:
                buildButtonsTab();
                break;
            case TAB_CONTROLS:
                buildControlsTab();
                break;
            case TAB_CONNECTION:
                buildConnectionTab();
                break;
            case TAB_SYSTEM:
            default:
                buildSystemTab();
                break;
        }
        rowsScroll.scrollTo(0, 0);
        if (animate && PocketPrefs.animationsEnabled(this) && from != index) {
            // The new rows come in from the side of the tab you moved to
            float dy = index > from ? 16 : -16;
            com.limelight.pocket.ui.PocketMotion.cascade(rowsContainer, 0, 22, 0, dy, 10);
        }
    }

    private void focusFirstRow() {
        for (int i = 0; i < rowsContainer.getChildCount(); i++) {
            View child = rowsContainer.getChildAt(i);
            if (child.isFocusable()) {
                child.requestFocus();
                return;
            }
        }
    }

    private void refreshRows() {
        for (Runnable r : rowRefreshers) {
            r.run();
        }
    }

    private SharedPreferences prefs() {
        return PocketPrefs.prefs(this);
    }

    // ---- Stream -------------------------------------------------------------------------

    private void buildStreamTab() {
        addHeader(R.string.pocket_header_quality);

        final String[] presetKeys = {PocketSetup.PRESET_BATTERY, PocketSetup.PRESET_BALANCED, PocketSetup.PRESET_QUALITY};
        final String[] presetLabels = {getString(R.string.pocket_preset_battery), getString(R.string.pocket_preset_balanced),
                getString(R.string.pocket_preset_quality)};
        addChoice(getString(R.string.pocket_set_preset), getString(R.string.pocket_set_preset_sub), presetLabels,
                () -> indexOf(presetKeys, prefs().getString(PocketPrefs.KEY_PRESET, PocketSetup.PRESET_CUSTOM)),
                index -> {
                    SharedPreferences.Editor e = prefs().edit();
                    PocketSetup.applyPresetValues(e, presetKeys[index]);
                    e.apply();
                    refreshRows();
                },
                getString(R.string.pocket_preset_custom));

        final String[] resValues = {"1280x720", "1920x1080", "2560x1440", "3840x2160"};
        final String[] resLabels = {"720p", "1080p", "1440p", "4K"};
        addChoice(getString(R.string.pocket_set_resolution), getString(R.string.pocket_set_resolution_sub), resLabels,
                () -> indexOf(resValues, prefs().getString("list_resolution", "1920x1080")),
                index -> {
                    prefs().edit().putString("list_resolution", resValues[index])
                            .putString(PocketPrefs.KEY_PRESET, PocketSetup.PRESET_CUSTOM).apply();
                    refreshRows();
                },
                getString(R.string.pocket_value_custom));

        final String[] fpsValues = {"30", "60", "90", "120"};
        final String[] fpsLabels = {"30 FPS", "60 FPS", "90 FPS", "120 FPS"};
        addChoice(getString(R.string.pocket_set_fps), null, fpsLabels,
                () -> indexOf(fpsValues, prefs().getString("list_fps", "60")),
                index -> {
                    prefs().edit().putString("list_fps", fpsValues[index])
                            .putString(PocketPrefs.KEY_PRESET, PocketSetup.PRESET_CUSTOM).apply();
                    refreshRows();
                },
                getString(R.string.pocket_value_custom));

        final String[] bitrateLabels = new String[BITRATES_MBPS.length];
        for (int i = 0; i < BITRATES_MBPS.length; i++) {
            bitrateLabels[i] = BITRATES_MBPS[i] + " Mbps";
        }
        addChoice(getString(R.string.pocket_set_bitrate_wifi), getString(R.string.pocket_set_bitrate_wifi_sub), bitrateLabels,
                () -> nearestBitrate(prefs().getInt("seekbar_bitrate_kbps", 30000)),
                index -> {
                    prefs().edit().putInt("seekbar_bitrate_kbps", BITRATES_MBPS[index] * 1000)
                            .putString(PocketPrefs.KEY_PRESET, PocketSetup.PRESET_CUSTOM).apply();
                    refreshRows();
                }, null);
        addChoice(getString(R.string.pocket_set_bitrate_usb), getString(R.string.pocket_set_bitrate_usb_sub), bitrateLabels,
                () -> nearestBitrate(PocketPrefs.usbBitrateKbps(this)),
                index -> {
                    prefs().edit().putInt(PocketPrefs.KEY_USB_BITRATE, BITRATES_MBPS[index] * 1000)
                            .putString(PocketPrefs.KEY_PRESET, PocketSetup.PRESET_CUSTOM).apply();
                    refreshRows();
                }, null);

        // Stream mode: Competitive (least delay), Balanced, Cinematic (smoothest picture)
        final String[] modeLabels = {getString(R.string.pocket_mode_competitive), getString(R.string.pocket_mode_balanced),
                getString(R.string.pocket_mode_cinematic)};
        addChoice(getString(R.string.pocket_set_stream_mode), getString(R.string.pocket_set_stream_mode_sub), modeLabels,
                () -> {
                    String pacing = prefs().getString("frame_pacing", "balanced");
                    boolean ultra = prefs().getBoolean("checkbox_ultra_low_latency", false);
                    if (ultra && "latency".equals(pacing)) {
                        return 0;
                    }
                    if (!ultra && "balanced".equals(pacing)) {
                        return 1;
                    }
                    if (!ultra && "smoothness".equals(pacing)) {
                        return 2;
                    }
                    return -1;
                },
                index -> prefs().edit()
                        .putBoolean("checkbox_ultra_low_latency", index == 0)
                        .putBoolean("pref_low_latency_frame_balance", index == 0)
                        .putString("frame_pacing", index == 0 ? "latency" : index == 1 ? "balanced" : "smoothness")
                        .apply(),
                getString(R.string.pocket_value_custom));

        addToggle(getString(R.string.pocket_set_auto_battery), getString(R.string.pocket_set_auto_battery_sub),
                () -> PocketPrefs.autoBatterySaver(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_AUTO_BATTERY, value).apply());

        addHeader(R.string.pocket_header_smart);

        final String[] fgValues = {PocketPrefs.FRAME_GEN_OFF, PocketPrefs.FRAME_GEN_2X, PocketPrefs.FRAME_GEN_LSFG};
        final String[] fgLabels = {getString(R.string.pocket_off), getString(R.string.pocket_framegen_2x),
                getString(R.string.pocket_framegen_lsfg)};
        addChoice(getString(R.string.pocket_set_framegen), getString(R.string.pocket_set_framegen_sub), fgLabels,
                () -> indexOf(fgValues, PocketPrefs.frameGen(this)),
                index -> {
                    prefs().edit().putString(PocketPrefs.KEY_FRAME_GEN, fgValues[index]).apply();
                    refreshRows();
                }, null);
        addAction(getString(R.string.pocket_set_lsfg_open), getString(R.string.pocket_set_lsfg_open_sub),
                () -> getString(LsfgApp.isInstalled(this) ? R.string.pocket_lsfg_installed : R.string.pocket_lsfg_missing),
                () -> {
                    if (!LsfgApp.open(this)) {
                        android.widget.Toast.makeText(this, R.string.pocket_lsfg_missing_toast,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });

        addToggle(getString(R.string.pocket_set_adaptive), getString(R.string.pocket_set_adaptive_sub),
                () -> PocketPrefs.adaptiveStream(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_ADAPTIVE, value).apply());

        addToggle(getString(R.string.pocket_set_resume), getString(R.string.pocket_set_resume_sub),
                () -> PocketPrefs.seamlessResume(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_SEAMLESS_RESUME, value).apply());

        addToggle(getString(R.string.pocket_set_mic), getString(R.string.pocket_set_mic_sub),
                () -> PocketPrefs.micPassthrough(this),
                value -> {
                    prefs().edit().putBoolean(PocketPrefs.KEY_MIC, value).apply();
                    if (value && !PocketMic.hasPermission(this)) {
                        androidx.core.app.ActivityCompat.requestPermissions(this,
                                new String[]{android.Manifest.permission.RECORD_AUDIO}, 4712);
                    }
                });

        addHeader(R.string.pocket_header_video);

        final String[] codecValues = {"forceh265", "forceav1", "neverh265", "auto"};
        final String[] codecLabels = {"HEVC (H.265)", "AV1", "H.264", getString(R.string.pocket_value_auto)};
        addChoice(getString(R.string.pocket_set_codec), getString(R.string.pocket_set_codec_sub), codecLabels,
                () -> indexOf(codecValues, prefs().getString("video_format", "auto")),
                index -> prefs().edit().putString("video_format", codecValues[index]).apply(), null);

        final String[] pacingValues = {"latency", "balanced", "smoothness"};
        final String[] pacingLabels = {getString(R.string.pocket_pacing_latency), getString(R.string.pocket_pacing_balanced),
                getString(R.string.pocket_pacing_smooth)};
        addChoice(getString(R.string.pocket_set_pacing), null, pacingLabels,
                () -> indexOf(pacingValues, prefs().getString("frame_pacing", "balanced")),
                index -> prefs().edit().putString("frame_pacing", pacingValues[index]).apply(),
                getString(R.string.pocket_value_custom));

        final String[] perfLabels = {getString(R.string.pocket_off), getString(R.string.pocket_perf_lite),
                getString(R.string.pocket_perf_full)};
        addChoice(getString(R.string.pocket_set_perf), null, perfLabels,
                () -> {
                    if (!prefs().getBoolean("checkbox_enable_perf_overlay", false)) {
                        return 0;
                    }
                    return prefs().getBoolean("checkbox_enable_perf_overlay_lite", false) ? 1 : 2;
                },
                index -> prefs().edit()
                        .putBoolean("checkbox_enable_perf_overlay", index != 0)
                        .putBoolean("checkbox_enable_perf_overlay_lite", index == 1)
                        .apply(), null);

        addToggle(getString(R.string.pocket_set_modern_hud), getString(R.string.pocket_set_modern_hud_sub),
                () -> PocketPrefs.modernHud(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_MODERN_HUD, value).apply());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isSustainedPerformanceModeSupported()) {
                addToggle(getString(R.string.pocket_set_sustained), getString(R.string.pocket_set_sustained_sub),
                        () -> PocketPrefs.sustainedPerformance(this),
                        value -> prefs().edit().putBoolean(PocketPrefs.KEY_SUSTAINED, value).apply());
            }
        }

        addToggle(getString(R.string.pocket_set_vdisplay), getString(R.string.pocket_set_vdisplay_sub),
                () -> prefs().getBoolean("checkbox_use_virtual_display", false),
                value -> prefs().edit().putBoolean("checkbox_use_virtual_display", value).apply());

        addToggle(getString(R.string.pocket_set_hdr), null,
                () -> prefs().getBoolean("checkbox_enable_hdr", false),
                value -> prefs().edit().putBoolean("checkbox_enable_hdr", value).apply());
    }

    private static int nearestBitrate(int kbps) {
        int best = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < BITRATES_MBPS.length; i++) {
            int diff = Math.abs(BITRATES_MBPS[i] * 1000 - kbps);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    // ---- Controls -----------------------------------------------------------------------

    // ---- Buttons ------------------------------------------------------------------------

    private void buildButtonsTab() {
        addHeader(R.string.pocket_header_back);
        addAction(getString(R.string.pocket_set_which_back), getString(R.string.pocket_set_which_sub),
                () -> PocketPrefs.describeButtonKey(this, PocketPrefs.getBackKey(this), R.string.pocket_key_auto_back),
                () -> startCapture(CAPTURE_BACK));
        addGestureRows(ButtonActions.BUTTON_BACK);

        addHeader(R.string.pocket_header_home);
        addAction(getString(R.string.pocket_set_which_home), getString(R.string.pocket_set_which_sub),
                () -> PocketPrefs.describeButtonKey(this, PocketPrefs.getHomeKey(this), R.string.pocket_key_auto_home),
                () -> startCapture(CAPTURE_HOME));
        addAction(getString(R.string.pocket_set_home_service), getString(R.string.pocket_set_home_service_sub),
                () -> getString(isAccessibilityServiceEnabled(this) ? R.string.pocket_service_on : R.string.pocket_service_off),
                this::showAccessibilityPrompt);
        addGestureRows(ButtonActions.BUTTON_HOME);

        addHeader(R.string.pocket_header_menu_button);
        addAction(getString(R.string.pocket_set_hotkey), getString(R.string.pocket_set_hotkey_sub),
                () -> {
                    String name = PocketPrefs.describeHotkey(PocketPrefs.getMenuHotkey(this));
                    return name != null ? name : getString(R.string.pocket_not_set);
                },
                () -> startCapture(CAPTURE_MENU));
        addGestureRows(ButtonActions.BUTTON_MENU);
        addLauncherRow(ButtonActions.BUTTON_MENU);

        // A second assignable button (M2), its own actions, and what it does on the home screens
        addHeader(R.string.pocket_header_m2);
        addAction(getString(R.string.pocket_set_m2_key), getString(R.string.pocket_set_m2_key_sub),
                () -> {
                    String name = PocketPrefs.describeHotkey(PocketPrefs.getM2Hotkey(this));
                    return name != null ? name : getString(R.string.pocket_not_set);
                },
                () -> startCapture(CAPTURE_M2));
        addGestureRows(ButtonActions.BUTTON_M2);
        addLauncherRow(ButtonActions.BUTTON_M2);

        // Recorded button sequences, played by M1 / M2 or a radial slot
        addHeader(R.string.pocket_header_macros);
        for (int i = 0; i < ButtonActions.MACRO_SLOTS; i++) {
            final int slot = i;
            addAction(getString(R.string.pocket_macro_n, slot + 1), getString(R.string.pocket_macro_sub),
                    () -> {
                        Macro m = ButtonActions.getMacro(this, slot);
                        return m != null ? m.describe() : getString(R.string.pocket_macro_empty);
                    },
                    () -> macroOptions(slot));
        }

        addHeader(R.string.pocket_header_radial);
        String[] positions = getResources().getStringArray(R.array.pocket_radial_positions);
        for (int i = 0; i < ButtonActions.RADIAL_SLOTS; i++) {
            final int slot = i;
            addAction(positions[i], null,
                    () -> getString(ButtonActions.getRadial(this, slot).label),
                    () -> pickAction(getString(R.string.pocket_header_radial) + " · " + positions[slot],
                            PocketAction.forRadial(), ButtonActions.getRadial(this, slot),
                            action -> ButtonActions.setRadial(this, slot, action)));
        }

        addHeader(R.string.pocket_header_more_buttons);
        addAction(getString(R.string.pocket_set_guide_help), getString(R.string.pocket_set_guide_help_sub), null,
                () -> new AlertDialog.Builder(this)
                        .setTitle(R.string.pocket_set_guide_help)
                        .setMessage(R.string.pocket_guide_help_body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
        addToggle(getString(R.string.pocket_set_feedback), getString(R.string.pocket_set_feedback_sub),
                () -> PocketPrefs.actionFeedback(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_ACTION_FEEDBACK, value).apply());
        addAction(getString(R.string.pocket_set_button_test), getString(R.string.pocket_set_button_test_sub), null,
                () -> startActivity(new Intent(this, ButtonTestActivity.class)));
        addAction(getString(R.string.pocket_set_buttons_reset), null, null, () -> {
            SharedPreferences.Editor e = prefs().edit();
            for (String key : prefs().getAll().keySet()) {
                if (key.startsWith("pocket_btn_") || key.startsWith("pocket_radial_")) {
                    e.remove(key);
                }
            }
            e.apply();
            refreshRows();
            Toast.makeText(this, R.string.pocket_buttons_reset_done, Toast.LENGTH_SHORT).show();
        });
    }

    /** What M1 / M2 do on the home screen and in the library. */
    private void addLauncherRow(final int button) {
        addAction(getString(R.string.pocket_set_launcher), getString(R.string.pocket_set_launcher_sub),
                () -> getString(ButtonActions.launcherLabel(ButtonActions.getLauncher(this, button))),
                () -> {
                    final String[] actions = ButtonActions.LAUNCHER_ACTIONS;
                    String[] labels = new String[actions.length];
                    int checked = 0;
                    String current = ButtonActions.getLauncher(this, button);
                    for (int i = 0; i < actions.length; i++) {
                        labels[i] = getString(ButtonActions.launcherLabel(actions[i]));
                        if (actions[i].equals(current)) {
                            checked = i;
                        }
                    }
                    com.limelight.pocket.ConsoleActivity.showImmersive(new AlertDialog.Builder(this)
                            .setTitle(getString(ButtonActions.buttonLabel(button)) + " \u00b7 " + getString(R.string.pocket_set_launcher))
                            .setSingleChoiceItems(labels, checked, (d, which) -> {
                                ButtonActions.setLauncher(this, button, actions[which]);
                                d.dismiss();
                                refreshRows();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .create());
                });
    }

    /** Record a macro (or record it again / delete it). */
    private void macroOptions(final int slot) {
        if (ButtonActions.getMacro(this, slot) == null) {
            recordMacro(slot);
            return;
        }
        com.limelight.pocket.ConsoleActivity.showImmersive(new AlertDialog.Builder(this)
                .setTitle(getString(R.string.pocket_macro_n, slot + 1))
                .setItems(new String[]{getString(R.string.pocket_macro_record_again), getString(R.string.pocket_macro_delete)},
                        (d, which) -> {
                            if (which == 0) {
                                recordMacro(slot);
                            }
                            else {
                                ButtonActions.setMacro(this, slot, null);
                                refreshRows();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private static final int REQUEST_MUSIC = 7501;

    private void pickMusic() {
        try {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
            startActivityForResult(pick, REQUEST_MUSIC);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.pocket_set_music_cant_pick, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MUSIC && resultCode == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
            }
            prefs().edit().putString(PocketMusic.KEY_MODE, PocketMusic.MODE_FILE).apply();
            PocketMusic.setTrack(this, uri);
            refreshRows();
        }
    }

    private void recordMacro(int slot) {
        Intent record = new Intent(this, MacroRecordActivity.class);
        record.putExtra(MacroRecordActivity.EXTRA_SLOT, slot);
        startActivity(record);
    }

    private void addButtonSection(int button, int header) {
        addHeader(header);
        addGestureRows(button);
    }

    private void addGestureRows(final int button) {
        for (int g = 0; g < 3; g++) {
            final int gesture = g;
            String sub = null;
            if (gesture == ButtonActions.GESTURE_TAP && ButtonGestures.isLive(
                    ButtonActions.get(this, button, ButtonActions.GESTURE_TAP),
                    ButtonActions.get(this, button, ButtonActions.GESTURE_DOUBLE),
                    ButtonActions.get(this, button, ButtonActions.GESTURE_HOLD))) {
                sub = getString(R.string.pocket_gesture_live_sub);
            }
            else if (gesture == ButtonActions.GESTURE_HOLD) {
                sub = getString(R.string.pocket_gesture_hold_sub);
            }
            addAction(getString(ButtonActions.gestureLabel(gesture)), sub,
                    () -> getString(ButtonActions.get(this, button, gesture).label),
                    () -> pickAction(getString(ButtonActions.buttonLabel(button)) + " · " + getString(ButtonActions.gestureLabel(gesture)),
                            PocketAction.forButtons(), ButtonActions.get(this, button, gesture),
                            action -> {
                                ButtonActions.set(this, button, gesture, action);
                                if (button == ButtonActions.BUTTON_HOME && ButtonActions.homeIsUsed(this)
                                        && !isAccessibilityServiceEnabled(this)) {
                                    showAccessibilityPrompt();
                                }
                            }));
        }
    }

    private interface ActionPicked { void picked(PocketAction action); }

    private void pickAction(String title, final java.util.List<PocketAction> actions, PocketAction current,
                            final ActionPicked picked) {
        String[] labels = new String[actions.size()];
        int checked = -1;
        for (int i = 0; i < actions.size(); i++) {
            labels[i] = getString(actions.get(i).label);
            if (actions.get(i) == current) {
                checked = i;
            }
        }
        com.limelight.pocket.ConsoleActivity.showImmersive(new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    picked.picked(actions.get(which));
                    dialog.dismiss();
                    refreshRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    // ---- Controls -----------------------------------------------------------------------

    private void buildControlsTab() {
        addHeader(R.string.pocket_header_buttons);

        addToggle(getString(R.string.pocket_set_flip), getString(R.string.pocket_set_flip_sub),
                () -> prefs().getBoolean("checkbox_flip_face_buttons", false),
                value -> prefs().edit().putBoolean("checkbox_flip_face_buttons", value).apply());

        addHeader(R.string.pocket_header_feel);

        addToggle(getString(R.string.pocket_set_rumble), getString(R.string.pocket_set_rumble_sub),
                () -> prefs().getBoolean("checkbox_enable_rumble", true),
                value -> prefs().edit()
                        .putBoolean("checkbox_enable_rumble", value)
                        .putBoolean("checkbox_vibrate_fallback", value)
                        .apply());

        final String[] deadzoneLabels = new String[DEADZONES.length];
        for (int i = 0; i < DEADZONES.length; i++) {
            deadzoneLabels[i] = DEADZONES[i] + "%";
        }
        addChoice(getString(R.string.pocket_set_deadzone), null, deadzoneLabels,
                () -> {
                    int current = prefs().getInt("seekbar_deadzone", 5);
                    for (int i = 0; i < DEADZONES.length; i++) {
                        if (DEADZONES[i] >= current) {
                            return i;
                        }
                    }
                    return DEADZONES.length - 1;
                },
                index -> prefs().edit().putInt("seekbar_deadzone", DEADZONES[index]).apply(), null);

        addToggle(getString(R.string.pocket_set_gyro), getString(R.string.pocket_set_gyro_sub),
                () -> prefs().getBoolean("checkbox_gamepad_motion_fallback", false),
                value -> prefs().edit().putBoolean("checkbox_gamepad_motion_fallback", value).apply());

        addToggle(getString(R.string.pocket_set_mouse), getString(R.string.pocket_set_mouse_sub),
                () -> prefs().getBoolean("checkbox_mouse_emulation", true),
                value -> prefs().edit().putBoolean("checkbox_mouse_emulation", value).apply());
    }

    // ---- Connection ---------------------------------------------------------------------

    private void buildConnectionTab() {
        addHeader(R.string.pocket_header_usb);

        addAction(getString(R.string.pocket_set_usb_status), null,
                () -> {
                    if (UsbBridge.isConnected()) {
                        return getString(R.string.pocket_usb_status_bridge);
                    }
                    UsbLink.TetherInfo info = UsbLink.findTetherInterface();
                    if (info == null) {
                        return getString(R.string.pocket_usb_status_off);
                    }
                    return getString(R.string.pocket_usb_status_on, info.describe());
                },
                () -> showUsbHelp(this));

        addAction(getString(R.string.pocket_set_tether_support), getString(R.string.pocket_set_tether_support_sub),
                () -> {
                    switch (UsbLink.getTetheringSupport(this)) {
                        case UsbLink.TETHER_SUPPORTED:
                            return getString(R.string.pocket_tether_supported);
                        case UsbLink.TETHER_UNSUPPORTED:
                            return getString(R.string.pocket_tether_unsupported);
                        default:
                            return getString(R.string.pocket_tether_unknown);
                    }
                }, () -> showUsbHelp(this));

        addToggle(getString(R.string.pocket_set_usb_auto), getString(R.string.pocket_set_usb_auto_sub),
                () -> PocketPrefs.usbAutoConnect(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_USB_AUTO_CONNECT, value).apply());

        final String[] actionValues = {PocketPrefs.ON_CONNECT_LIBRARY, PocketPrefs.ON_CONNECT_LAST_GAME, PocketPrefs.ON_CONNECT_NOTHING};
        final String[] actionLabels = {getString(R.string.pocket_usb_action_library), getString(R.string.pocket_usb_action_last),
                getString(R.string.pocket_usb_action_nothing)};
        addChoice(getString(R.string.pocket_set_usb_action), null, actionLabels,
                () -> indexOf(actionValues, PocketPrefs.usbOnConnect(this)),
                index -> prefs().edit().putString(PocketPrefs.KEY_USB_ON_CONNECT, actionValues[index]).apply(), null);

        addAction(getString(R.string.pocket_usb_help_title), getString(R.string.pocket_usb_help_sub), null,
                () -> showUsbHelp(this));

        addToggle(getString(R.string.pocket_set_auto_recover), getString(R.string.pocket_set_auto_recover_sub),
                () -> PocketPrefs.autoRecover(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_AUTO_RECOVER, value).apply());

        addAction(getString(R.string.pocket_set_usb_tether), getString(R.string.pocket_set_usb_tether_sub), null,
                () -> UsbLink.openTetherSettings(this));

        addHeader(R.string.pocket_header_pcs);

        addAction(getString(R.string.pocket_set_add_pc), getString(R.string.pocket_set_add_pc_sub), null,
                () -> startActivity(new Intent(this, AddComputerManually.class)));
    }

    // ---- System -------------------------------------------------------------------------

    private void buildSystemTab() {
        addHeader(R.string.pocket_header_app);

        final int[] scaleValues = {75, 80, 85, 90, 100, 110, 120};
        final String[] scaleLabels = new String[scaleValues.length];
        for (int i = 0; i < scaleValues.length; i++) {
            scaleLabels[i] = scaleValues[i] + "%";
        }
        addChoice(getString(R.string.pocket_set_ui_scale), getString(R.string.pocket_set_ui_scale_sub), scaleLabels,
                () -> {
                    int current = PocketPrefs.uiScalePercent(this);
                    for (int i = 0; i < scaleValues.length; i++) {
                        if (scaleValues[i] == current) {
                            return i;
                        }
                    }
                    return -1;
                },
                index -> {
                    prefs().edit().putInt(PocketPrefs.KEY_UI_SCALE, scaleValues[index]).apply();
                    // Rebuild this screen at the new size; the others follow when you go back
                    getIntent().putExtra("tab", TAB_SYSTEM);
                    rowsContainer.postDelayed(this::recreate, 350);
                }, null);

        final com.limelight.pocket.ui.PocketTheme.Preset[] themes = com.limelight.pocket.ui.PocketTheme.THEMES;
        final String[] themeLabels = new String[themes.length];
        for (int i = 0; i < themes.length; i++) {
            themeLabels[i] = getString(themes[i].name);
        }
        addChoice(getString(R.string.pocket_set_theme), getString(R.string.pocket_set_theme_sub), themeLabels,
                () -> com.limelight.pocket.ui.PocketTheme.themeIndex(this),
                index -> {
                    com.limelight.pocket.ui.PocketTheme.applyTheme(this, index);
                    PocketSounds.reload(this);
                    rowsContainer.postDelayed(() -> PocketSounds.preview(this, PocketSounds.CATEGORY_MENU), 260);
                    getIntent().putExtra("tab", TAB_SYSTEM);
                    rowsContainer.postDelayed(this::recreate, 420);
                }, getString(R.string.pocket_value_custom));

        {
            // Home left alone: the games' art drifts by with the time (dimmed)
            final int[] mins = com.limelight.pocket.home.AmbientScreen.MINUTES;
            String[] labels = new String[mins.length];
            for (int i = 0; i < mins.length; i++) {
                labels[i] = mins[i] == 0 ? getString(R.string.pocket_off) : getString(R.string.pocket_minutes_short, mins[i]);
            }
            addChoice(getString(R.string.pocket_ambient), getString(R.string.pocket_ambient_sub), labels,
                    () -> {
                        int now = prefs().getInt(com.limelight.pocket.home.AmbientScreen.KEY_MINUTES, 2);
                        for (int i = 0; i < mins.length; i++) {
                            if (mins[i] == now) {
                                return i;
                            }
                        }
                        return 2;
                    },
                    index -> prefs().edit().putInt(com.limelight.pocket.home.AmbientScreen.KEY_MINUTES, mins[index]).apply(),
                    null);
        }

        final String[] focusValues = {PocketPrefs.FOCUS_RING_ZOOM, PocketPrefs.FOCUS_RING, PocketPrefs.FOCUS_ZOOM};
        final String[] focusLabels = {getString(R.string.pocket_focus_ring_zoom), getString(R.string.pocket_focus_ring),
                getString(R.string.pocket_focus_zoom)};
        addChoice(getString(R.string.pocket_set_focus_style), getString(R.string.pocket_set_focus_style_sub), focusLabels,
                () -> indexOf(focusValues, PocketPrefs.focusStyle(this)),
                index -> prefs().edit().putString(PocketPrefs.KEY_FOCUS_STYLE, focusValues[index]).apply(), null);

        final String[] accentLabels = {getString(R.string.pocket_accent_white), getString(R.string.pocket_accent_ice),
                getString(R.string.pocket_accent_mint), getString(R.string.pocket_accent_lilac),
                getString(R.string.pocket_accent_rose), getString(R.string.pocket_accent_amber),
                getString(R.string.pocket_accent_coral), getString(R.string.pocket_accent_lime),
                getString(R.string.pocket_accent_cyan), getString(R.string.pocket_accent_violet),
                getString(R.string.pocket_accent_neon), getString(R.string.pocket_accent_portal)};
        addChoice(getString(R.string.pocket_set_accent), getString(R.string.pocket_set_accent_sub), accentLabels,
                () -> {
                    int current = PocketPrefs.accentColor(this);
                    for (int i = 0; i < com.limelight.pocket.ui.PocketTheme.ACCENTS.length; i++) {
                        if (com.limelight.pocket.ui.PocketTheme.ACCENTS[i] == current) {
                            return i;
                        }
                    }
                    return -1;
                },
                index -> {
                    prefs().edit().putInt(PocketPrefs.KEY_ACCENT, com.limelight.pocket.ui.PocketTheme.ACCENTS[index]).apply();
                    getIntent().putExtra("tab", TAB_SYSTEM);
                    rowsContainer.postDelayed(this::recreate, 250);
                }, null);

        final String[] backdropValues = {PocketPrefs.BACKDROP_AMBIENT, PocketPrefs.BACKDROP_ART, PocketPrefs.BACKDROP_GLOW,
                PocketPrefs.BACKDROP_BLACK};
        final String[] backdropLabels = {getString(R.string.pocket_backdrop_ambient), getString(R.string.pocket_backdrop_art), getString(R.string.pocket_backdrop_glow),
                getString(R.string.pocket_backdrop_black)};
        addChoice(getString(R.string.pocket_set_backdrop), getString(R.string.pocket_set_backdrop_sub), backdropLabels,
                () -> indexOf(backdropValues, PocketPrefs.backdrop(this)),
                index -> {
                    prefs().edit().putString(PocketPrefs.KEY_BACKDROP, backdropValues[index]).apply();
                    getIntent().putExtra("tab", TAB_SYSTEM);
                    rowsContainer.postDelayed(this::recreate, 250);
                }, null);

        addToggle(getString(R.string.pocket_set_intro), getString(R.string.pocket_set_intro_sub),
                () -> PocketPrefs.introAnimation(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_INTRO, value).apply());

        addToggle(getString(R.string.pocket_set_animations), getString(R.string.pocket_set_animations_sub),
                () -> PocketPrefs.animationsEnabled(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_ANIMATIONS, value).apply());

        if (com.limelight.pocket.ui.LiveGlass.supported()) {
            addToggle(getString(R.string.pocket_set_live_glass), getString(R.string.pocket_set_live_glass_sub),
                    () -> PocketPrefs.liveGlass(this),
                    value -> {
                        prefs().edit().putBoolean(PocketPrefs.KEY_LIVE_GLASS, value).apply();
                        getIntent().putExtra("tab", TAB_SYSTEM);
                        rowsContainer.postDelayed(this::recreate, 300);
                    });
        }

        if (com.limelight.pocket.ui.LiveGlass.supported()) {
            addToggle(getString(R.string.pocket_set_frost), getString(R.string.pocket_set_frost_sub),
                    () -> PocketPrefs.frost(this),
                    value -> {
                        PocketPrefs.setFrost(this, value);
                        getIntent().putExtra("tab", TAB_SYSTEM);
                        rowsContainer.postDelayed(this::recreate, 300);
                    });
        }

        addToggle(getString(R.string.pocket_set_console_home), getString(R.string.pocket_set_console_home_sub),
                () -> PocketPrefs.consoleHome(this),
                value -> PocketPrefs.setConsoleHome(this, value));

        final String[] modeLabels = {getString(R.string.pocket_view_carousel), getString(R.string.pocket_view_grid),
                getString(R.string.pocket_view_xmb)};
        addChoice(getString(R.string.pocket_view_mode), getString(R.string.pocket_view_mode_sub), modeLabels,
                () -> java.util.Arrays.asList(com.limelight.pocket.ui.LibraryLayout.MODES).indexOf(PocketPrefs.viewMode(this)),
                index -> PocketPrefs.setViewMode(this, com.limelight.pocket.ui.LibraryLayout.MODES[index]), null);
        final String[] cardLabels = {getString(R.string.pocket_cards_cover), getString(R.string.pocket_cards_wide),
                getString(R.string.pocket_cards_icon)};
        addChoice(getString(R.string.pocket_cards_style), getString(R.string.pocket_cards_style_sub), cardLabels,
                () -> java.util.Arrays.asList(com.limelight.pocket.ui.LibraryLayout.STYLES).indexOf(PocketPrefs.cardStyle(this)),
                index -> PocketPrefs.setCardStyle(this, com.limelight.pocket.ui.LibraryLayout.STYLES[index]), null);

        // Game pages: the trailer plays muted behind the page after a moment
        addToggle(getString(R.string.pocket_set_trailer_autoplay), getString(R.string.pocket_set_trailer_autoplay_sub),
                () -> PocketPrefs.trailerAutoplay(this),
                value -> PocketPrefs.setTrailerAutoplay(this, value));
        addAction(getString(R.string.pocket_set_clear_info), getString(R.string.pocket_set_clear_info_sub), null, () -> {
            com.limelight.pocket.info.GameInfoService.clearAll(this);
            Toast.makeText(this, R.string.pocket_info_cleared, Toast.LENGTH_SHORT).show();
        });

        addToggle(getString(R.string.pocket_set_haptics), getString(R.string.pocket_set_haptics_sub),
                () -> PocketPrefs.hapticsEnabled(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_HAPTICS, value).apply());

        addHeader(R.string.pocket_header_sounds);

        addToggle(getString(R.string.pocket_set_sounds), getString(R.string.pocket_set_sounds_sub),
                () -> PocketPrefs.uiSounds(this),
                value -> {
                    prefs().edit().putBoolean(PocketPrefs.KEY_UI_SOUNDS, value).apply();
                    if (value) {
                        PocketSounds.preload(this);
                    }
                });

        final String[] styleLabels = {getString(R.string.pocket_sound_glass), getString(R.string.pocket_sound_soft),
                getString(R.string.pocket_sound_crisp), getString(R.string.pocket_sound_retro),
                getString(R.string.pocket_sound_pop), getString(R.string.pocket_sound_aurora),
                getString(R.string.pocket_sound_crystal), getString(R.string.pocket_sound_tactile)};
        addChoice(getString(R.string.pocket_set_sound_style), getString(R.string.pocket_set_sound_style_sub), styleLabels,
                () -> PocketPrefs.soundStyleIndex(this),
                index -> {
                    prefs().edit().putString(PocketPrefs.KEY_SOUND_STYLE, PocketPrefs.SOUND_STYLES[index]).apply();
                    PocketSounds.reload(this);
                    // Let the new set load, then play a sample of it
                    rowsContainer.postDelayed(() -> PocketSounds.preview(this, PocketSounds.CATEGORY_MENU), 260);
                }, null);

        addSlider(getString(R.string.pocket_set_vol_master), getString(R.string.pocket_set_vol_master_sub),
                PocketPrefs.KEY_VOL_MASTER, PocketSounds.CATEGORY_MENU);
        addSlider(getString(R.string.pocket_set_vol_nav), getString(R.string.pocket_set_vol_nav_sub),
                PocketPrefs.KEY_VOL_NAV, PocketSounds.CATEGORY_NAV);
        addSlider(getString(R.string.pocket_set_vol_menu), getString(R.string.pocket_set_vol_menu_sub),
                PocketPrefs.KEY_VOL_MENU, PocketSounds.CATEGORY_MENU);
        addSlider(getString(R.string.pocket_set_vol_boot), getString(R.string.pocket_set_vol_boot_sub),
                PocketPrefs.KEY_VOL_BOOT, PocketSounds.CATEGORY_BOOT);

        // Music behind the console's screens
        final String[] musicLabels = {getString(R.string.pocket_off), getString(R.string.pocket_music_ambient),
                getString(R.string.pocket_music_file)};
        addChoice(getString(R.string.pocket_set_music), getString(R.string.pocket_set_music_sub), musicLabels,
                () -> java.util.Arrays.asList(PocketMusic.MODES).indexOf(PocketMusic.mode(this)),
                index -> {
                    PocketMusic.setMode(this, PocketMusic.MODES[index]);
                    if (PocketMusic.MODE_FILE.equals(PocketMusic.MODES[index])
                            && !prefs().contains(PocketMusic.KEY_URI)) {
                        pickMusic();
                    }
                }, null);
        final String[] volumeLabels = new String[11];
        for (int i = 0; i <= 10; i++) {
            volumeLabels[i] = (i * 10) + "%";
        }
        addChoice(getString(R.string.pocket_set_music_volume), getString(R.string.pocket_set_music_volume_sub), volumeLabels,
                () -> Math.round(PocketMusic.volume(this) / 10f),
                index -> PocketMusic.setVolume(this, index * 10), null);
        addAction(getString(R.string.pocket_set_music_pick), getString(R.string.pocket_set_music_pick_sub), null,
                this::pickMusic);

        addHeader(R.string.pocket_header_extras);

        addToggle(getString(R.string.pocket_set_battery_alerts), getString(R.string.pocket_set_battery_alerts_sub),
                () -> PocketPrefs.batteryAlerts(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_BATTERY_ALERTS, value).apply());

        addToggle(getString(R.string.pocket_set_color_art), getString(R.string.pocket_set_color_art_sub),
                () -> PocketPrefs.colorBackgroundArt(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_COLOR_ART, value).apply());

        addHeader(R.string.pocket_header_library);

        addChoice(getString(R.string.pocket_set_sort), getString(R.string.pocket_set_sort_sub),
                new String[]{getString(R.string.pocket_sort_recent), getString(R.string.pocket_sort_az),
                        getString(R.string.pocket_sort_pc)},
                () -> PocketPrefs.sortModeIndex(this),
                index -> prefs().edit().putString(PocketPrefs.KEY_SORT_MODE, PocketPrefs.SORT_MODES[index]).apply(),
                null);

        addToggle(getString(R.string.pocket_set_switch_no_ask), getString(R.string.pocket_set_switch_no_ask_sub),
                () -> PocketPrefs.switchWithoutAsking(this),
                value -> PocketPrefs.setSwitchWithoutAsking(this, value));

        addToggle(getString(R.string.pocket_set_open_last), getString(R.string.pocket_set_open_last_sub),
                () -> PocketPrefs.openLastPc(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_OPEN_LAST_PC, value).apply());

        addToggle(getString(R.string.pocket_set_latency), getString(R.string.pocket_set_latency_sub),
                () -> PocketPrefs.showLatency(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_SHOW_LATENCY, value).apply());

        addHeader(R.string.pocket_header_trophies);

        addAction(getString(R.string.pocket_set_ra), getString(R.string.pocket_set_ra_sub),
                () -> com.limelight.pocket.retro.Achievements.hasRa(this)
                        ? com.limelight.pocket.retro.Achievements.raUser(this) : getString(R.string.pocket_art_key_missing),
                this::askRetroAchievements);

        addToggle(getString(R.string.pocket_set_trophy_live), getString(R.string.pocket_set_trophy_live_sub),
                () -> com.limelight.pocket.retro.TrophyWatch.enabled(this),
                value -> {
                    prefs().edit().putBoolean(com.limelight.pocket.retro.TrophyWatch.KEY_ENABLED, value).apply();
                    if (value) {
                        com.limelight.pocket.retro.TrophyWatch.askForOverlay(this);
                    }
                });

        addAction(getString(R.string.pocket_set_trophy_overlay), getString(R.string.pocket_set_trophy_overlay_sub),
                () -> getString(com.limelight.pocket.retro.TrophyWatch.canDrawOver(this)
                        ? R.string.pocket_trophy_overlay_on : R.string.pocket_trophy_overlay_off),
                () -> com.limelight.pocket.retro.TrophyWatch.openOverlaySettings(this));

        addAction(getString(R.string.pocket_set_trophy_test), getString(R.string.pocket_set_trophy_test_sub), null,
                () -> com.limelight.pocket.retro.TrophyWatch.preview(this));

        addHeader(R.string.pocket_header_art);

        addAction(getString(R.string.pocket_set_sgdb_key), getString(R.string.pocket_set_sgdb_key_sub),
                () -> getString(PocketArt.hasKey(this) ? R.string.pocket_art_key_set : R.string.pocket_art_key_missing),
                () -> ArtPickerActivity.askForKey(this, this::refreshRows));

        addToggle(getString(R.string.pocket_set_auto_art), getString(R.string.pocket_set_auto_art_sub),
                () -> PocketArt.autoArt(this),
                value -> prefs().edit().putBoolean(PocketArt.KEY_AUTO_ART, value).apply());

        addToggle(getString(R.string.pocket_set_logos), getString(R.string.pocket_set_logos_sub),
                () -> PocketArt.logosForNames(this),
                value -> prefs().edit().putBoolean(PocketArt.KEY_LOGOS, value).apply());

        {
            final int[] sizes = PocketPrefs.LOGO_SCALES;
            String[] labels = new String[sizes.length];
            for (int i = 0; i < sizes.length; i++) {
                labels[i] = sizes[i] + "%";
            }
            addChoice(getString(R.string.pocket_logo_size), getString(R.string.pocket_logo_size_sub), labels,
                    () -> {
                        int now = PocketPrefs.logoScale(this);
                        for (int i = 0; i < sizes.length; i++) {
                            if (sizes[i] == now) {
                                return i;
                            }
                        }
                        return 4;
                    },
                    index -> prefs().edit().putInt(PocketPrefs.KEY_LOGO_SCALE, sizes[index]).apply(),
                    null);
        }

        addAction(getString(R.string.pocket_set_clear_art), getString(R.string.pocket_set_clear_art_sub), null, () -> {
            PocketArt.clearAll(this);
            PocketSounds.play(this, PocketSounds.TOGGLE_OFF);
            Toast.makeText(this, R.string.pocket_art_cleared, Toast.LENGTH_SHORT).show();
        });

        addHeader(R.string.pocket_header_accounts);

        addAction(getString(R.string.pocket_set_steam), getString(R.string.pocket_set_steam_sub),
                () -> {
                    if (!com.limelight.pocket.pclib.SteamAccount.linked(this)) {
                        return getString(R.string.pocket_steam_not_linked);
                    }
                    String name = com.limelight.pocket.pclib.SteamAccount.name(this);
                    return name.isEmpty() ? com.limelight.pocket.pclib.SteamAccount.steamId(this) : name;
                },
                () -> com.limelight.pocket.pclib.PcLibraryActivity.askToLink(this, this::refreshRows));

        addAction(getString(R.string.pocket_set_igdb), getString(R.string.pocket_set_igdb_sub),
                () -> getString(com.limelight.pocket.info.Igdb.configured(this) ? R.string.pocket_igdb_set : R.string.pocket_steam_not_linked),
                this::askIgdb);

        addHeader(R.string.pocket_header_device);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addAction(getString(R.string.pocket_set_language), getString(R.string.pocket_set_language_sub), null, () -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APP_LOCALE_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException e) {
                    startActivity(new Intent(this, StreamSettings.class));
                }
            });
        }

        addAction(getString(R.string.pocket_set_home), getString(R.string.pocket_set_home_sub),
                () -> getString(com.limelight.pocket.apps.HomeScreen.isDefault(this) ? R.string.pocket_on : R.string.pocket_off),
                () -> com.limelight.pocket.apps.HomeScreen.openChooser(this));

        addAction(getString(R.string.pocket_device_title), getString(R.string.pocket_set_device_sub), null,
                () -> com.limelight.pocket.device.DevicePanel.show(this));

        addAction(getString(R.string.pocket_info_title), getString(R.string.pocket_set_handheld_sub),
                () -> com.limelight.pocket.device.DeviceInfoPanel.chipLabel(),
                () -> com.limelight.pocket.device.DeviceInfoPanel.show(this));

        addAction(getString(R.string.pocket_set_notif_access), getString(R.string.pocket_set_notif_access_sub),
                () -> getString(com.limelight.pocket.device.PocketNotifications.allowed(this) ? R.string.pocket_on : R.string.pocket_off),
                () -> com.limelight.pocket.device.PocketNotifications.askForAccess(this));

        addToggle(getString(R.string.pocket_set_announce), getString(R.string.pocket_set_announce_sub),
                () -> PocketPrefs.prefs(this).getBoolean("pocket_announce_notifications", true),
                value -> PocketPrefs.prefs(this).edit().putBoolean("pocket_announce_notifications", value).apply());

        addAction(getString(R.string.pocket_apps_title), getString(R.string.pocket_set_apps_sub), null,
                () -> com.limelight.pocket.apps.AndroidAppsActivity.open(this));

        addAction(getString(R.string.pocket_retro_title), getString(R.string.pocket_set_retro_sub),
                () -> {
                    java.io.File root = com.limelight.pocket.retro.RetroLibrary.romRoot(this);
                    return root != null ? root.getAbsolutePath() : getString(R.string.pocket_retro_no_folder);
                },
                () -> com.limelight.pocket.retro.RetroActivity.open(this));

        addAction(getString(R.string.pocket_cloud_title), getString(R.string.pocket_set_cloud_sub), null,
                () -> startActivity(new Intent(this, com.limelight.pocket.cloud.CloudActivity.class)));

        addAction(getString(R.string.pocket_set_profiles), getString(R.string.pocket_set_profiles_sub), null,
                () -> startActivity(new Intent(this, ProfilesActivity.class)));

        addAction(getString(R.string.pocket_set_rp6), getString(R.string.pocket_set_rp6_sub), null, () ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.pocket_set_rp6)
                        .setMessage(R.string.pocket_rp6_confirm)
                        .setPositiveButton(R.string.pocket_apply, (d, w) -> {
                            PocketSetup.applyRp6Defaults(this);
                            Toast.makeText(this, R.string.pocket_rp6_applied, Toast.LENGTH_SHORT).show();
                            refreshRows();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());

        addAction(getString(R.string.pocket_set_rerun_setup), getString(R.string.pocket_set_rerun_setup_sub), null, () -> {
            startActivity(new Intent(this, OnboardingActivity.class).putExtra(OnboardingActivity.EXTRA_RERUN, true));
            overridePendingTransition(R.anim.pocket_pop_enter, R.anim.pocket_pop_exit);
            finish();
        });

        addAction(getString(R.string.pocket_set_reset_tips), getString(R.string.pocket_set_reset_tips_sub), null, () -> {
            PocketPrefs.resetTips(this);
            PocketSounds.play(this, PocketSounds.TOGGLE_ON);
            Toast.makeText(this, R.string.pocket_tips_reset_done, Toast.LENGTH_SHORT).show();
        });

        addHeader(R.string.pocket_header_advanced);

        addAction(getString(R.string.pocket_set_advanced), getString(R.string.pocket_set_advanced_sub), null,
                () -> startActivity(new Intent(this, StreamSettings.class)));

        addAction(getString(R.string.pocket_set_about), getString(R.string.pocket_about_sub),
                () -> BuildConfig.VERSION_NAME, null);
    }

    /** RetroAchievements: the user name and the Web API key (retroachievements.org/settings). */
    private void askRetroAchievements() {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        box.setPadding(pad, pad / 2, pad, 0);
        final android.widget.EditText user = new android.widget.EditText(this);
        user.setSingleLine(true);
        user.setHint(R.string.pocket_ra_user_hint);
        user.setText(com.limelight.pocket.retro.Achievements.raUser(this));
        final android.widget.EditText key = new android.widget.EditText(this);
        key.setSingleLine(true);
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        key.setHint(R.string.pocket_ra_key_hint);
        box.addView(user);
        box.addView(key);
        com.limelight.pocket.ConsoleActivity.showImmersive(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.pocket_set_ra)
                .setMessage(R.string.pocket_ra_message)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    final android.content.Context app = getApplicationContext();
                    com.limelight.pocket.retro.Achievements.setRa(app, user.getText().toString(), key.getText().toString());
                    Toast.makeText(this, R.string.pocket_steam_checking, Toast.LENGTH_SHORT).show();
                    final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    new Thread(() -> {
                        final int points = com.limelight.pocket.retro.Achievements.checkRa(app);
                        main.post(() -> {
                            if (points < 0) {
                                com.limelight.pocket.retro.Achievements.setRa(app, "", "");
                                Toast.makeText(app, R.string.pocket_ra_bad, Toast.LENGTH_LONG).show();
                            }
                            else {
                                Toast.makeText(app, app.getString(R.string.pocket_ra_ok, points), Toast.LENGTH_LONG).show();
                            }
                            refreshRows();
                        });
                    }, "PocketRaCheck").start();
                })
                .setNeutralButton(R.string.pocket_ra_signout, (dlg, w) -> {
                    com.limelight.pocket.retro.Achievements.setRa(this, "", "");
                    refreshRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /** IGDB's Twitch app (client ID and secret, free at dev.twitch.tv): checked, then kept. */
    private void askIgdb() {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        box.setPadding(pad, pad / 2, pad, 0);
        final android.widget.EditText id = new android.widget.EditText(this);
        id.setSingleLine(true);
        id.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        id.setHint(R.string.pocket_igdb_id_hint);
        id.setText(com.limelight.pocket.info.Igdb.clientId(this));
        final android.widget.EditText secret = new android.widget.EditText(this);
        secret.setSingleLine(true);
        secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        secret.setHint(R.string.pocket_igdb_secret_hint);
        box.addView(id);
        box.addView(secret);
        com.limelight.pocket.ConsoleActivity.showImmersive(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.pocket_set_igdb)
                .setMessage(R.string.pocket_igdb_message)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    com.limelight.pocket.info.Igdb.setCredentials(this, id.getText().toString(), secret.getText().toString());
                    Toast.makeText(this, R.string.pocket_steam_checking, Toast.LENGTH_SHORT).show();
                    final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    final android.content.Context app = getApplicationContext();
                    new Thread(() -> {
                        final boolean ok = com.limelight.pocket.info.Igdb.check(app);
                        main.post(() -> {
                            if (!ok) {
                                com.limelight.pocket.info.Igdb.setCredentials(app, "", "");
                            }
                            Toast.makeText(app, ok ? R.string.pocket_igdb_ok : R.string.pocket_igdb_bad, Toast.LENGTH_LONG).show();
                            refreshRows();
                        });
                    }, "PocketIgdbCheck").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    // ---- Row builders -------------------------------------------------------------------

    private void addHeader(int text) {
        TextView header = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = rowsContainer.getChildCount() == 0 ? 0 : dp(14);
        lp.bottomMargin = dp(6);
        header.setLayoutParams(lp);
        header.setPaddingRelative(dp(6), 0, 0, 0);
        header.setText(text);
        header.setAllCaps(true);
        header.setLetterSpacing(0.18f);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        header.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
        header.setTextColor(ContextCompat.getColor(this, R.color.pocket_text_dim));
        rowsContainer.addView(header);
    }

    private LinearLayout newRow(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPaddingRelative(dp(14), dp(7), dp(10), dp(7));
        row.setBackground(com.limelight.pocket.ui.PocketTheme.rowGlide(this));
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                PocketUi.tick(this);
            }
        });

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        titleView.setTextColor(ContextCompat.getColorStateList(this, R.color.pocket_row_text));
        titleView.setDuplicateParentStateEnabled(true);
        texts.setDuplicateParentStateEnabled(true);
        texts.addView(titleView);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            sub.setTextColor(ContextCompat.getColorStateList(this, R.color.pocket_row_text_dim));
            sub.setDuplicateParentStateEnabled(true);
            sub.setPadding(0, dp(1), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts);
        return row;
    }

    private TextView valueText() {
        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        value.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
        value.setTextColor(ContextCompat.getColorStateList(this, R.color.pocket_row_text));
        value.setDuplicateParentStateEnabled(true);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(96));
        return value;
    }

    private ImageView chevron(int res) {
        ImageView chev = new ImageView(this);
        chev.setImageResource(res);
        chev.setImageTintList(ContextCompat.getColorStateList(this, R.color.pocket_row_text_dim));
        chev.setDuplicateParentStateEnabled(true);
        chev.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
        return chev;
    }

    /** A 0-100% level. LEFT/RIGHT move it in steps of 5 (hold to sweep), touch drags it. */
    private void addSlider(String title, String subtitle, final String key, final int previewCategory) {
        LinearLayout row = newRow(title, subtitle);
        final com.limelight.pocket.ui.PocketSlider slider = new com.limelight.pocket.ui.PocketSlider(this);
        slider.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(dp(150), dp(30));
        sliderLp.setMarginStart(dp(10));
        row.addView(slider, sliderLp);
        final TextView value = valueText();
        value.setMinWidth(dp(50));
        row.addView(value);

        final long[] lastPreview = {0};
        final Runnable preview = () -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPreview[0] > (previewCategory == PocketSounds.CATEGORY_BOOT ? 600 : 120)) {
                lastPreview[0] = now;
                PocketSounds.preview(this, previewCategory);
            }
        };
        final Runnable refresh = () -> {
            int v = PocketPrefs.soundVolume(this, key);
            slider.setValue(v, false);
            value.setText(v + "%");
        };
        refresh.run();
        rowRefreshers.add(refresh);

        final StepHandler step = direction -> {
            int v = Math.max(0, Math.min(100, PocketPrefs.soundVolume(this, key) + direction * 5));
            prefs().edit().putInt(key, v).apply();
            slider.setValue(v, true);
            value.setText(v + "%");
            preview.run();
        };
        slider.setListener(v -> {
            prefs().edit().putInt(key, v).apply();
            value.setText(v + "%");
            preview.run();
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    step.step(rtl ? -dir : dir);
                }
                return true;
            }
            return false;
        });
        row.setOnClickListener(v -> {
            lastPreview[0] = 0;
            preview.run();
        });
        rowsContainer.addView(row);
    }

    /** A value picked from a list; LEFT/RIGHT step through it, A steps forward. */
    private void addChoice(String title, String subtitle, final String[] labels,
                           final IndexGetter getter, final IndexSetter setter, final String unknownLabel) {
        LinearLayout row = newRow(title, subtitle);
        ImageView left = chevron(R.drawable.ic_chevron_left);
        final TextView value = valueText();
        ImageView right = chevron(R.drawable.ic_chevron_right);
        row.addView(left);
        row.addView(value);
        row.addView(right);

        final Runnable refresh = () -> {
            int index = getter.get();
            if (index >= 0 && index < labels.length) {
                value.setText(labels[index]);
            }
            else {
                value.setText(unknownLabel != null ? unknownLabel : "—");
            }
        };
        refresh.run();
        rowRefreshers.add(refresh);

        final StepHandler step = direction -> {
            int index = getter.get();
            int next;
            if (index < 0) {
                next = direction > 0 ? 0 : labels.length - 1;
            }
            else {
                next = (index + direction + labels.length) % labels.length;
            }
            setter.set(next);
            // The new value slides in from the side you pressed, and that arrow gives a tap
            boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int now = getter.get();
            CharSequence label = now >= 0 && now < labels.length ? labels[now] : (unknownLabel != null ? unknownLabel : "\u2014");
            com.limelight.pocket.ui.PocketMotion.swapText(value, label, rtl ? -direction : direction);
            com.limelight.pocket.ui.PocketMotion.pop(direction > 0 ? right : left, 0.7f);
            PocketUi.tick(this);
        };
        row.setOnKeyListener((v, keyCode, event) -> {
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    step.step(rtl ? -dir : dir);
                }
                return true;
            }
            return false;
        });
        row.setOnClickListener(v -> step.step(1));
        left.setOnClickListener(v -> step.step(-1));
        right.setOnClickListener(v -> step.step(1));
        rowsContainer.addView(row);
    }

    private interface StepHandler { void step(int direction); }

    /** On/off. A toggles. */
    private void addToggle(String title, String subtitle, final BoolGetter getter, final BoolSetter setter) {
        LinearLayout row = newRow(title, subtitle);
        final TextView pill = new TextView(this);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        pill.setTypeface(com.limelight.pocket.ui.PocketFonts.medium());
        pill.setGravity(Gravity.CENTER);
        pill.setMinWidth(dp(52));
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        pill.setDuplicateParentStateEnabled(true);
        row.addView(pill);

        final Runnable refresh = () -> {
            boolean on = getter.get();
            pill.setText(on ? R.string.pocket_on : R.string.pocket_off);
            if (on) {
                pill.setBackground(com.limelight.pocket.ui.PocketTheme.toggleOn(this));
            }
            else {
                pill.setBackgroundResource(R.drawable.pocket_toggle_off);
            }
            pill.setTextColor(ContextCompat.getColorStateList(this, on ? R.color.pocket_toggle_on_text : R.color.pocket_toggle_off_text));
        };
        refresh.run();
        rowRefreshers.add(refresh);

        row.setOnClickListener(v -> {
            boolean on = !getter.get();
            setter.set(on);
            refresh.run();
            PocketSounds.play(this, on ? PocketSounds.TOGGLE_ON : PocketSounds.TOGGLE_OFF);
            if (PocketPrefs.animationsEnabled(this)) {
                // The pill springs a little when it flips
                pill.setScaleX(0.86f);
                pill.setScaleY(0.86f);
                pill.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(com.limelight.pocket.ui.Spring.POP).start();
            }
        });
        rowsContainer.addView(row);
    }

    /** Opens something. A runs the action. */
    private void addAction(String title, String subtitle, final TextGetter value, final Runnable action) {
        LinearLayout row = newRow(title, subtitle);
        if (value != null) {
            final TextView valueView = valueText();
            valueView.setMinWidth(0);
            valueView.setTextColor(ContextCompat.getColorStateList(this, R.color.pocket_row_text_dim));
            valueView.setPaddingRelative(dp(10), 0, dp(4), 0);
            valueView.setMaxWidth(dp(260));
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(valueView);
            Runnable refresh = () -> valueView.setText(value.get());
            refresh.run();
            rowRefreshers.add(refresh);
        }
        if (action != null) {
            row.addView(chevron(R.drawable.ic_chevron_right));
            row.setOnClickListener(v -> {
                PocketSounds.play(this, PocketSounds.SELECT);
                action.run();
            });
        }
        rowsContainer.addView(row);
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return -1;
    }

    // ---- Hotkey capture -----------------------------------------------------------------

    private void startCapture(int target) {
        captureTarget = target;
        capturingHotkey = true;
        // Home never reaches apps directly; the accessibility service hands it to us while capturing
        KeyboardAccessibilityService.keyTestListener = serviceCapture;
        TextView title = findViewById(R.id.pocketCaptureTitle);
        TextView sub = findViewById(R.id.pocketCaptureSub);
        if (title != null && sub != null) {
            switch (target) {
                case CAPTURE_BACK:
                    title.setText(R.string.pocket_capture_back_title);
                    sub.setText(R.string.pocket_capture_back_sub);
                    break;
                case CAPTURE_HOME:
                    title.setText(R.string.pocket_capture_home_title);
                    sub.setText(isAccessibilityServiceEnabled(this) ? R.string.pocket_capture_home_sub
                            : R.string.pocket_capture_home_sub_noservice);
                    break;
                default:
                    title.setText(R.string.pocket_capture_title);
                    sub.setText(R.string.pocket_capture_sub);
                    break;
            }
        }
        View overlay = findViewById(R.id.pocketCaptureOverlay);
        ViewGroup hints = findViewById(R.id.pocketCaptureHints);
        fillHintBar(this, hints, hintB(this, R.string.pocket_hint_cancel),
                hintX(this, target == CAPTURE_MENU || target == CAPTURE_M2 ? R.string.pocket_hint_clear : R.string.pocket_hint_reset_auto));
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(200).start();
        PocketUi.setBackdropBlur(findViewById(R.id.pocketMain), true);
    }

    private void endHotkeyCapture() {
        capturingHotkey = false;
        if (KeyboardAccessibilityService.keyTestListener == serviceCapture) {
            KeyboardAccessibilityService.keyTestListener = null;
        }
        PocketUi.setBackdropBlur(findViewById(R.id.pocketMain), false);
        View overlay = findViewById(R.id.pocketCaptureOverlay);
        overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
        refreshRows();
    }

    private void handleCaptureKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return;
        }
        // The accessibility service and the window can both hand us the same press
        if (event.getEventTime() == lastCaptureEventTime && event.getKeyCode() == lastCaptureKeyCode) {
            return;
        }
        lastCaptureEventTime = event.getEventTime();
        lastCaptureKeyCode = event.getKeyCode();
        int keyCode = event.getKeyCode();
        // Whatever happens below, the release of this key belongs to the capture
        swallowKeyUp = keyCode;
        if (captureTarget == CAPTURE_BACK || captureTarget == CAPTURE_HOME) {
            String prefKey = captureTarget == CAPTURE_BACK ? PocketPrefs.KEY_BACK_KEY : PocketPrefs.KEY_HOME_KEY;
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_ESCAPE:
                    endHotkeyCapture();
                    return;
                case KeyEvent.KEYCODE_BUTTON_X:
                    PocketPrefs.resetButtonKey(this, prefKey);
                    Toast.makeText(this, R.string.pocket_key_reset_done, Toast.LENGTH_SHORT).show();
                    endHotkeyCapture();
                    return;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_BUTTON_Y:
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_POWER:
                    Toast.makeText(this, R.string.pocket_hotkey_reserved, Toast.LENGTH_SHORT).show();
                    return;
            }
            PocketPrefs.setButtonKey(this, prefKey, event);
            PocketSounds.play(this, PocketSounds.SELECT);
            Toast.makeText(this, getString(R.string.pocket_hotkey_assigned,
                    PocketPrefs.describeHotkey(PocketPrefs.encodeKey(event))), Toast.LENGTH_SHORT).show();
            endHotkeyCapture();
            return;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
                endHotkeyCapture();
                return;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (captureTarget == CAPTURE_M2) {
                    PocketPrefs.clearM2Hotkey(this);
                }
                else {
                    PocketPrefs.clearMenuHotkey(this);
                }
                Toast.makeText(this, R.string.pocket_hotkey_cleared, Toast.LENGTH_SHORT).show();
                endHotkeyCapture();
                return;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_POWER:
                Toast.makeText(this, R.string.pocket_hotkey_reserved, Toast.LENGTH_SHORT).show();
                return;
        }
        String name;
        if (captureTarget == CAPTURE_M2) {
            PocketPrefs.setM2Hotkey(this, event);
            name = PocketPrefs.describeHotkey(PocketPrefs.getM2Hotkey(this));
        }
        else {
            PocketPrefs.setMenuHotkey(this, event);
            name = PocketPrefs.describeHotkey(PocketPrefs.getMenuHotkey(this));
        }
        Toast.makeText(this, getString(R.string.pocket_hotkey_assigned, name), Toast.LENGTH_SHORT).show();
        endHotkeyCapture();
    }

    // ---- Home-as-Guide (accessibility) ----------------------------------------------------

    public static boolean isAccessibilityServiceEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        String ours = new ComponentName(context, KeyboardAccessibilityService.class).flattenToString();
        String oursShort = new ComponentName(context, KeyboardAccessibilityService.class).flattenToShortString();
        for (String part : enabled.split(":")) {
            if (part.equalsIgnoreCase(ours) || part.equalsIgnoreCase(oursShort)) {
                return true;
            }
        }
        return false;
    }

    private void showAccessibilityPrompt() {
        com.limelight.pocket.ConsoleActivity.showImmersive(new AlertDialog.Builder(this)
                .setTitle(R.string.pocket_a11y_title)
                .setMessage(R.string.pocket_a11y_message)
                .setPositiveButton(R.string.pocket_a11y_open, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (RuntimeException ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    // ---- USB help -------------------------------------------------------------------------

    public static void showUsbHelp(Activity activity) {
        com.limelight.pocket.ConsoleActivity.showImmersive(new AlertDialog.Builder(activity)
                .setTitle(R.string.pocket_usb_help_title)
                .setMessage(R.string.pocket_usb_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .create());
    }
}
