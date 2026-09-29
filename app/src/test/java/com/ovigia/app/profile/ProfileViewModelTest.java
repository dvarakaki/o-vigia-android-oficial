package com.ovigia.app.profile;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.CharacterRepository;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.learning.LearningStore.Outcome;
import com.ovigia.app.social.Achievement;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Perfil montado a partir da conta online falsa e de um elenco falso. */
public class ProfileViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    private final Executor direct = Runnable::run;
    private FakeRepository repository;
    private LearningStore learningStore;
    private AccountStore accountStore;
    private CollectionStore collectionStore;
    private FakeCloud cloud;

    @Before
    public void setUp() {
        repository = new FakeRepository();
        cloud = new FakeCloud();
        learningStore = new LearningStore(cloud);
        accountStore = new AccountStore(cloud);
        collectionStore = new CollectionStore(cloud);
        accountStore.signUp("Davi", "davi@exemplo.com", "segredo#1");
    }

    private ProfileViewModel newViewModel() {
        return new ProfileViewModel(repository, learningStore, accountStore, collectionStore, direct, direct);
    }

    @Test
    public void identity_includesBioAndImages_andReloadPicksUpEdits() {
        ProfileViewModel vm = newViewModel();
        vm.start();
        assertNull(vm.state().getValue().accountBio);
        assertNull(vm.state().getValue().banner);

        accountStore.updateProfile("Davi A.", "Fã do Wolverine");
        accountStore.setImage(AccountStore.ImageKind.BANNER, "banner-base64");
        vm.reload();

        ProfileUiState state = vm.state().getValue();
        assertEquals("Davi A.", state.accountName);
        assertEquals("Fã do Wolverine", state.accountBio);
        assertEquals("banner-base64", state.banner);
    }

    @Test
    public void withoutSession_asksForLogin() {
        accountStore.signOut();
        ProfileViewModel vm = newViewModel();
        vm.start();

        assertEquals(ProfileUiState.Status.SIGNED_OUT, vm.state().getValue().status);
        assertEquals(0, repository.loads);
    }

    @Test
    public void signOut_endsSessionAndPublishesSignedOut() {
        ProfileViewModel vm = newViewModel();
        vm.start();
        assertEquals("Davi", vm.state().getValue().accountName);

        vm.signOut();

        assertEquals(ProfileUiState.Status.SIGNED_OUT, vm.state().getValue().status);
        assertNull(accountStore.currentAccount());
    }

    @Test
    public void collection_isShownForTheSignedInAccount() {
        String id = accountStore.currentAccount().id;
        collectionStore.save(id, 2, "Personagem 2", "img-2");

        ProfileViewModel vm = newViewModel();
        vm.start();

        ProfileUiState state = vm.state().getValue();
        assertEquals("davi@exemplo.com", state.accountEmail);
        assertEquals(1, state.collection.size());
        assertEquals("Personagem 2", state.collection.get(0).name);
    }

    @Test
    public void noGames_showsEmptyProfileWithoutLoadingTheRoster() {
        ProfileViewModel vm = newViewModel();
        assertTrue(vm.state().getValue().isLoading());

        vm.start();

        ProfileUiState state = vm.state().getValue();
        assertFalse(state.isLoading());
        assertTrue(state.hasNoGames());
        assertEquals(PlayerRank.NEWCOMER, state.rank);
        assertEquals("sem histórico não há por que tocar na rede", 0, repository.loads);
    }

    @Test
    public void games_fillStatsFavoritesAndRecentWithRosterNames() {
        String id = accountStore.currentAccount().id;
        learningStore.recordGame(id, 1, Collections.emptyList(), Outcome.ENGINE_GUESSED);
        learningStore.recordGame(id, 2, Collections.emptyList(), Outcome.REVEALED_AFTER_LOSS);
        learningStore.recordGame(id, 2, Collections.emptyList(), Outcome.ENGINE_GUESSED);
        learningStore.recordLoss(id);
        learningStore.recordGame(id, 1, Collections.emptyList(), Outcome.PICKED_FROM_ALTERNATIVES);

        ProfileViewModel vm = newViewModel();
        vm.start();
        vm.start();

        ProfileUiState state = vm.state().getValue();
        assertEquals("start() é idempotente", 1, repository.loads);
        assertEquals(5, state.gamesPlayed);
        assertEquals(2, state.engineWins);
        assertEquals(3, state.playerWins);
        assertEquals(40, state.engineWinPercent);
        assertEquals(2, state.distinctCharacters);
        assertEquals(PlayerRank.CHALLENGER, state.rank);

        assertEquals(2, state.favorites.size());
        assertEquals("Personagem 1", state.favorites.get(0).name);
        assertEquals(2, state.favorites.get(0).timesPicked);

        assertEquals(4, state.recentGames.size());
        assertEquals("Personagem 1", state.recentGames.get(0).name);
        assertEquals(Outcome.PICKED_FROM_ALTERNATIVES, state.recentGames.get(0).outcome);
        assertEquals("thumb-1", state.recentGames.get(0).thumbnailUrl);
    }

    @Test
    public void rosterFailure_stillShowsProfileWithoutNames() {
        learningStore.recordGame(accountStore.currentAccount().id, 1, Collections.emptyList(), Outcome.ENGINE_GUESSED);
        repository.failWith = CharacterRepository.LoadError.NO_CONNECTION;

        ProfileViewModel vm = newViewModel();
        vm.start();

        ProfileUiState state = vm.state().getValue();
        assertFalse(state.isLoading());
        assertEquals(1, state.gamesPlayed);
        assertEquals(1, state.favorites.size());
        assertNull(state.favorites.get(0).name);
        assertNull(state.recentGames.get(0).thumbnailUrl);
    }

    @Test
    public void achievementsAndUsername_areShownOnTheOwnProfile() {
        String id = accountStore.currentAccount().id;
        collectionStore.save(id, 1, "Avenger", "img-1");
        accountStore.setUsername(id, "davi");
        learningStore.recordLoss(id);
        RosterCatalog roster = RosterCatalog.parse(new StringReader(
                "{\"characters\":[{\"id\":1,\"teams\":[\"avengers\"],\"powers\":[\"voo\"],\"villain\":0.9}]}"));

        ProfileViewModel vm = new ProfileViewModel(repository, learningStore, accountStore, collectionStore,
                () -> roster, direct, direct);
        vm.start();

        ProfileUiState state = vm.state().getValue();
        assertEquals("davi", state.accountUsername);
        assertEquals(Achievement.values().length, state.achievements.size());
        assertTrue(state.achievements.get(Achievement.FIRST_HERO.ordinal()).isUnlocked());
        assertTrue(state.achievements.get(Achievement.BEAT_WATCHER.ordinal()).isUnlocked());
        assertEquals(1, state.achievements.get(Achievement.AVENGERS_5.ordinal()).current);
        assertEquals(1, state.achievements.get(Achievement.VILLAINS_5.ordinal()).current);
    }

    @Test
    public void rank_followsGamesPlayed() {
        assertEquals(PlayerRank.NEWCOMER, PlayerRank.forGames(0));
        assertEquals(PlayerRank.CURIOUS, PlayerRank.forGames(4));
        assertEquals(PlayerRank.CHALLENGER, PlayerRank.forGames(5));
        assertEquals(PlayerRank.VETERAN, PlayerRank.forGames(49));
        assertEquals(PlayerRank.LEGEND, PlayerRank.forGames(500));
        assertEquals(PlayerRank.VETERAN, PlayerRank.CHALLENGER.next());
        assertNull(PlayerRank.LEGEND.next());
    }

    private static final class FakeRepository implements CharacterRepository {
        int loads = 0;
        LoadError failWith = null;

        @Override
        public void loadCharacters(Callback callback) {
            loads++;
            if (failWith != null) {
                callback.onError(failWith);
                return;
            }
            List<CharacterProfile> cast = new ArrayList<>();
            for (int id = 1; id <= 3; id++) {
                cast.add(new CharacterProfile(id, "Personagem " + id, "img-" + id, "thumb-" + id,
                        new HashMap<>(), 0, false));
            }
            callback.onSuccess(cast, new HashMap<>());
        }
    }
}
