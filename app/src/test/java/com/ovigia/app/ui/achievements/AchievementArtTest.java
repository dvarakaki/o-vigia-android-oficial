package com.ovigia.app.ui.achievements;

import com.ovigia.app.social.Achievement;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Guarda das tabelas de {@link AchievementArt}. Os {@code switch} de lá têm
 * {@code default}, então esquecer uma conquista nova não quebra a compilação —
 * ela só herdaria em silêncio o nome e o emblema da última. É o que estes
 * testes pegam: tudo precisa ser diferente de todo mundo.
 */
public class AchievementArtTest {

    private interface Resource {
        int of(Achievement achievement);
    }

    private static void allDistinct(String what, Resource resource) {
        Map<Integer, Achievement> seen = new HashMap<>();
        for (Achievement a : Achievement.values()) {
            int id = resource.of(a);
            assertNotEquals(what + " de " + a + " não existe", 0, id);
            Achievement other = seen.put(id, a);
            assertEquals(what + " repetido entre " + other + " e " + a, null, other);
        }
        assertEquals(Achievement.values().length, seen.size());
    }

    @Test
    public void everyAchievementHasItsOwnNameDescriptionAndEmblem() {
        allDistinct("nome", AchievementArt::titleOf);
        allDistinct("descrição", AchievementArt::descriptionOf);
        allDistinct("emblema", AchievementArt::iconOf);
    }

    @Test
    public void everyRarityHasItsOwnCall() {
        Map<Integer, Achievement.Rarity> seen = new HashMap<>();
        for (Achievement.Rarity rarity : Achievement.Rarity.values()) {
            int id = AchievementArt.kickerOf(rarity);
            assertNotEquals("chamada de " + rarity + " não existe", 0, id);
            assertEquals("chamada repetida em " + rarity, null, seen.put(id, rarity));
        }
    }
}
