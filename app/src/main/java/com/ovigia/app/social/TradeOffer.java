package com.ovigia.app.social;

/**
 * Proposta de troca de heróis entre dois amigos, sempre 1 por 1: {@link #from}
 * pede o {@link #want} (que {@link #to} tem e ele não) e oferece o
 * {@link #offer} (que ele tem e {@link #to} não). Imutável.
 *
 * Ninguém perde nada: aceita a troca, cada um ganha o herói do outro e continua
 * com o seu. Quem recebe a proposta pode aceitar com outro herói do catálogo de
 * quem propôs no lugar do {@link #offer} — aí o {@link #offer} passa a ser o
 * escolhido.
 */
public final class TradeOffer {

    public enum Status {
        /** Esperando a resposta de {@link #to}. */
        PENDING,
        /**
         * {@link #to} aceitou (e já ganhou o {@link #offer}); falta {@link #from}
         * receber o {@link #want}, o que acontece na próxima vez que o app dele
         * carregar os amigos.
         */
        ACCEPTED
    }

    public final String id;
    /** Cartões como estavam quando a proposta foi feita. */
    public final UserCard from;
    public final UserCard to;
    /** Herói de {@link #to} que {@link #from} quer. */
    public final PublicProfile.Hero want;
    /** Herói de {@link #from} para {@link #to}. */
    public final PublicProfile.Hero offer;
    public final Status status;
    public final long createdAt;

    public TradeOffer(String id, UserCard from, UserCard to, PublicProfile.Hero want, PublicProfile.Hero offer,
                      Status status, long createdAt) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.want = want;
        this.offer = offer;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** Uma proposta aberta por vez para cada herói pedido a cada amigo. */
    public static String idFor(String fromUid, String toUid, int wantId) {
        return fromUid + "_" + toUid + "_" + wantId;
    }

    /** O outro lado da troca, visto por {@code myUid}. */
    public UserCard partnerOf(String myUid) {
        return from.uid.equals(myUid) ? to : from;
    }

    /** O herói que {@code myUid} ganha com a troca. */
    public PublicProfile.Hero heroFor(String myUid) {
        return from.uid.equals(myUid) ? want : offer;
    }

    /** O herói que {@code myUid} passa para o amigo (e continua tendo). */
    public PublicProfile.Hero heroFrom(String myUid) {
        return from.uid.equals(myUid) ? offer : want;
    }

    /** A mesma proposta com outro cartão para o lado de lá (ex.: o atual, com foto). */
    public TradeOffer withPartner(String myUid, UserCard partner) {
        return from.uid.equals(myUid)
                ? new TradeOffer(id, from, partner, want, offer, status, createdAt)
                : new TradeOffer(id, partner, to, want, offer, status, createdAt);
    }

    public boolean involves(String uid) {
        return from.uid.equals(uid) || to.uid.equals(uid);
    }
}
