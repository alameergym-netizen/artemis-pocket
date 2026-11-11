package com.limelight.pocket.info;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Game info: Steam and Wikipedia answers, store HTML, and picking the right game by name. */
public class InfoParsersTest {

    // A trimmed copy of a real appdetails answer, in Steam's current format (streamed trailers)
    private static final String STEAM_DETAILS = "{\"870780\":{\"success\":true,\"data\":{"
            + "\"type\":\"game\",\"name\":\"Control Ultimate Edition\",\"steam_appid\":870780,"
            + "\"short_description\":\"Winner of over 80 awards, Control is a visually stunning third-person action-adventure &amp; more.\","
            + "\"about_the_game\":\"<h2 class=\\\"bb_tag\\\">About</h2><p>After a secretive agency in New York is invaded&hellip;</p>"
            + "<ul class=\\\"bb_ul\\\"><li>Supernatural abilities</li><li>Shifting world</li></ul><img src=\\\"x.gif\\\">\","
            + "\"header_image\":\"https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/870780/header.jpg\","
            + "\"background_raw\":\"https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/870780/page_bg_raw.jpg\","
            + "\"website\":\"https://controlgame.com\","
            + "\"developers\":[\"Remedy Entertainment\"],\"publishers\":[\"505 Games\"],"
            + "\"metacritic\":{\"score\":85,\"url\":\"https://www.metacritic.com/game/pc/control\"},"
            + "\"genres\":[{\"id\":\"1\",\"description\":\"Action\"},{\"id\":\"25\",\"description\":\"Adventure\"}],"
            + "\"screenshots\":[{\"id\":0,\"path_thumbnail\":\"https://cdn/ss_1.600x338.jpg\",\"path_full\":\"https://cdn/ss_1.1920x1080.jpg\"},"
            + "{\"id\":1,\"path_thumbnail\":\"https://cdn/ss_2.600x338.jpg\",\"path_full\":\"https://cdn/ss_2.1920x1080.jpg\"}],"
            + "\"movies\":[{\"id\":256692021,\"name\":\"Launch Trailer\",\"thumbnail\":\"https://cdn/movie.jpg\","
            + "\"dash_av1\":\"https://video/dash_av1.mpd\",\"dash_h264\":\"https://video/dash_h264.mpd\","
            + "\"hls_h264\":\"https://video/hls_264_master.m3u8\",\"highlight\":true},"
            + "{\"id\":2,\"name\":\"Old\",\"thumbnail\":\"https://cdn/old.jpg\",\"webm\":{\"480\":\"a.webm\",\"max\":\"b.webm\"},"
            + "\"mp4\":{\"480\":\"https://video/old480.mp4\",\"max\":\"https://video/oldmax.mp4\"},\"highlight\":false},"
            + "{\"id\":3,\"name\":\"Nothing playable\",\"thumbnail\":\"https://cdn/x.jpg\"}],"
            + "\"release_date\":{\"coming_soon\":false,\"date\":\"27 Aug, 2020\"}}}}";

    @Test
    public void steamDetails_readsEverythingThePageShows() {
        GameInfo info = InfoParsers.parseSteamDetails(STEAM_DETAILS, 870780);
        assertNotNull(info);
        assertEquals(GameInfo.SOURCE_STEAM, info.source);
        assertEquals("Control Ultimate Edition", info.title);
        assertEquals(870780, info.steamAppId);
        assertEquals("Winner of over 80 awards, Control is a visually stunning third-person action-adventure & more.",
                info.shortDescription);
        assertTrue(info.about.startsWith("About\n\nAfter a secretive agency in New York is invaded…"));
        assertTrue(info.about.contains("• Supernatural abilities\n• Shifting world"));
        assertFalse(info.about.contains("<"));
        assertEquals(Arrays.asList("Remedy Entertainment"), info.developers);
        assertEquals(Arrays.asList("505 Games"), info.publishers);
        assertEquals(Arrays.asList("Action", "Adventure"), info.genres);
        assertEquals(85, info.metacritic);
        assertEquals("27 Aug, 2020", info.releaseDate);
        assertEquals("2020", info.releaseYear());
        assertFalse(info.comingSoon);
        assertEquals(2, info.screenshots.size());
        assertEquals("https://cdn/ss_2.1920x1080.jpg", info.screenshots.get(1).full);
        assertEquals("https://store.steampowered.com/app/870780/", info.pageUrl);
        assertTrue(info.background.endsWith("page_bg_raw.jpg"));
    }

    @Test
    public void steamDetails_trailers_preferHighlightAndPlayableStreams() {
        GameInfo info = InfoParsers.parseSteamDetails(STEAM_DETAILS, 870780);
        assertNotNull(info);
        // The entry with nothing to play is dropped
        assertEquals(2, info.movies.size());
        GameInfo.Movie main = info.mainTrailer();
        assertNotNull(main);
        assertEquals("Launch Trailer", main.name);
        // Steam streams trailers now: HLS is what plays (DASH kept as a fallback)
        assertEquals("https://video/hls_264_master.m3u8", main.playable());
        assertEquals("https://video/dash_h264.mpd", main.dash);
        // An older entry with files plays its best file
        assertEquals("https://video/oldmax.mp4", info.movies.get(1).playable());
    }

    @Test
    public void steamDetails_failureOrWrongIdIsNull() {
        assertNull(InfoParsers.parseSteamDetails("{\"5\":{\"success\":false}}", 5));
        assertNull(InfoParsers.parseSteamDetails(STEAM_DETAILS, 12345));
        assertNull(InfoParsers.parseSteamDetails("not json", 870780));
        assertNull(InfoParsers.parseSteamDetails(null, 870780));
        assertNull(InfoParsers.parseSteamDetails("[]", 870780));
    }

    @Test
    public void steamSearch_keepsAppsInOrder() {
        String json = "{\"total\":3,\"items\":[{\"type\":\"app\",\"name\":\"Control Ultimate Edition\",\"id\":870780},"
                + "{\"type\":\"app\",\"name\":\"Control Soundtrack\",\"id\":1},{\"type\":\"sub\",\"name\":\"Bundle\",\"id\":2},"
                + "{\"type\":\"app\",\"id\":0}]}";
        List<InfoParsers.Candidate> found = InfoParsers.parseSteamSearch(json);
        assertEquals(2, found.size());
        assertEquals(870780, found.get(0).id);
        assertEquals("Control Soundtrack", found.get(1).name);
        assertTrue(InfoParsers.parseSteamSearch("{}").isEmpty());
        assertTrue(InfoParsers.parseSteamSearch("garbage").isEmpty());
    }

    private static List<InfoParsers.Candidate> candidates(Object... idName) {
        InfoParsers.Candidate[] out = new InfoParsers.Candidate[idName.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = new InfoParsers.Candidate((Integer) idName[i * 2], (String) idName[i * 2 + 1]);
        }
        return Arrays.asList(out);
    }

    @Test
    public void bestMatch_exactNameWins() {
        assertEquals(10, InfoParsers.bestMatch("Resident Evil 2",
                candidates(11, "Resident Evil 2 - Deluxe Content", 10, "RESIDENT EVIL 2", 12, "Resident Evil 3")).id);
    }

    @Test
    public void bestMatch_editionsAndMarksDontMatter() {
        assertEquals(870780, InfoParsers.bestMatch("Control",
                candidates(1, "Control Soundtrack", 870780, "Control Ultimate Edition™", 3, "Remote Control Racers")).id);
        assertEquals(5, InfoParsers.bestMatch("FINAL FANTASY VII REMAKE INTERGRADE",
                candidates(5, "Final Fantasy 7 Remake Intergrade")).id);
        assertEquals(6, InfoParsers.bestMatch("Pokémon Legends", candidates(6, "Pokemon Legends")).id);
    }

    @Test
    public void bestMatch_skipsSoundtracksDemosAndDlc() {
        assertEquals(21, InfoParsers.bestMatch("Project Zomboid",
                candidates(20, "Project Zomboid Soundtrack", 21, "Project Zomboid")).id);
        assertEquals(40, InfoParsers.bestMatch("Lossless Scaling",
                candidates(41, "Lossless Scaling Demo", 40, "Lossless Scaling")).id);
        // Unless the app itself is one
        assertEquals(41, InfoParsers.bestMatch("Lossless Scaling Demo",
                candidates(41, "Lossless Scaling Demo", 40, "Lossless Scaling")).id);
    }

    @Test
    public void bestMatch_aDifferentGameIsNoMatch() {
        // A sequel with a different name is not the same game
        assertNull(InfoParsers.bestMatch("CONTROL Resonant", candidates(870780, "Control Ultimate Edition")));
        assertNull(InfoParsers.bestMatch("Valheim", candidates(1, "Valhalla Hills")));
        assertNull(InfoParsers.bestMatch("", candidates(1, "Anything")));
        assertNull(InfoParsers.bestMatch("Hades", null));
    }

    @Test
    public void normalize_tidiesNames() {
        assertEquals("control", InfoParsers.normalize("CONTROL Ultimate Edition™"));
        assertEquals("marvel s spider man", InfoParsers.normalize("Marvel's Spider-Man Remastered"));
        assertEquals("final fantasy 7 remake intergrade", InfoParsers.normalize("FINAL FANTASY VII REMAKE INTERGRADE"));
        assertEquals("grand theft auto 5", InfoParsers.normalize("Grand Theft Auto V"));
        assertEquals("ratchet and clank", InfoParsers.normalize("Ratchet & Clank"));
        assertEquals("", InfoParsers.normalize(null));
    }

    @Test
    public void htmlToText_keepsParagraphsAndLists() {
        String text = InfoParsers.htmlToText("<p>One&nbsp;two<br>three</p><p>Four &amp; five &#39;six&#39; &#x2014; seven</p>"
                + "<ul><li>A</li><li>B</li></ul><script>var x = 1;</script>");
        assertEquals("One two\nthree\n\nFour & five 'six' — seven\n\n• A\n• B", text);
        assertNull(InfoParsers.htmlToText(null));
        assertEquals("", InfoParsers.htmlToText("<img src=x>"));
        assertEquals("plain", InfoParsers.htmlToText("plain"));
    }

    @Test
    public void wikipedia_searchAndBestTitle() {
        String json = "{\"query\":{\"search\":[{\"title\":\"Control (disambiguation)\"},{\"title\":\"Control (film)\"},"
                + "{\"title\":\"Control (video game)\"},{\"title\":\"Control Resonant\"}]}}";
        List<String> titles = InfoParsers.parseWikiSearch(json);
        assertEquals(4, titles.size());
        assertEquals("Control (video game)", InfoParsers.bestWikiTitle("Control", titles));
        assertEquals("Valheim", InfoParsers.bestWikiTitle("Valheim", Arrays.asList("Valhalla", "Valheim")));
        // Arabic Wikipedia's qualifier for a video game
        assertEquals("كنترول (لعبة فيديو)",
                InfoParsers.bestWikiTitle("كنترول",
                        Arrays.asList("كنترول (توضيح)",
                                "كنترول (لعبة فيديو)")));
        assertNull(InfoParsers.bestWikiTitle("Hades", Arrays.asList("Hades (film)", "Hell")));
        assertTrue(InfoParsers.parseWikiSearch("{}").isEmpty());
    }

    @Test
    public void wikipedia_summary() {
        String json = "{\"type\":\"standard\",\"title\":\"Valheim\",\"description\":\"2021 video game\","
                + "\"extract\":\"Valheim is a survival and sandbox video game by the Swedish developer Iron Gate Studio. It was released in early access in 2021.\","
                + "\"content_urls\":{\"desktop\":{\"page\":\"https://en.wikipedia.org/wiki/Valheim\"}},"
                + "\"originalimage\":{\"source\":\"https://upload.wikimedia.org/valheim.png\"}}";
        GameInfo info = InfoParsers.parseWikiSummary(json);
        assertNotNull(info);
        assertEquals(GameInfo.SOURCE_WIKIPEDIA, info.source);
        assertEquals("Valheim is a survival and sandbox video game by the Swedish developer Iron Gate Studio.",
                info.shortDescription);
        assertTrue(info.about.endsWith("in 2021."));
        assertEquals("https://en.wikipedia.org/wiki/Valheim", info.pageUrl);
        assertNull(InfoParsers.parseWikiSummary("{\"type\":\"disambiguation\",\"extract\":\"Control may refer to\"}"));
        assertNull(InfoParsers.parseWikiSummary("{\"type\":\"standard\",\"extract\":\"\"}"));
    }

    @Test
    public void gameInfo_releaseYearAndTrailer() {
        GameInfo info = new GameInfo();
        info.releaseDate = "Coming soon";
        assertNull(info.releaseYear());
        info.releaseDate = "2 Dec, 2019";
        assertEquals("2019", info.releaseYear());
        assertNull(info.mainTrailer());
        assertFalse(info.hasText());
    }
}
