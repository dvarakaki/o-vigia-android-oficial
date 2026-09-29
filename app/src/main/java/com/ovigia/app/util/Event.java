package com.ovigia.app.util;

import androidx.annotation.VisibleForTesting;

/**
 * Valor de LiveData que deve ser tratado uma única vez (navegação, mensagens).
 *
 * Diferente de um {@code SingleLiveEvent}, o "já consumido" fica no próprio
 * valor, não no LiveData: funciona com vários observadores e com observadores
 * que se reinscrevem (rotação), sem perder nem repetir eventos.
 */
public final class Event<T> {

    private final T content;
    private boolean handled = false;

    public Event(T content) {
        this.content = content;
    }

    /** Devolve o conteúdo na primeira chamada e {@code null} nas seguintes. */
    public T consume() {
        if (handled) return null;
        handled = true;
        return content;
    }

    /** Conteúdo sem marcar como consumido. */
    @VisibleForTesting
    public T peek() {
        return content;
    }
}
