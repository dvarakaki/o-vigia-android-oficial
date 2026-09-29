package com.ovigia.app.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Amigos, pedidos pendentes e propostas de troca da conta conectada. Imutável. */
public final class FriendsHub {

    public static final FriendsHub EMPTY = new FriendsHub(Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList());

    public final List<UserCard> friends;
    /** Pedidos que outros jogadores mandaram para esta conta. */
    public final List<FriendRequest> incoming;
    /** Pedidos que esta conta mandou e ainda não foram respondidos. */
    public final List<FriendRequest> outgoing;
    /** Propostas de troca que amigos fizeram a esta conta, esperando resposta. */
    public final List<TradeOffer> incomingTrades;
    /** Propostas de troca que esta conta fez e ainda não foram respondidas. */
    public final List<TradeOffer> outgoingTrades;
    /**
     * Trocas que o amigo aceitou e que esta carga acabou de concluir: o herói já
     * entrou na coleção. Aparecem uma única vez.
     */
    public final List<TradeOffer> completedTrades;

    public FriendsHub(List<UserCard> friends, List<FriendRequest> incoming, List<FriendRequest> outgoing) {
        this(friends, incoming, outgoing, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public FriendsHub(List<UserCard> friends, List<FriendRequest> incoming, List<FriendRequest> outgoing,
                      List<TradeOffer> incomingTrades, List<TradeOffer> outgoingTrades,
                      List<TradeOffer> completedTrades) {
        this.friends = Collections.unmodifiableList(friends);
        this.incoming = Collections.unmodifiableList(incoming);
        this.outgoing = Collections.unmodifiableList(outgoing);
        this.incomingTrades = Collections.unmodifiableList(incomingTrades);
        this.outgoingTrades = Collections.unmodifiableList(outgoingTrades);
        this.completedTrades = Collections.unmodifiableList(completedTrades);
    }

    /**
     * As trocas de {@code myUid}, separadas: só as pendentes com quem ainda é
     * amigo entram nas listas (com quem desfez a amizade não dá mais para aceitar),
     * já com o cartão atual do amigo (a proposta não guarda a foto).
     */
    public FriendsHub withTrades(String myUid, List<TradeOffer> trades, List<TradeOffer> completed) {
        List<TradeOffer> in = new ArrayList<>();
        List<TradeOffer> out = new ArrayList<>();
        for (TradeOffer t : trades) {
            UserCard partner = friend(t.partnerOf(myUid).uid);
            if (t.status != TradeOffer.Status.PENDING || partner == null) continue;
            if (t.to.uid.equals(myUid)) in.add(t.withPartner(myUid, partner));
            else if (t.from.uid.equals(myUid)) out.add(t.withPartner(myUid, partner));
        }
        return new FriendsHub(friends, incoming, outgoing, in, out, completed);
    }

    @Nullable
    public UserCard friend(String uid) {
        for (UserCard f : friends) {
            if (f.uid.equals(uid)) return f;
        }
        return null;
    }

    /** Como esta conta está ligada a {@code uid}. */
    public Relationship relationshipWith(String myUid, String uid) {
        if (uid.equals(myUid)) return Relationship.SELF;
        if (friend(uid) != null) return Relationship.FRIENDS;
        if (incomingFrom(uid) != null) return Relationship.REQUEST_RECEIVED;
        for (FriendRequest r : outgoing) {
            if (r.to.uid.equals(uid)) return Relationship.REQUEST_SENT;
        }
        return Relationship.NONE;
    }

    @Nullable
    public FriendRequest incomingFrom(String uid) {
        for (FriendRequest r : incoming) {
            if (r.from.uid.equals(uid)) return r;
        }
        return null;
    }

    public enum Relationship {
        SELF,
        NONE,
        REQUEST_SENT,
        REQUEST_RECEIVED,
        FRIENDS
    }
}
