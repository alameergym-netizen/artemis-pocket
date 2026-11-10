package com.limelight.pocket.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.pocket.RecentGames;

import java.util.ArrayList;
import java.util.List;

/**
 * Game cards for the home screen's "Jump back in" row (same card as the library), fed into a
 * {@link CarouselAdapter} so they get the same focus lens, sounds and buttons.
 */
public class RecentGamesAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final List<RecentGames.Game> games = new ArrayList<>();
    private String uuid;
    private int runningAppId;

    public RecentGamesAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    /** The games of one PC (and which of them is running there, 0 for none). */
    public void set(String uuid, List<RecentGames.Game> list, int runningAppId) {
        boolean same = uuid != null && uuid.equals(this.uuid) && runningAppId == this.runningAppId
                && list.size() == games.size();
        if (same) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).app.getAppId() != games.get(i).app.getAppId()) {
                    same = false;
                    break;
                }
            }
        }
        if (same) {
            return;
        }
        this.uuid = uuid;
        this.runningAppId = runningAppId;
        games.clear();
        games.addAll(list);
        notifyDataSetChanged();
    }

    public String getUuid() {
        return uuid;
    }

    public int getRunningAppId() {
        return runningAppId;
    }

    @Override
    public int getCount() {
        return games.size();
    }

    @Override
    public RecentGames.Game getItem(int position) {
        return games.get(position);
    }

    @Override
    public long getItemId(int position) {
        return games.get(position).app.getAppId();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View card = convertView != null ? convertView : inflater.inflate(R.layout.app_grid_item, parent, false);
        RecentGames.Game game = games.get(position);
        final ImageView image = card.findViewById(R.id.grid_image);
        final TextView text = card.findViewById(R.id.grid_text);
        ImageView badge = card.findViewById(R.id.grid_overlay);
        final int appId = game.app.getAppId();
        final String name = game.app.getAppName();
        final String tag = uuid + ":" + appId;
        text.setText(name);
        if (badge != null) {
            boolean running = appId == runningAppId;
            badge.setImageResource(R.drawable.ic_pocket_playing);
            badge.setVisibility(running ? View.VISIBLE : View.GONE);
        }
        if (tag.equals(image.getTag()) && image.getDrawable() != null) {
            // Same game as before (a refresh): leave the art alone
            return card;
        }
        image.setTag(tag);
        Bitmap art = HeroArt.cachedCard(context, uuid, appId, name);
        if (art != null) {
            image.setImageBitmap(art);
            text.setVisibility(View.GONE);
        }
        else {
            // Decoded off the main thread, then faded in (no stutter while the row appears)
            image.setImageDrawable(null);
            text.setVisibility(View.VISIBLE);
            HeroArt.loadCardAsync(context, uuid, appId, name, bitmap -> {
                if (!tag.equals(image.getTag())) {
                    return;
                }
                image.setImageBitmap(bitmap);
                text.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
                if (bitmap != null && com.limelight.pocket.PocketPrefs.animationsEnabled(context)) {
                    image.setAlpha(0f);
                    image.animate().alpha(1f).setDuration(220).start();
                }
            });
        }
        card.setContentDescription(game.app.getAppName());
        return card;
    }
}
