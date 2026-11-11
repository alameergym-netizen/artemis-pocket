package com.limelight.pocket.ui;

import android.content.Context;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Plays a game's trailer into a TextureView (so it can fade and move like any view): muted and
 * looping behind the game page like a console's game hub, or full screen with sound. Steam
 * streams trailers as HLS (and DASH); older entries are plain files. One player per use; release()
 * it when the screen goes away.
 */
public final class TrailerPlayer {

    public interface Listener {
        /** The first picture is on screen (fade the view in now). */
        void onFirstFrame();

        /** It can't play (no connection, unsupported stream...). */
        void onError();

        /** It reached the end (not called while looping). */
        default void onEnded() {
        }
    }

    private final Context context;
    private final TextureView view;
    private ExoPlayer player;

    public TrailerPlayer(Context context, TextureView view) {
        this.context = context.getApplicationContext();
        this.view = view;
    }

    /**
     * Starts the trailer. muted: no sound and no audio focus (a background loop); otherwise it
     * takes the audio focus like a video player does.
     */
    public void play(String url, boolean muted, boolean loop, final Listener listener) {
        release();
        if (url == null) {
            return;
        }
        try {
            ExoPlayer.Builder builder = new ExoPlayer.Builder(context);
            player = builder.build();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(), !muted);
            player.setVolume(muted ? 0f : 1f);
            player.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            // A background trailer needn't be sharper than the screen
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setMaxVideoSize(1920, 1080).build());
            player.setVideoTextureView(view);
            player.addListener(new Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    if (listener != null) {
                        listener.onFirstFrame();
                    }
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    if (listener != null) {
                        listener.onError();
                    }
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED && listener != null) {
                        listener.onEnded();
                    }
                }
            });
            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
            player.play();
        } catch (RuntimeException e) {
            release();
            if (listener != null) {
                listener.onError();
            }
        }
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void resume() {
        if (player != null) {
            player.play();
        }
    }

    public void togglePause() {
        if (player != null) {
            if (player.isPlaying()) {
                player.pause();
            }
            else {
                player.play();
            }
        }
    }

    /** Skips ahead or back (ms). */
    public void seekBy(long ms) {
        if (player != null) {
            long target = Math.max(0, player.getCurrentPosition() + ms);
            long duration = player.getDuration();
            if (duration != C.TIME_UNSET) {
                target = Math.min(target, Math.max(0, duration - 500));
            }
            player.seekTo(target);
        }
    }

    public void release() {
        if (player != null) {
            try {
                player.clearVideoTextureView(view);
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
    }
}
