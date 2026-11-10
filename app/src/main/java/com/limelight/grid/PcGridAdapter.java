package com.limelight.grid;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Collections;
import java.util.Comparator;

public class PcGridAdapter extends GenericGridAdapter<PcView.ComputerObject> {

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        super(context, getLayoutIdForPreferences(prefs));
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        return R.layout.pc_grid_item;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void addComputer(PcView.ComputerObject computer) {
        itemList.add(computer);
        sortList();
    }

    private void sortList() {
        Collections.sort(itemList, new Comparator<PcView.ComputerObject>() {
            @Override
            public int compare(PcView.ComputerObject lhs, PcView.ComputerObject rhs) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
        });
    }

    public boolean removeComputer(PcView.ComputerObject computer) {
        return itemList.remove(computer);
    }

    @Override
    public void populateView(View parentView, ImageView imgView, RelativeLayout gridMask, ProgressBar prgView, TextView txtView, ImageView overlayView, PcView.ComputerObject obj) {
        imgView.setImageResource(R.drawable.ic_computer);
        if (obj.details.state == ComputerDetails.State.ONLINE) {
            imgView.setAlpha(1.0f);
        }
        else {
            imgView.setAlpha(0.35f);
        }
        if (obj.details.state == ComputerDetails.State.ONLINE &&
                com.limelight.pocket.UsbLink.getUsbAddressFor(context, obj.details.uuid) != null) {
            // Artemis Pocket: show which PC is on the other end of the cable
            imgView.setImageResource(R.drawable.ic_usb);
        }

        if (obj.details.state == ComputerDetails.State.UNKNOWN) {
            prgView.setVisibility(View.VISIBLE);
        }
        else {
            prgView.setVisibility(View.INVISIBLE);
        }

        txtView.setText(obj.details.name);
        if (obj.details.state == ComputerDetails.State.ONLINE) {
            txtView.setAlpha(1.0f);
        }
        else {
            txtView.setAlpha(0.4f);
        }

        if (obj.details.state == ComputerDetails.State.OFFLINE) {
            overlayView.setImageResource(R.drawable.ic_pc_offline);
            overlayView.setAlpha(0.4f);
            overlayView.setVisibility(View.VISIBLE);
        }
        // We must check if the status is exactly online and unpaired
        // to avoid colliding with the loading spinner when status is unknown
        else if (obj.details.state == ComputerDetails.State.ONLINE &&
                obj.details.pairState == PairingManager.PairState.NOT_PAIRED) {
            overlayView.setImageResource(R.drawable.ic_lock);
            overlayView.setAlpha(1.0f);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
        }

        // Artemis Pocket: status line under the PC name
        TextView statusView = parentView.findViewById(R.id.pc_status_text);
        ImageView dotView = parentView.findViewById(R.id.pc_status_dot);
        if (statusView != null && context instanceof PcView) {
            statusView.setText(((PcView) context).describeComputer(obj.details, false));
        }
        if (dotView != null) {
            int color;
            if (obj.details.state == ComputerDetails.State.ONLINE) {
                color = obj.details.pairState == PairingManager.PairState.PAIRED ?
                        R.color.pocket_ok : R.color.pocket_warn;
            }
            else if (obj.details.state == ComputerDetails.State.OFFLINE) {
                color = R.color.pocket_off;
            }
            else {
                color = R.color.pocket_text_faint;
            }
            dotView.setColorFilter(ContextCompat.getColor(context, color));
        }
        txtView.setAlpha(1.0f);

        // Artemis Pocket: the game it is playing (or played last) fills the card, console style
        ImageView art = parentView.findViewById(R.id.pc_art);
        View scrim = parentView.findViewById(R.id.pc_art_scrim);
        ImageView badge = parentView.findViewById(R.id.pc_badge);
        if (art != null && scrim != null) {
            int appId = obj.details.runningGameId != 0 ? obj.details.runningGameId
                    : com.limelight.pocket.PocketPrefs.getLastAppId(context, obj.details.uuid);
            // Its cover, or (for a plain one like Desktop) the latest game with real art
            android.graphics.Bitmap bitmap = obj.details.pairState == com.limelight.nvstream.http.PairingManager.PairState.PAIRED
                    ? com.limelight.pocket.RecentGames.coverFor(context, obj.details.uuid, appId) : null;
            if (bitmap != null) {
                art.setImageBitmap(bitmap);
                art.setVisibility(View.VISIBLE);
                art.setAlpha(obj.details.state == ComputerDetails.State.ONLINE ? 1f : 0.45f);
                scrim.setVisibility(View.VISIBLE);
                imgView.setVisibility(View.INVISIBLE);
                parentView.setClipToOutline(true);
                if (badge != null) {
                    // The PC (or cable) icon moves to a small badge in the corner
                    boolean usb = obj.details.state == ComputerDetails.State.ONLINE &&
                            com.limelight.pocket.UsbLink.getUsbAddressFor(context, obj.details.uuid) != null;
                    badge.setImageResource(usb ? R.drawable.ic_usb : R.drawable.ic_computer);
                    badge.setVisibility(View.VISIBLE);
                }
            }
            else {
                art.setImageDrawable(null);
                art.setVisibility(View.GONE);
                scrim.setVisibility(View.GONE);
                imgView.setVisibility(View.VISIBLE);
                if (badge != null) {
                    badge.setVisibility(View.GONE);
                }
            }
        }
    }
}
