package com.ovigia.app.premium;

import android.app.Activity;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Loja de mentira, só para o build de debug ({@code FAKE_BILLING=true} no
 * {@code local.properties}): deixa ver o fluxo do Vigia do Infinito inteiro sem
 * publicar o app no Google Play. A "compra" vive só enquanto o processo viver.
 *
 * Nunca entra no release: lá o {@code BuildConfig.FAKE_BILLING} é sempre falso e
 * o R8 apaga esta classe.
 */
public final class DebugBilling implements Billing {

    private static final String PRICE = "R$ 5,00";

    @Nullable private Listener listener;
    private final List<Purchase> purchases = new ArrayList<>();

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void query(QueryCallback callback) {
        callback.onResult(new Product(PRICE, null), new ArrayList<>(purchases), Response.OK);
    }

    @Override
    public Response launchPurchase(Activity activity, Product product, String accountTag) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Loja de teste (debug)")
                .setMessage("Vigia do Infinito por " + PRICE + ". Nada é cobrado: esta loja só existe no build de debug.")
                .setPositiveButton("Comprar", (d, w) -> finish(new Purchase(UUID.randomUUID().toString(),
                        Purchase.State.PURCHASED, false, accountTag)))
                .setNeutralButton("Pagamento pendente", (d, w) -> finish(new Purchase(UUID.randomUUID().toString(),
                        Purchase.State.PENDING, false, accountTag)))
                .setNegativeButton("Cancelar", (d, w) -> deliver(Response.CANCELED, Collections.emptyList()))
                .setOnCancelListener(d -> deliver(Response.CANCELED, Collections.emptyList()))
                .show();
        return Response.OK;
    }

    @Override
    public void acknowledge(String purchaseToken) {
        for (int i = 0; i < purchases.size(); i++) {
            Purchase p = purchases.get(i);
            if (p.token.equals(purchaseToken)) purchases.set(i, new Purchase(p.token, p.state, true, p.accountTag));
        }
    }

    private void finish(Purchase purchase) {
        purchases.add(purchase);
        deliver(Response.OK, Collections.singletonList(purchase));
    }

    private void deliver(Response response, List<Purchase> list) {
        if (listener != null) listener.onPurchasesUpdated(response, list);
    }
}
