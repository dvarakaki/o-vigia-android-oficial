package com.ovigia.app.premium;

import android.app.Activity;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ovigia.app.premium.InfiniteState.Status;
import com.ovigia.app.util.Event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * O Vigia do Infinito da conta logada: quem compra vira, e os personagens
 * lendários passam a entrar na coleção.
 *
 * A compra é única e é feita no Google Play, presa à conta do O Vigia que
 * comprou (o {@code accountTag} vai junto, sem revelar o id da conta). Outra
 * conta do O Vigia no mesmo Google Play não herda a compra.
 *
 * Com o {@link Server} (a API do O Vigia), é ele quem decide: o app manda o
 * token de cada compra paga, o servidor confere na Google Play Developer API,
 * confirma (acknowledge) e libera os lendários lacrados; e diz se a conta é
 * Vigia do Infinito, em qualquer aparelho em que ela entrar. Sem servidor (os
 * testes da loja), a conferência acontece no aparelho, com a resposta da loja.
 *
 * Tudo na main thread, exceto {@link #isInfinite}.
 */
public final class InfiniteWatcher {

    /** Avisos de uma vez só, para a tela que estiver aberta. */
    public enum Notice {
        /** A compra acabou de ser confirmada. */
        WELCOME,
        /** O pagamento ficou pendente: a confirmação chega depois. */
        PENDING,
        /** A loja ficou sem internet no meio do caminho. */
        OFFLINE,
        /** A loja recusou ou falhou. */
        FAILED
    }

    /** O servidor que confere as compras e sabe quem é Vigia do Infinito. Bloqueante: só no executor de I/O. */
    public interface Server {
        /** Se a conta é Vigia do Infinito; {@code null} se não deu para saber (sem rede e sem cópia). */
        @Nullable
        Boolean isInfinite(String accountId) throws Exception;

        /** Confere a compra e devolve se a conta é Vigia do Infinito depois disso. */
        boolean verify(String accountId, String purchaseToken) throws Exception;

        /** A compra já é de outra conta do O Vigia (o {@link #verify} lançou isso). */
        boolean isInUse(Exception failure);
    }

    private final Billing billing;
    private final Supplier<String> currentAccountId;
    @Nullable private final Server server;
    private final Executor io;
    private final Executor main;
    private final MutableLiveData<InfiniteState> state =
            new MutableLiveData<>(new InfiniteState(Status.CHECKING, null, false));
    private final MutableLiveData<Event<Notice>> notices = new MutableLiveData<>();
    private final List<Consumer<String>> infiniteListeners = new CopyOnWriteArrayList<>();

    /** Conta que é Vigia do Infinito agora (lida de outras threads). */
    @Nullable private volatile String infiniteAccount;

    @Nullable private Billing.Product product;
    /** Compras do produto neste Google Play; {@code null} até a loja responder a primeira vez. */
    @Nullable private List<Billing.Purchase> purchases;
    private boolean answered = false;
    private boolean querying = false;
    private boolean purchasing = false;

    /** Conta a que {@link #serverInfinite}, {@link #verifying} e {@link #inUse} se referem. */
    @Nullable private String serverAccount;
    /** O que o servidor disse da conta; {@code null} enquanto não respondeu. */
    @Nullable private Boolean serverInfinite;
    /** Tokens já mandados ao servidor (ou indo) para a conta. */
    private final Set<String> verifying = new HashSet<>();
    /** Tokens que não chegaram ao servidor (sem rede): voltam na próxima {@link #refresh}. */
    private final Set<String> failed = new HashSet<>();
    /** O servidor disse que a compra é de outra conta do O Vigia. */
    private boolean inUse = false;

    /**
     * @param currentAccountId id da conta logada (ou {@code null}); rápido, chamado na main thread
     */
    public InfiniteWatcher(Billing billing, Supplier<String> currentAccountId) {
        this(billing, currentAccountId, null, Runnable::run, Runnable::run);
    }

    /**
     * @param io   onde rodam as chamadas ao servidor
     * @param main a main thread, onde o resultado volta
     */
    public InfiniteWatcher(Billing billing, Supplier<String> currentAccountId, @Nullable Server server,
                           Executor io, Executor main) {
        this.billing = billing;
        this.currentAccountId = currentAccountId;
        this.server = server;
        this.io = io;
        this.main = main;
        billing.setListener(this::onPurchasesUpdated);
    }

    public LiveData<InfiniteState> state() {
        return state;
    }

    public LiveData<Event<Notice>> notices() {
        return notices;
    }

    /**
     * Avisado (na main thread, antes da tela) sempre que a loja confirma que a
     * conta é Vigia do Infinito — inclusive de novo, a cada consulta.
     */
    public void addInfiniteListener(Consumer<String> listener) {
        infiniteListeners.add(listener);
    }

    /** Se a conta é Vigia do Infinito, pelo que a loja disse da última vez. Qualquer thread. */
    public boolean isInfinite(@Nullable String accountId) {
        return accountId != null && accountId.equals(infiniteAccount);
    }

    /** Pergunta à loja de novo (ao abrir o app, ao entrar na tela de compra, num "tentar de novo"). */
    public void refresh() {
        verifying.removeAll(failed);
        failed.clear();
        askServer();
        if (querying) return;
        querying = true;
        publish();
        billing.query((found, owned, failure) -> {
            querying = false;
            answered = true;
            if (found != null) product = found;
            if (owned != null) {
                purchases = new ArrayList<>(owned);
                acknowledge(owned);
            }
            publish();
        });
    }

    /** Pergunta ao servidor se a conta logada é Vigia do Infinito. */
    private void askServer() {
        String account = currentAccountId.get();
        if (server == null || account == null) return;
        io.execute(() -> {
            Boolean infinite;
            try {
                infinite = server.isInfinite(account);
            } catch (Exception e) {
                infinite = null;
            }
            Boolean answer = infinite;
            main.execute(() -> {
                syncAccount();
                if (!account.equals(serverAccount) || answer == null) return;
                // Só sobe: um "não" atrasado não desfaz uma compra que acabou de ser conferida.
                if (!Boolean.TRUE.equals(serverInfinite)) serverInfinite = answer;
                publish();
            });
        });
    }

    /** Entrou, saiu ou trocou de conta: a compra é de uma conta só. */
    public void onSessionChanged() {
        purchasing = false;
        serverAccount = null;
        publish();
        refresh();
    }

    /** Abre a tela de pagamento da loja para a conta logada. */
    public void purchase(Activity activity) {
        String account = currentAccountId.get();
        InfiniteState current = state.getValue();
        if (account == null || product == null || current == null || !current.canPurchase()) return;
        Billing.Response response = billing.launchPurchase(activity, product, accountTag(account));
        switch (response) {
            case OK:
                purchasing = true;
                publish();
                break;
            case ALREADY_OWNED:
                // O Google Play já tem a compra: a consulta descobre de quem ela é.
                refresh();
                break;
            case CANCELED:
                break;
            case NETWORK:
                notify(Notice.OFFLINE);
                break;
            default:
                notify(Notice.FAILED);
                break;
        }
    }

    private void onPurchasesUpdated(Billing.Response response, List<Billing.Purchase> updated) {
        Status before = currentStatus();
        purchasing = false;
        switch (response) {
            case OK:
                merge(updated);
                acknowledge(updated);
                break;
            case ALREADY_OWNED:
                refresh();
                break;
            case CANCELED:
                break;
            case NETWORK:
                notify(Notice.OFFLINE);
                break;
            default:
                notify(Notice.FAILED);
                break;
        }
        publish();
        Status after = currentStatus();
        if (after == Status.OWNED && before != Status.OWNED) {
            notify(Notice.WELCOME);
        } else if (after == Status.PENDING && before != Status.PENDING) {
            notify(Notice.PENDING);
        }
    }

    /** Troca as compras que mudaram pelas versões novas (o mesmo token é a mesma compra). */
    private void merge(List<Billing.Purchase> updated) {
        List<Billing.Purchase> merged = new ArrayList<>();
        if (purchases != null) {
            for (Billing.Purchase old : purchases) {
                boolean replaced = false;
                for (Billing.Purchase fresh : updated) {
                    if (fresh.token.equals(old.token)) replaced = true;
                }
                if (!replaced) merged.add(old);
            }
        }
        merged.addAll(updated);
        purchases = merged;
        answered = true;
    }

    /**
     * Toda compra paga precisa ser confirmada, ou o Google Play devolve o dinheiro em 3 dias.
     * Com servidor, quem confirma é ele, depois de conferir.
     */
    private void acknowledge(List<Billing.Purchase> list) {
        if (server != null) return;
        for (Billing.Purchase p : list) {
            if (p.state == Billing.Purchase.State.PURCHASED && !p.acknowledged) billing.acknowledge(p.token);
        }
    }

    /** Manda ao servidor as compras pagas desta conta que ele ainda não viu. */
    private void verifyWithServer(String account) {
        if (server == null || purchases == null) return;
        String tag = accountTag(account);
        for (Billing.Purchase p : purchases) {
            boolean mine = p.accountTag == null || p.accountTag.equals(tag);
            if (!mine || p.state != Billing.Purchase.State.PURCHASED || !verifying.add(p.token)) continue;
            String token = p.token;
            io.execute(() -> {
                Boolean infinite = null;
                boolean taken = false;
                try {
                    infinite = server.verify(account, token);
                } catch (Exception e) {
                    taken = server.isInUse(e);
                }
                Boolean answer = infinite;
                boolean refused = taken;
                main.execute(() -> {
                    syncAccount();
                    if (!account.equals(serverAccount)) return;
                    Status before = currentStatus();
                    if (answer != null) {
                        if (!Boolean.TRUE.equals(serverInfinite)) serverInfinite = answer;
                    } else if (refused) {
                        inUse = true;
                    } else {
                        // Sem rede ou o servidor falhou: tenta de novo na próxima consulta.
                        failed.add(token);
                        notify(Notice.OFFLINE);
                    }
                    publish();
                    if (currentStatus() == Status.OWNED && before != Status.OWNED) notify(Notice.WELCOME);
                });
            });
        }
    }

    /** Trocou a conta logada: o que o servidor disse da anterior não vale para esta. */
    private void syncAccount() {
        String account = currentAccountId.get();
        if (Objects.equals(account, serverAccount)) return;
        serverAccount = account;
        serverInfinite = null;
        verifying.clear();
        failed.clear();
        inUse = false;
    }

    private void publish() {
        syncAccount();
        String account = currentAccountId.get();
        if (account != null) verifyWithServer(account);
        InfiniteState next = evaluate(account);
        infiniteAccount = next.isInfinite() ? account : null;
        // Os ouvintes (liberar os lacrados) entram na fila de I/O antes de as telas recarregarem.
        if (next.isInfinite()) {
            for (Consumer<String> listener : infiniteListeners) listener.accept(account);
        }
        if (!next.equals(state.getValue())) state.setValue(next);
    }

    private InfiniteState evaluate(@Nullable String account) {
        if (account == null) return new InfiniteState(Status.SIGNED_OUT, null, false);
        String price = product != null ? product.price : null;
        if (server != null) return evaluateWithServer(account, price);
        if (!answered) return new InfiniteState(Status.CHECKING, price, false);

        String tag = accountTag(account);
        boolean owned = false;
        boolean pending = false;
        boolean ownedByOther = false;
        if (purchases != null) {
            for (Billing.Purchase p : purchases) {
                // Sem marca de conta: a compra não saiu do app (código promocional) — vale para quem está logado.
                boolean mine = p.accountTag == null || p.accountTag.equals(tag);
                if (p.state == Billing.Purchase.State.PURCHASED) {
                    if (mine) owned = true; else ownedByOther = true;
                } else if (mine) {
                    pending = true;
                }
            }
        }
        if (owned) return new InfiniteState(Status.OWNED, price, false);
        if (pending) return new InfiniteState(Status.PENDING, price, false);
        if (ownedByOther) return new InfiniteState(Status.OWNED_BY_OTHER_ACCOUNT, price, false);
        if (product != null) return new InfiniteState(Status.AVAILABLE, price, purchasing);
        return new InfiniteState(querying ? Status.CHECKING : Status.UNAVAILABLE, null, false);
    }

    /** Com servidor: ele diz quem é Vigia do Infinito; a loja só conta o que está a caminho. */
    private InfiniteState evaluateWithServer(String account, @Nullable String price) {
        if (Boolean.TRUE.equals(serverInfinite)) return new InfiniteState(Status.OWNED, price, false);
        if (inUse) return new InfiniteState(Status.OWNED_BY_OTHER_ACCOUNT, price, false);
        String tag = accountTag(account);
        boolean paid = false;
        boolean pending = false;
        boolean ownedByOther = false;
        if (purchases != null) {
            for (Billing.Purchase p : purchases) {
                boolean mine = p.accountTag == null || p.accountTag.equals(tag);
                if (!mine) {
                    if (p.state == Billing.Purchase.State.PURCHASED) ownedByOther = true;
                } else if (p.state == Billing.Purchase.State.PURCHASED) {
                    paid = true;
                } else {
                    pending = true;
                }
            }
        }
        // Paga e ainda sem a palavra do servidor: conferindo.
        if (paid || (!answered && serverInfinite == null)) return new InfiniteState(Status.CHECKING, price, false);
        if (pending) return new InfiniteState(Status.PENDING, price, false);
        if (ownedByOther) return new InfiniteState(Status.OWNED_BY_OTHER_ACCOUNT, price, false);
        if (product != null) return new InfiniteState(Status.AVAILABLE, price, purchasing);
        return new InfiniteState(querying ? Status.CHECKING : Status.UNAVAILABLE, null, false);
    }

    private Status currentStatus() {
        InfiniteState current = state.getValue();
        return current != null ? current.status : Status.CHECKING;
    }

    private void notify(Notice notice) {
        notices.setValue(new Event<>(notice));
    }

    /**
     * Marca da conta que vai junto da compra: o SHA-256 do id, para a loja (e o
     * Google) não guardarem o id em si. Cabe no limite de 64 caracteres da loja.
     */
    static String accountTag(String accountId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(accountId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Todo Java tem SHA-256 (é exigido pela especificação).
            throw new IllegalStateException(e);
        }
    }
}
