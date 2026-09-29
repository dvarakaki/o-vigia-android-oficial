package com.ovigia.app.social;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.social.FriendsUiState.HeroReceived;
import com.ovigia.app.util.Event;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Troca de heróis 1 por 1 entre dois amigos, cada um no seu aparelho (conta,
 * coleção e sessão próprias), contra o servidor falso.
 */
public class TradesTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    private static final PublicProfile.Hero IRON_MAN = new PublicProfile.Hero(1455, "Iron Man", "http://img/1455", 0L);
    private static final PublicProfile.Hero THOR = new PublicProfile.Hero(2268, "Thor", "http://img/2268", 0L);
    private static final PublicProfile.Hero SPIDER_MAN = new PublicProfile.Hero(1009610, "Spider-Man", "http://img/sm", 0L);
    private static final PublicProfile.Hero WOLVERINE = new PublicProfile.Hero(1009718, "Wolverine", "http://img/w", 0L);

    private final Executor direct = Runnable::run;
    private FakeCloud backend;
    private Device davi;
    private Device ana;

    /** Um aparelho: conta local, coleção e o repositório que as liga ao servidor. */
    private final class Device {
        final AccountStore accounts;
        final CollectionStore collection;
        final SocialRepository repository;
        final UserCard card;

        Device(String name, String email, String username) throws SocialException {
            accounts = new AccountStore(backend);
            collection = new CollectionStore(backend);
            LearningStore learning = new LearningStore(backend);
            repository = new SocialRepository(backend, accounts, collection, learning, () -> null, direct, () -> 7L);
            accounts.signUp(name, email, "segredo#1");
            card = repository.claimUsername(username).card;
        }

        /** Passa a usar este aparelho (a sessão do servidor falso é uma só). */
        Device use() {
            backend.actAs(card.uid);
            return this;
        }

        void unlock(PublicProfile.Hero hero) throws SocialException {
            use();
            collection.save(accounts.currentAccount().id, hero.characterId, hero.name, hero.imageUrl);
            repository.publish();
        }

        boolean has(PublicProfile.Hero hero) {
            return collection.contains(accounts.currentAccount().id, hero.characterId);
        }
    }

    @Before
    public void setUp() throws SocialException {
        backend = new FakeCloud();
        davi = new Device("Davi", "davi@exemplo.com", "davi");
        ana = new Device("Ana", "ana@exemplo.com", "ana");

        davi.use().repository.sendRequest(ana.card, davi.repository.hub());
        ana.use().repository.accept(davi.card.uid, ana.repository.hub());

        davi.unlock(IRON_MAN);
        davi.unlock(THOR);
        ana.unlock(SPIDER_MAN);
        ana.unlock(WOLVERINE);
    }

    private TradeOffer daviAsksForSpiderManOffering(PublicProfile.Hero offer) throws SocialException {
        davi.use();
        PublicProfile anaProfile = davi.repository.friendProfile(ana.card.uid).profile;
        return davi.repository.proposeTrade(ana.card, anaProfile.heroes, SPIDER_MAN, offer);
    }

    // ---------------------------------------------------------------- repositório

    @Test
    public void friendProfile_knowsWhatEachSideCanGive() throws SocialException {
        SocialRepository.FriendProfile profile = davi.use().repository.friendProfile(ana.card.uid);

        assertEquals(2, profile.wantPicks.size());
        assertEquals(2, profile.offerPicks.size());
        for (TradeSuggestions.Pick p : profile.wantPicks) assertFalse(davi.has(p.hero));
        assertTrue(profile.sentTrades.isEmpty());
    }

    @Test
    public void acceptedTrade_givesEachSideTheOthersHero_andNobodyLosesTheirs() throws SocialException {
        TradeOffer sent = daviAsksForSpiderManOffering(IRON_MAN);

        ana.use();
        FriendsHub anaHub = ana.repository.hub();
        assertEquals(1, anaHub.incomingTrades.size());
        TradeOffer received = anaHub.incomingTrades.get(0);
        assertEquals(sent.id, received.id);
        assertEquals(SPIDER_MAN.characterId, received.want.characterId);

        // Ana prefere o Thor ao Homem de Ferro oferecido.
        SocialRepository.TradeChoices choices = ana.repository.tradeChoices(received);
        assertEquals(2, choices.picks.size());
        assertFalse(choices.partial);
        ana.repository.acceptTrade(received, THOR);

        assertTrue(ana.has(THOR));
        assertFalse("ganha só o escolhido", ana.has(IRON_MAN));
        assertTrue("quem dá continua com o seu", ana.has(SPIDER_MAN));
        assertTrue(ana.repository.hub().incomingTrades.isEmpty());

        // Davi recebe na próxima vez que carregar os amigos — uma vez só.
        davi.use();
        FriendsHub daviHub = davi.repository.hub();
        assertEquals(1, daviHub.completedTrades.size());
        assertTrue(daviHub.outgoingTrades.isEmpty());
        assertTrue(davi.has(SPIDER_MAN));
        assertTrue(davi.has(THOR));
        assertEquals(0, backend.tradeCount());
        assertTrue(davi.repository.hub().completedTrades.isEmpty());
        assertTrue("o perfil publicado já mostra o herói novo",
                containsHero(backend.lastPublished, SPIDER_MAN));
    }

    @Test
    public void proposal_mustBeOneTheOtherSideCanUse() throws SocialException {
        davi.use();
        List<PublicProfile.Hero> anaHeroes = davi.repository.friendProfile(ana.card.uid).profile.heroes;
        assertTradeInvalid(() -> davi.repository.proposeTrade(ana.card, anaHeroes, IRON_MAN, THOR));
        assertTradeInvalid(() -> davi.repository.proposeTrade(ana.card, anaHeroes, SPIDER_MAN, WOLVERINE));
        assertTradeInvalid(() -> davi.repository.proposeTrade(ana.card, anaHeroes, SPIDER_MAN, SPIDER_MAN));
        assertEquals(0, backend.tradeCount());
    }

    @Test
    public void accepting_aHeroAlreadyOwned_isRefused() throws SocialException {
        TradeOffer sent = daviAsksForSpiderManOffering(IRON_MAN);
        ana.unlock(IRON_MAN);

        TradeOffer received = ana.use().repository.hub().incomingTrades.get(0);
        assertEquals("sobra o Thor para escolher", 1, ana.repository.tradeChoices(received).picks.size());
        assertTradeInvalid(() -> ana.repository.acceptTrade(received, IRON_MAN));
        assertEquals(TradeOffer.Status.PENDING, backend.trade(sent.id).status);
    }

    @Test
    public void declinedTrade_justDisappears() throws SocialException {
        daviAsksForSpiderManOffering(IRON_MAN);
        ana.use();
        ana.repository.dismissTrade(ana.repository.hub().incomingTrades.get(0));

        FriendsHub daviHub = davi.use().repository.hub();
        assertTrue(daviHub.outgoingTrades.isEmpty());
        assertTrue(daviHub.completedTrades.isEmpty());
        assertFalse(davi.has(SPIDER_MAN));
    }

    @Test
    public void undoingTheFriendship_dropsTheOpenTrades() throws SocialException {
        daviAsksForSpiderManOffering(IRON_MAN);
        ana.use().repository.removeFriend(davi.card.uid);

        assertEquals(0, backend.tradeCount());
        try {
            daviAsksForSpiderManOffering(IRON_MAN);
            fail("propôs troca a quem não é mais amigo");
        } catch (SocialException e) {
            assertEquals(SocialException.Error.PERMISSION_DENIED, e.error);
        }
    }

    // ---------------------------------------------------------------- telas

    @Test
    public void friendProfileScreen_proposesWithTheBestOffer_andCanCancel() {
        davi.use();
        FriendProfileViewModel vm = new FriendProfileViewModel(ana.card.uid, davi.repository, direct, direct);
        vm.start();
        FriendProfileUiState state = vm.state().getValue();
        assertNotNull(state.suggestedWant());

        vm.startProposal(SPIDER_MAN.characterId);
        FriendProfileUiState.Proposal proposal = vm.state().getValue().proposal;
        assertNotNull(proposal);
        assertEquals("já vem com a melhor oferta", state.offerPicks.get(0).hero.characterId, proposal.offerId);

        vm.chooseOffer(THOR.characterId);
        vm.sendProposal();
        state = vm.state().getValue();
        assertNull("a gaveta fecha", state.proposal);
        assertEquals(FriendProfileViewModel.TradeMessage.PROPOSAL_SENT, vm.tradeMessages().getValue().consume());
        TradeOffer sent = state.sentTradeFor(SPIDER_MAN.characterId);
        assertNotNull(sent);
        assertEquals(THOR.characterId, backend.trade(sent.id).offer.characterId);

        vm.startProposal(SPIDER_MAN.characterId);
        assertNull("o mesmo herói não é pedido duas vezes", vm.state().getValue().proposal);

        vm.cancelProposal(sent.id);
        assertTrue(vm.state().getValue().sentTrades.isEmpty());
        assertEquals(0, backend.tradeCount());
    }

    @Test
    public void friendsScreen_showsTheOffer_letsPickAnotherHero_andCelebratesOnBothSides() throws SocialException {
        daviAsksForSpiderManOffering(IRON_MAN);

        ana.use();
        FriendsViewModel anaVm = new FriendsViewModel(ana.repository, direct, direct);
        anaVm.start();
        FriendsUiState anaState = anaVm.state().getValue();
        assertEquals(1, anaState.hub.incomingTrades.size());

        anaVm.openTrade(anaState.hub.incomingTrades.get(0).id);
        FriendsUiState.TradeReview review = anaVm.state().getValue().review;
        assertFalse(review.loading);
        assertEquals("começa no herói oferecido", IRON_MAN.characterId, review.chosenId);

        anaVm.chooseTradeHero(THOR.characterId);
        anaVm.acceptTrade();
        anaState = anaVm.state().getValue();
        assertNull(anaState.review);
        assertTrue(anaState.hub.incomingTrades.isEmpty());
        List<HeroReceived> anaGot = anaVm.heroesReceived().getValue().consume();
        assertEquals(THOR.characterId, anaGot.get(0).hero.characterId);
        assertEquals(davi.card.uid, anaGot.get(0).partner.uid);

        davi.use();
        FriendsViewModel daviVm = new FriendsViewModel(davi.repository, direct, direct);
        daviVm.start();
        Event<List<HeroReceived>> daviEvent = daviVm.heroesReceived().getValue();
        assertNotNull("quem pediu fica sabendo ao abrir a aba", daviEvent);
        HeroReceived daviGot = daviEvent.consume().get(0);
        assertEquals(SPIDER_MAN.characterId, daviGot.hero.characterId);
        assertEquals(ana.card.uid, daviGot.partner.uid);
        assertTrue(davi.has(SPIDER_MAN));
    }

    @Test
    public void friendsScreen_declineFromTheList() throws SocialException {
        daviAsksForSpiderManOffering(IRON_MAN);
        ana.use();
        FriendsViewModel vm = new FriendsViewModel(ana.repository, direct, direct);
        vm.start();

        vm.declineTrade(vm.state().getValue().hub.incomingTrades.get(0).id);

        assertTrue(vm.state().getValue().hub.incomingTrades.isEmpty());
        assertEquals(FriendsUiState.Message.TRADE_DECLINED, vm.messages().getValue().consume());
        assertEquals(0, backend.tradeCount());
    }

    // ---------------------------------------------------------------- apoio

    private interface Call {
        void run() throws SocialException;
    }

    private static void assertTradeInvalid(Call call) {
        try {
            call.run();
            fail("aceitou uma troca que não vale");
        } catch (SocialException e) {
            assertEquals(SocialException.Error.TRADE_INVALID, e.error);
        }
    }

    private static boolean containsHero(PublicProfile profile, PublicProfile.Hero hero) {
        for (PublicProfile.Hero h : profile.heroes) {
            if (h.characterId == hero.characterId) return true;
        }
        return false;
    }
}
