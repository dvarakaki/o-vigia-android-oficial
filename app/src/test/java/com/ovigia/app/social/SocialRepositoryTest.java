package com.ovigia.app.social;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.learning.LearningStore;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Os amigos da conta logada, contra o servidor falso em memória. */
public class SocialRepositoryTest {

    private final Executor direct = Runnable::run;
    private FakeCloud backend;
    private AccountStore accounts;
    private CollectionStore collection;
    private LearningStore learning;
    private SocialRepository repository;

    @Before
    public void setUp() {
        backend = new FakeCloud();
        accounts = new AccountStore(backend);
        collection = new CollectionStore(backend);
        learning = new LearningStore(backend);
        repository = new SocialRepository(backend, accounts, collection, learning, () -> null, direct, () -> 42L);
        accounts.signUp("Davi", "davi@exemplo.com", "segredo#1");
        backend.rosterNames.put(1455, "Iron Man");
    }

    /** O Vigia acertou o Homem de Ferro: a partida desbloqueia o herói no servidor. */
    private void winIronMan(String id) {
        learning.recordGame(id, 1455, new ArrayList<>(), LearningStore.Outcome.ENGINE_GUESSED);
        collection.save(id, 1455, "Iron Man", "http://img/ironman.jpg");
    }

    private SocialRepository.Session readyAs(String username) throws SocialException {
        return repository.claimUsername(username);
    }

    @Test
    public void session_goesStraightToTheUsername_thenReady() throws SocialException {
        assertEquals("logado já é online: falta só o @usuario",
                SocialRepository.Status.NEEDS_USERNAME, repository.session().status);

        SocialRepository.Session ready = repository.claimUsername("@Davi");
        assertEquals(SocialRepository.Status.READY, ready.status);
        assertEquals("davi", ready.card.username);
        assertEquals(accounts.currentAccountId(), ready.card.uid);
        assertEquals("davi", accounts.currentAccount().username);
        assertEquals("publica o perfil logo depois de escolher o @usuario", 1, backend.publishCount);
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
    public void takenUsername_isRefused() {
        backend.registerOther("ana@exemplo.com", "senha-ana", "ana", "Ana");
        try {
            repository.claimUsername("ana");
            fail("reservou o @usuario de outra conta");
        } catch (SocialException e) {
            assertEquals(SocialException.Error.USERNAME_TAKEN, e.error);
        }
        assertEquals(SocialRepository.Status.NEEDS_USERNAME, repository.session().status);
    }

    @Test
    public void invalidUsername_neverReachesTheServer() {
        try {
            repository.claimUsername("jo");
            fail();
        } catch (SocialException e) {
            assertEquals(SocialException.Error.USERNAME_INVALID, e.error);
        }
    }

    @Test
    public void publishedProfile_carriesHeroesStatsAndAchievements_fromTheAccount() throws SocialException {
        String id = accounts.currentAccountId();
        winIronMan(id);
        accounts.updateProfile("Davi", "Fã do Wolverine");
        accounts.setImage(AccountStore.ImageKind.AVATAR, "foto-base64");
        accounts.setImage(AccountStore.ImageKind.BANNER, "banner-base64");
        learning.recordLoss(id);
        readyAs("davi");

        PublicProfile published = backend.lastPublished;
        assertEquals("Fã do Wolverine", published.bio);
        assertEquals(FakeCloud.urlOf("foto-base64"), published.card.avatar);
        assertEquals(FakeCloud.urlOf("banner-base64"), published.banner);
        assertEquals(1, published.heroes.size());
        assertEquals("Iron Man", published.heroes.get(0).name);
        assertEquals(2, published.gamesPlayed);
        assertEquals(1, published.playerWins());
        assertEquals(42L, published.updatedAt);
        assertTrue(published.achievements.get(Achievement.FIRST_HERO.ordinal()).isUnlocked());
        assertTrue(published.achievements.get(Achievement.BEAT_WATCHER.ordinal()).isUnlocked());
    }

    @Test
    public void anotherDevice_publishesTheSameProfile() throws SocialException {
        winIronMan(accounts.currentAccountId());
        readyAs("davi");

        // Outro aparelho: só entra na conta — heróis, @usuario e números vêm dela.
        AccountStore otherAccounts = new AccountStore(backend);
        otherAccounts.signIn("davi@exemplo.com", "segredo#1");
        SocialRepository other = new SocialRepository(backend, otherAccounts, new CollectionStore(backend),
                new LearningStore(backend), () -> null, direct, () -> 43L);

        assertEquals(SocialRepository.Status.READY, other.session().status);
        other.publish();
        assertEquals(1, backend.lastPublished.heroes.size());
        assertEquals("davi", backend.lastPublished.card.username);
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
    public void actionsBeforeChoosingAUsername_sayNotConnected() {
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
    public void publishQuietly_neverThrows_evenOnUnexpectedFailures() throws SocialException {
        readyAs("davi");
        backend.operationFailure = new IllegalStateException("falha no SDK");

        // Roda solto numa thread de fundo: uma exceção aqui derrubaria o app.
        repository.publishQuietly();

        assertEquals("só a publicação de escolher o @usuario", 1, backend.publishCount);
    }
}
