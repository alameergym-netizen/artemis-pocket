package com.limelight.pocket.ui;

import android.database.DataSetObserver;
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
 * Focus look (v2): one focus lens for the whole row (see {@link FocusLens}) instead of a ring per
 * card; the focused card springs up in size while its neighbours step aside to make room, like a
 * console home screen. The "Focus style" setting picks lens + zoom, lens only or zoom only.
 */
public class CarouselAdapter extends RecyclerView.Adapter<CarouselAdapter.Holder> implements FocusLens.Host {

    public interface Listener {
        void onItemClicked(int position, View itemView);
        void onItemOptions(int position, View itemView);
        void onItemFocused(int position, View itemView);
    }

    private static final int CARD_PADDING_DP = 14;
    /** Starts quickly and lands softly, without overshoot. */
    private static final android.view.animation.Interpolator GLIDE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    /** Extra room the neighbours of the focused card step aside by. */
    private static final float MAKE_ROOM_DP = 4f;

    private final BaseAdapter source;
    private final Listener listener;
    private final float cardRadiusDp;
    private int generation = 0;
    private int focusedPosition = RecyclerView.NO_POSITION;
    private RecyclerView list;
    private FocusLens lens;

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

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        list = recyclerView;
        lens = new FocusLens(recyclerView, this, cardRadiusDp);
        recyclerView.addItemDecoration(lens);
        recyclerView.addOnScrollListener(parallaxScroll);
        recyclerView.addOnLayoutChangeListener(parallaxLayout);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        if (lens != null) {
            recyclerView.removeItemDecoration(lens);
        }
        recyclerView.removeOnScrollListener(parallaxScroll);
        recyclerView.removeOnLayoutChangeListener(parallaxLayout);
        lens = null;
        list = null;
        super.onDetachedFromRecyclerView(recyclerView);
    }

    // ---- Parallax: the art sits a little deeper than its card --------------------------------

    private static final float PARALLAX_DP = 9f;

    private final RecyclerView.OnScrollListener parallaxScroll = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
            applyParallax(rv);
        }
    };

    private final View.OnLayoutChangeListener parallaxLayout =
            (v, l, t, r, b, ol, ot, or, ob) -> applyParallax((RecyclerView) v);

    /** The picture inside each card shifts against the way the row moves (a window into depth). */
    private static void applyParallax(RecyclerView rv) {
        boolean on = PocketPrefs.animationsEnabled(rv.getContext());
        float half = rv.getWidth() / 2f;
        if (half <= 0) {
            return;
        }
        float shift = PARALLAX_DP * rv.getResources().getDisplayMetrics().density;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            View art = parallaxTarget(child);
            if (art == null) {
                continue;
            }
            if (!on) {
                art.setScaleX(1f);
                art.setScaleY(1f);
                art.setTranslationX(0f);
                continue;
            }
            float center = child.getLeft() + child.getWidth() / 2f;
            float offset = Math.max(-1.3f, Math.min(1.3f, (center - half) / half));
            if (art.getScaleX() != 1.14f) {
                art.setScaleX(1.14f);
                art.setScaleY(1.14f);
            }
            art.setTranslationX(-offset * shift);
        }
    }

    /** The card's picture (game art), never an icon. */
    private static View parallaxTarget(View frame) {
        View pcArt = frame.findViewById(R.id.pc_art);
        if (pcArt != null) {
            return pcArt.getVisibility() == View.VISIBLE ? pcArt : null;
        }
        return frame.findViewById(R.id.grid_image);
    }

    private boolean zoomStyle() {
        return list != null && !PocketPrefs.FOCUS_RING.equals(PocketPrefs.focusStyle(list.getContext()));
    }

    @Override
    public float focusScale() {
        if (list == null) {
            return 1f;
        }
        String style = PocketPrefs.focusStyle(list.getContext());
        if (PocketPrefs.FOCUS_RING.equals(style)) {
            return 1f;
        }
        return PocketPrefs.FOCUS_ZOOM.equals(style) ? 1.13f : 1.10f;
    }

    @Override
    public boolean showRing() {
        return list != null && !PocketPrefs.FOCUS_ZOOM.equals(PocketPrefs.focusStyle(list.getContext()));
    }

    /** How far a card steps aside for the focused one (signed, in px). */
    private float makeRoomShift(int position, View content) {
        if (list == null || focusedPosition == RecyclerView.NO_POSITION || position == focusedPosition
                || position == RecyclerView.NO_POSITION || !list.hasFocus() || !zoomStyle()) {
            return 0f;
        }
        float d = list.getResources().getDisplayMetrics().density;
        float amount = content.getWidth() * (focusScale() - 1f) / 2f + MAKE_ROOM_DP * d;
        if (content.getWidth() == 0) {
            amount = (108 * (focusScale() - 1f) / 2f + MAKE_ROOM_DP) * d;
        }
        float sign = position < focusedPosition ? -1f : 1f;
        if (list.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            sign = -sign;
        }
        return sign * amount;
    }

    /** Moves every visible card to its place around the focused one. */
    private void updateMakeRoom(boolean animate) {
        if (list == null) {
            return;
        }
        final RecyclerView rv = list;
        boolean animated = animate && PocketPrefs.animationsEnabled(rv.getContext());
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (!(child instanceof FrameLayout) || ((FrameLayout) child).getChildCount() == 0) {
                continue;
            }
            View content = ((FrameLayout) child).getChildAt(0);
            float target = makeRoomShift(rv.getChildAdapterPosition(child), content);
            if (Math.abs(content.getTranslationX() - target) < 0.5f) {
                continue;
            }
            content.animate().cancel();
            if (animated) {
                content.animate().setStartDelay(0).translationX(target).setDuration(300)
                        .setInterpolator(GLIDE)
                        .setUpdateListener(a -> rv.invalidate())
                        .start();
            }
            else {
                content.setTranslationX(target);
            }
        }
        if (lens != null) {
            lens.kick();
        }
    }

    static class Holder extends RecyclerView.ViewHolder {
        final FrameLayout frame;
        int boundGeneration = -1;

        Holder(FrameLayout frame) {
            super(frame);
            this.frame = frame;
        }
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
                if (lens != null) {
                    lens.press();
                }
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
                updateMakeRoom(true);
                PocketUi.tick(frame.getContext());
                listener.onItemFocused(pos, frame.getChildAt(0));
            }
            else if (!hasFocus && list != null) {
                // If focus left the row altogether, the cards close ranks again
                final RecyclerView rv = list;
                rv.post(() -> {
                    if (list == rv && !rv.hasFocus()) {
                        updateMakeRoom(true);
                    }
                });
            }
        });

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // The first card gets focus when the controller is first used
            frame.setFocusedByDefault(position == 0);
        }

        // Restore the focus look instantly after a rebind (the PC list refreshes every few seconds)
        applyFocus(holder, frame.hasFocus(), false);
        content.animate().cancel();
        content.setTranslationX(makeRoomShift(position, content));
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        applyFocus(holder, false, false);
        if (holder.frame.getChildCount() > 0) {
            holder.frame.getChildAt(0).animate().cancel();
            holder.frame.getChildAt(0).setTranslationX(0);
        }
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
        frame.animate().setStartDelay(0).scaleX(base * 0.95f).scaleY(base * 0.95f).setDuration(70)
                .setInterpolator(PocketUi.EASE)
                .withEndAction(() -> frame.animate().scaleX(base).scaleY(base).setDuration(360)
                        .setInterpolator(Spring.POP).start())
                .start();
    }

    private void applyFocus(final Holder holder, boolean focused, boolean animate) {
        final FrameLayout frame = holder.frame;
        float scale = focused ? focusScale() : 1f;
        boolean animated = animate && PocketPrefs.animationsEnabled(frame.getContext());

        View content = frame.getChildCount() > 0 ? frame.getChildAt(0) : null;
        if (content != null) {
            content.setActivated(focused);
        }

        // The focused card draws above its neighbours while it grows
        float d = frame.getResources().getDisplayMetrics().density;
        float z = focused ? 6 * d : 0f;
        // No cancel(): the row's entrance may still be sliding this card in (alpha / X); the
        // focus animation only takes over scale, Y and Z
        if (animated) {
            // A smooth glide in size, no overshoot (a bounce reads as a pulse)
            frame.animate().setStartDelay(0).scaleX(scale).scaleY(scale).translationY(0).translationZ(z)
                    .setDuration(focused ? 300 : 240)
                    .setInterpolator(GLIDE)
                    .setUpdateListener(a -> {
                        if (frame.getParent() instanceof View) {
                            ((View) frame.getParent()).invalidate();
                        }
                    })
                    .start();
        }
        else {
            // Take over any running scale animation, then settle right away
            frame.animate().scaleX(scale).scaleY(scale).translationY(0).translationZ(z)
                    .setStartDelay(0).setDuration(0).start();
            frame.setScaleX(scale);
            frame.setScaleY(scale);
            frame.setTranslationY(0);
            frame.setTranslationZ(z);
        }
    }
}
