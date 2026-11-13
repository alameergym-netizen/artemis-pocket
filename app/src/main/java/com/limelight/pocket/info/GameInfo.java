package com.limelight.pocket.info;

import java.util.ArrayList;
import java.util.List;

/**
 * What the game page shows about a game, put together from Steam (description, screenshots,
 * trailers, release date, studios, genres, Metacritic) or Wikipedia (a summary). Plain data:
 * kept on disk as JSON (Gson) and parsed by pure Java, so it is unit tested without a device.
 */
public final class GameInfo {

    public static final String SOURCE_STEAM = "steam";
    public static final String SOURCE_WIKIPEDIA = "wikipedia";
    public static final String SOURCE_IGDB = "igdb";
    public static final String SOURCE_PLAY = "play";

    /** The name as the source spells it. */
    public String title;
    /** Steam's app id (0 when it didn't come from Steam). */
    public int steamAppId;
    public String source;
    /** A line or two (plain text). */
    public String shortDescription;
    /** The full description (plain text, paragraphs separated by blank lines). */
    public String about;
    /** As the store writes it ("12 Nov, 2024", or "Coming soon"). */
    public String releaseDate;
    public boolean comingSoon;
    public List<String> developers = new ArrayList<>();
    public List<String> publishers = new ArrayList<>();
    public List<String> genres = new ArrayList<>();
    /** 0 when there is none. */
    public int metacritic;
    public String headerImage;
    public String background;
    public String website;
    public String pageUrl;
    public List<Shot> screenshots = new ArrayList<>();
    public List<Movie> movies = new ArrayList<>();
    /** Language the text is in ("english", "arabic"...). */
    public String language;
    public long fetchedAt;
    /** What critics and players thought, from every source that rates it (see Ratings). */
    public List<Rating> ratings = new ArrayList<>();
    /** Which version of the ratings lookup filled this in (older answers are looked up again). */
    public int ratingsVersion;

    /** One rating: who gave it, the score as they wrote it and on 0..100. */
    public static final class Rating {
        /** "Metacritic", "OpenCritic", "Steam", "IGN"... */
        public String source;
        /** As written: "85", "9/10", "92%", "4.3". */
        public String value;
        /** 0..100 (for the colour and the order), -1 when it can't be compared. */
        public int score = -1;
        /** A word or two more: "Very Positive", "12,345 reviews", "critics". */
        public String note;
        /** An aggregate of many reviews (Metacritic, OpenCritic, Steam's players), not one critic. */
        public boolean aggregate;
        /** Players' rather than critics'. */
        public boolean users;

        public Rating() {
        }

        public Rating(String source, String value, int score, String note, boolean aggregate, boolean users) {
            this.source = source;
            this.value = value;
            this.score = score;
            this.note = note;
            this.aggregate = aggregate;
            this.users = users;
        }
    }

    /** Adds a rating unless that source already has one. */
    public void addRating(Rating r) {
        if (r == null || r.source == null || r.value == null) {
            return;
        }
        if (ratings == null) {
            ratings = new ArrayList<>();
        }
        for (Rating have : ratings) {
            if (have.source.equalsIgnoreCase(r.source)) {
                return;
            }
        }
        ratings.add(r);
    }

    /** The critics' score to show first (Metacritic, then OpenCritic, then any other aggregate), or null. */
    public Rating criticScore() {
        if (ratings == null) {
            return null;
        }
        Rating best = null;
        for (Rating r : ratings) {
            if (!r.aggregate || r.users || r.score < 0) {
                continue;
            }
            if ("Metacritic".equals(r.source)) {
                return r;
            }
            if (best == null || "OpenCritic".equals(r.source)) {
                best = r;
            }
        }
        return best;
    }

    /** The players' score to show (Steam, Google Play, IGDB's users), or null. */
    public Rating playerScore() {
        if (ratings == null) {
            return null;
        }
        for (Rating r : ratings) {
            if (r.users && r.score >= 0) {
                return r;
            }
        }
        return null;
    }

    public static final class Shot {
        public String thumb;
        public String full;

        public Shot() {
        }

        public Shot(String thumb, String full) {
            this.thumb = thumb;
            this.full = full;
        }
    }

    public static final class Movie {
        public String name;
        public String thumb;
        /** HLS stream (.m3u8), what Steam serves now. */
        public String hls;
        /** DASH stream (.mpd, H.264). */
        public String dash;
        /** A direct file (older entries). */
        public String mp4;
        /** A YouTube video id (IGDB's trailers): opened in YouTube, not played here. */
        public String youtube;
        public boolean highlight;

        /** The best thing to play: a direct file, else HLS, else DASH (null when none). */
        public String playable() {
            if (mp4 != null && !mp4.isEmpty()) {
                return mp4;
            }
            if (hls != null && !hls.isEmpty()) {
                return hls;
            }
            return dash != null && !dash.isEmpty() ? dash : null;
        }
    }

    /** The trailer to show first (Steam's highlighted one), or null. */
    public Movie mainTrailer() {
        Movie first = null;
        for (Movie m : movies) {
            if (m.playable() == null) {
                continue;
            }
            if (m.highlight) {
                return m;
            }
            if (first == null) {
                first = m;
            }
        }
        return first;
    }

    /** A trailer that opens in YouTube (when there's nothing to play here), or null. */
    public Movie youtubeTrailer() {
        for (Movie m : movies) {
            if (m.youtube != null && !m.youtube.isEmpty()) {
                return m;
            }
        }
        return null;
    }

    /** The release year ("2019"), or null. */
    public String releaseYear() {
        if (releaseDate == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(19|20)\\d\\d").matcher(releaseDate);
        String year = null;
        while (m.find()) {
            year = m.group();
        }
        return year;
    }

    public boolean hasText() {
        return (shortDescription != null && !shortDescription.isEmpty()) || (about != null && !about.isEmpty());
    }
}
