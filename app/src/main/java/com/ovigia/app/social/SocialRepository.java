package com.ovigia.app.social;

import android.util.Log;

import androidx.annotation.Nullable;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.learning.LearningStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Os amigos online da conta logada.
 *
 * A conta do jogador já é a conta online ({@link AccountStore}): para aparecer
 * para os amigos falta só escolher um @usuario. A partir daí o perfil (cartão,
 * bio, banner, números, heróis e conquistas) é publicado sempre que algo muda.
 *
 * Amigos também trocam heróis, 1 por 1 ({@link TradeOffer}): ninguém perde o
 * seu, cada um ganha o do outro. Lendários ficam fora das trocas
 * ({@link TradeSuggestions#isTradeable}).
 *
 * Operações bloqueantes (disco e rede): chamar no executor social.
 */
public final class SocialRepository {

    private static final String TAG = "SocialRepository";

    public enum Status {
        /** O app não tem servidor configurado. */
        NOT_CONFIGURED,
        /** Ninguém logado neste aparelho. */
        SIGNED_OUT,
        /** Logado, mas ainda sem @usuario. */
        NEEDS_USERNAME,
        READY
    }

    /** Onde a conta logada está no caminho até os amigos. Imutável. */
    public static final class Session {
        public final Status status;
        @Nullable public final AccountStore.Account account;
        /** Cartão da própria conta; só em {@link Status#READY}. */
        @Nullable public final UserCard card;

        Session(Status status, @Nullable AccountStore.Account account, @Nullable UserCard card) {
            this.status = status;
            this.account = account;
            this.card = card;
        }
    }

    /** Perfil de um amigo mais o que dá para trocar com ele. */
    public static final class FriendProfile {
        public final PublicProfile profile;
        /** Heróis que o jogador também já desbloqueou. */
        public final Set<Integer> myUnlockedIds;
        /** Heróis do amigo que o jogador não tem, do mais interessante para ele ao menos. */
        public final List<TradeSuggestions.Pick> wantPicks;
        /** Heróis do jogador que o amigo não tem, do mais interessante para o amigo ao menos. */
        public final List<TradeSuggestions.Pick> offerPicks;
        /** Propostas pendentes que o jogador fez a esse amigo. */
        public final List<TradeOffer> sentTrades;

        FriendProfile(PublicProfile profile, Set<Integer> myUnlockedIds, List<TradeSuggestions.Pick> wantPicks,
                      List<TradeSuggestions.Pick> offerPicks, List<TradeOffer> sentTrades) {
            this.profile = profile;
            this.myUnlockedIds = Collections.unmodifiableSet(myUnlockedIds);
            this.wantPicks = Collections.unmodifiableList(wantPicks);
            this.offerPicks = Collections.unmodifiableList(offerPicks);
            this.sentTrades = Collections.unmodifiableList(sentTrades);
        }
    }

    /** Heróis que dá para ganhar ao aceitar uma proposta. */
    public static final class TradeChoices {
        /** Do catálogo de quem propôs, sem os que o jogador já tem; o oferecido sempre entra. */
        public final List<TradeSuggestions.Pick> picks;
        /** O catálogo não carregou: só o herói oferecido está em {@link #picks}. */
        public final boolean partial;

        TradeChoices(List<TradeSuggestions.Pick> picks, boolean partial) {
            this.picks = Collections.unmodifiableList(picks);
            this.partial = partial;
        }
    }

    private final SocialBackend backend;
    private final AccountStore accountStore;
    private final CollectionStore collectionStore;
    private final LearningStore learningStore;
    private final Supplier<RosterCatalog> roster;
    private final Executor executor;
    private final LongSupplier clock;
    private final AtomicBoolean publishQueued = new AtomicBoolean(false);

    /**
     * @param roster   equipes e vilania para as conquistas; pode devolver {@code null}
     * @param executor onde rodam as publicações em segundo plano
     */
    public SocialRepository(SocialBackend backend, AccountStore accountStore, CollectionStore collectionStore,
                            LearningStore learningStore, Supplier<RosterCatalog> roster, Executor executor,
                            LongSupplier clock) {
        this.backend = backend;
        this.accountStore = accountStore;
        this.collectionStore = collectionStore;
        this.learningStore = learningStore;
        this.roster = roster;
        this.executor = executor;
        this.clock = clock;
    }

    public boolean isConfigured() {
        return backend.isConfigured();
    }

    // ---------------------------------------------------------------- sessão

    /** Em que passo a conta logada está até os amigos. Sem rede, vale o que o aparelho já tem. */
    public Session session() {
        if (!backend.isConfigured()) return new Session(Status.NOT_CONFIGURED, null, null);
        AccountStore.Account account = accountStore.currentAccount();
        if (account == null) return new Session(Status.SIGNED_OUT, null, null);
        if (account.username == null) return new Session(Status.NEEDS_USERNAME, account, null);
        return new Session(Status.READY, account, cardFor(account));
    }

    /** Reserva o @usuario (ou troca o atual) e publica o perfil. */
    public Session claimUsername(String raw) throws SocialException {
        Session session = session();
        if (session.status != Status.NEEDS_USERNAME && session.status != Status.READY) {
            throw new SocialException(session.status == Status.NOT_CONFIGURED
                    ? SocialException.Error.NOT_CONFIGURED : SocialException.Error.NOT_CONNECTED);
        }
        String username = Username.normalize(raw);
        if (!Username.isValid(username)) throw new SocialException(SocialException.Error.USERNAME_INVALID);
        AccountStore.Account account = session.account;
        UserCard card = new UserCard(account.id, username, account.name, account.avatar);
        backend.claimUsername(card, account.username);
        accountStore.setUsername(account.id, username);
        Session ready = session();
        publishIgnoringErrors(ready);
        return ready;
    }

    // ---------------------------------------------------------------- publicação

    public void publish() throws SocialException {
        backend.publish(buildProfile(requireReady()));
    }

    /**
     * Publica em segundo plano se a conta estiver online. Pedidos que chegam
     * enquanto um já espera na fila viram um só.
     */
    public void publishQuietly() {
        if (!backend.isConfigured() || !publishQueued.compareAndSet(false, true)) return;
        executor.execute(() -> {
            publishQueued.set(false);
            try {
                publishIgnoringErrors(session());
            } catch (RuntimeException e) {
                // Roda solto numa thread de fundo: se nem a sessão der para ler, o app segue sem publicar.
                Log.w(TAG, "Não foi possível publicar o perfil", e);
            }
        });
    }

    private void publishIgnoringErrors(Session session) {
        if (session.status != Status.READY) return;
        try {
            backend.publish(buildProfile(session));
        } catch (SocialException | RuntimeException ignored) {
            // Sem rede: a próxima mudança (ou a próxima visita à aba) publica de novo.
        }
    }

    /** O que os amigos veem desta conta, montado a partir dos dados locais. */
    PublicProfile buildProfile(Session session) {
        AccountStore.Account account = session.account;
        List<PublicProfile.Hero> heroes = myHeroes(session);
        LearningStore.Stats stats = learningStore.stats(account.id);
        List<AchievementProgress> achievements = Achievements.evaluate(idsOf(heroes),
                RosterCatalog.orNull(roster), stats);
        return new PublicProfile(session.card, account.bio, account.banner,
                stats.gamesPlayed, stats.engineWins, stats.distinctCharacters, heroes, achievements,
                clock.getAsLong());
    }

    // ---------------------------------------------------------------- amigos

    /**
     * Amigos, pedidos e propostas de troca. As trocas que um amigo aceitou são
     * concluídas aqui: o herói pedido entra na coleção e a proposta é apagada
     * (ver {@link FriendsHub#completedTrades}).
     */
    public FriendsHub hub() throws SocialException {
        Session session = requireReady();
        FriendsHub hub = backend.loadHub();
        List<TradeOffer> trades;
        try {
            trades = backend.loadTrades();
        } catch (SocialException e) {
            // Amigos e pedidos carregaram: as trocas aparecem na próxima atualização.
            return hub;
        }
        String me = session.card.uid;
        List<TradeOffer> completed = new ArrayList<>();
        for (TradeOffer t : trades) {
            if (t.status == TradeOffer.Status.ACCEPTED && t.from.uid.equals(me) && complete(session, t)) {
                completed.add(t);
            }
        }
        // Os amigos veem o herói novo no perfil.
        if (!completed.isEmpty()) publishQuietly();
        return hub.withTrades(me, trades, completed);
    }

    /**
     * O amigo aceitou: o herói pedido (que o servidor já deu) aparece na coleção e o aviso sai da lista.
     * (Lendários ficam fora das trocas: o servidor recusa a proposta.)
     */
    private boolean complete(Session session, TradeOffer trade) {
        collectionStore.importEntry(session.account.id, trade.want.characterId, trade.want.name,
                trade.want.imageUrl, clock.getAsLong());
        try {
            backend.deleteTrade(trade.id);
            return true;
        } catch (SocialException e) {
            // O herói já está aqui; a próxima carga tenta apagar de novo (e só então avisa).
            return false;
        }
    }

    /** Quem usa esse @usuario. {@link SocialException.Error#NOT_FOUND} se ninguém. */
    public UserCard findByUsername(String raw) throws SocialException {
        requireReady();
        String username = Username.normalize(raw);
        if (!Username.isValid(username)) throw new SocialException(SocialException.Error.USERNAME_INVALID);
        UserCard card = backend.findByUsername(username);
        if (card == null) throw new SocialException(SocialException.Error.NOT_FOUND);
        return card;
    }

    /** Pede amizade; se o outro jogador já tinha pedido, os dois viram amigos na hora. */
    public void sendRequest(UserCard to, FriendsHub hub) throws SocialException {
        Session session = requireReady();
        if (to.uid.equals(session.card.uid)) throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        if (hub.incomingFrom(to.uid) != null) {
            accept(to.uid, hub);
            return;
        }
        backend.sendRequest(session.card, to);
    }

    public void accept(String fromUid, FriendsHub hub) throws SocialException {
        Session session = requireReady();
        backend.acceptRequest(fromUid);
        // Os dois tinham pedido: o pedido que eu mandei não serve mais.
        if (hub.relationshipWith(session.card.uid, fromUid) == FriendsHub.Relationship.REQUEST_RECEIVED) {
            for (FriendRequest r : hub.outgoing) {
                if (r.to.uid.equals(fromUid)) {
                    try {
                        backend.deleteRequest(session.card.uid, fromUid);
                    } catch (SocialException ignored) {
                        // Sobra um pedido para um amigo: a tela já mostra os dois como amigos.
                    }
                }
            }
        }
        // O novo amigo abre o perfil logo em seguida: que ele esteja em dia.
        publishIgnoringErrors(session);
    }

    public void decline(String fromUid) throws SocialException {
        backend.deleteRequest(fromUid, requireReady().card.uid);
    }

    public void cancel(String toUid) throws SocialException {
        backend.deleteRequest(requireReady().card.uid, toUid);
    }

    public void removeFriend(String friendUid) throws SocialException {
        requireReady();
        backend.removeFriend(friendUid);
        // Propostas com quem não é mais amigo não podem ser aceitas: não deixa nenhuma para trás.
        try {
            for (TradeOffer t : backend.loadTrades()) {
                if (t.involves(friendUid)) backend.deleteTrade(t.id);
            }
        } catch (SocialException ignored) {
            // A aba de amigos já esconde propostas de quem não é amigo.
        }
    }

    public FriendProfile friendProfile(String uid) throws SocialException {
        Session session = requireReady();
        PublicProfile profile = backend.loadProfile(uid);
        List<PublicProfile.Hero> myHeroes = myHeroes(session);
        Set<Integer> mine = idsOf(myHeroes);
        List<TradeOffer> sent = new ArrayList<>();
        try {
            for (TradeOffer t : backend.loadTrades()) {
                if (t.status == TradeOffer.Status.PENDING && t.from.uid.equals(session.card.uid)
                        && t.to.uid.equals(uid)) {
                    sent.add(t);
                }
            }
        } catch (SocialException ignored) {
            // O perfil vale sem as propostas: no pior caso o jogador pede de novo e o servidor recusa.
        }
        RosterCatalog catalog = RosterCatalog.orNull(roster);
        return new FriendProfile(profile, mine,
                TradeSuggestions.rank(profile.heroes, mine, catalog),
                TradeSuggestions.rank(myHeroes, idsOf(profile.heroes), catalog), sent);
    }

    // ---------------------------------------------------------------- trocas

    /**
     * Pede o {@code want} de um amigo oferecendo o {@code offer} em troca. O
     * jogador precisa ter o {@code offer} e não ter o {@code want}; o amigo, pelo
     * perfil publicado ({@code friendHeroes}), o contrário.
     */
    public TradeOffer proposeTrade(UserCard friend, List<PublicProfile.Hero> friendHeroes, PublicProfile.Hero want,
                                   PublicProfile.Hero offer) throws SocialException {
        Session session = requireReady();
        String accountId = session.account.id;
        Set<Integer> theirs = idsOf(friendHeroes);
        RosterCatalog catalog = RosterCatalog.orNull(roster);
        if (want.characterId == offer.characterId
                || !TradeSuggestions.isTradeable(want.characterId, catalog)
                || !TradeSuggestions.isTradeable(offer.characterId, catalog)
                || collectionStore.contains(accountId, want.characterId)
                || !collectionStore.contains(accountId, offer.characterId)
                || !theirs.contains(want.characterId)
                || theirs.contains(offer.characterId)) {
            throw new SocialException(SocialException.Error.TRADE_INVALID);
        }
        TradeOffer trade = new TradeOffer(TradeOffer.idFor(session.card.uid, friend.uid, want.characterId),
                session.card, friend, want, offer, TradeOffer.Status.PENDING, clock.getAsLong());
        return backend.proposeTrade(trade);
    }

    /**
     * Heróis que o jogador pode ganhar aceitando {@code trade}: o oferecido e os
     * outros do catálogo de quem propôs que ele ainda não tem. Sem o catálogo
     * (sem rede), só o oferecido.
     */
    public TradeChoices tradeChoices(TradeOffer trade) throws SocialException {
        Session session = requireReady();
        Set<Integer> mine = idsOf(myHeroes(session));
        List<PublicProfile.Hero> candidates = new ArrayList<>();
        candidates.add(trade.offer);
        boolean partial = false;
        try {
            candidates.addAll(backend.loadProfile(trade.from.uid).heroes);
        } catch (SocialException e) {
            partial = true;
        }
        return new TradeChoices(TradeSuggestions.rank(candidates, mine, RosterCatalog.orNull(roster)), partial);
    }

    /**
     * Aceita a proposta ganhando {@code chosen} (o herói oferecido ou outro do
     * catálogo de quem propôs), que entra na coleção na hora. Quem propôs também
     * recebe o herói pedido na hora, e vê o aviso quando o app dele carregar os amigos.
     */
    public void acceptTrade(TradeOffer trade, PublicProfile.Hero chosen) throws SocialException {
        Session session = requireReady();
        String accountId = session.account.id;
        if (!trade.to.uid.equals(session.card.uid)) throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        if (chosen.characterId == trade.want.characterId
                || !TradeSuggestions.isTradeable(chosen.characterId, RosterCatalog.orNull(roster))
                || collectionStore.contains(accountId, chosen.characterId)
                || !collectionStore.contains(accountId, trade.want.characterId)) {
            throw new SocialException(SocialException.Error.TRADE_INVALID);
        }
        backend.acceptTrade(trade.id, chosen);
        collectionStore.importEntry(accountId, chosen.characterId, chosen.name, chosen.imageUrl, clock.getAsLong());
        // Quem propôs vê o perfil deste lado: que ele já mostre o herói novo.
        publishIgnoringErrors(session);
    }

    /** Recusa (se veio para mim) ou cancela (se fui eu que propus). */
    public void dismissTrade(TradeOffer trade) throws SocialException {
        Session session = requireReady();
        if (!trade.involves(session.card.uid)) throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        backend.deleteTrade(trade.id);
    }

    private List<PublicProfile.Hero> myHeroes(Session session) {
        List<PublicProfile.Hero> heroes = new ArrayList<>();
        for (CollectionStore.Entry e : collectionStore.list(session.account.id)) {
            heroes.add(new PublicProfile.Hero(e.characterId, e.name, e.imageUrl, e.savedAt));
        }
        return heroes;
    }

    private static Set<Integer> idsOf(List<PublicProfile.Hero> heroes) {
        Set<Integer> ids = new HashSet<>();
        for (PublicProfile.Hero h : heroes) ids.add(h.characterId);
        return ids;
    }

    // ---------------------------------------------------------------- apoio

    private Session requireReady() throws SocialException {
        Session session = session();
        if (session.status == Status.READY) return session;
        throw new SocialException(session.status == Status.NOT_CONFIGURED
                ? SocialException.Error.NOT_CONFIGURED : SocialException.Error.NOT_CONNECTED);
    }

    private static UserCard cardFor(AccountStore.Account account) {
        return new UserCard(account.id, account.username, account.name, account.avatar);
    }
}
