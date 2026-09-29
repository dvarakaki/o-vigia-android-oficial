package com.ovigia.app.social;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.profile.PlayerRank;
import com.ovigia.app.social.FriendProfileUiState.Status;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FriendProfileViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    private final Executor direct = Runnable::run;
    private FakeCloud backend;
    private SocialRepository repository;
    private CollectionStore collection;
    private AccountStore accounts;
    private UserCard me;
    private UserCard ana;

    @Before
    public void setUp() throws SocialException {
        backend = new FakeCloud();
        accounts = new AccountStore(backend);
        collection = new CollectionStore(backend);
        LearningStore learning = new LearningStore(backend);
        repository = new SocialRepository(backend, accounts, collection, learning, () -> null, direct, () -> 1L);
        accounts.signUp("Davi", "davi@exemplo.com", "segredo#1");
        me = repository.claimUsername("davi").card;

        ana = backend.registerOther("ana@exemplo.com", "senha-ana", "ana", "Ana");
        List<PublicProfile.Hero> heroes = new ArrayList<>();
        heroes.add(new PublicProfile.Hero(1455, "Iron Man", "http://img/ironman.jpg", 20L));
        heroes.add(new PublicProfile.Hero(1442, "Captain America", "http://img/cap.jpg", 10L));
        List<AchievementProgress> achievements = Achievements.evaluate(Arrays.asList(1455, 1442), null, 12, 4);
        backend.putProfile(new PublicProfile(ana, "Fã da Tempestade", null, 12, 8, 9, heroes, achievements, 5L));
        backend.actAs(ana.uid);
        backend.sendRequest(ana, me);
        backend.actAs(me.uid);
        backend.acceptRequest(ana.uid);
    }

    @Test
    public void loadsTheFriendsProfile_andKnowsWhichHeroesAreAlsoMine() {
        collection.save(accounts.currentAccount().id, 1455, "Iron Man", "http://img/ironman.jpg");
        FriendProfileViewModel vm = new FriendProfileViewModel(ana.uid, repository, direct, direct);
        vm.start();

        FriendProfileUiState state = vm.state().getValue();
        assertEquals(Status.READY, state.status);
        assertEquals("Fã da Tempestade", state.profile.bio);
        assertEquals(2, state.profile.heroes.size());
        assertEquals(4, state.profile.playerWins());
        assertTrue(state.myUnlockedIds.contains(1455));
        assertFalse(state.myUnlockedIds.contains(1442));
        assertEquals(PlayerRank.CHALLENGER, state.rank());
        assertTrue(state.profile.achievements.get(Achievement.GAMES_10.ordinal()).isUnlocked());
    }

    @Test
    public void notFriendsAnymore_showsPermissionError() throws SocialException {
        backend.removeFriend(ana.uid);
        FriendProfileViewModel vm = new FriendProfileViewModel(ana.uid, repository, direct, direct);
        vm.start();

        assertEquals(Status.ERROR, vm.state().getValue().status);
        assertEquals(SocialException.Error.PERMISSION_DENIED, vm.state().getValue().error);
    }

    @Test
    public void unexpectedFailure_showsTheErrorInsteadOfCrashing() {
        backend.operationFailure = new IllegalStateException("SDK sem inicializar");
        FriendProfileViewModel vm = new FriendProfileViewModel(ana.uid, repository, direct, direct);
        vm.start();
        assertEquals(Status.ERROR, vm.state().getValue().status);
        assertEquals(SocialException.Error.UNKNOWN, vm.state().getValue().error);

        backend.operationFailure = null;
        vm.retry();
        assertEquals(Status.READY, vm.state().getValue().status);
    }

    @Test
    public void offline_canRetry() {
        backend.offline = true;
        FriendProfileViewModel vm = new FriendProfileViewModel(ana.uid, repository, direct, direct);
        vm.start();
        assertEquals(SocialException.Error.OFFLINE, vm.state().getValue().error);

        backend.offline = false;
        vm.retry();
        assertEquals(Status.READY, vm.state().getValue().status);
    }

    @Test
    public void removeFriend_endsTheFriendshipOnBothSides() {
        FriendProfileViewModel vm = new FriendProfileViewModel(ana.uid, repository, direct, direct);
        vm.start();
        vm.removeFriend();

        assertEquals(Status.REMOVED, vm.state().getValue().status);
        assertFalse(backend.areFriends(me.uid, ana.uid));
    }

    @Test
    public void removeFriendOffline_keepsTheProfileAndReportsIt() {
        FriendProfileViewModel vm = new FriendProfileViewModel(ana.uid, repository, direct, direct);
        vm.start();
        backend.offline = true;
        vm.removeFriend();

        assertEquals(Status.READY, vm.state().getValue().status);
        assertFalse(vm.state().getValue().removing);
        assertEquals(SocialException.Error.OFFLINE, vm.removeFailures().getValue().consume());
        assertTrue(backend.areFriends(me.uid, ana.uid));
    }
}
