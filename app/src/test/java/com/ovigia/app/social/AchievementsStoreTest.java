package com.ovigia.app.social;

import com.ovigia.app.cloud.FakeCloud;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Conquistas comemoradas moram na conta: a festa toca uma vez, não uma vez por aparelho. */
public class AchievementsStoreTest {

    private FakeCloud cloud;
    private String ana;
    private String bia;

    @Before
    public void setUp() {
        cloud = new FakeCloud();
        ana = cloud.createUser("ana@exemplo.com", "segredo#1");
        bia = cloud.createUser("bia@exemplo.com", "segredo#1");
        cloud.actAs(ana);
    }

    /** Progresso com as conquistas pedidas no alvo e o resto zerado. */
    private static List<AchievementProgress> unlocked(Achievement... achievements) {
        List<Achievement> wanted = Arrays.asList(achievements);
        List<AchievementProgress> progress = new ArrayList<>();
        for (Achievement a : Achievement.values()) {
            progress.add(new AchievementProgress(a, wanted.contains(a) ? a.target : 0));
        }
        return progress;
    }

    @Test
    public void firstCallOfAnAccount_isASilentBaseline() {
        AchievementsStore store = new AchievementsStore(cloud);
        assertFalse(store.isTracking(ana));

        List<Achievement> fresh = store.claimNewlyUnlocked(ana, unlocked(Achievement.FIRST_HERO,
                Achievement.GAMES_10));

        assertTrue("o que já era do jogador não vira festa", fresh.isEmpty());
        assertTrue(store.isTracking(ana));
        assertEquals(Arrays.asList("FIRST_HERO", "GAMES_10"), cloud.account(ana).celebrated);
    }

    @Test
    public void afterTheBaseline_eachAchievementIsClaimedOnce() {
        AchievementsStore store = new AchievementsStore(cloud);
        store.claimNewlyUnlocked(ana, unlocked(Achievement.FIRST_HERO));

        List<Achievement> fresh = store.claimNewlyUnlocked(ana,
                unlocked(Achievement.FIRST_HERO, Achievement.GAMES_10, Achievement.BEAT_WATCHER));

        assertEquals(Arrays.asList(Achievement.GAMES_10, Achievement.BEAT_WATCHER), fresh);
        assertEquals("a mesma conquista não cai duas vezes", Collections.emptyList(),
                store.claimNewlyUnlocked(ana, unlocked(Achievement.FIRST_HERO, Achievement.GAMES_10,
                        Achievement.BEAT_WATCHER)));
    }

    @Test
    public void celebratedOnOneDevice_isNotCelebratedAgainOnAnother() {
        new AchievementsStore(cloud).claimNewlyUnlocked(ana, unlocked(Achievement.FIRST_HERO));
        new AchievementsStore(cloud).claimNewlyUnlocked(ana, unlocked(Achievement.FIRST_HERO, Achievement.GAMES_10));

        AchievementsStore otherDevice = new AchievementsStore(cloud);
        assertTrue(otherDevice.isTracking(ana));
        assertTrue("outro aparelho não repete a festa",
                otherDevice.claimNewlyUnlocked(ana, unlocked(Achievement.FIRST_HERO, Achievement.GAMES_10)).isEmpty());
    }

    @Test
    public void accounts_areSeparated() {
        AchievementsStore store = new AchievementsStore(cloud);
        store.claimNewlyUnlocked(ana, unlocked(Achievement.FIRST_HERO));

        cloud.actAs(bia);
        assertFalse("outra conta ainda não tem marco zero", store.isTracking(bia));
        assertTrue(store.claimNewlyUnlocked(bia, unlocked(Achievement.FIRST_HERO)).isEmpty());
    }

    @Test
    public void withoutInternetAndNothingCached_nothingIsBaselined() {
        cloud.accountUnreachable = true;
        AchievementsStore store = new AchievementsStore(cloud);

        assertTrue(store.claimNewlyUnlocked(ana, unlocked(Achievement.FIRST_HERO)).isEmpty());
        assertNull("o marco zero espera a conta ser lida de verdade", cloud.account(ana));

        cloud.accountUnreachable = false;
        store.claimNewlyUnlocked(ana, unlocked(Achievement.FIRST_HERO));
        assertTrue(store.isTracking(ana));
    }
}
