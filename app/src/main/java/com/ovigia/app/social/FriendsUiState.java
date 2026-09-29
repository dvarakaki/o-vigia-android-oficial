package com.ovigia.app.social;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Estado da aba de amigos. Imutável: cada mudança gera uma cópia com {@code with…}. */
public final class FriendsUiState {

    public enum Status {
        LOADING,
        NOT_CONFIGURED,
        /** Sem conta local: a tela manda para o login. */
        SIGNED_OUT,
        /** Pedir a senha para conectar a conta online. */
        NEEDS_CONNECTION,
        /** Escolher o @usuario. */
        NEEDS_USERNAME,
        READY,
        /** Conectado, mas amigos e pedidos não carregaram ({@link #error}). */
        ERROR
    }

    public final Status status;
    /** Enviando um formulário (conectar, @usuario) ou recarregando. */
    public final boolean working;
    /** Erro do formulário atual ou da carga. */
    @Nullable public final SocialException.Error error;
    @Nullable public final UserCard me;
    /** Sugestão para o campo de @usuario. */
    public final String suggestedUsername;
    public final FriendsHub hub;
    public final Search search;
    /**
     * Contas (ou propostas de troca, pelo id) com uma ação em andamento: os
     * botões da linha ficam desabilitados.
     */
    public final Set<String> busyUids;
    /** Proposta de troca aberta na gaveta para responder, ou {@code null}. */
    @Nullable public final TradeReview review;

    private FriendsUiState(Status status, boolean working, @Nullable SocialException.Error error,
                           @Nullable UserCard me, String suggestedUsername, FriendsHub hub, Search search,
                           Set<String> busyUids, @Nullable TradeReview review) {
        this.status = status;
        this.working = working;
        this.error = error;
        this.me = me;
        this.suggestedUsername = suggestedUsername;
        this.hub = hub;
        this.search = search;
        this.busyUids = Collections.unmodifiableSet(busyUids);
        this.review = review;
    }

    static FriendsUiState of(Status status) {
        return new FriendsUiState(status, false, null, null, "", FriendsHub.EMPTY, Search.IDLE,
                Collections.emptySet(), null);
    }

    FriendsUiState withStatus(Status newStatus) {
        return new FriendsUiState(newStatus, working, error, me, suggestedUsername, hub, search, busyUids, review);
    }

    FriendsUiState withWorking(boolean newWorking) {
        return new FriendsUiState(status, newWorking, newWorking ? null : error, me, suggestedUsername, hub,
                search, busyUids, review);
    }

    FriendsUiState withError(@Nullable SocialException.Error newError) {
        return new FriendsUiState(status, false, newError, me, suggestedUsername, hub, search, busyUids, review);
    }

    FriendsUiState withMe(@Nullable UserCard newMe) {
        return new FriendsUiState(status, working, error, newMe, suggestedUsername, hub, search, busyUids, review);
    }

    FriendsUiState withSuggestion(String suggestion) {
        return new FriendsUiState(status, working, error, me, suggestion, hub, search, busyUids, review);
    }

    FriendsUiState withHub(FriendsHub newHub) {
        Search updated = search.result == null || me == null ? search
                : search.withRelationship(newHub.relationshipWith(me.uid, search.result.uid));
        // A proposta aberta sumiu (o amigo cancelou, ou já foi respondida em outro aparelho): fecha a gaveta.
        TradeReview stillOpen = review != null && review.find(newHub.incomingTrades) ? review : null;
        return new FriendsUiState(status, working, error, me, suggestedUsername, newHub, updated, busyUids,
                stillOpen);
    }

    FriendsUiState withSearch(Search newSearch) {
        return new FriendsUiState(status, working, error, me, suggestedUsername, hub, newSearch, busyUids, review);
    }

    FriendsUiState withBusy(String uid, boolean busy) {
        Set<String> copy = new HashSet<>(busyUids);
        if (busy) copy.add(uid); else copy.remove(uid);
        return new FriendsUiState(status, working, error, me, suggestedUsername, hub, search, copy, review);
    }

    FriendsUiState withReview(@Nullable TradeReview newReview) {
        return new FriendsUiState(status, working, error, me, suggestedUsername, hub, search, busyUids, newReview);
    }

    /** Busca por @usuario. Imutável. */
    public static final class Search {
        static final Search IDLE = new Search(false, null, null, null);

        public final boolean searching;
        @Nullable public final UserCard result;
        @Nullable public final FriendsHub.Relationship relationship;
        /** {@code NOT_FOUND}, {@code USERNAME_INVALID}, {@code OFFLINE}… */
        @Nullable public final SocialException.Error error;

        Search(boolean searching, @Nullable UserCard result, @Nullable FriendsHub.Relationship relationship,
               @Nullable SocialException.Error error) {
            this.searching = searching;
            this.result = result;
            this.relationship = relationship;
            this.error = error;
        }

        Search withRelationship(FriendsHub.Relationship newRelationship) {
            return new Search(searching, result, newRelationship, error);
        }
    }

    /**
     * Proposta de troca recebida, aberta para responder: o jogador escolhe qual
     * herói do catálogo do amigo quer ganhar (o oferecido vem marcado). Imutável.
     */
    public static final class TradeReview {
        public final TradeOffer trade;
        /** Carregando o catálogo do amigo. */
        public final boolean loading;
        /** Heróis do amigo que o jogador pode ganhar, do mais interessante ao menos. */
        public final List<TradeSuggestions.Pick> picks;
        /** O catálogo do amigo não carregou: só dá para aceitar o herói oferecido. */
        public final boolean partial;
        /** Herói escolhido; {@code -1} se o jogador já tem todos. */
        public final int chosenId;
        /** Aceitando. */
        public final boolean accepting;

        TradeReview(TradeOffer trade, boolean loading, List<TradeSuggestions.Pick> picks, boolean partial,
                    int chosenId, boolean accepting) {
            this.trade = trade;
            this.loading = loading;
            this.picks = Collections.unmodifiableList(picks);
            this.partial = partial;
            this.chosenId = chosenId;
            this.accepting = accepting;
        }

        static TradeReview opening(TradeOffer trade) {
            return new TradeReview(trade, true, Collections.emptyList(), false, trade.offer.characterId, false);
        }

        /** Catálogo carregado: fica o oferecido, se o jogador ainda não o tem; senão, a melhor sugestão. */
        TradeReview loaded(List<TradeSuggestions.Pick> newPicks, boolean newPartial) {
            int chosen = -1;
            for (TradeSuggestions.Pick p : newPicks) {
                if (p.hero.characterId == trade.offer.characterId) chosen = p.hero.characterId;
            }
            if (chosen == -1 && !newPicks.isEmpty()) chosen = newPicks.get(0).hero.characterId;
            return new TradeReview(trade, false, newPicks, newPartial, chosen, accepting);
        }

        TradeReview withChosen(int heroId) {
            return new TradeReview(trade, loading, picks, partial, heroId, accepting);
        }

        TradeReview withAccepting(boolean newAccepting) {
            return new TradeReview(trade, loading, picks, partial, chosenId, newAccepting);
        }

        @Nullable
        public TradeSuggestions.Pick chosen() {
            for (TradeSuggestions.Pick p : picks) {
                if (p.hero.characterId == chosenId) return p;
            }
            return null;
        }

        boolean find(List<TradeOffer> trades) {
            for (TradeOffer t : trades) {
                if (t.id.equals(trade.id)) return true;
            }
            return false;
        }
    }

    /** Herói que chegou por troca (aceita agora, ou aceita pelo amigo desde a última visita). Imutável. */
    public static final class HeroReceived {
        public final PublicProfile.Hero hero;
        /** Com quem foi a troca. */
        public final UserCard partner;

        HeroReceived(PublicProfile.Hero hero, UserCard partner) {
            this.hero = hero;
            this.partner = partner;
        }
    }

    /** Aviso curto depois de uma ação. */
    public enum Message {
        REQUEST_SENT,
        BECAME_FRIENDS,
        REQUEST_DECLINED,
        REQUEST_CANCELED,
        USERNAME_SAVED,
        CONNECTED,
        TRADE_DECLINED,
        TRADE_CANCELED,
        /** A troca não vale mais (ex.: o jogador já ganhou o herói de outro jeito). */
        TRADE_INVALID,
        /** A proposta não existe mais (o amigo cancelou). */
        TRADE_GONE,
        ACTION_FAILED_OFFLINE,
        ACTION_FAILED
    }
}
