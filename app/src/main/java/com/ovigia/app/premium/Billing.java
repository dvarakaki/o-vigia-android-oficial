package com.ovigia.app.premium;

import android.app.Activity;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * A loja que vende o Vigia do Infinito: uma compra única, que vale para sempre.
 *
 * A real é o Google Play ({@link PlayBilling}); o build de debug pode usar uma
 * loja de mentira ({@link DebugBilling}) e os testes, um falso em memória. Quem
 * decide o que a compra significa para a conta é o {@link InfiniteWatcher}.
 *
 * Tudo na main thread, inclusive os callbacks.
 */
public interface Billing {

    /** Id do produto no Play Console (compra única, R$ 5,00). Estável: as compras feitas ficam presas a ele. */
    String PRODUCT_ID = "vigia_do_infinito";

    /** Resposta da loja, já traduzida para o que o app precisa saber. */
    enum Response {
        OK,
        /** O jogador fechou a tela de pagamento. */
        CANCELED,
        /** Esta conta do Google Play já tem o produto. */
        ALREADY_OWNED,
        /** Sem loja neste aparelho, ou o produto não existe nela (app fora do Google Play). */
        UNAVAILABLE,
        /** Sem internet ou loja fora do ar: vale tentar de novo. */
        NETWORK,
        ERROR
    }

    /** Recebe as compras que mudaram fora de uma consulta: fim da tela de pagamento, pagamento pendente que caiu. */
    interface Listener {
        void onPurchasesUpdated(Response response, List<Purchase> purchases);
    }

    interface QueryCallback {
        /**
         * @param product   o produto à venda, ou {@code null} se a loja não o oferece
         * @param purchases as compras do produto nesta conta do Google Play, ou {@code null} se não deu para ler
         * @param failure   por que algo veio {@code null} ({@link Response#OK} se nada faltou)
         */
        void onResult(@Nullable Product product, @Nullable List<Purchase> purchases, Response failure);
    }

    void setListener(Listener listener);

    /** Consulta o produto e as compras dele. */
    void query(QueryCallback callback);

    /**
     * Abre a tela de pagamento da loja. A resposta final chega no {@link Listener};
     * o retorno daqui só diz se a tela abriu.
     *
     * @param accountTag identifica (sem revelar) a conta do O Vigia que está comprando
     */
    Response launchPurchase(Activity activity, Product product, String accountTag);

    /** Confirma a compra para a loja (sem isso o Google Play a estorna em 3 dias). */
    void acknowledge(String purchaseToken);

    /** O produto à venda. */
    final class Product {
        /** Preço já formatado na moeda do jogador ("R$ 5,00"). */
        public final String price;
        /** O objeto da loja, que ela mesma precisa para abrir a compra. */
        @Nullable final Object handle;

        public Product(String price, @Nullable Object handle) {
            this.price = price;
            this.handle = handle;
        }
    }

    /** Uma compra do produto. */
    final class Purchase {
        public enum State { PURCHASED, PENDING }

        public final String token;
        public final State state;
        public final boolean acknowledged;
        /** O {@code accountTag} da compra; {@code null} se ela não veio do app (código promocional). */
        @Nullable public final String accountTag;

        public Purchase(String token, State state, boolean acknowledged, @Nullable String accountTag) {
            this.token = token;
            this.state = state;
            this.acknowledged = acknowledged;
            this.accountTag = accountTag == null || accountTag.isEmpty() ? null : accountTag;
        }
    }
}
