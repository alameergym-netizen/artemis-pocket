package com.limelight.pocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ContextMenu;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.widget.PopupMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.limelight.R;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.StreamMenuView;

import java.util.ArrayList;
import java.util.List;

import java.util.Date;

/**
 * Base for the console-style screens: full-screen, a status bar of our own (link, battery,
 * clock), a button-hint bar, and the controller shortcuts shared by every screen:
 *   START or the configured menu button  -> quick settings
 */
public abstract class ConsoleActivity extends AppCompatActivity {

    public static final class Hint {
        final String glyph;
        final int color;
        final String label;

        public Hint(String glyph, int color, String label) {
            this.glyph = glyph;
            this.color = color;
            this.label = label;
        }
    }

    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private String menuHotkey;
    private boolean hotkeyDown;
    private int batteryPct = -1;
    private boolean charging;
    private boolean statusRunning;
    private int createdWithScale;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (level >= 0 && scale > 0) {
                batteryPct = Math.round(level * 100f / scale);
            }
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            updateBattery();
        }
    };

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            // Look for the PC's ADB USB bridge in the background
            UsbBridge.probeAsync();
            updateClock();
            updateLinkChip();
            onStatusTick();
            statusHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(PocketUi.wrapWithUiScale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PocketSetup.applyFirstRunDefaults(this);
        super.onCreate(savedInstanceState);
        createdWithScale = PocketPrefs.uiScalePercent(this);
        PocketSounds.preload(this);
        applyFullscreenWindow(this);
    }

    /** Edge-to-edge, behind the camera cutout, no system bars: the whole 1080p panel is ours. */
    public static void applyFullscreenWindow(android.app.Activity activity) {
        android.view.Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
        window.setStatusBarColor(0);
        window.setNavigationBarColor(0);
        hideSystemBars(activity);
    }

    public static void hideSystemBars(android.app.Activity activity) {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (createdWithScale != 0 && createdWithScale != PocketPrefs.uiScalePercent(this)) {
            // Interface size changed in settings: rebuild this screen at the new size
            recreate();
            return;
        }
        // A crash or caught error from last time: show it once, with a Copy button
        CrashReporter.showPendingReport(this);
        // A stream that was suspended (screen off, Home) or is switching quality comes back now
        maybeAutoResume();
        if (pendingResume == null) {
            maybeShowIntro();
        }
        menuHotkey = PocketPrefs.getMenuHotkey(this);
        enterImmersive();
        try {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (RuntimeException ignored) {
        }
        statusRunning = true;
        statusHandler.removeCallbacks(statusTick);
        statusHandler.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pendingResume != null) {
            // Left before the stream came back: keep the ticket for next time
            statusHandler.removeCallbacks(resumeLaunch);
            ResumeTicket.putBack(this, pendingResumeReason);
            pendingResume = null;
            removeResumeOverlay();
        }
        statusRunning = false;
        statusHandler.removeCallbacks(statusTick);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersive();
        }
    }

    protected void enterImmersive() {
        hideSystemBars(this);
    }

    /** Called every couple of seconds while the screen is visible. */
    protected void onStatusTick() {
    }

    /** START / menu button: open the console settings. Screens can override. */
    protected void onMenuButton() {
        openQuickSettings();
    }

    /** X button. Return true if the screen used it. */
    protected boolean onButtonX() {
        return false;
    }

    /** L1 / R1. Return true if the screen used it. */
    protected boolean onShoulder(int direction) {
        return false;
    }

    public void openQuickSettings() {
        PocketSounds.play(this, PocketSounds.OPEN);
        startActivity(new Intent(this, QuickSettingsActivity.class));
    }

    // ---- Seamless resume ------------------------------------------------------------------

    private Intent pendingResume;
    private int pendingResumeReason;
    private com.limelight.pocket.ui.ResumeOverlay resumeOverlay;
    private final Runnable resumeLaunch = this::launchPendingResume;

    /** Screens where a suspended stream may come back by itself. */
    protected boolean allowsAutoResume() {
        return true;
    }

    private void maybeAutoResume() {
        if (pendingResume != null || !allowsAutoResume() || !ResumeTicket.isArmed(this)) {
            return;
        }
        int reason = ResumeTicket.reason(this);
        if (reason == ResumeTicket.REASON_SUSPEND && !PocketPrefs.seamlessResume(this)) {
            ResumeTicket.disarm(this);
            return;
        }
        Intent intent = ResumeTicket.take(this);
        if (intent == null) {
            return;
        }
        pendingResume = intent;
        pendingResumeReason = reason;

        String caption;
        String subtitle;
        if (reason == ResumeTicket.REASON_SUSPEND) {
            caption = getString(R.string.pocket_resume_caption);
            subtitle = getString(ResumeTicket.isUsb(intent) ? R.string.pocket_resume_sub_usb : R.string.pocket_resume_sub);
        }
        else {
            caption = getString(reason == ResumeTicket.REASON_ADAPTIVE
                    ? R.string.pocket_adaptive_caption : R.string.pocket_quality_caption);
            int kbps = intent.getIntExtra(ResumeTicket.EXTRA_BITRATE, 0);
            int fps = intent.getIntExtra(ResumeTicket.EXTRA_FPS, 0);
            StringBuilder sb = new StringBuilder();
            if (kbps > 0) {
                sb.append(Math.round(kbps / 1000f)).append(" Mbps");
            }
            if (fps > 0) {
                if (sb.length() > 0) {
                    sb.append("  \u00b7  ");
                }
                sb.append(fps).append(" FPS");
            }
            subtitle = sb.toString();
        }

        removeResumeOverlay();
        resumeOverlay = new com.limelight.pocket.ui.ResumeOverlay(this);
        resumeOverlay.bind(caption, ResumeTicket.appName(intent), subtitle,
                hintA(this, R.string.pocket_hint_now), hintB(this, R.string.pocket_hint_cancel));
        ViewGroup content = findViewById(android.R.id.content);
        content.addView(resumeOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        long delay = reason == ResumeTicket.REASON_SUSPEND ? 1500 : 700;
        resumeOverlay.start(delay);
        PocketSounds.play(this, PocketSounds.OPEN);
        statusHandler.postDelayed(resumeLaunch, delay);
    }

    private void launchPendingResume() {
        if (pendingResume == null) {
            return;
        }
        statusHandler.removeCallbacks(resumeLaunch);
        if (ResumeTicket.isPreviousStreamStopping()) {
            // Let the last stream finish saying goodbye to the PC first
            if (resumeOverlay != null) {
                resumeOverlay.holdFull();
            }
            statusHandler.postDelayed(resumeLaunch, 150);
            return;
        }
        final Intent intent = pendingResume;
        if (ResumeTicket.isUsb(intent)) {
            new Thread(() -> {
                UsbBridge.ensureConnected();
                runOnUiThread(() -> startResumedStream(intent));
            }).start();
        }
        else {
            startResumedStream(intent);
        }
    }

    private void startResumedStream(Intent intent) {
        if (pendingResume != intent) {
            return; // cancelled meanwhile
        }
        pendingResume = null;
        PocketSounds.play(this, PocketSounds.SELECT);
        startActivity(intent);
        if (PocketPrefs.animationsEnabled(this)) {
            overridePendingTransition(R.anim.pocket_launch_in, R.anim.pocket_launch_out);
        }
        statusHandler.postDelayed(this::removeResumeOverlay, 600);
    }

    private void cancelResume() {
        statusHandler.removeCallbacks(resumeLaunch);
        pendingResume = null;
        PocketSounds.play(this, PocketSounds.BACK);
        if (resumeOverlay != null) {
            resumeOverlay.dismiss();
            resumeOverlay = null;
        }
    }

    private void removeResumeOverlay() {
        if (resumeOverlay != null) {
            ViewGroup parent = (ViewGroup) resumeOverlay.getParent();
            if (parent != null) {
                parent.removeView(resumeOverlay);
            }
            resumeOverlay = null;
        }
    }

    // ---- Intro (once per app start) ------------------------------------------------------

    private static boolean introShown;
    private android.widget.FrameLayout introOverlay;

    private void maybeShowIntro() {
        if (introShown || !allowsAutoResume()) {
            return;
        }
        introShown = true;
        if (!PocketPrefs.introAnimation(this) || !PocketPrefs.animationsEnabled(this)) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        introOverlay = new android.widget.FrameLayout(this);
        introOverlay.setBackgroundColor(0xFF000000);
        introOverlay.setClickable(true);
        introOverlay.setOnClickListener(v -> dismissIntro());

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        introOverlay.addView(column, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.ic_pocket_logo);
        logo.setColorFilter(PocketPrefs.accentColor(this));
        column.addView(logo, new LinearLayout.LayoutParams(Math.round(96 * d), Math.round(96 * d)));

        TextView name = new TextView(this);
        name.setText(R.string.app_label);
        name.setAllCaps(true);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setLetterSpacing(0.6f);
        name.setPadding(0, Math.round(14 * d), 0, 0);
        column.addView(name);

        ViewGroup content = findViewById(android.R.id.content);
        content.addView(introOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        logo.setAlpha(0f);
        logo.setScaleX(0.86f);
        logo.setScaleY(0.86f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(PocketUi.EASE).start();
        name.setAlpha(0f);
        name.animate().alpha(1f).setStartDelay(160).setDuration(420).start();
        android.animation.ValueAnimator spacing = android.animation.ValueAnimator.ofFloat(0.6f, 0.32f);
        spacing.setDuration(900);
        spacing.setInterpolator(PocketUi.EASE);
        spacing.addUpdateListener(a -> name.setLetterSpacing((Float) a.getAnimatedValue()));
        spacing.start();
        PocketSounds.play(this, PocketSounds.OPEN);
        statusHandler.postDelayed(this::dismissIntro, 1150);
    }

    private void dismissIntro() {
        final android.widget.FrameLayout overlay = introOverlay;
        if (overlay == null) {
            return;
        }
        introOverlay = null;
        statusHandler.removeCallbacks(this::dismissIntro);
        overlay.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) {
                parent.removeView(overlay);
            }
        }).start();
    }

    // ---- Console panels (options, game settings, connection test) -------------------------

    private StreamMenuView consolePanel;
    private int swallowKeyUp = -1;

    private StreamMenuView consolePanel() {
        if (consolePanel == null) {
            consolePanel = new StreamMenuView(this);
            consolePanel.setConfirmLabel(getString(R.string.pocket_sm_confirm));
            consolePanel.setListener(() -> PocketUi.setBackdropBlur(blurTarget(), false));
            ViewGroup content = findViewById(android.R.id.content);
            content.addView(consolePanel, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        consolePanel.bringToFront();
        return consolePanel;
    }

    /** The screen behind a panel, softly blurred while the panel is open. */
    private View blurTarget() {
        View main = findViewById(R.id.pocketMain);
        if (main != null) {
            return main;
        }
        ViewGroup content = findViewById(android.R.id.content);
        View first = content != null && content.getChildCount() > 0 ? content.getChildAt(0) : null;
        return first != consolePanel ? first : null;
    }

    public boolean isConsolePanelShowing() {
        return consolePanel != null && consolePanel.isShowing();
    }

    /** A glass side panel driven by the controller, like the in-stream menu. */
    public void showConsolePanel(String title, String subtitle, String stats, List<StreamMenuView.Item> items) {
        PocketUi.setBackdropBlur(blurTarget(), true);
        consolePanel().show(title != null ? title : "", subtitle != null ? subtitle : "", stats, items,
                hintA(this, R.string.pocket_hint_select), hintB(this, R.string.pocket_hint_back),
                new Hint("\u25c0\u25b6", 0xFFFFFFFF, getString(R.string.pocket_hint_change)));
    }

    public void pushConsolePage(String title, List<StreamMenuView.Item> items) {
        if (consolePanel != null && consolePanel.isShowing()) {
            consolePanel.push(title, items);
        }
    }

    public void hideConsolePanel() {
        if (consolePanel != null) {
            consolePanel.hide();
        }
    }

    /**
     * Shows this screen's options for a card (its context menu) as a console panel instead of
     * Android's plain menu. extra items go first.
     */
    protected void showOptionsFor(View anchor, String subtitle, List<StreamMenuView.Item> extra) {
        final String[] header = {null};
        final Menu base = new PopupMenu(this, anchor != null ? anchor : getWindow().getDecorView()).getMenu();
        ContextMenu menu = (ContextMenu) java.lang.reflect.Proxy.newProxyInstance(ContextMenu.class.getClassLoader(),
                new Class<?>[]{ContextMenu.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setHeaderTitle":
                            header[0] = args[0] instanceof Integer ? getString((Integer) args[0]) : String.valueOf(args[0]);
                            return proxy;
                        case "setHeaderIcon":
                        case "setHeaderView":
                            return proxy;
                        case "clearHeader":
                            header[0] = null;
                            return null;
                        default:
                            return method.invoke(base, args);
                    }
                });
        try {
            onCreateContextMenu(menu, anchor, null);
        } catch (RuntimeException e) {
            CrashReporter.saveCaught(this, e);
            return;
        }
        List<StreamMenuView.Item> items = new ArrayList<>();
        if (extra != null) {
            items.addAll(extra);
        }
        addMenuItems(base, items);
        if (!items.isEmpty()) {
            showConsolePanel(header[0], subtitle, null, items);
        }
    }

    private void addMenuItems(Menu menu, List<StreamMenuView.Item> out) {
        for (int i = 0; i < menu.size(); i++) {
            final MenuItem item = menu.getItem(i);
            if (!item.isVisible()) {
                continue;
            }
            final String title = String.valueOf(item.getTitle());
            if (item.hasSubMenu()) {
                final SubMenu sub = item.getSubMenu();
                out.add(StreamMenuView.Item.submenu(title, () -> {
                    List<StreamMenuView.Item> page = new ArrayList<>();
                    addMenuItems(sub, page);
                    pushConsolePage(title, page);
                }));
            }
            else if (item.isCheckable()) {
                out.add(StreamMenuView.Item.action(title, () -> onContextItemSelected(item))
                        .withValue(getString(item.isChecked() ? R.string.pocket_on : R.string.pocket_off)));
            }
            else {
                out.add(StreamMenuView.Item.action(title, () -> onContextItemSelected(item)));
            }
        }
    }

    // ---- Blocking card (waking a PC, ...) ---------------------------------------------------

    private com.limelight.pocket.ui.ResumeOverlay blockingCard;
    private Runnable blockingCancel;

    public void showBlockingCard(String caption, String title, String subtitle, long durationMs, Runnable onCancel) {
        hideBlockingCard();
        blockingCard = new com.limelight.pocket.ui.ResumeOverlay(this);
        blockingCard.bind(caption, title, subtitle, hintB(this, R.string.pocket_hint_cancel));
        ViewGroup content = findViewById(android.R.id.content);
        content.addView(blockingCard, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        blockingCard.start(durationMs);
        blockingCancel = onCancel;
    }

    public void hideBlockingCard() {
        if (blockingCard != null) {
            blockingCard.dismiss();
            blockingCard = null;
        }
        blockingCancel = null;
    }

    public boolean isBlockingCardShowing() {
        return blockingCard != null;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (isConsolePanelShowing() && (event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float x = Math.abs(hx) > 0.5f ? hx : event.getAxisValue(MotionEvent.AXIS_X);
            float y = Math.abs(hy) > 0.5f ? hy : event.getAxisValue(MotionEvent.AXIS_Y);
            consolePanel.onStick(x, y);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (blockingCard != null) {
            int k = event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && (k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE)) {
                Runnable cancel = blockingCancel;
                hideBlockingCard();
                swallowKeyUp = k;
                PocketSounds.play(this, PocketSounds.BACK);
                if (cancel != null) {
                    cancel.run();
                }
            }
            return true;
        }
        if (isConsolePanelShowing()) {
            boolean handled = consolePanel.handleKey(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN && !consolePanel.isShowing()) {
                // The panel closed on this press: its release must not act on the screen below
                swallowKeyUp = event.getKeyCode();
            }
            return handled;
        }
        if (swallowKeyUp >= 0 && event.getKeyCode() == swallowKeyUp) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                swallowKeyUp = -1;
                return true;
            }
            if (event.getRepeatCount() > 0) {
                return true;
            }
            swallowKeyUp = -1; // a fresh press
        }
        if (introOverlay != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                dismissIntro();
            }
            return true;
        }
        if (pendingResume != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_BUTTON_A:
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        launchPendingResume();
                        break;
                    case KeyEvent.KEYCODE_BUTTON_B:
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_ESCAPE:
                        cancelResume();
                        break;
                    default:
                        break;
                }
            }
            return true;
        }
        if (PocketPrefs.matchesHotkey(menuHotkey, event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0 && !hotkeyDown) {
                    hotkeyDown = true;
                    onMenuButton();
                }
            }
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                hotkeyDown = false;
            }
            return true;
        }

        int keyCode = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (up && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B)) {
            PocketSounds.play(this, PocketSounds.BACK);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_START:
                if (up) {
                    onMenuButton();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (up) {
                    onButtonX();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                if (up) {
                    onShoulder(-1);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (up) {
                    onShoulder(1);
                }
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- Status bar ---------------------------------------------------------------------

    private void updateClock() {
        TextView clock = findViewById(R.id.pocketClock);
        if (clock != null) {
            clock.setText(DateFormat.getTimeFormat(this).format(new Date()));
        }
    }

    private void updateBattery() {
        TextView battery = findViewById(R.id.pocketBattery);
        if (battery != null && batteryPct >= 0) {
            battery.setText((charging ? "⚡ " : "") + batteryPct + "%");
            battery.setTextColor(ContextCompat.getColor(this,
                    batteryPct <= 15 && !charging ? R.color.pocket_off : R.color.pocket_text_dim));
        }
    }

    public static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        Network net = cm.getActiveNetwork();
        if (net == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    protected void updateLinkChip() {
        TextView chip = findViewById(R.id.pocketLinkChip);
        if (chip == null) {
            return;
        }
        int icon;
        int text;
        int bg;
        int color;
        if (UsbLink.isAnyUsbLinkUp()) {
            icon = R.drawable.ic_usb;
            text = R.string.pocket_link_usb;
            bg = R.drawable.pocket_chip_usb_bg;
            color = R.color.pocket_bg;
        }
        else if (isWifiConnected(this)) {
            icon = R.drawable.ic_wifi;
            text = R.string.pocket_link_wifi;
            bg = R.drawable.pocket_chip_bg;
            color = R.color.pocket_text;
        }
        else {
            icon = R.drawable.ic_link_off;
            text = R.string.pocket_link_offline;
            bg = R.drawable.pocket_chip_bg;
            color = R.color.pocket_text_dim;
        }
        chip.setText(text);
        chip.setBackgroundResource(bg);
        int tint = ContextCompat.getColor(this, color);
        chip.setTextColor(tint);
        Drawable d = ContextCompat.getDrawable(this, icon);
        if (d != null) {
            d = d.mutate();
            int size = dp(13);
            d.setBounds(0, 0, size, size);
            d.setTint(tint);
            chip.setCompoundDrawablesRelative(d, null, null, null);
            chip.setCompoundDrawablePadding(dp(5));
        }
    }

    // ---- Hint bar -----------------------------------------------------------------------

    public static Hint hintA(Context c, int label) {
        return new Hint("A", ContextCompat.getColor(c, R.color.pocket_btn_a), c.getString(label));
    }

    public static Hint hintB(Context c, int label) {
        return new Hint("B", ContextCompat.getColor(c, R.color.pocket_btn_b), c.getString(label));
    }

    public static Hint hintX(Context c, int label) {
        return new Hint("X", ContextCompat.getColor(c, R.color.pocket_btn_x), c.getString(label));
    }

    public static Hint hintY(Context c, int label) {
        return new Hint("Y", ContextCompat.getColor(c, R.color.pocket_btn_y), c.getString(label));
    }

    public static Hint hintStart(Context c, int label) {
        return new Hint("☰", 0xFFFFFFFF, c.getString(label));
    }

    public static Hint hintShoulders(Context c, int label) {
        return new Hint("L1 R1", 0xFFFFFFFF, c.getString(label));
    }

    protected void setHints(Hint... hints) {
        ViewGroup bar = findViewById(R.id.pocketHintBar);
        if (bar != null) {
            fillHintBar(this, bar, hints);
        }
    }

    public static void fillHintBar(Context context, ViewGroup bar, Hint... hints) {
        bar.removeAllViews();
        float density = context.getResources().getDisplayMetrics().density;
        for (Hint hint : hints) {
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.setMarginStart((int) (14 * density));
            item.setLayoutParams(itemLp);

            TextView glyph = new TextView(context);
            boolean pill = hint.glyph.length() > 1;
            int size = (int) (18 * density);
            glyph.setLayoutParams(new LinearLayout.LayoutParams(
                    pill ? ViewGroup.LayoutParams.WRAP_CONTENT : size, size));
            if (pill) {
                glyph.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
            }
            glyph.setGravity(Gravity.CENTER);
            glyph.setText(hint.glyph);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, pill ? 8.5f : 10);
            glyph.setTextColor(0xFF000000);
            glyph.setTypeface(glyph.getTypeface(), android.graphics.Typeface.BOLD);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(pill ? GradientDrawable.RECTANGLE : GradientDrawable.OVAL);
            if (pill) {
                bg.setCornerRadius(size);
            }
            bg.setColor(hint.color);
            glyph.setBackground(bg);
            item.addView(glyph);

            TextView label = new TextView(context);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.setMarginStart((int) (6 * density));
            label.setLayoutParams(labelLp);
            label.setText(hint.label);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            label.setTextColor(ContextCompat.getColor(context, R.color.pocket_text_dim));
            item.addView(label);

            bar.addView(item);
        }
    }

    protected int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    protected boolean isStatusRunning() {
        return statusRunning;
    }

    /** Fade+rise entrance used when a screen's content appears. */
    public static void reveal(View view, int delayMs) {
        if (view == null) {
            return;
        }
        if (!PocketPrefs.animationsEnabled(view.getContext())) {
            view.setAlpha(1f);
            view.setTranslationY(0);
            return;
        }
        view.setAlpha(0f);
        view.animate().alpha(1f).setStartDelay(delayMs).setDuration(180)
                .setInterpolator(PocketUi.EASE).start();
    }
}
