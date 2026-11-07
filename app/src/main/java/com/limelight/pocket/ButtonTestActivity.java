package com.limelight.pocket;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.KeyboardAccessibilityService;
import com.limelight.R;
import com.limelight.pocket.ui.AuroraView;
import com.limelight.pocket.ui.PocketUi;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Shows exactly what every button sends (key code, scan code, device, flags) and whether the
 * accessibility service can see it. Used to set up M1/M2 and to diagnose the Home button.
 */
public class ButtonTestActivity extends ConsoleActivity implements KeyboardAccessibilityService.KeyTestListener {

    private TextView bigName;
    private TextView details;
    private TextView serviceStatus;
    private TextView log;
    private final Deque<String> history = new ArrayDeque<>();
    private long lastBackUp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(new AuroraView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(28), dp(16), dp(28), dp(14));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text(R.string.pocket_set_button_test, 19, R.color.pocket_text, true);
        column.addView(title);
        TextView help = text(R.string.pocket_button_test_help, 11.5f, R.color.pocket_text_dim, false);
        help.setPadding(0, dp(2), 0, dp(12));
        column.addView(help);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundResource(R.drawable.pocket_panel_bg);
        panel.setPadding(dp(20), dp(16), dp(20), dp(16));
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        column.addView(panel, panelLp);

        bigName = text(R.string.pocket_button_test_waiting, 26, R.color.pocket_text, true);
        bigName.setGravity(Gravity.CENTER);
        panel.addView(bigName);
        details = text(0, 12, R.color.pocket_text_dim, false);
        details.setGravity(Gravity.CENTER);
        details.setPadding(0, dp(4), 0, dp(10));
        panel.addView(details);
        log = text(0, 10.5f, R.color.pocket_text_faint, false);
        log.setGravity(Gravity.CENTER);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        panel.addView(log);

        serviceStatus = text(0, 11, R.color.pocket_text_dim, false);
        serviceStatus.setPadding(0, dp(10), 0, dp(8));
        column.addView(serviceStatus);

        LinearLayout hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setBackgroundResource(R.drawable.pocket_glass_pill);
        hints.setPadding(dp(2), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams hintsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintsLp.gravity = Gravity.END;
        column.addView(hints, hintsLp);
        fillHintBar(this, hints, hintB(this, R.string.pocket_button_test_exit));

        setContentView(root);
        ConsoleActivity.reveal(panel, 60);
    }

    private TextView text(int res, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        if (res != 0) {
            t.setText(res);
        }
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(this, color));
        if (medium) {
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        }
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeyboardAccessibilityService.keyTestListener = this;
        boolean enabled = QuickSettingsActivity.isAccessibilityServiceEnabled(this);
        serviceStatus.setText(enabled ? R.string.pocket_button_test_service_on : R.string.pocket_button_test_service_off);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (KeyboardAccessibilityService.keyTestListener == this) {
            KeyboardAccessibilityService.keyTestListener = null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            show(event, false);
        }
        // Press B / Back twice quickly to leave
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B)
                && event.getAction() == KeyEvent.ACTION_UP) {
            long now = SystemClock.uptimeMillis();
            if (now - lastBackUp < 700) {
                finish();
            }
            lastBackUp = now;
        }
        return true;
    }

    @Override
    public void onServiceKeyEvent(final KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            runOnUiThread(() -> show(event, true));
        }
    }

    private void show(KeyEvent event, boolean viaService) {
        String name = KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", "");
        InputDevice device = event.getDevice();
        String deviceName = device != null ? device.getName() : "—";
        StringBuilder sb = new StringBuilder();
        sb.append("keycode ").append(event.getKeyCode())
                .append(" · scancode ").append(event.getScanCode())
                .append("\n").append(deviceName);
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            sb.append(" · virtual key");
        }
        if (viaService) {
            sb.append("\n").append(getString(R.string.pocket_button_test_via_service));
        }
        PocketUi.swapText(bigName, name);
        details.setText(sb.toString());
        PocketUi.tick(this);
        if (PocketPrefs.animationsEnabled(this)) {
            bigName.setScaleX(1.15f);
            bigName.setScaleY(1.15f);
            bigName.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(PocketUi.EASE).start();
        }

        history.addFirst(name + "  (" + event.getKeyCode() + "/" + event.getScanCode() + ")" + (viaService ? "  ⓐ" : ""));
        while (history.size() > 6) {
            history.removeLast();
        }
        StringBuilder lines = new StringBuilder();
        for (String line : history) {
            if (lines.length() > 0) {
                lines.append('\n');
            }
            lines.append(line);
        }
        log.setText(lines.toString());
    }
}
