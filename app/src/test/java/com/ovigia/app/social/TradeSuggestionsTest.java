package com.ovigia.app.social;

import com.ovigia.app.data.roster.RosterCatalog;

import org.junit.Test;

import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TradeSuggestionsTest {

    /** 1–4 Vingadores; 5 vilão famoso; 6 famoso sem equipe; 7 anônimo; 8 do Quarteto. */
    private static final RosterCatalog ROSTER = RosterCatalog.parse(new StringReader("{\"characters\":["
            + "{\"id\":1,\"teams\":[\"avengers\"],\"villain\":0.1},"
            + "{\"id\":2,\"teams\":[\"avengers\"],\"villain\":0.1},"
            + "{\"id\":3,\"teams\":[\"avengers\"],\"villain\":0.1},"
            + "{\"id\":4,\"teams\":[\"avengers\"],\"villain\":0.1},"
            + "{\"id\":5,\"teams\":[],\"villain\":0.9,\"mainstream\":true},"
            + "{\"id\":6,\"teams\":[],\"villain\":0.1,\"mainstream\":true},"
            + "{\"id\":7,\"teams\":[],\"villain\":0.1},"
            + "{\"id\":8,\"teams\":[\"f4\"],\"villain\":0.1}"
            + "]}"));

    private static PublicProfile.Hero hero(int id) {
        return new PublicProfile.Hero(id, "Herói " + id, null, 0L);
    }

    private static List<PublicProfile.Hero> heroes(int... ids) {
        PublicProfile.Hero[] list = new PublicProfile.Hero[ids.length];
        for (int i = 0; i < ids.length; i++) list[i] = hero(ids[i]);
        return Arrays.asList(list);
    }

    private static int[] order(List<TradeSuggestions.Pick> picks) {
        int[] ids = new int[picks.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = picks.get(i).hero.characterId;
        return ids;
    }

    @Test
    public void theAchievementCloserToDone_weighsMore() {
        // Com 3 dos 5 Vingadores, o 4 deixa a conquista em 4/5; o vilão famoso começa
        // "Eu tenho um exército" (1/5) e o do Quarteto, "Reunião de família" (1/4).
        List<TradeSuggestions.Pick> picks = TradeSuggestions.rank(heroes(7, 8, 5, 4),
                new HashSet<>(Arrays.asList(1, 2, 3)), ROSTER);

        assertEquals(Arrays.toString(new int[]{4, 5, 8, 7}), Arrays.toString(order(picks)));
        assertEquals(TradeSuggestions.Reason.ADVANCES, picks.get(0).reason);
        assertEquals(Achievement.AVENGERS_5, picks.get(0).achievement);
        assertEquals(Achievement.VILLAINS_5, picks.get(1).achievement);
    }

    @Test
    public void completes_beatsAdvances_beatsPopular_beatsNothing() {
        RosterCatalog fiveAvengers = RosterCatalog.parse(new StringReader("{\"characters\":["
                + "{\"id\":1,\"teams\":[\"avengers\"]},{\"id\":2,\"teams\":[\"avengers\"]},"
                + "{\"id\":3,\"teams\":[\"avengers\"]},{\"id\":4,\"teams\":[\"avengers\"]},"
                + "{\"id\":9,\"teams\":[\"avengers\"]},"
                + "{\"id\":6,\"teams\":[],\"mainstream\":true},{\"id\":7,\"teams\":[]},"
                + "{\"id\":8,\"teams\":[\"f4\"]}"
                + "]}"));
        List<TradeSuggestions.Pick> picks = TradeSuggestions.rank(heroes(7, 6, 8, 9),
                new HashSet<>(Arrays.asList(1, 2, 3, 4)), fiveAvengers);

        assertEquals(9, picks.get(0).hero.characterId);
        assertEquals(TradeSuggestions.Reason.COMPLETES, picks.get(0).reason);
        assertEquals(Achievement.AVENGERS_5, picks.get(0).achievement);
        assertEquals(8, picks.get(1).hero.characterId);
        assertEquals(TradeSuggestions.Reason.ADVANCES, picks.get(1).reason);
        assertEquals(6, picks.get(2).hero.characterId);
        assertEquals(TradeSuggestions.Reason.POPULAR, picks.get(2).reason);
        assertEquals(7, picks.get(3).hero.characterId);
        assertEquals(TradeSuggestions.Reason.NONE, picks.get(3).reason);
        assertNull(picks.get(3).achievement);
    }

    @Test
    public void heroesTheReceiverAlreadyHas_andRepeats_areLeftOut() {
        List<TradeSuggestions.Pick> picks = TradeSuggestions.rank(heroes(1, 2, 2, 3),
                new HashSet<>(Collections.singletonList(1)), ROSTER);
        assertEquals(2, picks.size());
    }

    @Test
    public void withoutRoster_keepsTheIncomingOrder() {
        List<TradeSuggestions.Pick> picks = TradeSuggestions.rank(heroes(7, 3, 5), Collections.emptySet(), null);
        assertEquals(Arrays.toString(new int[]{7, 3, 5}), Arrays.toString(order(picks)));
        assertEquals(TradeSuggestions.Reason.NONE, picks.get(0).reason);
    }
}
