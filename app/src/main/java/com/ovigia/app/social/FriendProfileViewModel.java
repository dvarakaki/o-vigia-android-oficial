package com.ovigia.app.social;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ovigia.app.social.FriendProfileUiState.Proposal;
import com.ovigia.app.social.FriendProfileUiState.Status;
import com.ovigia.app.util.Event;

import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

/**
 * Perfil completo de um amigo: carrega do servidor, propõe trocas de heróis e
 * permite desfazer a amizade.
 */
public class FriendProfileViewModel extends ViewModel {

    /** Aviso curto depois de uma ação de troca. */
    public enum TradeMessage {
        PROPOSAL_SENT,
        PROPOSAL_CANCELED,
        /** O herói já não serve (ex.: o jogador desbloqueou o pedido jogando). */
        TRADE_INVALID,
        FAILED_OFFLINE,
        FAILED
    }

    private final String friendUid;
    private final SocialRepository repository;
    private final Executor socialExecutor;
    private final Executor mainExecutor;

    private final MutableLiveData<FriendProfileUiState> state = new MutableLiveData<>(FriendProfileUiState.loading());
    /** Falha ao desfazer a amizade (o perfil continua na tela). */
    private final MutableLiveData<Event<SocialException.Error>> removeFailures = new MutableLiveData<>();
    private final MutableLiveData<Event<TradeMessage>> tradeMessages = new MutableLiveData<>();
    private boolean started = false;

    public FriendProfileViewModel(String friendUid, SocialRepository repository, Executor socialExecutor,
                                  Executor mainExecutor) {
        this.friendUid = friendUid;
        this.repository = repository;
        this.socialExecutor = socialExecutor;
        this.mainExecutor = mainExecutor;
    }

    public LiveData<FriendProfileUiState> state() { return state; }

    public LiveData<Event<SocialException.Error>> removeFailures() { return removeFailures; }

    public LiveData<Event<TradeMessage>> tradeMessages() { return tradeMessages; }

    public void start() {
        if (started) return;
        started = true;
        load();
    }

    public void retry() {
        FriendProfileUiState current = state.getValue();
        if (current.status != Status.ERROR) return;
        state.setValue(FriendProfileUiState.loading());
        load();
    }

    private void load() {
        socialExecutor.execute(() -> {
            try {
                SocialRepository.FriendProfile loaded = repository.friendProfile(friendUid);
                mainExecutor.execute(() -> state.setValue(FriendProfileUiState.ready(loaded)));
            } catch (SocialException | RuntimeException e) {
                SocialException.Error error = SocialException.errorOf(e);
                post(s -> s.withStatus(Status.ERROR, error));
            }
        });
    }

    public void removeFriend() {
        FriendProfileUiState current = state.getValue();
        if (current.status != Status.READY || current.removing) return;
        state.setValue(current.withRemoving(true));
        socialExecutor.execute(() -> {
            try {
                repository.removeFriend(friendUid);
                post(s -> s.withRemoving(false).withStatus(Status.REMOVED, null));
            } catch (SocialException | RuntimeException e) {
                SocialException.Error error = SocialException.errorOf(e);
                mainExecutor.execute(() -> {
                    state.setValue(state.getValue().withRemoving(false));
                    removeFailures.setValue(new Event<>(error));
                });
            }
        });
    }

    // ---------------------------------------------------------------- trocas

    /**
     * Abre a troca pedindo o herói {@code wantId} do amigo, já com a melhor
     * oferta escolhida. Não faz nada se o jogador já tem esse herói ou já pediu.
     */
    public void startProposal(int wantId) {
        FriendProfileUiState current = state.getValue();
        if (current.status != Status.READY || current.proposal != null) return;
        TradeSuggestions.Pick want = current.wantPick(wantId);
        if (want == null || current.sentTradeFor(wantId) != null) return;
        int offerId = current.offerPicks.isEmpty() ? -1 : current.offerPicks.get(0).hero.characterId;
        state.setValue(current.withProposal(new Proposal(want, offerId, false)));
    }

    public void chooseOffer(int offerId) {
        FriendProfileUiState current = state.getValue();
        Proposal proposal = current.proposal;
        if (proposal == null || proposal.sending || current.offerPick(offerId) == null) return;
        state.setValue(current.withProposal(proposal.withOffer(offerId)));
    }

    public void closeProposal() {
        FriendProfileUiState current = state.getValue();
        if (current.proposal == null || current.proposal.sending) return;
        state.setValue(current.withProposal(null));
    }

    public void sendProposal() {
        FriendProfileUiState current = state.getValue();
        Proposal proposal = current.proposal;
        if (proposal == null || proposal.sending || current.profile == null) return;
        TradeSuggestions.Pick offer = current.offerPick(proposal.offerId);
        if (offer == null) return;
        PublicProfile profile = current.profile;
        state.setValue(current.withProposal(proposal.withSending(true)));
        socialExecutor.execute(() -> {
            try {
                TradeOffer sent = repository.proposeTrade(profile.card, profile.heroes, proposal.want.hero, offer.hero);
                post(s -> s.withSent(sent));
                tradeMessage(TradeMessage.PROPOSAL_SENT);
            } catch (SocialException | RuntimeException e) {
                post(s -> s.proposal == null ? s : s.withProposal(s.proposal.withSending(false)));
                tradeMessage(failure(SocialException.errorOf(e)));
            }
        });
    }

    /** Desiste de uma proposta já enviada a este amigo. */
    public void cancelProposal(String tradeId) {
        FriendProfileUiState current = state.getValue();
        TradeOffer trade = null;
        for (TradeOffer t : current.sentTrades) {
            if (t.id.equals(tradeId)) trade = t;
        }
        if (trade == null || current.cancelingTrades.contains(tradeId)) return;
        TradeOffer target = trade;
        state.setValue(current.withCanceling(tradeId, true));
        socialExecutor.execute(() -> {
            try {
                repository.dismissTrade(target);
                post(s -> s.withoutSent(tradeId));
                tradeMessage(TradeMessage.PROPOSAL_CANCELED);
            } catch (SocialException | RuntimeException e) {
                post(s -> s.withCanceling(tradeId, false));
                tradeMessage(failure(SocialException.errorOf(e)));
            }
        });
    }

    private static TradeMessage failure(SocialException.Error error) {
        switch (error) {
            case OFFLINE: return TradeMessage.FAILED_OFFLINE;
            case TRADE_INVALID: return TradeMessage.TRADE_INVALID;
            default: return TradeMessage.FAILED;
        }
    }

    // ---------------------------------------------------------------- apoio

    private void post(UnaryOperator<FriendProfileUiState> change) {
        mainExecutor.execute(() -> state.setValue(change.apply(state.getValue())));
    }

    private void tradeMessage(TradeMessage message) {
        mainExecutor.execute(() -> tradeMessages.setValue(new Event<>(message)));
    }

    public static final class Factory implements ViewModelProvider.Factory {

        private final String friendUid;
        private final SocialRepository repository;
        private final Executor socialExecutor;
        private final Executor mainExecutor;

        public Factory(String friendUid, SocialRepository repository, Executor socialExecutor, Executor mainExecutor) {
            this.friendUid = friendUid;
            this.repository = repository;
            this.socialExecutor = socialExecutor;
            this.mainExecutor = mainExecutor;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new FriendProfileViewModel(friendUid, repository, socialExecutor, mainExecutor);
        }
    }
}
