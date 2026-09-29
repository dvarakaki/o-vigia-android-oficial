package com.ovigia.app.social;

import androidx.annotation.Nullable;

import com.ovigia.app.profile.PlayerRank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Estado do perfil de um amigo. Imutável: cada mudança gera uma cópia com {@code with…}. */
public final class FriendProfileUiState {

    public enum Status {
        LOADING,
        READY,
        /** Não carregou; ver {@link #error} ({@code PERMISSION_DENIED}: não são mais amigos). */
        ERROR,
        /** A amizade foi desfeita nesta tela: ela deve fechar. */
        REMOVED
    }

    public final Status status;
    @Nullable public final PublicProfile profile;
    /** Heróis que o jogador também desbloqueou (esses abrem a ficha). */
    public final Set<Integer> myUnlockedIds;
    @Nullable public final SocialException.Error error;
    /** Desfazendo a amizade. */
    public final boolean removing;
    /** Heróis do amigo que o jogador não tem, do mais interessante ao menos (o primeiro é a sugestão). */
    public final List<TradeSuggestions.Pick> wantPicks;
    /** Heróis do jogador que o amigo não tem, do mais interessante para o amigo ao menos. */
    public final List<TradeSuggestions.Pick> offerPicks;
    /** Propostas pendentes que o jogador fez a este amigo. */
    public final List<TradeOffer> sentTrades;
    /** Proposta sendo montada (a gaveta da troca está aberta), ou {@code null}. */
    @Nullable public final Proposal proposal;
    /** Propostas sendo canceladas. */
    public final Set<String> cancelingTrades;

    private FriendProfileUiState(Status status, @Nullable PublicProfile profile, Set<Integer> myUnlockedIds,
                                 @Nullable SocialException.Error error, boolean removing,
                                 List<TradeSuggestions.Pick> wantPicks, List<TradeSuggestions.Pick> offerPicks,
                                 List<TradeOffer> sentTrades, @Nullable Proposal proposal,
                                 Set<String> cancelingTrades) {
        this.status = status;
        this.profile = profile;
        this.myUnlockedIds = Collections.unmodifiableSet(myUnlockedIds);
        this.error = error;
        this.removing = removing;
        this.wantPicks = Collections.unmodifiableList(wantPicks);
        this.offerPicks = Collections.unmodifiableList(offerPicks);
        this.sentTrades = Collections.unmodifiableList(sentTrades);
        this.proposal = proposal;
        this.cancelingTrades = Collections.unmodifiableSet(cancelingTrades);
    }

    static FriendProfileUiState loading() {
        return new FriendProfileUiState(Status.LOADING, null, Collections.emptySet(), null, false,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null,
                Collections.emptySet());
    }

    static FriendProfileUiState ready(SocialRepository.FriendProfile loaded) {
        return new FriendProfileUiState(Status.READY, loaded.profile, loaded.myUnlockedIds, null, false,
                loaded.wantPicks, loaded.offerPicks, loaded.sentTrades, null, Collections.emptySet());
    }

    FriendProfileUiState withStatus(Status newStatus, @Nullable SocialException.Error newError) {
        return new FriendProfileUiState(newStatus, profile, myUnlockedIds, newError, removing, wantPicks, offerPicks,
                sentTrades, proposal, cancelingTrades);
    }

    FriendProfileUiState withRemoving(boolean newRemoving) {
        return new FriendProfileUiState(status, profile, myUnlockedIds, error, newRemoving, wantPicks, offerPicks,
                sentTrades, proposal, cancelingTrades);
    }

    FriendProfileUiState withProposal(@Nullable Proposal newProposal) {
        return new FriendProfileUiState(status, profile, myUnlockedIds, error, removing, wantPicks, offerPicks,
                sentTrades, newProposal, cancelingTrades);
    }

    FriendProfileUiState withSent(TradeOffer trade) {
        List<TradeOffer> copy = new ArrayList<>(sentTrades);
        copy.add(0, trade);
        return new FriendProfileUiState(status, profile, myUnlockedIds, error, removing, wantPicks, offerPicks,
                copy, null, cancelingTrades);
    }

    FriendProfileUiState withoutSent(String tradeId) {
        List<TradeOffer> copy = new ArrayList<>(sentTrades);
        copy.removeIf(t -> t.id.equals(tradeId));
        return new FriendProfileUiState(status, profile, myUnlockedIds, error, removing, wantPicks, offerPicks,
                copy, proposal, cancelingTrades).withCanceling(tradeId, false);
    }

    FriendProfileUiState withCanceling(String tradeId, boolean canceling) {
        Set<String> copy = new HashSet<>(cancelingTrades);
        if (canceling) copy.add(tradeId); else copy.remove(tradeId);
        return new FriendProfileUiState(status, profile, myUnlockedIds, error, removing, wantPicks, offerPicks,
                sentTrades, proposal, copy);
    }

    public PlayerRank rank() {
        return PlayerRank.forGames(profile == null ? 0 : profile.gamesPlayed);
    }

    /** Proposta pendente pedindo o herói {@code heroId}, ou {@code null}. */
    @Nullable
    public TradeOffer sentTradeFor(int heroId) {
        for (TradeOffer t : sentTrades) {
            if (t.want.characterId == heroId) return t;
        }
        return null;
    }

    /** O herói do amigo que vale mais a pena pedir e que ainda não foi pedido, ou {@code null}. */
    @Nullable
    public TradeSuggestions.Pick suggestedWant() {
        for (TradeSuggestions.Pick p : wantPicks) {
            if (sentTradeFor(p.hero.characterId) == null) return p;
        }
        return null;
    }

    @Nullable
    public TradeSuggestions.Pick wantPick(int heroId) {
        return find(wantPicks, heroId);
    }

    @Nullable
    public TradeSuggestions.Pick offerPick(int heroId) {
        return find(offerPicks, heroId);
    }

    @Nullable
    private static TradeSuggestions.Pick find(List<TradeSuggestions.Pick> picks, int heroId) {
        for (TradeSuggestions.Pick p : picks) {
            if (p.hero.characterId == heroId) return p;
        }
        return null;
    }

    /** Troca sendo montada: o herói pedido é fixo, o oferecido o jogador escolhe. Imutável. */
    public static final class Proposal {
        public final TradeSuggestions.Pick want;
        /** Herói oferecido; {@code -1} quando o jogador não tem nada que o amigo não tenha. */
        public final int offerId;
        public final boolean sending;

        Proposal(TradeSuggestions.Pick want, int offerId, boolean sending) {
            this.want = want;
            this.offerId = offerId;
            this.sending = sending;
        }

        Proposal withOffer(int newOfferId) {
            return new Proposal(want, newOfferId, sending);
        }

        Proposal withSending(boolean newSending) {
            return new Proposal(want, offerId, newSending);
        }
    }
}
