package com.limelight.pocket.ui;

import android.animation.ValueAnimator;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.pocket.PocketPrefs;

/**
 * Shows the existing Artemis grid adapters (PCs, games) in a horizontal console carousel.
 *
 * The upstream adapters are plain BaseAdapters built for a GridView; this bridge feeds their views
 * into a RecyclerView so we get smooth horizontal scrolling and per-card focus without rewriting
 * the adapters (keeps upstream merges painless).
 *
 * Focus look: a crisp white outline drawn a few dp outside the card (like a console home screen)
 * and/or a small zoom, depending on the "Focus style" setting. No glows, sweeps or bounces.
 */
public class CarouselAdapter extends RecyclerView.Adapter<CarouselAdapter.Holder> {

    public interface Listener {
        void onItemClicked(int position, View itemView);
        void onItemOptions(int position, View itemView);
        void onItemFocused(int position, View itemView);
    }

    private static final int CARD_PADDING_DP = 14;
    private static final int RING_GAP_DP = 5;
    private static final float RING_WIDTH_DP = 2f;

    private final BaseAdapter source;
    private final Listener listener;
    private final float cardRadiusDp;
    private int generation = 0;
    private int focusedPosition = RecyclerView.NO_POSITION;

    public CarouselAdapter(BaseAdapter source, Listener listener, float cardRadiusDp) {
        this.source = source;
        this.listener = listener;
        this.cardRadiusDp = cardRadiusDp;
        source.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                notifyDataSetChanged();
            }

            @Override
            public void onInvalidated() {
                // The source switched item layouts; don't hand it views of the old layout
                generation++;
                notifyDataSetChanged();
            }
        });
    }

    public int getFocusedPosition() {
        return focusedPosition;
    }

    static class Holder extends RecyclerView.ViewHolder {
        final FrameLayout frame;
        int boundGeneration = -1;
        ValueAnimator ringAnimator;

        Holder(FrameLayout frame) {
            super(frame);
            this.frame = frame;
        }
    }

    private Drawable createRing(FrameLayout frame) {
        float d = frame.getResources().getDisplayMetrics().density;
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.RECTANGLE);
        ring.setColor(0x00000000);
        ring.setStroke(Math.round(RING_WIDTH_DP * d), PocketTheme.accent(frame.getContext()));
        ring.setCornerRadius((cardRadiusDp + RING_GAP_DP - 1) * d);
        int inset = Math.round((CARD_PADDING_DP - RING_GAP_DP) * d);
        Drawable drawable = new InsetDrawable(ring, inset);
        drawable.setAlpha(0);
        return drawable;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout frame = new FrameLayout(parent.getContext());
        frame.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.setFocusable(true);
        frame.setFocusableInTouchMode(false);
        frame.setClickable(true);
        frame.setLongClickable(true);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            frame.setDefaultFocusHighlightEnabled(false);
        }
        int pad = Math.round(parent.getResources().getDisplayMetrics().density * CARD_PADDING_DP);
        frame.setPadding(pad, pad, pad, pad);
        frame.setForeground(createRing(frame));
        return new Holder(frame);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        View existing = holder.frame.getChildCount() > 0 ? holder.frame.getChildAt(0) : null;
        if (holder.boundGeneration != generation) {
            existing = null;
        }

        View content = source.getView(position, existing, holder.frame);
        if (content != existing) {
            holder.frame.removeAllViews();
            holder.frame.addView(content);
        }
        holder.boundGeneration = generation;
        content.setDuplicateParentStateEnabled(false);

        final FrameLayout frame = holder.frame;
        frame.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                pressFeedback(frame);
                com.limelight.pocket.PocketSounds.play(frame.getContext(), com.limelight.pocket.PocketSounds.SELECT);
                listener.onItemClicked(pos, frame.getChildAt(0));
            }
        });
        frame.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onItemOptions(pos, frame.getChildAt(0));
            }
            return true;
        });
        frame.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    int pos = holder.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        listener.onItemOptions(pos, frame.getChildAt(0));
                    }
                }
                return true;
            }
            return false;
        });
        frame.setOnFocusChangeListener((v, hasFocus) -> {
            int pos = holder.getBindingAdapterPosition();
            applyFocus(holder, hasFocus, true);
            if (hasFocus && pos != RecyclerView.NO_POSITION) {
                focusedPosition = pos;
                PocketUi.tick(frame.getContext());
                listener.onItemFocused(pos, frame.getChildAt(0));
            }
        });

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // The first card gets focus when the controller is first used
            frame.setFocusedByDefault(position == 0);
        }

        // Restore the focus look instantly after a rebind (the PC list refreshes every few seconds)
        applyFocus(holder, frame.hasFocus(), false);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        applyFocus(holder, false, false);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return source.getCount();
    }

    /** A quick, shallow "press in" so A feels physical. */
    private static void pressFeedback(final FrameLayout frame) {
        if (!PocketPrefs.animationsEnabled(frame.getContext())) {
            return;
        }
        final float base = frame.getScaleX();
        frame.animate().cancel();
        frame.animate().setStartDelay(0).scaleX(base * 0.95f).scaleY(base * 0.95f).setDuration(70)
                .setInterpolator(PocketUi.EASE)
                .withEndAction(() -> frame.animate().scaleX(base).scaleY(base).setDuration(360)
                        .setInterpolator(Spring.POP).start())
                .start();
    }

    private void applyFocus(final Holder holder, boolean focused, boolean animate) {
        final FrameLayout frame = holder.frame;
        String style = PocketPrefs.focusStyle(frame.getContext());
        boolean ring = !PocketPrefs.FOCUS_ZOOM.equals(style);
        boolean zoom = !PocketPrefs.FOCUS_RING.equals(style);
        float scale = focused && zoom ? (ring ? 1.05f : 1.08f) : 1f;
        int ringAlpha = focused && ring ? 255 : 0;
        boolean animated = animate && PocketPrefs.animationsEnabled(frame.getContext());

        View content = frame.getChildCount() > 0 ? frame.getChildAt(0) : null;
        if (content != null) {
            content.setActivated(focused);
        }

        // The focused card lifts towards you (a soft spring in, a quick settle out) and its
        // shadow becomes a soft glow in the accent colour
        float d = frame.getResources().getDisplayMetrics().density;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            int glow = PocketTheme.accent(frame.getContext());
            frame.setOutlineSpotShadowColor(glow);
            frame.setOutlineAmbientShadowColor(glow);
        }
        float lift = focused ? -4 * d : 0f;
        float z = focused ? 10 * d : 0f;
        frame.animate().cancel();
        if (animated) {
            frame.animate().setStartDelay(0).scaleX(scale).scaleY(scale).translationY(lift).translationZ(z)
                    .setDuration(focused ? 420 : 200)
                    .setInterpolator(focused ? Spring.SOFT : PocketUi.EASE)
                    .start();
        }
        else {
            frame.setScaleX(scale);
            frame.setScaleY(scale);
            frame.setTranslationY(lift);
            frame.setTranslationZ(z);
        }

        final Drawable fg = frame.getForeground();
        if (fg == null) {
            return;
        }
        if (holder.ringAnimator != null) {
            holder.ringAnimator.cancel();
            holder.ringAnimator = null;
        }
        if (animated) {
            int from = fg.getAlpha();
            ValueAnimator a = ValueAnimator.ofInt(from, ringAlpha);
            a.setDuration(focused ? 120 : 90);
            a.addUpdateListener(an -> {
                fg.setAlpha((Integer) an.getAnimatedValue());
                frame.invalidate();
            });
            holder.ringAnimator = a;
            a.start();
        }
        else {
            fg.setAlpha(ringAlpha);
            frame.invalidate();
        }
    }
}
