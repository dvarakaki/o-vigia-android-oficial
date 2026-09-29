package com.ovigia.app.social;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.profile.FakeProfileImages;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Ligação da conta local com os amigos online, contra o servidor falso em memória. */
public class SocialRepositoryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;
    private FakeSocialBackend backend;
    private AccountStore accounts;
    private CollectionStore collection;
    private LearningStore learning;
    private FakeCredentialVault vault;
    private SocialRepository repository;

    @Before
    public void setUp() {
        backend = new FakeSocialBackend();
        accounts = new AccountStore(() -> tmp.getRoot().toPath().resolve("accounts.json").toFile(), 1_000);
        collection = new CollectionStore(() -> tmp.getRoot().toPath().resolve("collection.json").toFile());
        learning = new LearningStore(() -> tmp.getRoot().toPath().resolve("learning.json").toFile(), direct);
        vault = new FakeCredentialVault();
        repository = new SocialRepository(backend, accounts, collection, learning,
                new FakeProfileImages(tmp.getRoot()), () -> null, direct, () -> 42L, vault);
        accounts.signUp("Davi", "davi@exemplo.com", "segredo#1");
    }

    private SocialRepository.Session readyAs(String username) throws SocialException {
        repository.connect("segredo#1");
        return repository.claimUsername(username);
    }

    @Test
    public void session_walksTheStepsToOnline() throws SocialException {
        assertEquals(SocialRepository.Status.NEEDS_CONNECTION, repository.session().status);

        SocialRepository.Session connected = repository.connect("segredo#1");
        assertEquals(SocialRepository.Status.NEEDS_USERNAME, connected.status);
        assertTrue("conta online criada com o mesmo e-mail", backend.accountExists("davi@exemplo.com"));
        assertNotNull(accounts.currentAccount().cloudUid);

        SocialRepository.Session ready = repository.claimUsername("@Davi");
        assertEquals(SocialRepository.Status.READY, ready.status);
        assertEquals("davi", ready.card.username);
        assertEquals("davi", accounts.currentAccount().username);
        assertEquals("publica o perfil logo depois de escolher o @usuario", 1, backend.publishCount);
    }

    @Test
    public void connect_requiresTheLocalPassword() {
        try {
            repository.connect("outra-senha");
            fail("conectou com senha errada");
        } catch (SocialException e) {
            assertEquals(SocialException.Error.WRONG_PASSWORD, e.error);
        }
        assertFalse("nada foi criado online", backend.accountExists("davi@exemplo.com"));
    }

    @Test
    public void withoutServer_orSignedOut_sessionSaysSo() {
        backend.configured = false;
        assertEquals(SocialRepository.Status.NOT_CONFIGURED, repository.session().status);
        backend.configured = true;
        accounts.signOut();
        assertEquals(SocialRepository.Status.SIGNED_OUT, repository.session().status);
    }

    @Test
    public void takenUsername_isRefused() throws SocialException {
        backend.registerOther("ana@exemplo.com", "senha-ana", "ana", "Ana");
        repository.connect("segredo#1");
        try {
            repository.claimUsername("ana");
            fail("reservou o @usuario de outra conta");
        } catch (SocialException e) {
            assertEquals(SocialException.Error.USERNAME_TAKEN, e.error);
        }
        assertEquals(SocialRepository.Status.NEEDS_USERNAME, repository.session().status);
    }

    @Test
    public void invalidUsername_neverReachesTheServer() throws SocialException {
        repository.connect("segredo#1");
        try {
            repository.claimUsername("jo");
            fail();
        } catch (SocialException e) {
            assertEquals(SocialException.Error.USERNAME_INVALID, e.error);
        }
    }

    @Test
    public void publishedProfile_carriesHeroesStatsAndAchievements() throws SocialException {
        String id = accounts.currentAccount().id;
        collection.save(id, 1455, "Iron Man", "http://img/ironman.jpg");
        accounts.updateProfile("Davi", "Fã do Wolverine", "davi@exemplo.com", null);
        learning.recordLoss(id);
        readyAs("davi");

        PublicProfile published = backend.lastPublished;
        assertEquals("Fã do Wolverine", published.bio);
        assertEquals(1, published.heroes.size());
        assertEquals("Iron Man", published.heroes.get(0).name);
        assertEquals(1, published.gamesPlayed);
        assertEquals(1, published.playerWins());
        assertEquals(42L, published.updatedAt);
        assertTrue(published.achievements.get(Achievement.FIRST_HERO.ordinal()).isUnlocked());
        assertTrue(published.achievements.get(Achievement.BEAT_WATCHER.ordinal()).isUnlocked());
    }

    @Test
    public void connectingOnAnotherDevice_bringsTheHeroesAndUsername() throws SocialException {
        readyAs("davi");
        String uid = accounts.currentAccount().cloudUid;
        List<PublicProfile.Hero> heroes = new ArrayList<>();
        heroes.add(new PublicProfile.Hero(1009610, "Spider-Man", "http://img/spidey.jpg", 1000L));
        backend.putProfile(new PublicProfile(new UserCard(uid, "davi", "Davi", null), null, null, 0, 0, 0,
                heroes, Achievements.fromPublished(null), 1L));

        // Outro aparelho: conta local nova com o mesmo e-mail e senha.
        AccountStore otherDevice = new AccountStore(() -> tmp.getRoot().toPath().resolve("other.json").toFile(), 1_000);
        otherDevice.signUp("Davi", "davi@exemplo.com", "segredo#1");
        backend.signOut();
        SocialRepository other = new SocialRepository(backend, otherDevice, collection, learning,
                new FakeProfileImages(tmp.getRoot()), () -> null, direct, () -> 43L);

        SocialRepository.Session session = other.connect("segredo#1");

        assertEquals(SocialRepository.Status.READY, session.status);
        assertEquals("davi", otherDevice.currentAccount().username);
        List<CollectionStore.Entry> imported = collection.list(otherDevice.currentAccount().id);
        assertEquals(1, imported.size());
        assertEquals(1009610, imported.get(0).characterId);
        assertEquals("mantém a data original do desbloqueio", 1000L, imported.get(0).savedAt);
    }

    @Test
    public void resumeAfterSignIn_opensTheOnlineSession_soFriendsNeverAskThePasswordAgain() throws SocialException {
        // Conta que nunca conectou: o login já cria a conta online e só falta o @usuario.
        repository.resumeAfterSignIn(accounts.currentAccount(), "segredo#1");
        assertTrue("o login cria a conta online", backend.accountExists("davi@exemplo.com"));
        assertEquals(SocialRepository.Status.NEEDS_USERNAME, repository.session().status);

        repository.claimUsername("davi");
        backend.signOut();
        assertEquals(SocialRepository.Status.NEEDS_CONNECTION, repository.session().status);

        repository.resumeAfterSignIn(accounts.currentAccount(), "segredo#1");
        assertEquals(SocialRepository.Status.READY, repository.session().status);
    }

    @Test
    public void signInWithoutNetwork_finishesTheConnectionLater_withoutAskingThePasswordAgain() throws SocialException {
        readyAs("davi");
        backend.signOut();
        backend.offline = true;

        repository.resumeAfterSignIn(accounts.currentAccount(), "segredo#1");
        assertEquals(SocialRepository.Status.NEEDS_CONNECTION, repository.session().status);
        assertFalse("a senha digitada no login fica guardada para terminar depois", vault.isEmpty());

        backend.offline = false;
        SocialRepository.Session resumed = repository.resumePending();

        assertEquals(SocialRepository.Status.READY, resumed.status);
        assertTrue("conectou: a sessão do servidor assume e o cofre esvazia", vault.isEmpty());
    }

    @Test
    public void resumePending_stillOffline_saysOffline_andKeepsThePassword() {
        repository.resumeAfterSignIn(accounts.currentAccount(), "segredo#1");
        backend.signOut();
        backend.offline = true;
        vault.save(accounts.currentAccount().id, "segredo#1");

        try {
            repository.resumePending();
            fail("sem rede não há como conectar");
        } catch (SocialException e) {
            assertEquals(SocialException.Error.OFFLINE, e.error);
        }
        assertFalse("tenta de novo na próxima", vault.isEmpty());
    }

    @Test
    public void resumePending_withAPasswordChangedElsewhere_forgetsIt_andAsks() throws SocialException {
        readyAs("davi");
        backend.signOut();
        backend.offline = true;
        repository.resumeAfterSignIn(accounts.currentAccount(), "segredo#1");
        // Enquanto isso, a senha da conta online mudou em outro aparelho.
        backend.offline = false;
        backend.signIn("davi@exemplo.com", "segredo#1", false);
        backend.changePassword("davi@exemplo.com", "segredo#1", "trocada-no-outro");
        backend.signOut();

        SocialRepository.Session session = repository.resumePending();

        assertEquals(SocialRepository.Status.NEEDS_CONNECTION, session.status);
        assertTrue("senha que o servidor recusa não fica guardada", vault.isEmpty());
    }

    @Test
    public void nothingPending_resumeJustReportsTheSession() throws SocialException {
        assertEquals(SocialRepository.Status.NEEDS_CONNECTION, repository.resumePending().status);
        assertEquals("sem senha guardada nem tenta o servidor", 0, backend.signOutCount);
    }

    @Test
    public void thePendingPassword_isForgottenOnSignOut_andNeverServesAnotherAccount() {
        backend.offline = true;
        repository.resumeAfterSignIn(accounts.currentAccount(), "segredo#1");
        assertFalse(vault.isEmpty());

        repository.onSignedOut();
        assertTrue("sair da conta apaga a senha guardada", vault.isEmpty());

        repository.resumeAfterSignIn(accounts.currentAccount(), "segredo#1");
        accounts.signOut();
        accounts.signUp("Ana", "ana@exemplo.com", "senha-ana");
        assertNull("a senha do Davi não serve para a Ana", vault.read(accounts.currentAccount().id));
    }

    @Test
    public void switchingAccounts_closesTheOtherOnlineSession() throws SocialException {
        SocialRepository.Session davi = readyAs("davi");
        accounts.signOut();
        accounts.signUp("Ana", "ana@exemplo.com", "senha-ana");

        repository.resumeAfterSignIn(accounts.currentAccount(), "senha-ana");

        assertNotEquals("a sessão online do Davi não fica aberta para a Ana",
                davi.card.uid, backend.signedInUid());
        assertEquals(SocialRepository.Status.NEEDS_USERNAME, repository.session().status);
    }

    @Test
    public void recover_bringsBackTheWholeAccountOnANewDevice() throws SocialException {
        String oldId = accounts.currentAccount().id;
        accounts.updateProfile("Davi", "vigia noturno", "davi@exemplo.com", null);
        accounts.setImage(AccountStore.ImageKind.AVATAR, "avatar.jpg");
        accounts.setImage(AccountStore.ImageKind.BANNER, "banner.jpg");
        collection.save(oldId, 1009610, "Spider-Man", "http://img/spidey.jpg");
        learning.importStats(oldId, 7, 5, 3);
        String uid = readyAs("davi").card.uid;

        // Aparelho novo: nada gravado aqui, só o e-mail e a senha que o jogador lembra.
        backend.signOut();
        AccountStore freshAccounts = new AccountStore(
                () -> tmp.getRoot().toPath().resolve("other.json").toFile(), 1_000);
        CollectionStore freshCollection = new CollectionStore(
                () -> tmp.getRoot().toPath().resolve("other-collection.json").toFile());
        LearningStore freshLearning = new LearningStore(
                () -> tmp.getRoot().toPath().resolve("other-learning.json").toFile(), direct);
        FakeProfileImages freshImages = new FakeProfileImages(tmp.getRoot());
        SocialRepository other = new SocialRepository(backend, freshAccounts, freshCollection, freshLearning,
                freshImages, () -> null, direct, () -> 43L);

        AccountStore.Account recovered = other.recover(" Davi@Exemplo.com ", "segredo#1");

        assertNotNull("conta recuperada do servidor", recovered);
        assertEquals("Davi", recovered.name);
        assertEquals("davi@exemplo.com", recovered.email);
        assertEquals("davi", recovered.username);
        assertEquals(uid, recovered.cloudUid);
        assertEquals("vigia noturno", recovered.bio);
        assertEquals("a foto volta na versão publicada",
                "shared:avatar.jpg@" + SocialRepository.AVATAR_SHARE_PX, freshImages.saved.get(recovered.avatarFile));
        assertEquals("o banner volta na versão publicada",
                "shared:banner.jpg@" + SocialRepository.BANNER_SHARE_PX, freshImages.saved.get(recovered.bannerFile));
        assertEquals("entra direto nos amigos, sem pedir a senha de novo",
                SocialRepository.Status.READY, other.session().status);

        List<CollectionStore.Entry> heroes = freshCollection.list(recovered.id);
        assertEquals(1, heroes.size());
        assertEquals(1009610, heroes.get(0).characterId);
        LearningStore.Stats stats = freshLearning.stats(recovered.id);
        assertEquals(7, stats.gamesPlayed);
        assertEquals(5, stats.engineWins);
        assertEquals(3, stats.distinctCharacters);
    }

    @Test
    public void recover_onlyWithTheRightEmailAndPassword() throws SocialException {
        readyAs("davi");
        backend.signOut();
        AccountStore freshAccounts = new AccountStore(
                () -> tmp.getRoot().toPath().resolve("other.json").toFile(), 1_000);
        SocialRepository other = new SocialRepository(backend, freshAccounts, collection, learning,
                new FakeProfileImages(tmp.getRoot()), () -> null, direct, () -> 43L);

        assertNull("senha errada não recupera", other.recover("davi@exemplo.com", "errada1"));
        assertNull("e-mail sem conta online não recupera", other.recover("ninguem@exemplo.com", "segredo#1"));
        assertNull(freshAccounts.currentAccount());
        assertNull("nenhuma sessão online fica aberta", backend.signedInUid());

        backend.offline = true;
        assertNull("sem rede não recupera", other.recover("davi@exemplo.com", "segredo#1"));
        assertNull(freshAccounts.currentAccount());
    }

    @Test
    public void recover_doesNotDuplicateAnAccountThisDeviceAlreadyHas() throws SocialException {
        String id = readyAs("davi").account.id;
        backend.signOut();
        accounts.signOut();

        assertNull(repository.recover("davi@exemplo.com", "segredo#1"));

        assertNull("não deixa uma sessão pela metade", accounts.currentAccount());
        assertEquals("a conta deste aparelho continua sendo a mesma",
                id, accounts.signIn("davi@exemplo.com", "segredo#1").account.id);
    }

    @Test
    public void friendship_requestAcceptAndProfiles() throws SocialException {
        UserCard ana = backend.registerOther("ana@exemplo.com", "senha-ana", "ana", "Ana");
        SocialRepository.Session me = readyAs("davi");

        UserCard found = repository.findByUsername("@ANA");
        assertEquals(ana.uid, found.uid);
        try {
            repository.friendProfile(ana.uid);
            fail("viu o perfil de quem não é amigo");
        } catch (SocialException e) {
            assertEquals(SocialException.Error.PERMISSION_DENIED, e.error);
        }

        repository.sendRequest(found, repository.hub());
        assertTrue(backend.hasRequest(me.card.uid, ana.uid));
        assertEquals(FriendsHub.Relationship.REQUEST_SENT, repository.hub().relationshipWith(me.card.uid, ana.uid));

        // Ana aceita no aparelho dela.
        backend.actAs(ana.uid);
        backend.acceptRequest(me.card.uid);
        backend.actAs(me.card.uid);

        FriendsHub hub = repository.hub();
        assertEquals(1, hub.friends.size());
        assertEquals(FriendsHub.Relationship.FRIENDS, hub.relationshipWith(me.card.uid, ana.uid));
        assertEquals("Ana", repository.friendProfile(ana.uid).profile.card.name);

        repository.removeFriend(ana.uid);
        assertFalse(backend.areFriends(me.card.uid, ana.uid));
    }

    @Test
    public void askingSomeoneWhoAlreadyAsked_makesYouFriendsRightAway() throws SocialException {
        UserCard ana = backend.registerOther("ana@exemplo.com", "senha-ana", "ana", "Ana");
        SocialRepository.Session me = readyAs("davi");
        backend.actAs(ana.uid);
        backend.sendRequest(ana, me.card);
        backend.actAs(me.card.uid);

        repository.sendRequest(ana, repository.hub());

        assertTrue(backend.areFriends(me.card.uid, ana.uid));
        assertFalse(backend.hasRequest(ana.uid, me.card.uid));
        assertFalse(backend.hasRequest(me.card.uid, ana.uid));
    }

    @Test
    public void declineAndCancel_removeTheRequest() throws SocialException {
        UserCard ana = backend.registerOther("ana@exemplo.com", "senha-ana", "ana", "Ana");
        UserCard bia = backend.registerOther("bia@exemplo.com", "senha-bia", "bia", "Bia");
        SocialRepository.Session me = readyAs("davi");
        backend.actAs(ana.uid);
        backend.sendRequest(ana, me.card);
        backend.actAs(me.card.uid);
        repository.sendRequest(bia, repository.hub());

        repository.decline(ana.uid);
        repository.cancel(bia.uid);

        FriendsHub hub = repository.hub();
        assertTrue(hub.incoming.isEmpty());
        assertTrue(hub.outgoing.isEmpty());
        assertTrue(hub.friends.isEmpty());
    }

    @Test
    public void deletingOnline_onlyBlocksWhenOffline() throws SocialException {
        readyAs("davi");
        AccountStore.Account account = accounts.currentAccount();

        backend.offline = true;
        try {
            repository.deleteOnlineAccount(account, "segredo#1");
            fail("sem rede a exclusão deveria parar");
        } catch (SocialException e) {
            assertEquals(SocialException.Error.OFFLINE, e.error);
        }

        backend.offline = false;
        repository.deleteOnlineAccount(account, "segredo#1");
        assertFalse(backend.accountExists("davi@exemplo.com"));
        // Já apagada: não há mais o que fazer, mas não impede a exclusão local.
        repository.deleteOnlineAccount(account, "segredo#1");
    }

    @Test
    public void passwordChange_followsToTheOnlineAccount() throws SocialException {
        readyAs("davi");
        accounts.changePassword("segredo#1", "nova-senha");
        repository.onPasswordChanged(accounts.currentAccount(), "segredo#1", "nova-senha");

        backend.signOut();
        assertEquals(accounts.currentAccount().cloudUid, backend.signIn("davi@exemplo.com", "nova-senha", false));
    }

    @Test
    public void actionsBeforeGoingOnline_sayNotConnected() {
        try {
            repository.hub();
            fail();
        } catch (SocialException e) {
            assertEquals(SocialException.Error.NOT_CONNECTED, e.error);
        }
        repository.publishQuietly();
        assertEquals(0, backend.publishCount);
    }

    @Test
    public void publishQuietly_neverThrows_evenWhenTheSessionCannotBeRead() throws SocialException {
        readyAs("davi");
        backend.sessionFailure = new IllegalStateException("falha ao ler a sessão online");

        // Roda solto numa thread de fundo: uma exceção aqui derrubaria o app.
        repository.publishQuietly();

        assertEquals("só a publicação de escolher o @usuario", 1, backend.publishCount);
    }
}
