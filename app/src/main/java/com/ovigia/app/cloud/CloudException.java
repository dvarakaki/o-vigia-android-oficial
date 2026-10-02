package com.ovigia.app.cloud;

/** Falha ao falar com o servidor da conta; {@link #reason} diz o que mostrar ao jogador. */
public final class CloudException extends Exception {

    public enum Reason {
        /** O app foi compilado sem o endereço da API. */
        NOT_CONFIGURED,
        /** Sem internet ou o servidor não respondeu a tempo. */
        OFFLINE,
        /** Login: e-mail ou senha não conferem (o servidor não diz qual). */
        WRONG_CREDENTIALS,
        /** Operação na conta logada: a senha informada não confere. */
        WRONG_PASSWORD,
        EMAIL_IN_USE,
        INVALID_EMAIL,
        WEAK_PASSWORD,
        /** Precisa de uma conta com sessão aberta. */
        NOT_SIGNED_IN,
        /** Tentativas demais em pouco tempo: o servidor pediu para esperar. */
        TOO_MANY_ATTEMPTS,
        /** Vigia do Infinito: o Google Play não reconhece a compra. */
        PURCHASE_INVALID,
        /** Vigia do Infinito: a compra já é de outra conta do O Vigia. */
        PURCHASE_IN_USE,
        FAILED
    }

    public final Reason reason;

    public CloudException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public CloudException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }
}
