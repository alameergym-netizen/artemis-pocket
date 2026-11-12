package com.limelight.pocket.retro;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.limelight.R;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.PocketSounds;
import com.limelight.pocket.ui.RemoteImages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Achievements while you play: when a game with RetroAchievements starts in its emulator, this
 * keeps an eye on the account (RetroAchievements is told by the emulator itself) and, the moment
 * a new one is earned, shows it over the game like a console does (TrophyToast), with its sound.
 * Without the "over other apps" permission it comes as a notification instead. It stops when you
 * come back to Pocket Nexus (the game is over), or after a few hours.
 */
public final class TrophyWatch extends Service {

    public static final String KEY_ENABLED = "pocket_trophy_live";

    private static final String CHANNEL_WATCH = "pocket_trophy_watch";
    private static final String CHANNEL_POP = "pocket_trophy_pop2";
    private static final int NOTE_WATCH = 0x7A01;
    private static final int NOTE_POP = 0x7A10;
    private static final String EXTRA_SYSTEM = "pocket.watch.system";
    private static final String EXTRA_PATH = "pocket.watch.path";
    private static final String EXTRA_NAME = "pocket.watch.name";
    private static final String ACTION_STOP = "pocket.watch.STOP";
    private static final long MAX_MS = 6L * 60 * 60 * 1000;
    private static final long EVERY_MS = 10_000;

    private static volatile boolean running;
    /** Asked to stop before it had even started (it stops as soon as it does). */
    private static volatile boolean stopWhenStarted;

    // ---- From the screens ----------------------------------------------------------------------

    /** Pop-ups while playing (on unless turned off). */
    public static boolean enabled(Context c) {
        return PocketPrefs.prefs(c).getBoolean(KEY_ENABLED, true);
    }

    /** May it draw over other apps (needed to show over the game itself)? */
    public static boolean canDrawOver(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c);
    }

    public static void openOverlaySettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (RuntimeException e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Asks (once it's needed) to be allowed over other apps, saying why. */
    public static void askForOverlay(final Activity a) {
        if (canDrawOver(a)) {
            return;
        }
        com.limelight.pocket.ConsoleActivity.showImmersive(
                new android.app.AlertDialog.Builder(a, R.style.PocketDialog)
                        .setTitle(R.string.pocket_trophy_overlay_title)
                        .setMessage(R.string.pocket_trophy_overlay_ask)
                        .setPositiveButton(R.string.pocket_trophy_overlay_allow, (d, w) -> openOverlaySettings(a))
                        .setNegativeButton(android.R.string.cancel, null)
                        .create());
    }

    /** Starts watching for this game (it was just launched). */
    public static void start(Context c, Achievements.Target t) {
        if (t == null || !"ra".equals(t.kind) || !enabled(c) || !Achievements.hasRa(c)
                || Achievements.consoleId(t.system) <= 0) {
            return;
        }
        stopWhenStarted = false;
        Intent i = new Intent(c, TrophyWatch.class);
        i.putExtra(EXTRA_SYSTEM, t.system);
        i.putExtra(EXTRA_PATH, t.path);
        i.putExtra(EXTRA_NAME, t.name);
        try {
            androidx.core.content.ContextCompat.startForegroundService(c, i);
        } catch (RuntimeException ignored) {
            // Not allowed from here right now: the game still starts
        }
    }

    /** Back in Pocket Nexus: the game is over, stop watching. */
    public static void stop(Context c) {
        if (running) {
            try {
                c.stopService(new Intent(c, TrophyWatch.class));
            } catch (RuntimeException ignored) {
            }
        }
        else {
            // Starting right now: it must still go to the foreground first, then it stops
            stopWhenStarted = true;
        }
    }

    /** Shows what a pop-up looks like, on this screen (Settings). */
    public static void preview(Activity a) {
        TrophyToast.Card card = new TrophyToast.Card();
        card.kicker = a.getString(R.string.pocket_trophy_kicker).toUpperCase(Locale.getDefault());
        card.title = a.getString(R.string.pocket_trophy_preview_title);
        card.line = a.getString(R.string.pocket_trophy_preview_line);
        card.points = "+10";
        final TrophyToast toast = new TrophyToast(a, card);
        final ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(toast.frameWidth(), toast.frameHeight(),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Math.round(4 * a.getResources().getDisplayMetrics().density);
        content.addView(toast, lp);
        toast.setElevation(40 * a.getResources().getDisplayMetrics().density);
        chime(a);
        toast.play(() -> content.removeView(toast));
    }

    /** The pop-up's sound (at the menu sounds' volume). */
    static void chime(Context c) {
        float v = Math.max(0f, Math.min(1f, PocketSounds.volume(c, PocketSounds.CATEGORY_MENU) * 1.15f));
        if (v <= 0.01f) {
            return;
        }
        try {
            MediaPlayer p = MediaPlayer.create(c, R.raw.pocket_trophy, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(), 0);
            if (p == null) {
                return;
            }
            p.setVolume(v, v);
            p.setOnCompletionListener(MediaPlayer::release);
            p.start();
        } catch (RuntimeException ignored) {
        }
    }

    // ---- The service ---------------------------------------------------------------------------

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<TrophyToast.Card> queue = new ArrayDeque<>();
    private volatile boolean alive;
    private Thread loop;
    private Achievements.Target target;
    private WindowManager wm;
    private TrophyToast showing;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || intent.getStringExtra(EXTRA_SYSTEM) == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Achievements.Target t = Achievements.Target.retro(intent.getStringExtra(EXTRA_SYSTEM),
                intent.getStringExtra(EXTRA_PATH), intent.getStringExtra(EXTRA_NAME));
        try {
            Notification n = watchNote(t.name);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTE_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            }
            else {
                startForeground(NOTE_WATCH, n);
            }
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (stopWhenStarted) {
            stopWhenStarted = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        boolean sameGame = target != null && target.key().equals(t.key());
        target = t;
        if (loop == null || !sameGame) {
            if (loop != null) {
                loop.interrupt();
            }
            alive = true;
            final Achievements.Target watching = t;
            loop = new Thread(() -> watch(watching), "PocketTrophyWatch");
            loop.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        alive = false;
        if (loop != null) {
            loop.interrupt();
            loop = null;
        }
        queue.clear();
        if (showing != null) {
            showing.cancel();
            removeShown();
        }
        // The lists catch up with everything earned in this session
        if (target != null) {
            Achievements.load(getApplicationContext(), target, true, s -> { });
        }
        super.onDestroy();
    }

    private void watch(Achievements.Target t) {
        final Context c = getApplicationContext();
        long until = System.currentTimeMillis() + MAX_MS;
        // The game's list (rarity, how many) and its id at RetroAchievements
        Achievements.fetchNow(c, t);
        int game = Achievements.raGameId(c, t);
        Set<Integer> seen = new HashSet<>();
        boolean seeded = false;
        int failures = 0;
        while (alive && !Thread.currentThread().isInterrupted() && System.currentTimeMillis() < until) {
            try {
                JSONArray recent = new JSONArray(Achievements.get(Achievements.RA
                        + "API_GetUserRecentAchievements.php?u=" + Achievements.enc(Achievements.raUser(c))
                        + "&y=" + Achievements.enc(Achievements.raKey(c)) + "&m=" + (seeded ? 30 : 240)));
                failures = 0;
                // Oldest first, so several at once come in the order they were earned
                for (int i = recent.length() - 1; i >= 0; i--) {
                    JSONObject o = recent.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    int id = o.optInt("AchievementID", 0);
                    if (id == 0 || (game > 0 && o.optInt("GameID", game) != game)) {
                        continue;
                    }
                    if (!seen.add(id) || !seeded) {
                        continue;
                    }
                    earned(c, t, o);
                }
                seeded = true;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (++failures > 30) {
                    break;
                }
            }
            try {
                Thread.sleep(seeded ? EVERY_MS : 3000);
            } catch (InterruptedException e) {
                return;
            }
        }
        main.post(this::stopSelf);
    }

    /** One just earned: its card (badge, rarity, how far along), then the pop-up. Off the main thread. */
    private void earned(Context c, Achievements.Target t, JSONObject o) throws InterruptedException {
        String title = o.optString("Title", "");
        boolean hardcore = o.optInt("HardcoreMode", 0) == 1;
        int points = o.optInt("Points", 0);
        Achievements.Set set = Achievements.markEarned(c, t, title, System.currentTimeMillis(), hardcore);
        Achievements.Trophy trophy = Achievements.find(set, title);
        final TrophyToast.Card card = new TrophyToast.Card();
        card.kicker = c.getString(R.string.pocket_trophy_kicker).toUpperCase(Locale.getDefault());
        card.title = title;
        card.hardcore = hardcore;
        card.points = points > 0 ? "+" + points : null;
        StringBuilder line = new StringBuilder();
        if (trophy != null && trophy.rarity >= 0) {
            line.append(c.getString(tier(trophy.rarity))).append(' ')
                    .append(String.format(Locale.getDefault(), trophy.rarity < 10 ? "%.1f%%" : "%.0f%%", trophy.rarity));
        }
        if (set != null && set.total > 0) {
            if (line.length() > 0) {
                line.append("  ·  ");
            }
            line.append(set.earned).append(" / ").append(set.total);
        }
        if (line.length() == 0) {
            line.append(o.optString("Description", ""));
        }
        card.line = line.toString();
        String badge = o.optString("BadgeURL", "");
        if (badge.isEmpty() && trophy != null) {
            badge = trophy.icon;
        }
        else if (badge.startsWith("/")) {
            badge = Achievements.RA_MEDIA + badge;
        }
        card.badge = download(c, badge);
        enqueue(card);
        // The whole game done: the gold one
        if (set != null && set.total > 0 && set.earned >= set.total) {
            TrophyToast.Card gold = new TrophyToast.Card();
            gold.mastered = true;
            gold.hardcore = hardcore;
            gold.kicker = c.getString(R.string.pocket_trophy_mastered_kicker).toUpperCase(Locale.getDefault());
            gold.title = set.title != null && !set.title.isEmpty() ? set.title : t.name;
            gold.line = c.getString(R.string.pocket_trophy_mastered_line, set.total, set.pointsTotal);
            gold.points = null;
            gold.badge = download(c, set.icon);
            enqueue(gold);
        }
    }

    private static Bitmap download(Context c, String url) throws InterruptedException {
        if (url == null || url.isEmpty()) {
            return null;
        }
        final Bitmap[] out = new Bitmap[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        int size = Math.round(96 * c.getResources().getDisplayMetrics().density);
        RemoteImages.load(c, url, size, b -> {
            out[0] = b;
            done.countDown();
        });
        done.await(8, java.util.concurrent.TimeUnit.SECONDS);
        Bitmap b = out[0];
        if (b != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && b.getConfig() == Bitmap.Config.HARDWARE) {
            try {
                b = b.copy(Bitmap.Config.ARGB_8888, false);
            } catch (RuntimeException | OutOfMemoryError e) {
                b = null;
            }
        }
        return b;
    }

    private static int tier(float pct) {
        if (pct < 5f) {
            return R.string.pocket_trophy_ultra_rare;
        }
        if (pct < 15f) {
            return R.string.pocket_trophy_very_rare;
        }
        if (pct < 30f) {
            return R.string.pocket_trophy_rare;
        }
        if (pct < 60f) {
            return R.string.pocket_trophy_uncommon;
        }
        return R.string.pocket_trophy_common;
    }

    // ---- Showing them, one after another ------------------------------------------------------

    private void enqueue(final TrophyToast.Card card) {
        main.post(() -> {
            if (!running) {
                return;
            }
            queue.add(card);
            if (showing == null) {
                next();
            }
        });
    }

    private void next() {
        final TrophyToast.Card card = queue.poll();
        if (card == null || !running) {
            return;
        }
        if (!canDrawOver(this)) {
            notifyInstead(card);
            main.postDelayed(this::next, 1500);
            return;
        }
        try {
            if (wm == null) {
                wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            }
            final TrophyToast toast = new TrophyToast(this, card);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(toast.frameWidth(), toast.frameHeight(), type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(4 * getResources().getDisplayMetrics().density);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            lp.setTitle("Achievement");
            wm.addView(toast, lp);
            showing = toast;
            chime(this);
            toast.play(() -> {
                removeShown();
                main.postDelayed(this::next, 350);
            });
        } catch (RuntimeException e) {
            showing = null;
            notifyInstead(card);
        }
    }

    private void removeShown() {
        View v = showing;
        showing = null;
        if (v != null && wm != null) {
            try {
                wm.removeViewImmediate(v);
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ---- Notifications ---------------------------------------------------------------------------

    private void channels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_WATCH) == null) {
            NotificationChannel w = new NotificationChannel(CHANNEL_WATCH,
                    getString(R.string.pocket_trophy_channel_watch), NotificationManager.IMPORTANCE_MIN);
            w.setShowBadge(false);
            nm.createNotificationChannel(w);
        }
        if (nm.getNotificationChannel(CHANNEL_POP) == null) {
            NotificationChannel p = new NotificationChannel(CHANNEL_POP,
                    getString(R.string.pocket_trophy_channel_pop), NotificationManager.IMPORTANCE_HIGH);
            p.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.pocket_trophy),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(p);
        }
    }

    private Notification watchNote(String game) {
        channels();
        Intent stop = new Intent(this, TrophyWatch.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_WATCH)
                .setSmallIcon(R.drawable.ic_pocket_trophy)
                .setContentTitle(getString(R.string.pocket_trophy_watch_title))
                .setContentText(getString(R.string.pocket_trophy_watch_text, game))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                .addAction(0, getString(R.string.pocket_trophy_watch_stop), stopPi)
                .build();
    }

    /** No permission to draw over the game: a notification with the same news. */
    private void notifyInstead(TrophyToast.Card card) {
        channels();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_POP)
                .setSmallIcon(R.drawable.ic_pocket_trophy)
                .setContentTitle(card.kicker != null ? card.kicker : "")
                .setContentText(card.title + (card.points != null ? "  " + card.points : ""))
                .setSubText(card.line)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_EVENT)
                .setTimeoutAfter(9000)
                .setAutoCancel(true);
        if (card.badge != null) {
            b.setLargeIcon(card.badge);
        }
        try {
            nm.notify(NOTE_POP + (int) (System.currentTimeMillis() % 1000), b.build());
        } catch (RuntimeException ignored) {
        }
    }
}
