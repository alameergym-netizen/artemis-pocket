package com.limelight.pocket;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.pocket.ui.AuroraView;
import com.limelight.pocket.ui.PocketMotion;

/**
 * Records a macro: after a short count-down, the controller buttons you press (with their timing)
 * are kept, up to ten seconds. Holding START finishes early (that press isn't part of it). Then
 * A keeps it, X records again, B leaves it as it was. It plays back on the PC from M1 / M2, a
 * double press or hold, or a radial slot set to "Macro N".
 */
public class MacroRecordActivity extends ConsoleActivity {

    public static final String EXTRA_SLOT = "pocket_macro_slot";
    private static final long FINISH_HOLD_MS = 1200;

    private static final int STATE_COUNTDOWN = 0;
    private static final int STATE_RECORDING = 1;
    private static final int STATE_DONE = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int slot;
    private int state;
    private long startedAt;
    private int held;
    private int hatFlags;
    private Macro.Builder builder;
    private Macro result;
    private long startDownAt = -1;
    private int heldBeforeStart;

    private TextView title;
    private TextView big;
    private TextView sub;
    private ProgressBar progress;
    private LinearLayout hintBar;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (state != STATE_RECORDING) {
                return;
            }
            long t = SystemClock.uptimeMillis() - startedAt;
            progress.setProgress((int) Math.min(Macro.MAX_MS, t));
            if (startDownAt >= 0 && SystemClock.uptimeMillis() - startDownAt >= FINISH_HOLD_MS) {
                finishRecording(true);
                return;
            }
            if (t >= Macro.MAX_MS || builder.isFull()) {
                finishRecording(false);
                return;
            }
            main.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        slot = Math.max(0, Math.min(ButtonActions.MACRO_SLOTS - 1, getIntent().getIntExtra(EXTRA_SLOT, 0)));

        FrameLayout root = new FrameLayout(this);
        root.setId(R.id.pocketRoot);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.pocket_bg));
        root.addView(new AuroraView(this), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(dp(48), dp(24), dp(48), dp(24));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        title = text(11f, R.color.pocket_text_dim, "sans-serif-medium");
        title.setLetterSpacing(0.2f);
        title.setAllCaps(true);
        title.setText(getString(R.string.pocket_macro_recording, slot + 1));
        column.addView(title);

        big = text(34f, R.color.pocket_text, "sans-serif-black");
        big.setPadding(0, dp(10), 0, dp(10));
        big.setGravity(Gravity.CENTER);
        big.setMaxLines(2);
        column.addView(big);

        sub = text(12.5f, R.color.pocket_text_dim, null);
        sub.setGravity(Gravity.CENTER);
        sub.setMaxLines(4);
        column.addView(sub, new LinearLayout.LayoutParams(dp(520), -2));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(Macro.MAX_MS);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(com.limelight.pocket.ui.PocketTheme.accent(this)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(420), dp(6));
        plp.topMargin = dp(22);
        column.addView(progress, plp);

        hintBar = new LinearLayout(this);
        hintBar.setId(R.id.pocketHintBar);
        hintBar.setOrientation(LinearLayout.HORIZONTAL);
        hintBar.setGravity(Gravity.CENTER_VERTICAL);
        hintBar.setBackgroundResource(R.drawable.pocket_glass_pill);
        hintBar.setPadding(dp(2), dp(6), dp(14), dp(6));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        hlp.setMargins(0, 0, dp(18), dp(12));
        root.addView(hintBar, hlp);

        setContentView(root);
        startCountdown();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, String font) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(ContextCompat.getColor(this, color));
        if (font != null) {
            t.setTypeface(android.graphics.Typeface.create(font, android.graphics.Typeface.NORMAL));
        }
        return t;
    }

    @Override
    protected boolean allowsAutoResume() {
        return false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacksAndMessages(null);
        if (state != STATE_DONE) {
            // Left mid-way: nothing is kept
            finish();
        }
    }

    // ---- Steps --------------------------------------------------------------------------------

    private void startCountdown() {
        state = STATE_COUNTDOWN;
        held = 0;
        hatFlags = 0;
        startDownAt = -1;
        result = null;
        progress.setProgress(0);
        sub.setText(R.string.pocket_macro_how);
        fillHintBar(this, hintBar, hintB(this, R.string.pocket_hint_cancel));
        for (int i = 3; i >= 1; i--) {
            final int n = i;
            main.postDelayed(() -> {
                big.setText(String.valueOf(n));
                PocketMotion.pop(big, 0.82f);
                PocketSounds.play(this, PocketSounds.NAV);
            }, (3 - i) * 700L);
        }
        main.postDelayed(this::startRecording, 2100);
    }

    private void startRecording() {
        state = STATE_RECORDING;
        builder = new Macro.Builder();
        startedAt = SystemClock.uptimeMillis();
        big.setText(R.string.pocket_macro_go);
        PocketSounds.play(this, PocketSounds.SELECT);
        sub.setText(R.string.pocket_macro_finish_how);
        main.post(tick);
    }

    private void record(int flags) {
        if (state != STATE_RECORDING) {
            return;
        }
        builder.state(flags, (int) (SystemClock.uptimeMillis() - startedAt));
        String names = Macro.describeFlags(flags);
        big.setText(names.isEmpty() ? "·" : names);
    }

    private void finishRecording(boolean byStart) {
        state = STATE_DONE;
        main.removeCallbacks(tick);
        if (byStart && startDownAt >= 0) {
            // The press that finished it isn't part of it: back to what was held before it
            builder.state(heldBeforeStart, (int) (startDownAt - startedAt));
        }
        startDownAt = -1;
        result = builder.build();
        PocketSounds.play(this, PocketSounds.TOGGLE_ON);
        if (result.isEmpty()) {
            big.setText(R.string.pocket_macro_nothing);
            sub.setText(R.string.pocket_macro_nothing_sub);
            fillHintBar(this, hintBar, hintX(this, R.string.pocket_macro_again), hintB(this, R.string.pocket_hint_back));
        }
        else {
            big.setText(R.string.pocket_macro_done);
            sub.setText(result.describe());
            fillHintBar(this, hintBar, hintA(this, R.string.pocket_macro_keep),
                    hintX(this, R.string.pocket_macro_again), hintB(this, R.string.pocket_hint_cancel));
        }
    }

    // ---- Buttons ------------------------------------------------------------------------------

    private static int flagFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return ControllerPacket.A_FLAG;
            case KeyEvent.KEYCODE_BUTTON_B: return ControllerPacket.B_FLAG;
            case KeyEvent.KEYCODE_BUTTON_X: return ControllerPacket.X_FLAG;
            case KeyEvent.KEYCODE_BUTTON_Y: return ControllerPacket.Y_FLAG;
            case KeyEvent.KEYCODE_BUTTON_L1: return ControllerPacket.LB_FLAG;
            case KeyEvent.KEYCODE_BUTTON_R1: return ControllerPacket.RB_FLAG;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return ControllerPacket.LS_CLK_FLAG;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return ControllerPacket.RS_CLK_FLAG;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return ControllerPacket.BACK_FLAG;
            case KeyEvent.KEYCODE_BUTTON_START: return ControllerPacket.PLAY_FLAG;
            case KeyEvent.KEYCODE_BUTTON_MODE: return ControllerPacket.SPECIAL_BUTTON_FLAG;
            case KeyEvent.KEYCODE_DPAD_UP: return ControllerPacket.UP_FLAG;
            case KeyEvent.KEYCODE_DPAD_DOWN: return ControllerPacket.DOWN_FLAG;
            case KeyEvent.KEYCODE_DPAD_LEFT: return ControllerPacket.LEFT_FLAG;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return ControllerPacket.RIGHT_FLAG;
            default: return 0;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        switch (state) {
            case STATE_RECORDING: {
                int flag = flagFor(key);
                if (flag == 0) {
                    return true;
                }
                if (down && event.getRepeatCount() == 0) {
                    if (key == KeyEvent.KEYCODE_BUTTON_START) {
                        startDownAt = SystemClock.uptimeMillis();
                        heldBeforeStart = held | hatFlags;
                    }
                    held |= flag;
                    record(held | hatFlags);
                }
                else if (up) {
                    if (key == KeyEvent.KEYCODE_BUTTON_START) {
                        startDownAt = -1;
                    }
                    held &= ~flag;
                    record(held | hatFlags);
                }
                return true;
            }
            case STATE_DONE:
                if (up) {
                    if (key == KeyEvent.KEYCODE_BUTTON_A && result != null && !result.isEmpty()) {
                        ButtonActions.setMacro(this, slot, result);
                        Toast.makeText(this, getString(R.string.pocket_macro_saved, slot + 1), Toast.LENGTH_SHORT).show();
                        PocketSounds.play(this, PocketSounds.SELECT);
                        finish();
                    }
                    else if (key == KeyEvent.KEYCODE_BUTTON_X) {
                        startCountdown();
                    }
                    else if (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK) {
                        PocketSounds.play(this, PocketSounds.BACK);
                        finish();
                    }
                }
                return true;
            default:
                // Count-down: B leaves
                if (up && (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK)) {
                    PocketSounds.play(this, PocketSounds.BACK);
                    finish();
                }
                return true;
        }
    }

    /** D-pads that report as a hat (axes) instead of keys. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (state == STATE_RECORDING && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            int hat = 0;
            if (x < -0.5f) {
                hat |= ControllerPacket.LEFT_FLAG;
            }
            else if (x > 0.5f) {
                hat |= ControllerPacket.RIGHT_FLAG;
            }
            if (y < -0.5f) {
                hat |= ControllerPacket.UP_FLAG;
            }
            else if (y > 0.5f) {
                hat |= ControllerPacket.DOWN_FLAG;
            }
            if (hat != hatFlags) {
                hatFlags = hat;
                record(held | hatFlags);
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onMenuButton() {
        // START is a button of the macro here
    }

    @Override
    protected void playEntrance() {
    }

    @Override
    protected void playEntrance(boolean fromBoot) {
    }
}
