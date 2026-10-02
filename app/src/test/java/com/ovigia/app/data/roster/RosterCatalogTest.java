package com.ovigia.app.data.roster;

import com.google.gson.JsonParseException;
import com.ovigia.app.data.QuestionKeys;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Valida o {@code assets/roster.json} de verdade: é aqui que um erro de digitação
 * na curadoria (poder inexistente, time errado, id repetido) é pego antes de
 * virar uma pergunta sem sentido no jogo.
 */
public class RosterCatalogTest {

    private static RosterCatalog roster;

    @BeforeClass
    public static void loadRealRoster() throws IOException {
        // Testes JVM rodam com o diretório do módulo como working dir.
        File file = new File("src/main/assets/roster.json");
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            roster = RosterCatalog.parse(reader);
        }
    }

    @Test
    public void rosterHasAPlayableCast() {
        assertTrue("elenco pequeno demais para o jogo ter graça", roster.size() >= 100);
    }

    @Test
    public void everyEntryUsesOnlyKnownVocabulary() {
        for (RosterCatalog.Entry e : roster.entries()) {
            assertNotNull("id " + e.id + " sem nome", e.name);
            assertFalse(e.name + " sem nenhum poder", e.powers.isEmpty());
            for (String power : e.powers) {
                assertTrue(e.name + ": poder desconhecido '" + power + "'", QuestionKeys.POWERS.contains(power));
            }
            for (String team : e.teams) {
                assertTrue(e.name + ": time desconhecido '" + team + "'", QuestionKeys.TEAMS.contains(team));
            }
            assertTrue(e.name + ": vilania fora de [0,1]", e.villain >= 0 && e.villain <= 1);
        }
    }

    @Test
    public void everyPowerInTheVocabularyIsUsedBySomeone() {
        for (String power : QuestionKeys.POWERS) {
            boolean used = false;
            for (RosterCatalog.Entry e : roster.entries()) {
                if (e.powers.contains(power)) used = true;
            }
            assertTrue("poder '" + power + "' não é usado por ninguém — pergunta inútil", used);
        }
    }

    @Test
    public void everyEntryHasAnExplicitRarity() {
        for (RosterCatalog.Entry e : roster.entries()) {
            assertNotNull(e.name + " sem raridade no roster.json", e.rarity);
            assertNotNull(e.name + ": raridade desconhecida '" + e.rarity + "'", Rarity.fromKey(e.rarity));
        }
    }

    @Test
    public void theWellKnown_areNeverEpicOrLegendary() {
        // Quanto menos conhecido, mais raro: quem o grande público conhece é comum ou raro.
        for (RosterCatalog.Entry e : roster.entries()) {
            if (!e.mainstream) continue;
            assertTrue(e.name + " é conhecido e não pode ser " + e.rarity,
                    e.rarity() == Rarity.COMMON || e.rarity() == Rarity.RARE);
        }
    }

    @Test
    public void everyRarityHasCharacters_andLegendariesStayAMinority() {
        Map<Rarity, Integer> count = new EnumMap<>(Rarity.class);
        for (RosterCatalog.Entry e : roster.entries()) count.merge(e.rarity(), 1, Integer::sum);
        for (Rarity r : Rarity.values()) {
            assertTrue("nenhum personagem " + r, count.getOrDefault(r, 0) > 0);
        }
        double legendaryShare = count.get(Rarity.LEGENDARY) / (double) roster.size();
        assertTrue("lendários demais deixam o jogo gratuito pobre: " + legendaryShare, legendaryShare <= 0.35);
        assertTrue("o Vigia do Infinito precisa valer a pena: " + legendaryShare, legendaryShare >= 0.10);
    }

    @Test
    public void onlyLegendariesRequireTheInfiniteWatcher() {
        assertTrue(Rarity.LEGENDARY.requiresInfinite());
        assertFalse(Rarity.EPIC.requiresInfinite());
        assertFalse(Rarity.RARE.requiresInfinite());
        assertFalse(Rarity.COMMON.requiresInfinite());
    }

    @Test
    public void missingRarity_isCommon_andUnknownCharacters_too() {
        RosterCatalog small = RosterCatalog.parse(new StringReader(
                "{\"characters\":[{\"id\":1},{\"id\":2,\"rarity\":\"lendario\"}]}"));
        assertEquals(Rarity.COMMON, small.rarityOf(1));
        assertEquals(Rarity.LEGENDARY, small.rarityOf(2));
        assertEquals(Rarity.COMMON, small.rarityOf(999));
    }

    @Test(expected = JsonParseException.class)
    public void unknownRarity_isRejected() {
        RosterCatalog.parse(new StringReader("{\"characters\":[{\"id\":1,\"rarity\":\"mitico\"}]}"));
    }

    @Test
    public void idFilter_listsEveryIdOnce() {
        String[] ids = roster.idFilter().split("\\|");
        assertTrue(ids.length == roster.size());
    }

    @Test(expected = JsonParseException.class)
    public void duplicateIds_areRejected() {
        RosterCatalog.parse(new StringReader("{\"characters\":[{\"id\":1},{\"id\":1}]}"));
    }

    @Test(expected = JsonParseException.class)
    public void emptyRoster_isRejected() {
        RosterCatalog.parse(new StringReader("{\"characters\":[]}"));
    }
}
