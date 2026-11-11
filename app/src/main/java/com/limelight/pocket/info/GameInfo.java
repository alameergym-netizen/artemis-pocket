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
