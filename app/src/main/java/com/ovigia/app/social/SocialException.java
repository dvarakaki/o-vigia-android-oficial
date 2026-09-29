package com.ovigia.app.social;

/** Falha de uma operação online; {@link #error} diz o que mostrar ao jogador. */
public final class SocialException extends Exception {

    public enum Error {
        /** O app foi compilado sem configuração do Firebase. */
        NOT_CONFIGURED,
        /** Sem internet ou o servidor não respondeu a tempo. */
        OFFLINE,
        /** A senha não confere com a conta. */
        WRONG_PASSWORD,
        /** Precisa estar conectado (sessão online) para isso. */
        NOT_CONNECTED,
        USERNAME_TAKEN,
        USERNAME_INVALID,
        /** Não existe (ex.: @usuario que ninguém usa). */
        NOT_FOUND,
        /** O servidor recusou (ex.: o perfil de quem não é mais amigo). */
        PERMISSION_DENIED,
        /** A troca não vale mais (ex.: o jogador já tem o herói que ia ganhar, ou não tem mais o que ia dar). */
        TRADE_INVALID,
        UNKNOWN
    }

    public final Error error;

    public SocialException(Error error) {
        super(error.name());
        this.error = error;
    }

    public SocialException(Error error, Throwable cause) {
        super(error.name(), cause);
        this.error = error;
    }
}
