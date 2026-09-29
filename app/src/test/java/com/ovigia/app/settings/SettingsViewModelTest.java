package com.ovigia.app.settings;

import com.ovigia.app.cloud.FakeCloud;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.learning.LearningStore.Outcome;
import com.ovigia.app.settings.SettingsUiState.Message;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;
    private SettingsStore settings;
    private LearningStore learningStore;
    private AccountStore accountStore;
    private String accountId;
    private FakeCache cache;
    private final FakeCloud cloud = new FakeCloud();

    @Before
    public void setUp() {
        settings = new SettingsStore(() -> new File(tmp.getRoot(), "settings.json"), direct);
        learningStore = new LearningStore(cloud);
        accountStore = new AccountStore(cloud);
        accountStore.signUp("Davi", "davi@exemplo.com", "segredo#1");
        accountId = accountStore.currentAccount().id;
        cache = new FakeCache(2048);
    }

    private SettingsViewModel newViewModel(Executor io) {
        return new SettingsViewModel(settings, learningStore, accountStore, cache, io, direct);
    }

    @Test
    public void start_loadsPreferencesAndCacheSize() {
        settings.setKeepScreenOn(false);
        SettingsViewModel vm = newViewModel(direct);
        assertFalse(vm.state().getValue().loaded);

        vm.start();

        SettingsUiState state = vm.state().getValue();
        assertTrue(state.loaded);
        assertTrue(state.hapticFeedback);
        assertFalse(state.keepScreenOn);
        assertEquals(2048, state.cacheBytes);
    }

    @Test
    public void toggles_areSavedToTheStore() {
        SettingsViewModel vm = newViewModel(direct);
        vm.start();

        vm.setHapticFeedback(false);
        vm.setKeepScreenOn(false);

        assertFalse(vm.state().getValue().hapticFeedback);
        assertFalse(vm.state().getValue().keepScreenOn);
        assertFalse(settings.hapticFeedback());
        assertFalse(settings.keepScreenOn());
    }

    @Test
    public void hapticStrength_isLoaded_andSavedToTheStore() {
        settings.setHapticLevel(2);
        SettingsViewModel vm = newViewModel(direct);
        vm.start();
        assertEquals(2, vm.state().getValue().hapticLevel);

        vm.setHapticLevel(10);

        assertEquals(10, vm.state().getValue().hapticLevel);
        assertEquals(10, settings.hapticStrength().level);
    }

    @Test
    public void hapticStrength_withVibrationOff_staysWhereItWas() {
        SettingsViewModel vm = newViewModel(direct);
        vm.start();
        vm.setHapticFeedback(false);

        vm.setHapticLevel(10);

        assertEquals(HapticStrength.DEFAULT_LEVEL, vm.state().getValue().hapticLevel);
        assertEquals(HapticStrength.DEFAULT_LEVEL, settings.hapticStrength().level);

        vm.setHapticFeedback(true);
        assertEquals("religar volta no nível guardado", HapticStrength.DEFAULT_LEVEL,
                vm.state().getValue().hapticLevel);
    }

    @Test
    public void toggles_beforeLoading_areIgnored() {
        SettingsViewModel vm = newViewModel(direct);

        vm.setHapticFeedback(false);
        vm.setHapticLevel(10);

        assertTrue(settings.hapticFeedback());
        assertEquals(HapticStrength.DEFAULT_LEVEL, settings.hapticStrength().level);
    }

    @Test
    public void clearCache_showsProgress_thenNewSizeAndMessage() {
        QueueExecutor io = new QueueExecutor();
        SettingsViewModel vm = newViewModel(io);
        vm.start();
        io.runAll();

        vm.clearCache();
        assertTrue(vm.state().getValue().clearingCache);
        vm.clearCache();
        io.runAll();

        assertEquals("toque repetido não limpa duas vezes", 1, cache.clears);
        assertFalse(vm.state().getValue().clearingCache);
        assertEquals(0, vm.state().getValue().cacheBytes);
        assertEquals(Message.CACHE_CLEARED, vm.messages().getValue().peek());
    }

    @Test
    public void forgetLearning_resetsStatsAndWarns() {
        learningStore.recordGame(accountId, 1, Collections.emptyList(), Outcome.ENGINE_GUESSED);
        SettingsViewModel vm = newViewModel(direct);

        vm.forgetLearning();

        assertEquals(0, learningStore.stats(accountId).gamesPlayed);
        assertEquals(Message.LEARNING_FORGOTTEN, vm.messages().getValue().peek());
    }

    @Test
    public void forgetLearning_withoutSession_doesNothing() {
        learningStore.recordGame(accountId, 1, Collections.emptyList(), Outcome.ENGINE_GUESSED);
        accountStore.signOut();
        SettingsViewModel vm = newViewModel(direct);

        vm.forgetLearning();

        assertEquals("sem sessão, o aprendizado da conta antiga fica intacto",
                1, learningStore.stats(accountId).gamesPlayed);
    }

    private static final class FakeCache implements AppCache {
        long size;
        int clears = 0;

        FakeCache(long size) {
            this.size = size;
        }

        @Override
        public long sizeBytes() {
            return size;
        }

        @Override
        public void clear() {
            clears++;
            size = 0;
        }
    }

    /** Executor de I/O controlado pelo teste: permite ver o estado "limpando" antes do fim. */
    private static final class QueueExecutor implements Executor {
        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        void runAll() {
            while (!pending.isEmpty()) pending.remove(0).run();
        }
    }
}
