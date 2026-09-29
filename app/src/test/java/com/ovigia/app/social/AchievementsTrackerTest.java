package com.ovigia.app.social;

import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.cloud.PlayerBackend;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.util.Event;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** O ciclo completo: coleção e partidas em disco temporário viram conquistas comemoradas. */
public class AchievementsTrackerTest {

    private static final RosterCatalog ROSTER = RosterCatalog.parse(new StringReader("{\"characters\":["
            + "{\"id\":1,\"teams\":[\"avengers\"],\"powers\":[\"voo\"],\"villain\":0.1},"
            + "{\"id\":2,\"teams\":[\"avengers\"],\"powers\":[\"voo\"],\"villain\":0.1}"
            + "]}"));

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;
    private final FakeCloud cloud = new FakeCloud();
    private AccountStore accounts;
    private CollectionStore collection;
    private LearningStore learning;
    private AchievementsStore store;
    private AchievementsTracker tracker;
    private String accountId;

    @Before
    public void setUp() {
        accounts = new AccountStore(cloud);
        collection = new CollectionStore(cloud);
        learning = new LearningStore(cloud);
        store = new AchievementsStore(cloud);
        tracker = new AchievementsTracker(accounts, collection, learning, store, () -> ROSTER, direct, direct);
        accounts.signUp("Davi", "davi@exemplo.com", "segredo#1");
        accountId = accounts.currentAccount().id;
    }

    private List<Achievement> lastEvent() {
        Event<List<Achievement>> event = tracker.unlocked().getValue();
        return event == null ? null : event.peek();
    }

    @Test
    public void withoutASession_nothingIsPublished() {
        accounts.signOut();

        tracker.sync();

        assertNull(lastEvent());
    }

    @Test
    public void theFirstSyncIsTheBaseline_andOnlyWhatComesAfterItIsCelebrated() {
        collection.save(accountId, 1, "Homem de Ferro", null);
        tracker.sync();
        assertNull("a coleção que já existia não rende festa", lastEvent());

        collection.save(accountId, 2, "Thor", null);
        tracker.sync();

        assertNull("nenhuma conquista nova: dois heróis ainda não fecham nada", lastEvent());

        // Outro aparelho jogou 12 partidas nesse meio-tempo.
        cloud.importLearning(accountId, new PlayerBackend.Learning(12, 1, new HashMap<>(), new HashMap<>()),
                new ArrayList<>());
        learning.invalidate();
        tracker.sync();

        assertEquals(Arrays.asList(Achievement.BEAT_WATCHER_10, Achievement.GAMES_10, Achievement.BEAT_WATCHER),
                lastEvent());
    }

    @Test
    public void eachAchievementIsCelebratedOnlyOnce() {
        tracker.sync();
        collection.save(accountId, 1, "Homem de Ferro", null);
        tracker.sync();
        assertEquals(Collections.singletonList(Achievement.FIRST_HERO), lastEvent());

        tracker.unlocked().getValue().consume();
        tracker.sync();

        assertNull("o segundo sync não repete a conquista", tracker.unlocked().getValue().consume());
    }

    @Test
    public void headline_putsTheRarestFirst_andCutsTheTail() {
        List<Achievement> fresh = AchievementsTracker.headline(Arrays.asList(
                Achievement.FIRST_HERO, Achievement.HEROES_25, Achievement.GAMES_50,
                Achievement.AVENGERS_5, Achievement.GAMES_10));

        assertEquals(AchievementsTracker.MAX_PER_BURST, fresh.size());
        assertEquals(Achievement.GAMES_50, fresh.get(0));
        assertEquals("entre as raras vale a ordem do enum", Achievement.HEROES_25, fresh.get(1));
        assertEquals(Achievement.AVENGERS_5, fresh.get(2));
        assertTrue("uma leva pequena passa inteira",
                AchievementsTracker.headline(Collections.singletonList(Achievement.FIRST_HERO))
                        .equals(Collections.singletonList(Achievement.FIRST_HERO)));
    }
}
