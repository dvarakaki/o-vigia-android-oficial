package com.ovigia.app.premium;

import androidx.annotation.Nullable;

import java.util.Objects;

/** O que a conta logada é diante do Vigia do Infinito. Imutável. */
public final class InfiniteState {

    public enum Status {
        /** Primeira consulta à loja ainda no caminho. */
        CHECKING,
        /** Ninguém logado: a compra fica presa a uma conta do O Vigia. */
        SIGNED_OUT,
        /** Dá para comprar, por {@link #price}. */
        AVAILABLE,
        /** Pagamento iniciado e ainda não confirmado (boleto, por exemplo). */
        PENDING,
        /** A conta é Vigia do Infinito. */
        OWNED,
        /** Este Google Play já tornou outra conta do O Vigia Vigia do Infinito. */
        OWNED_BY_OTHER_ACCOUNT,
        /** A loja não respondeu ou não vende o produto (app instalado fora do Google Play, por exemplo). */
        UNAVAILABLE
    }

    public final Status status;
    /** Preço formatado na moeda do jogador, quando a loja o informou. */
    @Nullable public final String price;
    /** A tela de pagamento da loja está aberta. */
    public final boolean purchasing;

    InfiniteState(Status status, @Nullable String price, boolean purchasing) {
        this.status = status;
        this.price = price;
        this.purchasing = purchasing;
    }

    public boolean isInfinite() {
        return status == Status.OWNED;
    }

    /** O botão de compra pode abrir a loja agora. */
    public boolean canPurchase() {
        return status == Status.AVAILABLE && price != null && !purchasing;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof InfiniteState)) return false;
        InfiniteState other = (InfiniteState) o;
        return status == other.status && purchasing == other.purchasing && Objects.equals(price, other.price);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, price, purchasing);
    }
}
