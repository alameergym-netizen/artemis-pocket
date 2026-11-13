package com.limelight.pocket.info;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Wikipedia's "Video game reviews" box, read into the game page's ratings. */
public class RatingsTest {

    private static final String BOX = "Some text\n==Reception==\n{{Video game reviews\n"
            + "| MC = PC: 87/100<ref name=\"mc\">{{cite web|url=x|title=Reviews}}</ref><br />NS: 90/100<ref>{{cite web|title=y}}</ref>\n"
            + "| OC = 89/100<ref>z</ref>\n"
            + "| Destruct = 9.5/10<ref>a</ref>\n"
            + "| GSpot = 9/10<ref>b</ref>\n"
            + "| IGN = 9.4/10<ref>c</ref>\n"
            + "| NLife = {{Rating|10|10}}<ref>d</ref>\n"
            + "| PCGUS = 92/100<ref name=pcg/>\n"
            + "| Fam = 33/40\n"
            + "| 1UP = A-\n"
            + "| EuroG = Recommended\n"
            + "| rev1 = [[The Guardian]]\n"
            + "| rev1Score = {{Rating|4|5}}<ref>e</ref>\n"
            + "| GR = 91.5%\n"
            + "| IGN_PS4 = 8/10\n"
            + "}}\nmore {{Infobox}}";

    private static GameInfo.Rating find(List<GameInfo.Rating> list, String source) {
        for (GameInfo.Rating r : list) {
            if (r.source.equals(source)) {
                return r;
            }
        }
        return null;
    }

    @Test
    public void aggregatesFirstThenCritics() {
        List<GameInfo.Rating> r = Ratings.fromWikitext(BOX);
        assertEquals("Metacritic", r.get(0).source);
        assertEquals("87", r.get(0).value);
        assertEquals(87, r.get(0).score);
        assertTrue(r.get(0).aggregate);
        assertEquals("89", find(r, "OpenCritic").value);
        assertEquals(92, find(r, "GameRankings").score);
        assertEquals("9.4/10", find(r, "IGN").value);
        assertEquals("10/10", find(r, "Nintendo Life").value);
        assertEquals(83, find(r, "Famitsu").score);
        assertEquals("4/5", find(r, "The Guardian").value);
        assertEquals(91, find(r, "1UP").score);
        // A word, not a score: left out
        assertNull(find(r, "Eurogamer"));
    }

    @Test
    public void platformColumns() {
        List<GameInfo.Rating> r = Ratings.fromWikitext("{{Video game reviews\n|PS4 = true\n|XONE=true\n"
                + "|MC_PS4 = 82/100<ref>x</ref>\n|MC_XONE = 80/100\n|IGN_PS4 = 8.5/10\n|GSpot_XONE= 7/10\n}}");
        assertEquals("82", find(r, "Metacritic").value);
        assertEquals(85, find(r, "IGN").score);
        assertEquals(70, find(r, "GameSpot").score);
    }

    @Test
    public void noBox() {
        assertTrue(Ratings.fromWikitext("An article with no reviews.").isEmpty());
        assertTrue(Ratings.fromWikitext(null).isEmpty());
    }

    @Test
    public void steamWords() {
        assertEquals("Overwhelmingly Positive", Ratings.steamWords(96, 12000));
        assertEquals("Mixed", Ratings.steamWords(55, 300));
        assertNull(Ratings.steamWords(80, 0));
    }
}
