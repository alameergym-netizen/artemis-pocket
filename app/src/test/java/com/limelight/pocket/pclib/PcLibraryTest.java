package com.limelight.pocket.pclib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The PC library: the PC helper's game lines, the Steam account and how both come together. */
public class PcLibraryTest {

    @Test
    public void parse_readsEveryField() {
        PcGame g = PcGame.parse("G\tsteam\t1245620\tELDEN RING\tinstalled\t-1\t48213\t1730000000\t1245620");
        assertNotNull(g);
        assertEquals("steam", g.source);
        assertEquals("1245620", g.id);
        assertEquals("ELDEN RING", g.name);
        assertTrue(g.isInstalled());
        assertEquals(-1, g.progress);
        assertEquals(48213, g.sizeMb);
        assertEquals(1730000000L, g.lastPlayed);
        assertEquals("1245620", g.artSteamId);
        assertEquals("LAUNCH steam 1245620", g.launchCommand());
        assertEquals("STEAM INSTALL 1245620", g.installCommand());
    }

    @Test
    public void parse_installingAndOtherStores() {
        PcGame d = PcGame.parse("G\tsteam\t570\tDota 2\tinstalling\t37\t0\t0\t570");
        assertTrue(d.isBusy());
        assertEquals(37, d.progress);
        PcGame epic = PcGame.parse("G\tepic\tFortnite\tFortnite\tinstalled\t-1\t30000\t0\t");
        assertEquals("epic", epic.source);
        assertEquals("", epic.artSteamId);
        assertNull(epic.installCommand());
        assertEquals("Epic Games", epic.storeLabel());
        // Older helpers may send fewer fields
        PcGame gog = PcGame.parse("G\tgog\t1207658924\tThe Witcher 3\tinstalled");
        assertNotNull(gog);
        assertEquals(0, gog.sizeMb);
        // Odd states become installed, progress is kept in range
        assertTrue(PcGame.parse("G\tsteam\t1\tX\tweird\t250").isInstalled());
        assertEquals(100, PcGame.parse("G\tsteam\t1\tX\tinstalling\t250").progress);
    }

    @Test
    public void parse_rejectsGarbage() {
        assertNull(PcGame.parse(null));
        assertNull(PcGame.parse("END 3"));
        assertNull(PcGame.parse("G\tsteam"));
        assertNull(PcGame.parse("G\tsteam\t\tName\tinstalled"));
        assertNull(PcGame.parse("G\tsteam\t12 34\tName\tinstalled"));
        List<PcGame> all = PcGame.parseAll(Arrays.asList("G\tsteam\t10\tA\tinstalled", "noise", "END 1"));
        assertEquals(1, all.size());
    }

    @Test
    public void merge_addsOwnedGamesNotOnThePc() {
        List<PcGame> onPc = new ArrayList<>();
        onPc.add(PcGame.parse("G\tsteam\t10\tCounter-Strike\tinstalled\t-1\t100\t5\t10"));
        onPc.add(PcGame.parse("G\tepic\tSugar\tSomething\tinstalled"));
        PcGame owned1 = new PcGame(PcGame.STEAM, "10", "Counter-Strike", PcGame.NOT_INSTALLED);
        owned1.playtimeMin = 600;
        owned1.lastPlayed = 99;
        PcGame owned2 = new PcGame(PcGame.STEAM, "20", "Team Fortress Classic", PcGame.NOT_INSTALLED);
        owned2.playtimeMin = 30;
        List<PcGame> merged = PcGame.merge(onPc, Arrays.asList(owned1, owned2));
        assertEquals(3, merged.size());
        PcGame cs = merged.get(0);
        assertTrue(cs.isInstalled());
        assertTrue(cs.owned);
        assertEquals(600, cs.playtimeMin);
        assertEquals(99, cs.lastPlayed);
        PcGame tfc = merged.get(2);
        assertEquals("20", tfc.id);
        assertEquals(PcGame.NOT_INSTALLED, tfc.state);
        assertEquals("20", tfc.artSteamId);
        assertEquals("STEAM INSTALL 20", tfc.installCommand());
        // Without an account: just the PC's games
        assertEquals(2, PcGame.merge(onPc, null).size());
    }

    @Test
    public void sortAndFilter() {
        List<PcGame> games = new ArrayList<>();
        PcGame notInstalled = new PcGame(PcGame.STEAM, "1", "Alpha", PcGame.NOT_INSTALLED);
        notInstalled.lastPlayed = 1000;
        PcGame old = new PcGame(PcGame.STEAM, "2", "Beta", PcGame.INSTALLED);
        old.lastPlayed = 10;
        PcGame recent = new PcGame(PcGame.GOG, "3", "Gamma", PcGame.INSTALLED);
        recent.lastPlayed = 500;
        PcGame busy = new PcGame(PcGame.STEAM, "4", "Delta", PcGame.INSTALLING);
        games.add(notInstalled);
        games.add(old);
        games.add(recent);
        games.add(busy);
        PcGame.sort(games);
        assertEquals("Gamma", games.get(0).name);
        assertEquals("Beta", games.get(1).name);
        assertEquals("Delta", games.get(2).name);
        assertEquals("Alpha", games.get(3).name);
        assertEquals(3, PcGame.filter(games, PcGame.FILTER_INSTALLED).size());
        assertEquals(1, PcGame.filter(games, PcGame.FILTER_NOT_INSTALLED).size());
        assertEquals(3, PcGame.filter(games, PcGame.FILTER_STEAM).size());
        assertEquals(1, PcGame.filter(games, PcGame.FILTER_OTHER_STORES).size());
        assertEquals(4, PcGame.filter(games, PcGame.FILTER_ALL).size());
    }

    @Test
    public void normalize_matchesNamesFromDifferentPlaces() {
        assertEquals(PcGame.normalize("Elden Ring"), PcGame.normalize("ELDEN RING™"));
        assertEquals(PcGame.normalize("Pokémon"), PcGame.normalize("Pokemon"));
        assertEquals("halflife2", PcGame.normalize("Half-Life 2"));
        assertFalse(PcGame.normalize("Portal").equals(PcGame.normalize("Portal 2")));
        assertEquals("", PcGame.normalize(null));
    }

    @Test
    public void labels() {
        assertEquals("", PcGame.sizeLabel(0));
        assertEquals("830 MB", PcGame.sizeLabel(830));
        assertEquals("1.5 GB", PcGame.sizeLabel(1536));
        assertEquals("45 min", PcGame.playtimeLabel(45));
        assertEquals("12 h", PcGame.playtimeLabel(12 * 60 + 5));
        assertEquals("", PcGame.playtimeLabel(0));
    }

    @Test
    public void accountInput_readsWhatPeopleType() {
        assertEquals("76561197960287930", SteamParsers.accountInput("76561197960287930"));
        assertEquals("76561197960287930", SteamParsers.accountInput("https://steamcommunity.com/profiles/76561197960287930/"));
        assertEquals("vanity:gabelogannewell", SteamParsers.accountInput("https://steamcommunity.com/id/gabelogannewell"));
        assertEquals("vanity:my_name", SteamParsers.accountInput("  my_name "));
        assertNull(SteamParsers.accountInput(""));
        assertNull(SteamParsers.accountInput("http://example.com/x"));
        assertTrue(SteamParsers.looksLikeApiKey("0123456789ABCDEF0123456789abcdef"));
        assertFalse(SteamParsers.looksLikeApiKey("nope"));
    }

    @Test
    public void webApi_vanityAndOwnedGames() {
        assertEquals("76561197960287930",
                SteamParsers.vanityResult("{\"response\":{\"steamid\":\"76561197960287930\",\"success\":1}}"));
        assertNull(SteamParsers.vanityResult("{\"response\":{\"success\":42,\"message\":\"No match\"}}"));
        assertNull(SteamParsers.vanityResult("not json"));

        List<PcGame> owned = SteamParsers.ownedGames("{\"response\":{\"game_count\":2,\"games\":["
                + "{\"appid\":10,\"name\":\"Counter-Strike\",\"playtime_forever\":120,\"rtime_last_played\":1700000000},"
                + "{\"appid\":0,\"name\":\"bad\"},"
                + "{\"appid\":20,\"name\":\"Team Fortress Classic\",\"playtime_forever\":0}]}}");
        assertNotNull(owned);
        assertEquals(2, owned.size());
        assertEquals("10", owned.get(0).id);
        assertEquals(120, owned.get(0).playtimeMin);
        assertEquals(1700000000L, owned.get(0).lastPlayed);
        assertTrue(owned.get(1).owned);
        assertEquals("Gabe", SteamParsers.personaName("{\"response\":{\"players\":[{\"personaname\":\" Gabe \"}]}}"));
        assertEquals("", SteamParsers.personaName("{\"response\":{\"players\":[]}}"));
        // A private profile: an empty response
        assertNull(SteamParsers.ownedGames("{\"response\":{}}"));
        assertEquals(0, SteamParsers.ownedGames("{\"response\":{\"game_count\":0}}").size());
    }
}
