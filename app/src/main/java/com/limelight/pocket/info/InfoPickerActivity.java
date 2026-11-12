package com.limelight.pocket.info;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.ui.AuroraView;
import com.limelight.pocket.ui.PocketFonts;
import com.limelight.pocket.ui.PocketTheme;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.RemoteImages;
import com.limelight.pocket.ui.SoftRing;
import com.limelight.pocket.ui.Spring;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Which game is it?" for a game's details, the way the art picker asks it: the name is looked
 * up on Steam, IGDB (when it's set up) and Wikipedia at once, and every game they know by that
 * name comes up with its picture, the year and where it's from. Pick one and the game's page,
 * description, screenshots and trailer come from that one from now on. X searches another name,
 * Y goes back to finding it by itself.
 */
public class InfoPickerActivity extends ConsoleActivity {

    public static final String EXTRA_NAME = "pocket.info.name";
    public static final int REQUEST = 4721;

    /** Opens it for the game known by this name (the name its details are looked up by). */
    public static void open(Activity from, String name) {
        if (name == null || !GameInfoService.isLookupable(name)) {
            return;
        }
        Intent i = new Intent(from, InfoPickerActivity.class);
        i.putExtra(EXTRA_NAME, name);
        PocketSounds.play(from, PocketSounds.OPEN);
        from.startActivityForResult(i, REQUEST);
        from.overridePendingTransition(R.anim.pocket_section_in, R.anim.pocket_section_out);
    }

    private static final String STEAM = "steam";
    private static final String IGDB = "igdb";
    private static final String WIKI = "wiki";

    /** One game a source knows. */
    private static final class Option {
        String source;
        String id;
        String name;
        String sub;
        String image;
        int rank;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(3);
    private final List<Option> options = new ArrayList<>();
    private final Rows rows = new Rows();
    private float d;
    private String name;
    private String term;
    private int token;
    private int pending;
    private TextView searching;
    private TextView current;
    private TextView status;
    private ProgressBar spinner;
    private RecyclerView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;
        name = getIntent().getStringExtra(EXTRA_NAME);
        if (name == null) {
            finish();
            return;
        }
        term = name;
        setContentView(build());
        setHints(hintA(this, R.string.pocket_info_choose),
                hintX(this, R.string.pocket_info_search),
                hintY(this, R.string.pocket_info_auto),
                hintB(this, R.string.pocket_hint_back));
        showCurrent();
        search(term);
    }

    @Override
    protected void onDestroy() {
        token++;
        work.shutdownNow();
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    private TextView text(float sp, android.graphics.Typeface face, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(new AuroraView(this), new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0xD9000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(30), dp(18), dp(30), 0);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        TextView kicker = text(9.5f, PocketFonts.semibold(), PocketTheme.accent(this) == 0xFFFFFFFF
                ? 0xB3FFFFFF : PocketTheme.accent(this));
        kicker.setText(R.string.pocket_info_kicker);
        kicker.setAllCaps(true);
        kicker.setLetterSpacing(0.2f);
        column.addView(kicker);
        TextView title = text(22f, PocketFonts.bold(), 0xFFFFFFFF);
        title.setText(R.string.pocket_info_which);
        title.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(6);
        column.addView(title, tl);
        searching = text(12f, PocketFonts.medium(), 0xC7FFFFFF);
        searching.setMaxLines(1);
        searching.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(8);
        column.addView(searching, sl);
        current = text(11f, PocketFonts.medium(), 0x8CFFFFFF);
        current.setMaxLines(1);
        current.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(4);
        column.addView(current, cl);

        FrameLayout body = new FrameLayout(this);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1f);
        bl.topMargin = dp(10);
        column.addView(body, bl);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setPadding(dp(6), dp(8), dp(6), dp(64));
        list.setVerticalFadingEdgeEnabled(true);
        list.setFadingEdgeLength(dp(24));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.setDefaultFocusHighlightEnabled(false);
        }
        list.setAdapter(rows);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        body.addView(spinner, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        status = text(13f, PocketFonts.medium(), 0xB3FFFFFF);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(dp(3), 1f);
        status.setVisibility(View.GONE);
        body.addView(status, new FrameLayout.LayoutParams(dp(420), -2, Gravity.CENTER));

        LinearLayout hints = new LinearLayout(this);
        hints.setId(R.id.pocketHintBar);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        hlp.setMarginEnd(dp(18));
        hlp.bottomMargin = dp(9);
        root.addView(hints, hlp);
        return root;
    }

    /** What the details come from now (picked, or found by the name). */
    private void showCurrent() {
        String pick = GameInfoService.pickFor(this, name);
        int steam = GameInfoService.steamIdFor(this, name);
        if (steam > 0) {
            current.setText(getString(R.string.pocket_info_now_picked, "Steam  #" + steam));
        }
        else if (pick != null) {
            String[] p = pick.split(":", 3);
            current.setText(getString(R.string.pocket_info_now_picked,
                    IGDB.equals(p[0]) ? "IGDB  #" + p[1] : "Wikipedia  ·  " + (p.length > 2 ? p[2] : "")));
        }
        else {
            current.setText(R.string.pocket_info_now_auto);
        }
    }

    // ---- Searching ------------------------------------------------------------------------------

    private void search(final String what) {
        term = what;
        final int my = ++token;
        options.clear();
        rows.notifyDataSetChanged();
        searching.setText(getString(R.string.pocket_info_searching_for, what));
        status.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        final Context app = getApplicationContext();
        pending = 3;
        work.execute(() -> deliver(my, steam(what)));
        work.execute(() -> deliver(my, igdb(app, what)));
        work.execute(() -> deliver(my, wikipedia(what)));
    }

    private void deliver(final int my, final List<Option> found) {
        main.post(() -> {
            if (my != token || isFinishing()) {
                return;
            }
            pending--;
            boolean first = options.isEmpty();
            options.addAll(found);
            Collections.sort(options, (a, b) -> a.rank - b.rank);
            rows.notifyDataSetChanged();
            if (pending <= 0) {
                spinner.setVisibility(View.GONE);
                if (options.isEmpty()) {
                    status.setText(getString(R.string.pocket_info_none, term));
                    status.setVisibility(View.VISIBLE);
                }
            }
            if (first && !options.isEmpty()) {
                list.post(() -> {
                    RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(0);
                    if (h != null) {
                        h.itemView.requestFocus();
                    }
                });
            }
        });
    }

    private static List<Option> steam(String what) {
        List<Option> out = new ArrayList<>();
        try {
            int i = 0;
            for (InfoParsers.Candidate c : GameInfoService.searchSteamNow(what)) {
                Option o = new Option();
                o.source = STEAM;
                o.id = String.valueOf(c.id);
                o.name = c.name;
                o.sub = "Steam";
                o.image = "https://cdn.akamai.steamstatic.com/steam/apps/" + c.id + "/header.jpg";
                o.rank = i++;
                out.add(o);
                if (i >= 8) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static List<Option> igdb(Context c, String what) {
        List<Option> out = new ArrayList<>();
        if (!Igdb.configured(c)) {
            return out;
        }
        try {
            JSONArray games = new JSONArray(Igdb.search(c, what));
            for (int i = 0; i < games.length() && out.size() < 8; i++) {
                JSONObject g = games.optJSONObject(i);
                if (g == null || g.optString("name", "").isEmpty()) {
                    continue;
                }
                Option o = new Option();
                o.source = IGDB;
                o.id = String.valueOf(g.optLong("id"));
                o.name = g.optString("name");
                StringBuilder sub = new StringBuilder("IGDB");
                long when = g.optLong("first_release_date", 0);
                if (when > 0) {
                    java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                    cal.setTimeInMillis(when * 1000);
                    sub.append("  ·  ").append(cal.get(java.util.Calendar.YEAR));
                }
                String summary = g.optString("summary", "");
                if (!summary.isEmpty()) {
                    sub.append("  ·  ").append(summary.length() > 120 ? summary.substring(0, 120) + "…" : summary);
                }
                o.sub = sub.toString();
                String img = firstImage(g.optJSONArray("screenshots"));
                if (img == null) {
                    img = firstImage(g.optJSONArray("artworks"));
                }
                if (img != null) {
                    o.image = ExtraParsers.igdbImage(img, "screenshot_med");
                }
                else {
                    JSONObject cover = g.optJSONObject("cover");
                    if (cover != null && !cover.optString("image_id", "").isEmpty()) {
                        o.image = ExtraParsers.igdbImage(cover.optString("image_id"), "cover_big");
                    }
                }
                o.rank = 100 + out.size();
                out.add(o);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String firstImage(JSONArray a) {
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !o.optString("image_id", "").isEmpty()) {
                return o.optString("image_id");
            }
        }
        return null;
    }

    private static List<Option> wikipedia(String what) {
        List<Option> out = new ArrayList<>();
        String lang = GameInfoService.wikiLanguage();
        List<String> langs = new ArrayList<>();
        langs.add(lang);
        if (!"en".equals(lang)) {
            langs.add("en");
        }
        for (String l : langs) {
            try {
                JSONObject root = new JSONObject(GameInfoService.searchWikipediaNow(what, l));
                JSONObject q = root.optJSONObject("query");
                JSONArray found = q != null ? q.optJSONArray("search") : null;
                for (int i = 0; found != null && i < found.length() && i < 5; i++) {
                    JSONObject r = found.optJSONObject(i);
                    if (r == null) {
                        continue;
                    }
                    Option o = new Option();
                    o.source = WIKI;
                    o.id = l + ":" + r.optString("title");
                    o.name = r.optString("title");
                    String snippet = Html.fromHtml(r.optString("snippet", "")).toString().trim();
                    o.sub = "Wikipedia" + (l.equals("en") ? "" : " (" + l.toUpperCase(Locale.ROOT) + ")")
                            + (snippet.isEmpty() ? "" : "  ·  " + snippet);
                    o.rank = 200 + out.size();
                    out.add(o);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ---- Choosing -------------------------------------------------------------------------------

    private void choose(Option o) {
        PocketSounds.play(this, PocketSounds.SELECT);
        if (STEAM.equals(o.source)) {
            try {
                GameInfoService.setSteamId(this, name, Integer.parseInt(o.id));
            } catch (NumberFormatException e) {
                return;
            }
        }
        else {
            GameInfoService.setPick(this, name, o.source + ":" + o.id);
        }
        Toast.makeText(this, getString(R.string.pocket_info_picked_toast, o.name), Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
        overridePendingTransition(R.anim.pocket_section_in, R.anim.pocket_section_out);
    }

    private void backToAuto() {
        if (!GameInfoService.isPicked(this, name)) {
            return;
        }
        PocketSounds.play(this, PocketSounds.TOGGLE_OFF);
        GameInfoService.resetPick(this, name);
        Toast.makeText(this, R.string.pocket_info_auto_toast, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        showCurrent();
    }

    private void askSearch() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setText(term);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        box.addView(input);
        ConsoleActivity.showImmersive(new AlertDialog.Builder(this, R.style.PocketDialog)
                .setTitle(R.string.pocket_info_search)
                .setView(box)
                .setPositiveButton(R.string.pocket_art_search, (dlg, w) -> {
                    String t = input.getText().toString().trim();
                    if (!t.isEmpty()) {
                        search(t);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    @Override
    protected boolean onButtonX() {
        askSearch();
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isConsolePanelShowing()) {
            return super.dispatchKeyEvent(event);
        }
        boolean firstDown = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (firstDown) {
                    View f = getCurrentFocus();
                    if (f != null && f.getTag() instanceof Option) {
                        choose((Option) f.getTag());
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (firstDown) {
                    backToAuto();
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    // ---- The rows -------------------------------------------------------------------------------

    private static final class Row extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView letter;
        final TextView name;
        final TextView sub;
        final TextView chip;
        final GradientDrawable face;
        final SoftRing ring;
        String url;

        Row(View v, ImageView image, TextView letter, TextView name, TextView sub, TextView chip,
            GradientDrawable face, SoftRing ring) {
            super(v);
            this.image = image;
            this.letter = letter;
            this.name = name;
            this.sub = sub;
            this.chip = chip;
            this.face = face;
            this.ring = ring;
        }
    }

    private final class Rows extends RecyclerView.Adapter<Row> {
        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = InfoPickerActivity.this;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(8), dp(8), dp(14), dp(8));
            card.setFocusable(true);
            card.setClickable(true);
            GradientDrawable face = new GradientDrawable();
            face.setCornerRadius(dp(16));
            face.setColor(0x14FFFFFF);
            card.setBackground(face);
            SoftRing ring = SoftRing.forTheme(c, dp(16), false);
            ring.setAlpha(0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                card.setForeground(ring);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                card.setDefaultFocusHighlightEnabled(false);
            }
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(76));
            lp.setMargins(0, dp(4), 0, dp(4));
            card.setLayoutParams(lp);

            FrameLayout pic = new FrameLayout(c);
            GradientDrawable picBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF2A2A33, 0xFF15151A});
            picBg.setCornerRadius(dp(11));
            pic.setBackground(picBg);
            pic.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(11));
                }
            });
            pic.setClipToOutline(true);
            TextView letter = text(20f, PocketFonts.bold(), 0x66FFFFFF);
            letter.setGravity(Gravity.CENTER);
            pic.addView(letter, new FrameLayout.LayoutParams(-1, -1));
            ImageView image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.addView(image, new FrameLayout.LayoutParams(-1, -1));
            card.addView(pic, new LinearLayout.LayoutParams(dp(128), dp(60)));

            LinearLayout words = new LinearLayout(c);
            words.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1f);
            wl.setMarginStart(dp(14));
            TextView name = text(14f, PocketFonts.semibold(), 0xFFFFFFFF);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(name);
            TextView sub = text(11f, PocketFonts.regular(), 0xA6FFFFFF);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setLineSpacing(dp(1.5f), 1f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = dp(5);
            words.addView(sub, sl);
            card.addView(words, wl);

            TextView chip = text(9.5f, PocketFonts.bold(), 0xFFFFFFFF);
            chip.setAllCaps(true);
            chip.setLetterSpacing(0.08f);
            chip.setPadding(dp(8), dp(4), dp(8), dp(4));
            LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(-2, -2);
            chl.setMarginStart(dp(10));
            card.addView(chip, chl);

            final Row row = new Row(card, image, letter, name, sub, chip, face, ring);
            card.setOnFocusChangeListener((v, has) -> {
                look(row, has);
                if (has) {
                    PocketUi.tick(InfoPickerActivity.this);
                }
            });
            card.setOnClickListener(v -> {
                if (v.getTag() instanceof Option) {
                    choose((Option) v.getTag());
                }
            });
            card.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_DPAD_UP
                    && event.getAction() == KeyEvent.ACTION_DOWN && row.getBindingAdapterPosition() == 0);
            return row;
        }

        @Override
        public void onBindViewHolder(@NonNull Row h, int position) {
            Option o = options.get(position);
            h.itemView.setTag(o);
            h.name.setText(o.name);
            h.sub.setText(o.sub);
            h.letter.setText(o.name.isEmpty() ? "?" : o.name.substring(0, 1).toUpperCase(Locale.getDefault()));
            int color = STEAM.equals(o.source) ? 0xFF66C0F4 : IGDB.equals(o.source) ? 0xFFB78CFF : 0xFFE6E6E6;
            h.chip.setText(STEAM.equals(o.source) ? "Steam" : IGDB.equals(o.source) ? "IGDB" : "Wiki");
            h.chip.setTextColor(color);
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setCornerRadius(dp(100));
            chipBg.setColor((color & 0x00FFFFFF) | 0x26000000);
            h.chip.setBackground(chipBg);
            h.url = o.image;
            h.image.setImageDrawable(null);
            if (o.image != null) {
                final String url = o.image;
                RemoteImages.load(InfoPickerActivity.this, url, dp(128), b -> {
                    if (b != null && url.equals(h.url)) {
                        h.image.setImageBitmap(b);
                        if (PocketPrefs.animationsEnabled(InfoPickerActivity.this)) {
                            h.image.setAlpha(0f);
                            h.image.animate().alpha(1f).setDuration(200).start();
                        }
                    }
                });
            }
            look(h, h.itemView.hasFocus());
        }

        @Override
        public int getItemCount() {
            return options.size();
        }
    }

    private void look(Row r, boolean focused) {
        r.face.setColor(focused ? 0x2EFFFFFF : 0x14FFFFFF);
        r.ring.setAlpha(focused ? 255 : 0);
        float s = focused ? 1.015f : 1f;
        if (PocketPrefs.animationsEnabled(this)) {
            r.itemView.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(6) : 0)
                    .setDuration(Spring.UI_DURATION_MS).setInterpolator(Spring.UI_CURVE).start();
        }
        else {
            r.itemView.setScaleX(s);
            r.itemView.setScaleY(s);
        }
    }
}
