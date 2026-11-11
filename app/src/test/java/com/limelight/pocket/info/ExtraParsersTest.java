package com.limelight.pocket.info;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** IGDB's answers and Google Play's pages, read into what the game page shows. */
public class ExtraParsersTest {

    private static final String IGDB = "["
            + "{\"id\":1,\"name\":\"The Legend of Zelda: Ocarina of Time 3D\",\"summary\":\"A remake.\"},"
            + "{\"id\":2,\"name\":\"The Legend of Zelda: Ocarina of Time\",\"version_parent\":9,\"summary\":\"Port.\"},"
            + "{\"id\":3,\"name\":\"The Legend of Zelda: Ocarina of Time\",\"summary\":\"Link travels through time. It is great.\","
            + "\"storyline\":\"Ganondorf wants the Triforce.\",\"first_release_date\":911865600,"
            + "\"genres\":[{\"id\":5,\"name\":\"Adventure\"},{\"id\":12,\"name\":\"Role-playing (RPG)\"}],"
            + "\"involved_companies\":[{\"company\":{\"name\":\"Nintendo EAD\"},\"developer\":true,\"publisher\":false},"
            + "{\"company\":{\"name\":\"Nintendo\"},\"developer\":false,\"publisher\":true}],"
            + "\"screenshots\":[{\"image_id\":\"sc1\"},{\"image_id\":\"sc2\"}],\"artworks\":[{\"image_id\":\"ar1\"}],"
            + "\"videos\":[{\"video_id\":\"dQw4w9WgXcQ\",\"name\":\"Trailer\"},{\"video_id\":\"bad id!\"}],"
            + "\"cover\":{\"image_id\":\"co1\"},\"aggregated_rating\":99.4,\"url\":\"https://www.igdb.com/games/x\"}"
            + "]";

    @Test
    public void igdb_picksTheMainGameAndReadsEverything() {
        GameInfo info = ExtraParsers.parseIgdb(IGDB, "The Legend of Zelda: Ocarina of Time");
        assertNotNull(info);
        assertEquals(GameInfo.SOURCE_IGDB, info.source);
        assertTrue(info.about.startsWith("Link travels through time."));
        assertTrue(info.about.contains("Ganondorf"));
        assertEquals("24 Nov, 1998", info.releaseDate);
        assertEquals("1998", info.releaseYear());
        assertEquals(2, info.genres.size());
        assertEquals("Nintendo EAD", info.developers.get(0));
        assertEquals("Nintendo", info.publishers.get(0));
        assertEquals(99, info.metacritic);
        // Screenshots, then artworks
        assertEquals(3, info.screenshots.size());
        assertEquals("https://images.igdb.com/igdb/image/upload/t_1080p/sc1.jpg", info.screenshots.get(0).full);
        assertEquals("https://images.igdb.com/igdb/image/upload/t_1080p/ar1.jpg", info.background);
        // Only the good video id; it opens in YouTube
        assertEquals(1, info.movies.size());
        assertEquals("dQw4w9WgXcQ", info.movies.get(0).youtube);
        assertNull(info.movies.get(0).playable());
        assertNull(info.mainTrailer());
        assertNotNull(info.youtubeTrailer());
        String[] art = ExtraParsers.igdbArt(IGDB, "the legend of zelda ocarina of time");
        assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co1.jpg", art[0]);
        assertEquals("https://images.igdb.com/igdb/image/upload/t_1080p/ar1.jpg", art[1]);
    }

    @Test
    public void igdb_nothingWhenNoSameName() {
        assertNull(ExtraParsers.parseIgdb(IGDB, "Majora's Mask"));
        assertNull(ExtraParsers.parseIgdb("not json", "x"));
        assertNull(ExtraParsers.parseIgdb("{}", "x"));
        assertTrue(ExtraParsers.igdbQuery("Say \"hi\"").startsWith("search \"Say  hi\";"));
        assertEquals("tok", ExtraParsers.twitchToken("{\"access_token\":\"tok\",\"expires_in\":5000,\"token_type\":\"bearer\"}")[0]);
        assertNull(ExtraParsers.twitchToken("{\"status\":400}"));
    }

    private static final String PLAY = "<html><head>"
            + "<meta property=\"og:title\" content=\"Stardew Valley - Apps on Google Play\">"
            + "<meta name=\"description\" content=\"Short &amp; sweet.\">"
            + "<script type=\"application/ld+json\" nonce=\"x\">{\"@context\":\"https://schema.org\",\"@type\":\"SoftwareApplication\","
            + "\"name\":\"Stardew Valley\",\"description\":\"Move to the countryside.\",\"applicationCategory\":\"GAME_SIMULATION\","
            + "\"image\":\"https://play-lh.googleusercontent.com/icon=s180\",\"author\":{\"@type\":\"Person\",\"name\":\"ConcernedApe\"}}</script>"
            + "</head><body>"
            + "<img src=\"https://play-lh.googleusercontent.com/feat=w1052-h592\" alt=\"Cover art\">"
            + "<img alt=\"Screenshot image\" src=\"https://play-lh.googleusercontent.com/s1=w526-h296-rw\" itemprop=\"image\">"
            + "<img src=\"https://play-lh.googleusercontent.com/s2=w526-h296-rw\" alt=\"Screenshot image\">"
            + "<img src=\"https://example.com/other.png\" alt=\"Screenshot image\">"
            + "<div class=\"x\" data-g-id=\"description\">Move to the countryside, and cultivate a new life.<br>Raise animals.</div>"
            + "</body></html>";

    @Test
    public void play_readsTheAppPage() {
        ExtraParsers.PlayApp app = ExtraParsers.parsePlay(PLAY);
        assertNotNull(app);
        assertEquals("Stardew Valley", app.name);
        assertEquals("ConcernedApe", app.developer);
        assertTrue(app.isGame());
        assertTrue(app.description.startsWith("Move to the countryside, and cultivate"));
        assertEquals("https://play-lh.googleusercontent.com/icon=s180", app.icon);
        assertEquals("https://play-lh.googleusercontent.com/feat=w1920", app.featureGraphic);
        assertEquals(2, app.screenshots.size());
        assertEquals("https://play-lh.googleusercontent.com/s1=w1600", app.screenshots.get(0));
        GameInfo info = ExtraParsers.playInfo(app);
        assertEquals(GameInfo.SOURCE_PLAY, info.source);
        assertEquals(2, info.screenshots.size());
        assertEquals("ConcernedApe", info.developers.get(0));
        assertTrue(info.hasText());
    }

    @Test
    public void play_fallsBackToMetaTags() {
        String html = "<meta property=\"og:title\" content=\"VLC for Android – Apps on Google Play\">"
                + "<meta name=\"description\" content=\"Plays everything.\">";
        ExtraParsers.PlayApp app = ExtraParsers.parsePlay(html);
        assertNotNull(app);
        assertEquals("VLC for Android", app.name);
        assertEquals("Plays everything.", app.description);
        assertFalse(app.isGame());
        assertNull(ExtraParsers.parsePlay(""));
        assertNull(ExtraParsers.parsePlay("<html>nothing</html>"));
        assertEquals("https://play-lh.googleusercontent.com/abc=w640",
                ExtraParsers.playSized("https://play-lh.googleusercontent.com/abc=w526-h296-rw", 640));
    }
}
