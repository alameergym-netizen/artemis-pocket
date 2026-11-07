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
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
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
            updateClock();
            updateLinkChip();
            onStatusTick();
            statusHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PocketSetup.applyFirstRunDefaults(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
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
        startActivity(new Intent(this, QuickSettingsActivity.class));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
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
        if (UsbLink.isLinkUp()) {
            icon = R.drawable.ic_usb;
            text = R.string.pocket_link_usb;
            bg = R.drawable.pocket_chip_usb_bg;
            color = R.color.pocket_accent;
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
            int size = dp(16);
            d.setBounds(0, 0, size, size);
            d.setTint(tint);
            chip.setCompoundDrawablesRelative(d, null, null, null);
            chip.setCompoundDrawablePadding(dp(6));
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
        return new Hint("☰", 0xFFE6E9F0, c.getString(label));
    }

    public static Hint hintShoulders(Context c, int label) {
        return new Hint("L1 R1", 0xFFE6E9F0, c.getString(label));
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
            itemLp.setMarginStart((int) (18 * density));
            item.setLayoutParams(itemLp);

            TextView glyph = new TextView(context);
            boolean pill = hint.glyph.length() > 1;
            int size = (int) (22 * density);
            glyph.setLayoutParams(new LinearLayout.LayoutParams(
                    pill ? ViewGroup.LayoutParams.WRAP_CONTENT : size, size));
            if (pill) {
                glyph.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
            }
            glyph.setGravity(Gravity.CENTER);
            glyph.setText(hint.glyph);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, pill ? 10 : 12);
            glyph.setTextColor(0xFF0A0D14);
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
            labelLp.setMarginStart((int) (7 * density));
            label.setLayoutParams(labelLp);
            label.setText(hint.label);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
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
        view.setTranslationY(view.getResources().getDisplayMetrics().density * 18);
        view.animate().alpha(1f).translationY(0).setStartDelay(delayMs).setDuration(420)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
    }
}
