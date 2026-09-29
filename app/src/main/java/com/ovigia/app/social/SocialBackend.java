package com.ovigia.app.social;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Servidor dos amigos online, para a conta com sessão aberta (o login é do
 * {@code PlayerBackend}). A implementação real é o {@link FirebaseSocialBackend};
 * os testes usam um falso em memória.
 *
 * Todas as operações são bloqueantes (rede): chamar fora da main thread.
 */
public interface SocialBackend {

    /** Se o app tem um servidor para falar. Rápido: pode ser chamado em qualquer thread. */
    boolean isConfigured();

    /** Cartão de uma conta, ou {@code null} se ela ainda não escolheu @usuario. */
    @Nullable
    UserCard loadCard(String uid) throws SocialException;

    /**
     * Reserva {@code card.username} para a conta conectada e grava o cartão,
     * liberando {@code previousUsername} se ele for outro.
     */
    void claimUsername(UserCard card, @Nullable String previousUsername) throws SocialException;

    /** Grava o cartão e o perfil da conta conectada. */
    void publish(PublicProfile profile) throws SocialException;

    /** Cartão de quem usa {@code username} (já normalizado), ou {@code null}. */
    @Nullable
    UserCard findByUsername(String username) throws SocialException;

    FriendsHub loadHub() throws SocialException;

    void sendRequest(UserCard from, UserCard to) throws SocialException;

    /** Aceita o pedido que {@code fromUid} mandou para a conta conectada. */
    void acceptRequest(String fromUid) throws SocialException;

    /** Apaga um pedido (recusar, se veio para mim; cancelar, se fui eu que mandei). */
    void deleteRequest(String fromUid, String toUid) throws SocialException;

    void removeFriend(String friendUid) throws SocialException;

    /** Propostas de troca em que a conta conectada está, de um lado ou do outro, em qualquer status. */
    List<TradeOffer> loadTrades() throws SocialException;

    /** Grava a proposta ({@code trade.from} precisa ser a conta conectada; o status é ignorado). */
    void proposeTrade(TradeOffer trade) throws SocialException;

    /**
     * Aceita a proposta {@code tradeId} feita à conta conectada, trocando o herói
     * oferecido por {@code chosenOffer} (o mesmo, ou outro do catálogo de quem propôs).
     */
    void acceptTrade(String tradeId, PublicProfile.Hero chosenOffer) throws SocialException;

    /** Apaga a proposta (recusar, cancelar, ou concluir depois de aceita). */
    void deleteTrade(String tradeId) throws SocialException;

    /** Perfil completo de um amigo (ou da própria conta). */
    PublicProfile loadProfile(String uid) throws SocialException;
}
