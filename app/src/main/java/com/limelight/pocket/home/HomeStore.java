package com.limelight.pocket.home;

import android.content.Context;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.RecentGames;
import com.limelight.pocket.retro.RetroLibrary;
import com.limelight.pocket.retro.RomScanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Home shows: the games and apps pinned to it, and (unless turned off) what was played
 * last from anywhere: streamed games, cloud services, retro games, Android apps, shortcuts.
 */
public final class HomeStore {
    private HomeStore() {}

    private static final String KEY_PINS = "pocket_home_pins";
    private static final String KEY_HISTORY = "pocket_home_history";
    private static final String KEY_SHOW_RECENT = "pocket_home_show_recent";
    private static final int MAX_HISTORY = 60;
    private static final int MAX_SHOWN = 36;

    /** One card of Home. */
    public static final class Entry {
        public final HomeItem item;
        public final long lastUsed;
        public final boolean pinned;
        /** The PC's name for a streamed game ("" otherwise). */
        public final String pcName;

        Entry(HomeItem item, long lastUsed, boolean pinned, String pcName) {
            this.item = item;
            this.lastUsed = lastUsed;
            this.pinned = pinned;
            this.pcName = pcName != null ? pcName : "";
        }
    }

    // ---- Pins ------------------------------------------------------------------------------

    public static List<HomeItem> pins(Context context) {
        return HomeItem.parseAll(PocketPrefs.prefs(context).getString(KEY_PINS, ""));
    }

    private static void savePins(Context context, List<HomeItem> pins) {
        PocketPrefs.prefs(context).edit().putString(KEY_PINS, HomeItem.joinAll(pins)).apply();
    }

    public static boolean isPinned(Context context, HomeItem item) {
        return pins(context).contains(item);
    }

    /** Pins it to Home (in front). */
    public static void pin(Context context, HomeItem item) {
        List<HomeItem> pins = pins(context);
        pins.remove(item);
        pins.add(0, item);
        savePins(context, pins);
    }

    public static void unpin(Context context, HomeItem item) {
        List<HomeItem> pins = pins(context);
        if (pins.remove(item)) {
            savePins(context, pins);
        }
    }

    /** Also takes it out of what was played (so it leaves Home altogether). */
    public static void forget(Context context, HomeItem item) {
        unpin(context, item);
        List<String> keep = new ArrayList<>();
        for (String line : historyLines(context)) {
            HomeItem h = HomeItem.parse(line.substring(line.indexOf('\t') + 1));
            if (h != null && !h.equals(item)) {
                keep.add(line);
            }
        }
        PocketPrefs.prefs(context).edit().putString(KEY_HISTORY, android.text.TextUtils.join("\n", keep))
                .putStringSet(KEY_HIDDEN, hidden(context, item.key())).apply();
    }

    private static final String KEY_HIDDEN = "pocket_home_hidden";

    private static java.util.Set<String> hidden(Context context, String add) {
        java.util.Set<String> s = new java.util.HashSet<>(PocketPrefs.prefs(context)
                .getStringSet(KEY_HIDDEN, Collections.<String>emptySet()));
        if (add != null) {
            s.add(add);
        }
        return s;
    }

    public static boolean showRecent(Context context) {
        return PocketPrefs.prefs(context).getBoolean(KEY_SHOW_RECENT, true);
    }

    public static void setShowRecent(Context context, boolean on) {
        PocketPrefs.prefs(context).edit().putBoolean(KEY_SHOW_RECENT, on).apply();
    }

    // ---- What was played ---------------------------------------------------------------------

    private static List<String> historyLines(Context context) {
        List<String> out = new ArrayList<>();
        for (String l : PocketPrefs.prefs(context).getString(KEY_HISTORY, "").split("\n")) {
            if (l.indexOf('\t') > 0) {
                out.add(l);
            }
        }
        return out;
    }

    /** Remembers that it was just started (Home shows it first). */
    public static void record(Context context, HomeItem item) {
        List<String> lines = new ArrayList<>();
        lines.add(System.currentTimeMillis() + "\t" + item.toLine());
        for (String l : historyLines(context)) {
            HomeItem h = HomeItem.parse(l.substring(l.indexOf('\t') + 1));
            if (h != null && !h.equals(item) && lines.size() < MAX_HISTORY) {
                lines.add(l);
            }
        }
        java.util.Set<String> hid = hidden(context, null);
        hid.remove(item.key());
        PocketPrefs.prefs(context).edit().putString(KEY_HISTORY, android.text.TextUtils.join("\n", lines))
                .putStringSet(KEY_HIDDEN, hid).apply();
    }

    // ---- Everything on Home (blocking: never on the main thread) ------------------------------

    public static List<Entry> load(Context context) {
        Context app = context.getApplicationContext();
        Map<String, String> pcNames = new HashMap<>();
        List<ComputerDetails> pcs = new ArrayList<>();
        ComputerDatabaseManager db = null;
        try {
            db = new ComputerDatabaseManager(app);
            pcs = db.getAllComputers();
        } catch (RuntimeException ignored) {
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
        for (ComputerDetails pc : pcs) {
            if (pc.uuid != null) {
                pcNames.put(pc.uuid, pc.name);
            }
        }

        Map<String, Entry> all = new LinkedHashMap<>();
        java.util.Set<String> hid = hidden(app, null);
        List<HomeItem> pins = pins(app);
        boolean recent = showRecent(app);

        // What was started from Pocket Nexus (any kind)
        Map<String, Long> used = new HashMap<>();
        List<HomeItem> history = new ArrayList<>();
        for (String l : historyLines(app)) {
            int tab = l.indexOf('\t');
            HomeItem h = HomeItem.parse(l.substring(tab + 1));
            if (h == null) {
                continue;
            }
            long t;
            try {
                t = Long.parseLong(l.substring(0, tab));
            } catch (NumberFormatException e) {
                t = 0;
            }
            used.put(h.key(), t);
            history.add(h);
        }
        // Streamed games: when each was played (kept by the PC's own list)
        List<HomeItem> streamed = new ArrayList<>();
        for (ComputerDetails pc : pcs) {
            if (pc.uuid == null) {
                continue;
            }
            int n = 0;
            for (RecentGames.Game g : RecentGames.forPc(app, pc.uuid, 40)) {
                if (g.lastPlayed <= 0 || n >= 8) {
                    break;
                }
                HomeItem item = new HomeItem(HomeItem.PC, g.app.getAppName(), pc.uuid, "", "", g.app.getAppId());
                Long before = used.get(item.key());
                used.put(item.key(), Math.max(g.lastPlayed, before != null ? before : 0));
                streamed.add(item);
                n++;
            }
        }
        // Retro games played
        List<HomeItem> retro = new ArrayList<>();
        for (String[] r : RetroLibrary.recentEntries(app)) {
            String key = r[1];
            int slash = key.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            String system = key.substring(0, slash);
            String rel = key.substring(slash + 1);
            String file = rel.substring(rel.lastIndexOf('/') + 1);
            HomeItem item = new HomeItem(HomeItem.RETRO, RomScanner.cleanName(file, false), system, rel, "", 0);
            long t;
            try {
                t = Long.parseLong(r[0]);
            } catch (NumberFormatException e) {
                t = 0;
            }
            Long before = used.get(item.key());
            used.put(item.key(), Math.max(t, before != null ? before : 0));
            retro.add(item);
        }

        for (HomeItem p : pins) {
            Long t = used.get(p.key());
            all.put(p.key(), new Entry(p, t != null ? t : 0, true, pcNames.get(p.a)));
        }
        if (recent) {
            List<HomeItem> candidates = new ArrayList<>(history);
            candidates.addAll(streamed);
            candidates.addAll(retro);
            for (HomeItem h : candidates) {
                if (all.containsKey(h.key()) || hid.contains(h.key())) {
                    continue;
                }
                Long t = used.get(h.key());
                all.put(h.key(), new Entry(h, t != null ? t : 0, false, pcNames.get(h.a)));
            }
        }
        List<Entry> out = new ArrayList<>(all.values());
        // Played most recently first; pins never played keep their order after them
        Collections.sort(out, (x, y) -> {
            if ((x.lastUsed > 0) != (y.lastUsed > 0)) {
                return x.lastUsed > 0 ? -1 : 1;
            }
            return Long.compare(y.lastUsed, x.lastUsed);
        });
        // Only what was used, or pinned
        List<Entry> shown = new ArrayList<>();
        for (Entry e : out) {
            if (e.pinned || e.lastUsed > 0) {
                shown.add(e);
            }
            if (shown.size() >= MAX_SHOWN) {
                break;
            }
        }
        return shown;
    }

    /** The paired PCs (uuid, name, address) for "PC games" and the PC widget. Blocking. */
    public static List<ComputerDetails> computers(Context context) {
        ComputerDatabaseManager db = null;
        try {
            db = new ComputerDatabaseManager(context.getApplicationContext());
            return db.getAllComputers();
        } catch (RuntimeException e) {
            return new ArrayList<>();
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
