package com.ovigia.app.social;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AchievementsStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file;

    @Before
    public void setUp() {
        file = new File(tmp.getRoot(), "achievements.json");
    }

    /** Progresso com as conquistas pedidas no alvo e o resto zerado. */
    private static List<AchievementProgress> unlocked(Achievement... achievements) {
        List<Achievement> wanted = Arrays.asList(achievements);
        List<AchievementProgress> progress = new java.util.ArrayList<>();
        for (Achievement a : Achievement.values()) {
            progress.add(new AchievementProgress(a, wanted.contains(a) ? a.target : 0));
        }
        return progress;
    }

    @Test
    public void firstCallOfAnAccount_isASilentBaseline() {
        AchievementsStore store = new AchievementsStore(() -> file);
        assertFalse(store.isTracking("ana"));

        List<Achievement> fresh = store.claimNewlyUnlocked("ana", unlocked(Achievement.FIRST_HERO,
                Achievement.GAMES_10));

        assertTrue("o que já era do jogador não vira festa", fresh.isEmpty());
        assertTrue(store.isTracking("ana"));
    }

    @Test
    public void afterTheBaseline_eachAchievementIsClaimedOnce() {
        AchievementsStore store = new AchievementsStore(() -> file);
        store.claimNewlyUnlocked("ana", unlocked(Achievement.FIRST_HERO));

        List<Achievement> fresh = store.claimNewlyUnlocked("ana",
                unlocked(Achievement.FIRST_HERO, Achievement.GAMES_10, Achievement.BEAT_WATCHER));

        assertEquals(Arrays.asList(Achievement.GAMES_10, Achievement.BEAT_WATCHER), fresh);
        assertEquals("a mesma conquista não cai duas vezes", Collections.emptyList(),
                store.claimNewlyUnlocked("ana", unlocked(Achievement.FIRST_HERO, Achievement.GAMES_10,
                        Achievement.BEAT_WATCHER)));
    }

    @Test
    public void baselineAndClaims_persistAndAreSeparatedByAccount() {
        AchievementsStore store = new AchievementsStore(() -> file);
        store.claimNewlyUnlocked("ana", unlocked(Achievement.FIRST_HERO));
        store.claimNewlyUnlocked("ana", unlocked(Achievement.FIRST_HERO, Achievement.GAMES_10));

        AchievementsStore reopened = new AchievementsStore(() -> file);
        assertTrue(reopened.isTracking("ana"));
        assertTrue("reabrir não repete a festa",
                reopened.claimNewlyUnlocked("ana", unlocked(Achievement.FIRST_HERO, Achievement.GAMES_10)).isEmpty());
        assertFalse("outra conta ainda não tem marco zero", reopened.isTracking("bia"));
        assertTrue(reopened.claimNewlyUnlocked("bia", unlocked(Achievement.FIRST_HERO)).isEmpty());
    }

    @Test
    public void deleteAccount_forgetsEverythingOfThatAccount() {
        AchievementsStore store = new AchievementsStore(() -> file);
        store.claimNewlyUnlocked("ana", unlocked(Achievement.FIRST_HERO));
        store.claimNewlyUnlocked("bia", unlocked());

        store.deleteAccount("ana");

        assertFalse(store.isTracking("ana"));
        assertTrue("a outra conta continua intacta", store.isTracking("bia"));
        assertTrue(new AchievementsStore(() -> file).claimNewlyUnlocked("ana",
                unlocked(Achievement.FIRST_HERO)).isEmpty());
    }
}
