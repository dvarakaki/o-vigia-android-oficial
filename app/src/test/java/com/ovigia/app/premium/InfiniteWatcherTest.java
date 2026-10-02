package com.ovigia.app.premium;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.premium.InfiniteState.Status;
import com.ovigia.app.premium.InfiniteWatcher.Notice;
import com.ovigia.app.util.Event;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A compra do Vigia do Infinito, vista pelo app: de quem ela é e o que a tela mostra. */
public class InfiniteWatcherTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    private FakeStore store;
    private String account;
    private InfiniteWatcher watcher;
    private final List<String> released = new ArrayList<>();

    @Before
    public void setUp() {
        store = new FakeStore();
        account = "uid-ana";
        watcher = new InfiniteWatcher(store, () -> account);
        watcher.addInfiniteListener(released::add);
    }

    private InfiniteState state() {
        return watcher.state().getValue();
    }

    private Notice lastNotice() {
        Event<Notice> event = watcher.notices().getValue();
        return event == null ? null : event.peek();
    }

    @Test
    public void beforeTheStoreAnswers_itIsChecking() {
        store.answer = false;
        watcher.refresh();
        assertEquals(Status.CHECKING, state().status);
        assertFalse(watcher.isInfinite(account));
    }

    @Test
    public void storeOffersTheProduct_withItsPrice() {
        watcher.refresh();
        assertEquals(Status.AVAILABLE, state().status);
        assertEquals("R$ 5,00", state().price);
        assertTrue(state().canPurchase());
    }

    @Test
    public void purchase_tagsTheAccount_andWelcomesTheNewInfinite() {
        watcher.refresh();
        watcher.purchase(null);
        assertTrue("tela de pagamento aberta", state().purchasing);
        assertFalse("não dá para abrir duas vezes", state().canPurchase());
        assertEquals(InfiniteWatcher.accountTag(account), store.launchedTag);

        store.complete(Billing.Purchase.State.PURCHASED);

        assertEquals(Status.OWNED, state().status);
        assertTrue(watcher.isInfinite(account));
        assertEquals(Notice.WELCOME, lastNotice());
        assertEquals("a compra é confirmada para a loja não estornar", 1, store.acknowledged.size());
        assertTrue("os lacrados são liberados", released.contains(account));
    }

    @Test
    public void accountTag_hidesTheId_andFitsTheStoreLimit() {
        String tag = InfiniteWatcher.accountTag("uid-ana");
        assertEquals(64, tag.length());
        assertFalse(tag.contains("ana"));
        assertEquals("estável", tag, InfiniteWatcher.accountTag("uid-ana"));
    }

    @Test
    public void canceling_goesBackToAvailable_quietly() {
        watcher.refresh();
        watcher.purchase(null);
        store.deliver(Billing.Response.CANCELED, Collections.emptyList());
        assertEquals(Status.AVAILABLE, state().status);
        assertFalse(state().purchasing);
        assertNull(lastNotice());
    }

    @Test
    public void pendingPayment_waits_thenWelcomesWhenItClears() {
        watcher.refresh();
        watcher.purchase(null);
        Billing.Purchase pending = store.complete(Billing.Purchase.State.PENDING);
        assertEquals(Status.PENDING, state().status);
        assertEquals(Notice.PENDING, lastNotice());
        assertFalse(watcher.isInfinite(account));
        assertTrue("pendente não se confirma", store.acknowledged.isEmpty());

        // O boleto caiu com o app aberto: a loja avisa a mesma compra, agora paga.
        store.deliver(Billing.Response.OK, Collections.singletonList(
                new Billing.Purchase(pending.token, Billing.Purchase.State.PURCHASED, false, pending.accountTag)));
        assertEquals(Status.OWNED, state().status);
        assertEquals(Notice.WELCOME, lastNotice());
    }

    @Test
    public void reinstalling_restoresTheOwnPurchase_silently() {
        store.owned.add(new Billing.Purchase("t1", Billing.Purchase.State.PURCHASED, true,
                InfiniteWatcher.accountTag(account)));
        watcher.refresh();
        assertEquals(Status.OWNED, state().status);
        assertNull("restaurar não é comprar: sem festa", lastNotice());
        assertTrue("já confirmada, não confirma de novo", store.acknowledged.isEmpty());
    }

    @Test
    public void purchaseOfAnotherAccount_doesNotCount() {
        store.owned.add(new Billing.Purchase("t1", Billing.Purchase.State.PURCHASED, true,
                InfiniteWatcher.accountTag("uid-bia")));
        watcher.refresh();
        assertEquals(Status.OWNED_BY_OTHER_ACCOUNT, state().status);
        assertFalse(watcher.isInfinite(account));
        assertFalse(state().canPurchase());
    }

    @Test
    public void promoCode_withoutTag_countsForWhoeverIsSignedIn() {
        store.owned.add(new Billing.Purchase("t1", Billing.Purchase.State.PURCHASED, false, null));
        watcher.refresh();
        assertEquals(Status.OWNED, state().status);
        assertEquals(1, store.acknowledged.size());
    }

    @Test
    public void switchingAccounts_reevaluatesTheSamePurchase() {
        store.owned.add(new Billing.Purchase("t1", Billing.Purchase.State.PURCHASED, true,
                InfiniteWatcher.accountTag(account)));
        watcher.refresh();
        assertTrue(watcher.isInfinite("uid-ana"));

        account = "uid-bia";
        watcher.onSessionChanged();
        assertEquals(Status.OWNED_BY_OTHER_ACCOUNT, state().status);
        assertFalse(watcher.isInfinite("uid-ana"));
        assertFalse(watcher.isInfinite("uid-bia"));

        account = null;
        watcher.onSessionChanged();
        assertEquals(Status.SIGNED_OUT, state().status);
    }

    @Test
    public void storeWithoutTheProduct_isUnavailable_andRetries() {
        store.product = null;
        watcher.refresh();
        assertEquals(Status.UNAVAILABLE, state().status);
        assertFalse(state().canPurchase());

        store.product = new Billing.Product("R$ 5,00", null);
        watcher.refresh();
        assertEquals(Status.AVAILABLE, state().status);
    }

    @Test
    public void storeFailure_isReported() {
        watcher.refresh();
        watcher.purchase(null);
        store.deliver(Billing.Response.NETWORK, Collections.emptyList());
        assertEquals(Notice.OFFLINE, lastNotice());
        assertEquals(Status.AVAILABLE, state().status);
    }

    @Test
    public void alreadyOwned_whenLaunching_asksTheStoreAgain() {
        watcher.refresh();
        store.launchResponse = Billing.Response.ALREADY_OWNED;
        store.owned.add(new Billing.Purchase("t1", Billing.Purchase.State.PURCHASED, true,
                InfiniteWatcher.accountTag(account)));
        watcher.purchase(null);
        assertEquals(Status.OWNED, state().status);
    }

    /** Loja em memória, que responde na hora. */
    private static final class FakeStore implements Billing {
        Billing.Product product = new Billing.Product("R$ 5,00", null);
        final List<Purchase> owned = new ArrayList<>();
        final List<String> acknowledged = new ArrayList<>();
        boolean answer = true;
        Response launchResponse = Response.OK;
        String launchedTag;
        private Listener listener;
        private int nextToken = 1;

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void query(QueryCallback callback) {
            if (answer) callback.onResult(product, new ArrayList<>(owned), Response.OK);
        }

        @Override
        public Response launchPurchase(android.app.Activity activity, Product product, String accountTag) {
            launchedTag = accountTag;
            return launchResponse;
        }

        @Override
        public void acknowledge(String purchaseToken) {
            acknowledged.add(purchaseToken);
        }

        Purchase complete(Purchase.State state) {
            Purchase p = new Purchase("token-" + nextToken++, state, false, launchedTag);
            owned.add(p);
            deliver(Response.OK, Collections.singletonList(p));
            return p;
        }

        void deliver(Response response, List<Purchase> purchases) {
            listener.onPurchasesUpdated(response, purchases);
        }
    }
}
