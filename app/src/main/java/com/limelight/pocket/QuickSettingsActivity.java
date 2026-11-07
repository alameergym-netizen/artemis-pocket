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

import java.util.ArrayList;
import java.util.List;

/**
 * The console settings screen: the handful of options people actually change, laid out for a
 * controller (tabs on L1/R1, rows on the D-pad, values on left/right). Everything else lives in
 * the full Artemis settings, one row away under "Advanced".
 */
public class QuickSettingsActivity extends ConsoleActivity {

    private interface IndexGetter { int get(); }
    private interface IndexSetter { void set(int index); }
    private interface BoolGetter { boolean get(); }
    private interface BoolSetter { void set(boolean value); }
    private interface TextGetter { String get(); }

    private static final int TAB_STREAM = 0;
    private static final int TAB_CONTROLS = 1;
    private static final int TAB_CONNECTION = 2;
    private static final int TAB_SYSTEM = 3;

    private static final int[] BITRATES_MBPS = {5, 10, 15, 20, 25, 30, 40, 50, 60, 80, 100, 120, 150};
    private static final int[] DEADZONES = {0, 2, 3, 4, 5, 6, 8, 10, 15, 20};

    private final List<TextView> tabs = new ArrayList<>();
    private final List<Runnable> rowRefreshers = new ArrayList<>();
    private LinearLayout rowsContainer;
    private ScrollView rowsScroll;
    private int currentTab = TAB_STREAM;
    private boolean capturingHotkey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_settings);

        rowsContainer = findViewById(R.id.pocketRows);
        rowsScroll = findViewById(R.id.pocketRowsScroll);

        LinearLayout tabBar = findViewById(R.id.pocketTabs);
        int[] tabNames = {R.string.pocket_tab_stream, R.string.pocket_tab_controls,
                R.string.pocket_tab_connection, R.string.pocket_tab_system};
        for (int i = 0; i < tabNames.length; i++) {
            final int index = i;
            TextView tab = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            lp.bottomMargin = dp(6);
            tab.setLayoutParams(lp);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setPaddingRelative(dp(18), 0, dp(12), 0);
            tab.setText(tabNames[i]);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tab.setTypeface(tab.getTypeface(), android.graphics.Typeface.BOLD);
            tab.setTextColor(ContextCompat.getColorStateList(this, R.color.pocket_tab_text));
            tab.setBackgroundResource(R.drawable.pocket_tab_bg);
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && currentTab != index) {
                    selectTab(index, false);
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
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
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
        if (animate) {
            ConsoleActivity.reveal(rowsContainer, 0);
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

    private void buildControlsTab() {
        addHeader(R.string.pocket_header_buttons);

        addAction(getString(R.string.pocket_set_hotkey), getString(R.string.pocket_set_hotkey_sub),
                () -> {
                    String name = PocketPrefs.describeHotkey(PocketPrefs.getMenuHotkey(this));
                    return name != null ? name : getString(R.string.pocket_not_set);
                },
                this::startHotkeyCapture);

        addToggle(getString(R.string.pocket_set_back_guide), getString(R.string.pocket_set_back_guide_sub),
                () -> prefs().getBoolean(PocketPrefs.KEY_BACK_AS_GUIDE, false),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_BACK_AS_GUIDE, value).apply());

        addToggle(getString(R.string.pocket_set_home_guide), getString(R.string.pocket_set_home_guide_sub),
                () -> PocketPrefs.homeAsGuide(this),
                value -> {
                    prefs().edit().putBoolean(PocketPrefs.KEY_HOME_AS_GUIDE, value).apply();
                    if (value && !isAccessibilityServiceEnabled(this)) {
                        showAccessibilityPrompt();
                    }
                });

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
                    UsbLink.TetherInfo info = UsbLink.findTetherInterface();
                    if (info == null) {
                        return getString(R.string.pocket_usb_status_off);
                    }
                    return getString(R.string.pocket_usb_status_on, info.describe());
                },
                () -> showUsbHelp(this));

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

        addAction(getString(R.string.pocket_set_usb_tether), getString(R.string.pocket_set_usb_tether_sub), null,
                this::openTetherSettings);

        addHeader(R.string.pocket_header_pcs);

        addAction(getString(R.string.pocket_set_add_pc), getString(R.string.pocket_set_add_pc_sub), null,
                () -> startActivity(new Intent(this, AddComputerManually.class)));
    }

    private void openTetherSettings() {
        String[] actions = {"android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS};
        for (String action : actions) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ---- System -------------------------------------------------------------------------

    private void buildSystemTab() {
        addHeader(R.string.pocket_header_app);

        addToggle(getString(R.string.pocket_set_animations), getString(R.string.pocket_set_animations_sub),
                () -> PocketPrefs.animationsEnabled(this),
                value -> prefs().edit().putBoolean(PocketPrefs.KEY_ANIMATIONS, value).apply());

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

        addHeader(R.string.pocket_header_advanced);

        addAction(getString(R.string.pocket_set_advanced), getString(R.string.pocket_set_advanced_sub), null,
                () -> startActivity(new Intent(this, StreamSettings.class)));

        addAction(getString(R.string.pocket_set_about), getString(R.string.pocket_about_sub),
                () -> BuildConfig.VERSION_NAME, null);
    }

    // ---- Row builders -------------------------------------------------------------------

    private void addHeader(int text) {
        TextView header = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = rowsContainer.getChildCount() == 0 ? 0 : dp(18);
        lp.bottomMargin = dp(8);
        header.setLayoutParams(lp);
        header.setPaddingRelative(dp(6), 0, 0, 0);
        header.setText(text);
        header.setAllCaps(true);
        header.setLetterSpacing(0.18f);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        header.setTextColor(ContextCompat.getColor(this, R.color.pocket_accent));
        rowsContainer.addView(header);
    }

    private LinearLayout newRow(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        row.setPaddingRelative(dp(18), dp(10), dp(14), dp(10));
        row.setBackgroundResource(R.drawable.pocket_row_bg);
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (PocketPrefs.animationsEnabled(this)) {
                v.animate().scaleX(hasFocus ? 1.015f : 1f).scaleY(hasFocus ? 1.015f : 1f).setDuration(160).start();
            }
        });

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTextColor(ContextCompat.getColor(this, R.color.pocket_text));
        texts.addView(titleView);

        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            sub.setTextColor(ContextCompat.getColor(this, R.color.pocket_text_dim));
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts);
        return row;
    }

    private TextView valueText() {
        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        value.setTypeface(value.getTypeface(), android.graphics.Typeface.BOLD);
        value.setTextColor(ContextCompat.getColor(this, R.color.pocket_text));
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(120));
        return value;
    }

    private ImageView chevron(int res) {
        ImageView chev = new ImageView(this);
        chev.setImageResource(res);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.pocket_text_dim));
        chev.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        return chev;
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
            refresh.run();
            if (PocketPrefs.animationsEnabled(this)) {
                value.setTranslationX(direction * dp(10));
                value.setAlpha(0.3f);
                value.animate().translationX(0).alpha(1f).setDuration(180).start();
            }
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
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pill.setTypeface(pill.getTypeface(), android.graphics.Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setMinWidth(dp(64));
        pill.setPadding(dp(12), dp(5), dp(12), dp(5));
        row.addView(pill);

        final Runnable refresh = () -> {
            boolean on = getter.get();
            pill.setText(on ? R.string.pocket_on : R.string.pocket_off);
            pill.setBackgroundResource(on ? R.drawable.pocket_chip_usb_bg : R.drawable.pocket_chip_bg);
            pill.setTextColor(ContextCompat.getColor(this, on ? R.color.pocket_accent : R.color.pocket_text_dim));
        };
        refresh.run();
        rowRefreshers.add(refresh);

        row.setOnClickListener(v -> {
            setter.set(!getter.get());
            refresh.run();
            if (PocketPrefs.animationsEnabled(this)) {
                pill.setScaleX(0.85f);
                pill.setScaleY(0.85f);
                pill.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
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
            valueView.setTextColor(ContextCompat.getColor(this, R.color.pocket_text_dim));
            valueView.setPaddingRelative(dp(12), 0, dp(6), 0);
            valueView.setMaxWidth(dp(320));
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(valueView);
            Runnable refresh = () -> valueView.setText(value.get());
            refresh.run();
            rowRefreshers.add(refresh);
        }
        if (action != null) {
            row.addView(chevron(R.drawable.ic_chevron_right));
            row.setOnClickListener(v -> action.run());
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

    private void startHotkeyCapture() {
        capturingHotkey = true;
        View overlay = findViewById(R.id.pocketCaptureOverlay);
        ViewGroup hints = findViewById(R.id.pocketCaptureHints);
        fillHintBar(this, hints, hintB(this, R.string.pocket_hint_cancel), hintX(this, R.string.pocket_hint_clear));
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(200).start();
    }

    private void endHotkeyCapture() {
        capturingHotkey = false;
        View overlay = findViewById(R.id.pocketCaptureOverlay);
        overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
        refreshRows();
    }

    private void handleCaptureKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return;
        }
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
                endHotkeyCapture();
                return;
            case KeyEvent.KEYCODE_BUTTON_X:
                PocketPrefs.clearMenuHotkey(this);
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
        PocketPrefs.setMenuHotkey(this, event);
        String name = PocketPrefs.describeHotkey(PocketPrefs.getMenuHotkey(this));
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.pocket_a11y_title)
                .setMessage(R.string.pocket_a11y_message)
                .setPositiveButton(R.string.pocket_a11y_open, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (RuntimeException ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- USB help -------------------------------------------------------------------------

    public static void showUsbHelp(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.pocket_usb_help_title)
                .setMessage(R.string.pocket_usb_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
