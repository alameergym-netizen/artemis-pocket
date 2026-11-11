package com.limelight.pocket.cloud;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.ui.AnchoredLayoutManager;
import com.limelight.pocket.ui.CarouselAdapter;
import com.limelight.pocket.ui.HeroFocus;
import com.limelight.pocket.ui.KeyArtStage;
import com.limelight.pocket.ui.PocketMotion;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.pocket.ui.SectionBar;
import com.limelight.pocket.ui.StreamMenuView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Cloud section: Xbox Cloud Gaming, GeForce NOW and Boosteroid, each with a built-in
 * client that runs by itself (its own process, task and sign-in: one can keep playing while
 * another is opened), and their official apps when they're installed.
 */
public class CloudActivity extends ConsoleActivity implements CarouselAdapter.Listener {

    private RecyclerView carousel;
    private final Cards adapter = new Cards();
    private KeyArtStage keyArt;
    private int heroPos = -1;

    @Override
    protected boolean allowsAutoResume() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_view);
        TextView title = findViewById(R.id.appListText);
        if (title != null) {
            title.setText(R.string.pocket_cloud_title);
        }
        SectionBar.attach(this, SectionBar.CLOUD);
        hide(R.id.profilesButton);
        hide(R.id.pocketPcLibraryButton);
        carousel = findViewById(R.id.pocketAppCarousel);
        carousel.setLayoutManager(new AnchoredLayoutManager(this));
        carousel.setItemAnimator(null);
        carousel.setAdapter(new CarouselAdapter(adapter, this, 14));
        keyArt = KeyArtStage.find(this);
        if (keyArt != null) {
            keyArt.show(null);
        }
        heroFocus = HeroFocus.install(this, new HeroFocus.Host() {
            @Override
            public boolean heroCanLift() {
                return carousel != null && carousel.hasFocus();
            }

            @Override
            public void heroPlay() {
                if (heroPos >= 0) {
                    play(CloudService.ALL[heroPos]);
                }
            }
        }, R.id.appFragmentContainer);
        setHints(hintA(this, R.string.pocket_hero_play),
                hintY(this, R.string.pocket_hint_options),
                hintShoulders(this, R.string.pocket_retro_hint_sections),
                hintStart(this, R.string.pocket_hint_settings));
        TextView section = findViewById(R.id.pocketSectionTitle);
        if (section != null) {
            section.setText(getString(R.string.pocket_cloud_services) + "  ·  " + CloudService.ALL.length);
        }
        hide(R.id.pocketHeroLogo);
        hide(R.id.pocketHeroPlaytime);
        focusAt(0, 0);
    }

    private void hide(int id) {
        View v = findViewById(id);
        if (v != null) {
            v.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Running or not may have changed
        adapter.notifyDataSetChanged();
        if (heroPos >= 0) {
            int p = heroPos;
            heroPos = -1;
            updateHero(p);
        }
    }

    private void focusAt(final int position, final int attempt) {
        carousel.post(() -> {
            if (isFinishing()) {
                return;
            }
            RecyclerView.ViewHolder holder = carousel.findViewHolderForAdapterPosition(position);
            if (holder != null && holder.itemView.requestFocus()) {
                return;
            }
            if (attempt < 6) {
                carousel.postDelayed(() -> focusAt(position, attempt + 1), attempt < 2 ? 16 : 120);
            }
            if (heroPos != position) {
                updateHero(position);
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && carousel != null && !carousel.hasFocus() && !isConsolePanelShowing()) {
            focusAt(Math.max(0, heroPos), 0);
        }
    }

    private void updateHero(int position) {
        if (position < 0 || position >= CloudService.ALL.length) {
            return;
        }
        CloudService s = CloudService.ALL[position];
        TextView title = findViewById(R.id.pocketHeroTitle);
        if (title != null && position != heroPos) {
            title.setVisibility(View.VISIBLE);
            PocketMotion.swapText(title, getString(s.name), heroPos < 0 ? 0 : Integer.signum(position - heroPos));
        }
        heroPos = position;
        TextView subtitle = findViewById(R.id.pocketHeroSubtitle);
        if (subtitle != null) {
            subtitle.setVisibility(View.VISIBLE);
            String sub = getString(s.about);
            if (s.isRunning(this)) {
                sub = getString(R.string.pocket_cloud_running) + "  ·  " + sub;
            }
            subtitle.setText(sub);
        }
        TextView play = findViewById(R.id.pocketHeroPlay);
        if (play != null) {
            PocketUi.stylePlayPill(play);
            play.setVisibility(View.VISIBLE);
            play.setText("▶  " + getString(s.isRunning(this) ? R.string.pocket_cloud_return : R.string.pocket_hero_play));
        }
    }

    private void play(CloudService s) {
        PocketSounds.play(this, PocketSounds.SELECT);
        try {
            startActivity(s.playerIntent(this));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.pocket_cloud_no_webview, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onItemFocused(int position, View itemView) {
        updateHero(position);
    }

    @Override
    public void onItemClicked(int position, View itemView) {
        if (position >= 0 && position < CloudService.ALL.length) {
            play(CloudService.ALL[position]);
        }
    }

    @Override
    public void onItemOptions(int position, View itemView) {
        if (position < 0 || position >= CloudService.ALL.length) {
            return;
        }
        final CloudService s = CloudService.ALL[position];
        List<StreamMenuView.Item> items = new ArrayList<>();
        items.add(StreamMenuView.Item.action(getString(s.isRunning(this) ? R.string.pocket_cloud_return : R.string.pocket_hero_play),
                () -> play(s)));
        final String official = s.installedOfficial(this);
        if (official != null) {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_cloud_open_official), () -> {
                Intent i = getPackageManager().getLaunchIntentForPackage(official);
                if (i != null) {
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            }));
        }
        else {
            items.add(StreamMenuView.Item.action(getString(R.string.pocket_cloud_get_official), () -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + s.officialPackages[0]))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    Toast.makeText(this, s.officialPackages[0], Toast.LENGTH_LONG).show();
                }
            }));
        }
        if (s.isRunning(this)) {
            items.add(StreamMenuView.Item.confirm(getString(R.string.pocket_cloud_close, getString(s.name)), () -> {
                s.stop(this);
                PocketSounds.play(this, PocketSounds.TOGGLE_OFF);
                carousel.postDelayed(() -> {
                    heroPos = -1;
                    updateHero(position);
                    adapter.notifyDataSetChanged();
                }, 400);
            }));
        }
        items.add(StreamMenuView.Item.header(getString(R.string.pocket_cloud_controls)));
        PocketSounds.play(this, PocketSounds.OPEN);
        showConsolePanel(getString(s.name), getString(s.about), null, items);
    }

    @Override
    protected boolean onShoulder(int direction) {
        return SectionBar.step(this, SectionBar.CLOUD, direction);
    }

    // ---- Cards ------------------------------------------------------------------------------

    private static int darker(int color, float f) {
        return Color.rgb(Math.round(Color.red(color) * f), Math.round(Color.green(color) * f), Math.round(Color.blue(color) * f));
    }

    /** The service's name on its colour (no logos), with a "running" dot. */
    private Bitmap cardFor(CloudService s, boolean running, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w * 0.4f, h, s.color, darker(s.color, 0.22f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(w * 0.2f, h * 0.15f, w, 0x3DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, glow);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        t.setTextAlign(Paint.Align.CENTER);
        String[] words = getString(s.name).split(" ");
        float size = h * 0.11f;
        t.setTextSize(size);
        for (String word : words) {
            while (t.measureText(word) > w * 0.84f && size > h * 0.05f) {
                size *= 0.92f;
                t.setTextSize(size);
            }
        }
        float y = h * 0.42f - (words.length - 1) * size * 0.55f;
        for (String word : words) {
            c.drawText(word, w / 2f, y, t);
            y += size * 1.1f;
        }
        Paint cloud = new Paint(Paint.ANTI_ALIAS_FLAG);
        cloud.setColor(0x40FFFFFF);
        float cy = h * 0.8f;
        c.drawCircle(w * 0.42f, cy, w * 0.11f, cloud);
        c.drawCircle(w * 0.56f, cy - w * 0.05f, w * 0.14f, cloud);
        c.drawRoundRect(w * 0.3f, cy - w * 0.02f, w * 0.72f, cy + w * 0.11f, w * 0.06f, w * 0.06f, cloud);
        if (running) {
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0xFF4CFF8F);
            c.drawCircle(w * 0.86f, h * 0.08f, w * 0.045f, dot);
        }
        return b;
    }

    private final class Cards extends BaseAdapter {
        @Override
        public int getCount() {
            return CloudService.ALL.length;
        }

        @Override
        public CloudService getItem(int position) {
            return CloudService.ALL[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View card = convertView != null ? convertView
                    : LayoutInflater.from(CloudActivity.this).inflate(R.layout.app_grid_item, parent, false);
            CloudService s = CloudService.ALL[position];
            ImageView image = card.findViewById(R.id.grid_image);
            TextView text = card.findViewById(R.id.grid_text);
            View overlay = card.findViewById(R.id.grid_overlay);
            if (overlay != null) {
                overlay.setVisibility(View.GONE);
            }
            text.setVisibility(View.GONE);
            card.setContentDescription(getString(s.name));
            float d = getResources().getDisplayMetrics().density;
            image.setImageBitmap(cardFor(s, s.isRunning(CloudActivity.this), Math.round(108 * d), Math.round(162 * d)));
            return card;
        }
    }
}
