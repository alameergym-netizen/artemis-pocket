package com.limelight.pocket.apps;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.ui.PocketTheme;
import com.limelight.pocket.ui.PocketUi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picks the Android games and apps for the library, several at once: A ticks one, Y ticks every
 * game and emulator, X (or START) adds them. The first time, games and emulators come ticked.
 */
public class AppPickerActivity extends AppCompatActivity {

    public static final int REQUEST = 7303;
    /** Result: the packages that are new in the library. */
    public static final String RESULT_ADDED = "pocket.apps.added";

    public static void open(Activity from) {
        PocketSounds.play(from, PocketSounds.OPEN);
        from.startActivityForResult(new Intent(from, AppPickerActivity.class), REQUEST);
    }

    private final List<AndroidLibrary.InstalledApp> apps = new ArrayList<>();
    private final Set<String> picked = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> icons = new LruCache<>(120);
    private RecyclerView list;
    private TextView addButton;
    private TextView subtitle;
    private float d;
    private int accent;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(PocketUi.wrapWithUiScale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ConsoleActivity.applyFullscreenWindow(this);
        d = getResources().getDisplayMetrics().density;
        accent = PocketTheme.accent(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0A0F);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(32), dp(22), dp(32), dp(14));
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text(24, 0xFFFFFFFF, true);
        title.setText(R.string.pocket_picker_title);
        column.addView(title);
        subtitle = text(12.5f, 0x99FFFFFF, false);
        subtitle.setText(R.string.pocket_picker_loading);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        column.addView(subtitle);

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(4));
        list.setAdapter(new Rows());
        column.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(10), 0, 0);
        addButton = text(13, 0xFF000000, true);
        addButton.setPadding(dp(18), dp(8), dp(18), dp(8));
        GradientDrawable addBg = new GradientDrawable();
        addBg.setCornerRadius(dp(100));
        addBg.setColor(accent);
        addButton.setBackground(addBg);
        addButton.setFocusable(true);
        addButton.setClickable(true);
        addButton.setOnClickListener(v -> confirm());
        bar.addView(addButton);
        View space = new View(this);
        bar.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER_VERTICAL);
        ConsoleActivity.fillHintBar(this, hints,
                ConsoleActivity.hintA(this, R.string.pocket_picker_hint_tick),
                ConsoleActivity.hintY(this, R.string.pocket_picker_hint_games),
                ConsoleActivity.hintX(this, R.string.pocket_picker_hint_add),
                ConsoleActivity.hintB(this, R.string.pocket_hint_back));
        bar.addView(hints);
        column.addView(bar);
        setContentView(root);
        updateButton();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        work.shutdownNow();
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return t;
    }

    private void load() {
        final boolean firstTime = !AndroidLibrary.isSetUp(this);
        final Set<String> inLibrary = AndroidLibrary.packages(this);
        work.execute(() -> {
            final List<AndroidLibrary.InstalledApp> found = AndroidLibrary.installed(this);
            // Games and emulators first, then the rest, each by name
            Collections.sort(found, (a, b) -> {
                int ra = rank(a.type);
                int rb = rank(b.type);
                return ra != rb ? Integer.compare(ra, rb) : a.label.compareToIgnoreCase(b.label);
            });
            main.post(() -> {
                if (isFinishing()) {
                    return;
                }
                apps.clear();
                apps.addAll(found);
                picked.clear();
                for (AndroidLibrary.InstalledApp a : found) {
                    if (inLibrary.contains(a.packageName) || (firstTime && a.type != AndroidAppsActivity.TYPE_APP)) {
                        picked.add(a.packageName);
                    }
                }
                subtitle.setText(getString(R.string.pocket_picker_sub, found.size()));
                list.getAdapter().notifyDataSetChanged();
                updateButton();
                list.post(() -> {
                    RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(0);
                    if (h != null) {
                        h.itemView.requestFocus();
                    }
                });
            });
        });
    }

    private static int rank(int type) {
        return type == AndroidAppsActivity.TYPE_GAME ? 0 : (type == AndroidAppsActivity.TYPE_EMULATOR ? 1 : 2);
    }

    private void updateButton() {
        addButton.setText(getString(R.string.pocket_picker_add, picked.size()));
    }

    private void toggle(int position) {
        if (position < 0 || position >= apps.size()) {
            return;
        }
        String pkg = apps.get(position).packageName;
        boolean now = !picked.remove(pkg);
        if (now) {
            picked.add(pkg);
        }
        PocketSounds.play(this, now ? PocketSounds.TOGGLE_ON : PocketSounds.TOGGLE_OFF);
        list.getAdapter().notifyItemChanged(position);
        updateButton();
    }

    /** Y: every game and emulator ticked (or all of them unticked if they already were). */
    private void toggleGames() {
        boolean allPicked = true;
        for (AndroidLibrary.InstalledApp a : apps) {
            if (a.type != AndroidAppsActivity.TYPE_APP && !picked.contains(a.packageName)) {
                allPicked = false;
            }
        }
        for (AndroidLibrary.InstalledApp a : apps) {
            if (a.type != AndroidAppsActivity.TYPE_APP) {
                if (allPicked) {
                    picked.remove(a.packageName);
                }
                else {
                    picked.add(a.packageName);
                }
            }
        }
        PocketSounds.play(this, allPicked ? PocketSounds.TOGGLE_OFF : PocketSounds.TOGGLE_ON);
        list.getAdapter().notifyDataSetChanged();
        updateButton();
    }

    private void confirm() {
        Set<String> added = AndroidLibrary.set(this, picked);
        PocketSounds.play(this, PocketSounds.SELECT);
        Intent result = new Intent();
        result.putExtra(RESULT_ADDED, added.toArray(new String[0]));
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (up) {
                    View f = getCurrentFocus();
                    if (f != null) {
                        f.performClick();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (up) {
                    confirm();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (up) {
                    toggleGames();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (up) {
                    PocketSounds.play(this, PocketSounds.BACK);
                    finish();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!up) {
                    PocketUi.tick(this);
                }
                break;
            default:
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    private Bitmap icon(String pkg) {
        Bitmap hit = icons.get(pkg);
        if (hit != null) {
            return hit;
        }
        try {
            Drawable dr = getPackageManager().getApplicationIcon(pkg);
            int size = dp(40);
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            dr.setBounds(0, 0, size, size);
            dr.draw(c);
            icons.put(pkg, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private final class Rows extends RecyclerView.Adapter<Rows.Holder> {
        final class Holder extends RecyclerView.ViewHolder {
            final TextView check;
            final ImageView image;
            final TextView label;
            final TextView kind;

            Holder(LinearLayout row, TextView check, ImageView image, TextView label, TextView kind) {
                super(row);
                this.check = check;
                this.image = image;
                this.label = label;
                this.kind = kind;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(AppPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(6), dp(16), dp(6));
            row.setFocusable(true);
            row.setClickable(true);
            StateListDrawable bg = new StateListDrawable();
            GradientDrawable focused = new GradientDrawable();
            focused.setCornerRadius(dp(14));
            focused.setColor(0x24FFFFFF);
            focused.setStroke(Math.max(1, dp(1.5f)), PocketTheme.withAlpha(accent, 0xCC));
            bg.addState(new int[]{android.R.attr.state_focused}, focused);
            bg.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
            row.setBackground(bg);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

            TextView check = text(13, 0xFF000000, true);
            check.setGravity(Gravity.CENTER);
            row.addView(check, new LinearLayout.LayoutParams(dp(26), dp(26)));
            ImageView image = new ImageView(AppPickerActivity.this);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
            ilp.setMarginStart(dp(14));
            row.addView(image, ilp);
            TextView label = text(15, 0xFFFFFFFF, true);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            llp.setMarginStart(dp(14));
            row.addView(label, llp);
            TextView kind = text(11, 0x99FFFFFF, false);
            row.addView(kind);
            final Holder h = new Holder(row, check, image, label, kind);
            row.setOnClickListener(v -> toggle(h.getBindingAdapterPosition()));
            row.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    v.animate().scaleX(1.015f).scaleY(1.015f).setDuration(160).start();
                }
                else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                }
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder h, int position) {
            final AndroidLibrary.InstalledApp app = apps.get(position);
            boolean on = picked.contains(app.packageName);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            if (on) {
                circle.setColor(accent);
            }
            else {
                circle.setColor(0);
                circle.setStroke(Math.max(1, dp(1.5f)), 0x80FFFFFF);
            }
            h.check.setBackground(circle);
            h.check.setText(on ? "✓" : "");
            h.label.setText(app.label);
            h.kind.setText(app.type == AndroidAppsActivity.TYPE_GAME ? R.string.pocket_apps_type_game
                    : app.type == AndroidAppsActivity.TYPE_EMULATOR ? R.string.pocket_apps_type_emulator
                    : R.string.pocket_apps_type_app);
            h.itemView.setAlpha(on ? 1f : 0.72f);
            final String pkg = app.packageName;
            h.image.setTag(pkg);
            Bitmap hit = icons.get(pkg);
            if (hit != null) {
                h.image.setImageBitmap(hit);
                return;
            }
            h.image.setImageDrawable(null);
            work.execute(() -> {
                final Bitmap b = icon(pkg);
                main.post(() -> {
                    if (pkg.equals(h.image.getTag())) {
                        h.image.setImageBitmap(b);
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }
}
