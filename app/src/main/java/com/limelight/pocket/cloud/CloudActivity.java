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
        // Real covers, backgrounds and logos (SteamGridDB, with a key): looked up once
        for (final CloudService s : CloudService.ALL) {
            com.limelight.pocket.home.HomeItem item = homeItem(s);
            com.limelight.pocket.PocketArt.autoFetch(this, com.limelight.pocket.home.HomeActivity.artUuid(item),
                    com.limelight.pocket.home.HomeActivity.artId(item), getString(s.name), true, () -> {
                        if (isFinishing()) {
                            return;
                        }
                        CARDS.evictAll();
                        adapter.notifyDataSetChanged();
                        if (heroPos >= 0) {
                            int p = heroPos;
                            heroPos = -1;
                            updateHero(p);
                        }
                    });
        }
    }

    private static final android.util.LruCache<String, Bitmap> CARDS = new android.util.LruCache<>(8);
    private static final android.util.LruCache<String, Bitmap> BACKDROPS = new android.util.LruCache<>(3);

    private String artUuid(CloudService s) {
        return com.limelight.pocket.home.HomeActivity.artUuid(homeItem(s));
    }

    private int artId(CloudService s) {
        return com.limelight.pocket.home.HomeActivity.artId(homeItem(s));
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
        boolean changed = position != heroPos;
        if (title != null && changed) {
            title.setVisibility(View.VISIBLE);
            PocketMotion.swapText(title, getString(s.name), heroPos < 0 ? 0 : Integer.signum(position - heroPos));
        }
        heroPos = position;
        if (changed) {
            showArt(s, position);
        }
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

    /** Its background (SteamGridDB's, else light in its colours) and logo in place of its name. */
    private void showArt(final CloudService s, final int position) {
        final ImageView logo = findViewById(R.id.pocketHeroLogo);
        final TextView title = findViewById(R.id.pocketHeroTitle);
        if (logo != null) {
            logo.setVisibility(View.GONE);
        }
        final String uuid = artUuid(s);
        final int id = artId(s);
        com.limelight.pocket.PocketArt.load(this, uuid, id, com.limelight.pocket.PocketArt.HERO, bitmap -> {
            if (heroPos != position || keyArt == null) {
                return;
            }
            if (bitmap != null) {
                keyArt.show(bitmap);
                return;
            }
            Bitmap back = BACKDROPS.get(s.id);
            if (back == null) {
                int w = Math.max(640, getResources().getDisplayMetrics().widthPixels / 2);
                back = CloudArt.backdrop(this, s, w, Math.round(w * 9f / 16f));
                BACKDROPS.put(s.id, back);
            }
            keyArt.show(back);
        });
        com.limelight.pocket.PocketArt.load(this, uuid, id, com.limelight.pocket.PocketArt.LOGO, bitmap -> {
            if (heroPos == position && bitmap != null && logo != null && title != null) {
                logo.setImageBitmap(bitmap);
                logo.setVisibility(View.VISIBLE);
                title.setVisibility(View.GONE);
            }
        });
    }

    private void play(CloudService s) {
        PocketSounds.play(this, PocketSounds.SELECT);
        com.limelight.pocket.home.HomeStore.record(this, homeItem(s));
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
        items.add(com.limelight.pocket.home.HomePins.item(this, homeItem(s)));
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

    @Override
    public void onBackPressed() {
        if (isConsolePanelShowing()) {
            super.onBackPressed();
            return;
        }
        SectionBar.goHome(this, SectionBar.CLOUD);
    }

    private com.limelight.pocket.home.HomeItem homeItem(CloudService s) {
        return new com.limelight.pocket.home.HomeItem(com.limelight.pocket.home.HomeItem.CLOUD, getString(s.name), s.id, "", "", 0);
    }

    // ---- Cards ------------------------------------------------------------------------------

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
            final int w = Math.round(108 * d);
            final int h = Math.round(162 * d);
            final boolean running = s.isRunning(CloudActivity.this);
            final String key = s.id + "|" + running + "|" + com.limelight.pocket.PocketArt.version();
            image.setTag(key);
            Bitmap ready = CARDS.get(key);
            if (ready != null) {
                image.setImageBitmap(ready);
                return card;
            }
            com.limelight.pocket.PocketArt.load(CloudActivity.this, artUuid(s), artId(s), com.limelight.pocket.PocketArt.COVER, cover -> {
                if (!key.equals(image.getTag())) {
                    return;
                }
                Bitmap made = CloudArt.card(CloudActivity.this, s, getString(s.name), running, cover, w, h);
                CARDS.put(key, made);
                image.setImageBitmap(made);
            });
            return card;
        }
    }
}
