package com.limelight.pocket.ui;

import android.database.DataSetObserver;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.pocket.PocketPrefs;

/**
 * Shows the existing Artemis grid adapters (PCs, games) in a horizontal console carousel.
 *
 * The upstream adapters are plain BaseAdapters built for a GridView; this bridge feeds their views
 * into a RecyclerView so we get smooth horizontal scrolling and per-card focus animations without
 * rewriting the adapters (keeps upstream merges painless).
 */
public class CarouselAdapter extends RecyclerView.Adapter<CarouselAdapter.Holder> {

    public interface Listener {
        void onItemClicked(int position, View itemView);
        void onItemOptions(int position, View itemView);
        void onItemFocused(int position, View itemView);
    }

    private static final float FOCUS_SCALE = 1.10f;

    private final BaseAdapter source;
    private final Listener listener;
    private int generation = 0;
    private int focusedPosition = RecyclerView.NO_POSITION;

    public CarouselAdapter(BaseAdapter source, Listener listener) {
        this.source = source;
        this.listener = listener;
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
        int pad = (int) (parent.getResources().getDisplayMetrics().density * 14);
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
            applyFocus(frame, hasFocus, true);
            if (hasFocus && pos != RecyclerView.NO_POSITION) {
                focusedPosition = pos;
                listener.onItemFocused(pos, frame.getChildAt(0));
            }
        });

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // The first card gets focus when the controller is first used
            frame.setFocusedByDefault(position == 0);
        }

        // Restore the focus look instantly after a rebind (the PC list refreshes every few seconds)
        applyFocus(frame, frame.hasFocus(), false);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        applyFocus(holder.frame, false, false);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return source.getCount();
    }

    static void applyFocus(FrameLayout frame, boolean focused, boolean animate) {
        View content = frame.getChildCount() > 0 ? frame.getChildAt(0) : null;
        if (content != null) {
            content.setActivated(focused);
            View ring = content.findViewWithTag("focus_ring");
            if (ring != null) {
                ring.setActivated(focused);
            }
        }

        float scale = focused ? FOCUS_SCALE : 1.0f;
        float z = focused ? frame.getResources().getDisplayMetrics().density * 12 : 0f;
        if (animate && PocketPrefs.animationsEnabled(frame.getContext())) {
            frame.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(z)
                    .setDuration(focused ? 260 : 180)
                    .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                    .start();
        }
        else {
            frame.animate().cancel();
            frame.setScaleX(scale);
            frame.setScaleY(scale);
            frame.setTranslationZ(z);
        }
    }
}
