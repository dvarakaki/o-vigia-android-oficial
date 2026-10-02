package com.ovigia.app.premium;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClient.ProductType;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * {@link Billing} com a Google Play Billing Library.
 *
 * Para vender, o produto {@link Billing#PRODUCT_ID} precisa existir no Play
 * Console como produto de compra única, e o app precisa ter vindo do Google
 * Play. Instalado por fora (o APK do GitHub, por exemplo), a loja não oferece o
 * produto e o app mostra a compra como indisponível.
 *
 * A biblioteca nem sempre responde na main thread: tudo que sai daqui volta
 * pelo {@code mainExecutor}.
 */
public final class PlayBilling implements Billing, PurchasesUpdatedListener {

    private static final String TAG = "PlayBilling";

    private final BillingClient client;
    private final Executor mainExecutor;
    /** O que espera a conexão com a loja; {@code null} enquanto não há conexão em andamento. */
    @Nullable private List<Consumer<Response>> waiting;
    @Nullable private Listener listener;

    public PlayBilling(Context context, Executor mainExecutor) {
        this.mainExecutor = mainExecutor;
        client = BillingClient.newBuilder(context.getApplicationContext())
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void query(QueryCallback callback) {
        whenConnected(connection -> {
            if (connection != Response.OK) {
                callback.onResult(null, null, connection);
                return;
            }
            QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                    .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PRODUCT_ID)
                            .setProductType(ProductType.INAPP)
                            .build()))
                    .build();
            client.queryProductDetailsAsync(params, (detailsResult, details) -> {
                Product product = productOf(details == null ? null : details.getProductDetailsList());
                Response detailsResponse = product != null ? Response.OK
                        : detailsResult.getResponseCode() == BillingResponseCode.OK
                        ? Response.UNAVAILABLE : responseOf(detailsResult);
                client.queryPurchasesAsync(
                        QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build(),
                        (purchasesResult, list) -> {
                            List<Purchase> purchases = purchasesResult.getResponseCode() == BillingResponseCode.OK
                                    ? purchasesOf(list) : null;
                            Response failure = purchases == null ? responseOf(purchasesResult) : detailsResponse;
                            mainExecutor.execute(() -> callback.onResult(product, purchases, failure));
                        });
            });
        });
    }

    @Override
    public Response launchPurchase(Activity activity, Product product, String accountTag) {
        if (!client.isReady() || !(product.handle instanceof ProductDetails)) return Response.NETWORK;
        ProductDetails details = (ProductDetails) product.handle;
        BillingFlowParams.ProductDetailsParams.Builder item = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details);
        ProductDetails.OneTimePurchaseOfferDetails offer = details.getOneTimePurchaseOfferDetails();
        if (offer != null && offer.getOfferToken() != null) item.setOfferToken(offer.getOfferToken());
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(item.build()))
                .setObfuscatedAccountId(accountTag)
                .build();
        return responseOf(client.launchBillingFlow(activity, params));
    }

    @Override
    public void acknowledge(String purchaseToken) {
        whenConnected(connection -> {
            if (connection != Response.OK) return;
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
                    result -> {
                        // Falhou: a próxima consulta (o app abre e consulta sempre) tenta de novo.
                        if (result.getResponseCode() != BillingResponseCode.OK) {
                            Log.w(TAG, "Confirmação recusada: " + result.getDebugMessage());
                        }
                    });
        });
    }

    @Override
    public void onPurchasesUpdated(BillingResult result, @Nullable List<com.android.billingclient.api.Purchase> list) {
        Response response = responseOf(result);
        List<Purchase> purchases = purchasesOf(list);
        mainExecutor.execute(() -> {
            if (listener != null) listener.onPurchasesUpdated(response, purchases);
        });
    }

    /** Roda {@code action} com a loja conectada (ou com o motivo de não ter conectado), na main thread. */
    private void whenConnected(Consumer<Response> action) {
        if (client.isReady()) {
            action.accept(Response.OK);
            return;
        }
        if (waiting != null) {
            waiting.add(action);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(action);
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                Response response = responseOf(result);
                mainExecutor.execute(() -> release(response));
            }

            @Override
            public void onBillingServiceDisconnected() {
                // A biblioteca reconecta sozinha na próxima chamada (enableAutoServiceReconnection).
                mainExecutor.execute(() -> release(Response.NETWORK));
            }
        });
    }

    private void release(Response response) {
        List<Consumer<Response>> actions = waiting;
        waiting = null;
        if (actions == null) return;
        for (Consumer<Response> action : actions) action.accept(response);
    }

    @Nullable
    private static Product productOf(@Nullable List<ProductDetails> list) {
        if (list == null) return null;
        for (ProductDetails details : list) {
            if (!PRODUCT_ID.equals(details.getProductId())) continue;
            ProductDetails.OneTimePurchaseOfferDetails offer = details.getOneTimePurchaseOfferDetails();
            if (offer != null) return new Product(offer.getFormattedPrice(), details);
        }
        return null;
    }

    private static List<Purchase> purchasesOf(@Nullable List<com.android.billingclient.api.Purchase> list) {
        List<Purchase> purchases = new ArrayList<>();
        if (list == null) return purchases;
        for (com.android.billingclient.api.Purchase p : list) {
            if (!p.getProducts().contains(PRODUCT_ID)) continue;
            Purchase.State state;
            switch (p.getPurchaseState()) {
                case com.android.billingclient.api.Purchase.PurchaseState.PURCHASED:
                    state = Purchase.State.PURCHASED;
                    break;
                case com.android.billingclient.api.Purchase.PurchaseState.PENDING:
                    state = Purchase.State.PENDING;
                    break;
                default:
                    continue;
            }
            String tag = p.getAccountIdentifiers() != null ? p.getAccountIdentifiers().getObfuscatedAccountId() : null;
            purchases.add(new Purchase(p.getPurchaseToken(), state, p.isAcknowledged(), tag));
        }
        return purchases;
    }

    private static Response responseOf(BillingResult result) {
        switch (result.getResponseCode()) {
            case BillingResponseCode.OK:
                return Response.OK;
            case BillingResponseCode.USER_CANCELED:
                return Response.CANCELED;
            case BillingResponseCode.ITEM_ALREADY_OWNED:
                return Response.ALREADY_OWNED;
            case BillingResponseCode.BILLING_UNAVAILABLE:
            case BillingResponseCode.ITEM_UNAVAILABLE:
            case BillingResponseCode.FEATURE_NOT_SUPPORTED:
            case BillingResponseCode.DEVELOPER_ERROR:
                return Response.UNAVAILABLE;
            case BillingResponseCode.SERVICE_UNAVAILABLE:
            case BillingResponseCode.SERVICE_DISCONNECTED:
            case BillingResponseCode.NETWORK_ERROR:
                return Response.NETWORK;
            default:
                Log.w(TAG, "Resposta inesperada da loja: " + result.getResponseCode() + " " + result.getDebugMessage());
                return Response.ERROR;
        }
    }
}
