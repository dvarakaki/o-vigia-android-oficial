package com.ovigia.app.social;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.profile.FakeProfileImages;
import com.ovigia.app.social.FriendsUiState.Message;
import com.ovigia.app.social.FriendsUiState.Status;
import com.ovigia.app.util.Event;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Aba de amigos do começo (conectar) ao fim (pedidos e amigos), com o servidor falso. */
public class FriendsViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;
    private FakeSocialBackend backend;
    private AccountStore accounts;
    private SocialRepository repository;

    @Before
    public void setUp() {
        backend = new FakeSocialBackend();
        accounts = new AccountStore(() -> tmp.getRoot().toPath().resolve("accounts.json").toFile(), 1_000);
        CollectionStore collection = new CollectionStore(() -> tmp.getRoot().toPath().resolve("collection.json").toFile());
        LearningStore learning = new LearningStore(() -> tmp.getRoot().toPath().resolve("learning.json").toFile(), direct);
        repository = new SocialRepository(backend, accounts, collection, learning,
                new FakeProfileImages(tmp.getRoot()), () -> null, direct, () -> 1L);
        accounts.signUp("Davi Souza", "davi@exemplo.com", "segredo#1");
    }

    private FriendsViewModel newViewModel() {
        return new FriendsViewModel(repository, direct, direct);
    }

    @Test
    public void signInThatCouldNotConnect_showsOffline_thenConnectsByItself_neverAskingThePassword() {
        CollectionStore collection = new CollectionStore(() -> tmp.getRoot().toPath().resolve("c2.json").toFile());
        LearningStore learning = new LearningStore(() -> tmp.getRoot().toPath().resolve("l2.json").toFile(), direct);
        repository = new SocialRepository(backend, accounts, collection, learning,
                new FakeProfileImages(tmp.getRoot()), () -> null, direct, () -> 1L, new FakeCredentialVault());
        backend.offline = true;
        repository.resumeAfterSignIn(accounts.currentAccount(), "segredo#1");

        FriendsViewModel vm = newViewModel();
        vm.start();
        assertEquals("sem rede: 'sem conexão', não a tela da senha",
                FriendsUiState.Status.ERROR, vm.state().getValue().status);
        assertEquals(SocialException.Error.OFFLINE, vm.state().getValue().error);

        backend.offline = false;
        vm.refresh();
        assertEquals("a rede voltou: conectou sozinho, falta só o @usuario",
                FriendsUiState.Status.NEEDS_USERNAME, vm.state().getValue().status);
    }

    private static Message lastMessage(FriendsViewModel vm) {
        Event<Message> event = vm.messages().getValue();
        return event == null ? null : event.consume();
    }

    private FriendsViewModel onlineAs(String username) {
        FriendsViewModel vm = newViewModel();
        vm.start();
        vm.connect("segredo#1");
        vm.claimUsername(username);
        return vm;
    }

    @Test
    public void newAccount_goesFromPasswordToUsernameToReady() {
        FriendsViewModel vm = newViewModel();
        vm.start();
        assertEquals(Status.NEEDS_CONNECTION, vm.state().getValue().status);

        vm.connect("segredo#1");
        assertEquals(Status.NEEDS_USERNAME, vm.state().getValue().status);
        assertEquals("sugere a partir do nome", "davisouza", vm.state().getValue().suggestedUsername);

        vm.claimUsername("davi");
        FriendsUiState state = vm.state().getValue();
        assertEquals(Status.READY, state.status);
        assertEquals("davi", state.me.username);
        assertTrue(state.hub.friends.isEmpty());
        assertFalse(state.working);
    }

    @Test
    public void wrongPassword_staysOnTheStepWithTheError() {
        FriendsViewModel vm = newViewModel();
        vm.start();
        vm.connect("errada");

        FriendsUiState state = vm.state().getValue();
        assertEquals(Status.NEEDS_CONNECTION, state.status);
        assertEquals(SocialException.Error.WRONG_PASSWORD, state.error);
        assertFalse(state.working);
    }

    @Test
    public void unexpectedFailureWhileLoading_showsTheErrorStepInsteadOfCrashing() {
        accounts.linkCloud(accounts.currentAccount().id, "uid-1", "davi@exemplo.com");
        backend.sessionFailure = new IllegalStateException("falha ao ler a sessão online");
        FriendsViewModel vm = newViewModel();
        vm.start();

        FriendsUiState state = vm.state().getValue();
        assertEquals(Status.ERROR, state.status);
        assertEquals(SocialException.Error.UNKNOWN, state.error);

        backend.sessionFailure = null;
        vm.refresh();
        assertEquals("tentar de novo volta ao fluxo normal", Status.NEEDS_CONNECTION, vm.state().getValue().status);
    }

    @Test
    public void offlineWhileLoading_showsTheErrorStep_andRetryRecovers() {
        onlineAs("davi");
        backend.offline = true;
        FriendsViewModel vm = newViewModel();
        vm.start();
        assertEquals(Status.ERROR, vm.state().getValue().status);
        assertEquals(SocialException.Error.OFFLINE, vm.state().getValue().error);

        backend.offline = false;
        vm.refresh();
        assertEquals(Status.READY, vm.state().getValue().status);
    }

    @Test
    public void notConfigured_andSignedOut() {
        backend.configured = false;
        FriendsViewModel vm = newViewModel();
        vm.start();
        assertEquals(Status.NOT_CONFIGURED, vm.state().getValue().status);

        backend.configured = true;
        accounts.signOut();
        FriendsViewModel other = newViewModel();
        other.start();
        assertEquals(Status.SIGNED_OUT, other.state().getValue().status);
    }

    @Test
    public void search_findsPlayers_andShowsTheRelationship() {
        UserCard ana = backend.registerOther("ana@exemplo.com", "senha-ana", "ana", "Ana");
        FriendsViewModel vm = onlineAs("davi");

        vm.search("@nobody");
        assertEquals(SocialException.Error.NOT_FOUND, vm.state().getValue().search.error);

        vm.search("x");
        assertEquals(SocialException.Error.USERNAME_INVALID, vm.state().getValue().search.error);

        vm.search("davi");
        assertEquals(FriendsHub.Relationship.SELF, vm.state().getValue().search.relationship);

        vm.search("ANA");
        FriendsUiState.Search search = vm.state().getValue().search;
        assertEquals(ana.uid, search.result.uid);
        assertEquals(FriendsHub.Relationship.NONE, search.relationship);
        assertNull(search.error);

        vm.sendRequest(search.result);
        assertEquals(Message.REQUEST_SENT, lastMessage(vm));
        FriendsUiState state = vm.state().getValue();
        assertEquals("a busca acompanha a lista nova", FriendsHub.Relationship.REQUEST_SENT, state.search.relationship);
        assertEquals(1, state.hub.outgoing.size());
        assertTrue(state.busyUids.isEmpty());

        vm.cancel(ana.uid);
        assertEquals(Message.REQUEST_CANCELED, lastMessage(vm));
        assertTrue(vm.state().getValue().hub.outgoing.isEmpty());
        assertEquals(FriendsHub.Relationship.NONE, vm.state().getValue().search.relationship);
    }

    @Test
    public void incomingRequest_acceptMakesFriends_declineRemoves() {
        UserCard ana = backend.registerOther("ana@exemplo.com", "senha-ana", "ana", "Ana");
        UserCard bia = backend.registerOther("bia@exemplo.com", "senha-bia", "bia", "Bia");
        FriendsViewModel vm = onlineAs("davi");
        UserCard me = vm.state().getValue().me;
        backend.actAs(ana.uid);
        try {
            backend.sendRequest(ana, me);
            backend.actAs(bia.uid);
            backend.sendRequest(bia, me);
        } catch (SocialException e) {
            throw new AssertionError(e);
        }
        backend.actAs(me.uid);

        vm.refresh();
        assertEquals(2, vm.state().getValue().hub.incoming.size());

        vm.accept(ana.uid);
        assertEquals(Message.BECAME_FRIENDS, lastMessage(vm));
        vm.decline(bia.uid);
        assertEquals(Message.REQUEST_DECLINED, lastMessage(vm));

        FriendsUiState state = vm.state().getValue();
        assertTrue(state.hub.incoming.isEmpty());
        assertEquals(1, state.hub.friends.size());
        assertEquals("Ana", state.hub.friends.get(0).name);
        assertTrue(backend.areFriends(me.uid, ana.uid));
        assertFalse(backend.areFriends(me.uid, bia.uid));
    }

    @Test
    public void actionOffline_keepsTheListAndWarns() {
        UserCard ana = backend.registerOther("ana@exemplo.com", "senha-ana", "ana", "Ana");
        FriendsViewModel vm = onlineAs("davi");
        backend.offline = true;

        vm.sendRequest(ana);

        assertEquals(Message.ACTION_FAILED_OFFLINE, lastMessage(vm));
        FriendsUiState state = vm.state().getValue();
        assertEquals(Status.READY, state.status);
        assertTrue(state.busyUids.isEmpty());
    }

    @Test
    public void startIsIdempotent() {
        FriendsViewModel vm = onlineAs("davi");
        int published = backend.publishCount;
        vm.start();
        assertEquals("não recarrega nem republica ao voltar para a tela", published, backend.publishCount);
    }
}
