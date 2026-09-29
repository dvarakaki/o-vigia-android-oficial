package com.ovigia.app.catalog;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.catalog.CatalogUiState.Filter;
import com.ovigia.app.catalog.CatalogUiState.Item;
import com.ovigia.app.catalog.CatalogUiState.Status;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.CharacterRepository;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.util.SearchText;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Catálogo da conta logada: todo o elenco jogável, com os heróis desbloqueados
 * ({@link CollectionStore}) revelados e os demais bloqueados. Se o elenco não
 * carregar (sem cache e sem rede), mostra só os desbloqueados, com nome e foto
 * guardados no desbloqueio.
 */
public class CatalogViewModel extends ViewModel {

    private final AccountStore accountStore;
    private final CollectionStore collectionStore;
    private final CharacterRepository repository;
    private final Executor ioExecutor;
    private final Executor mainExecutor;

    private final MutableLiveData<CatalogUiState> state = new MutableLiveData<>(CatalogUiState.of(Status.LOADING));
    /** Todos os itens, em ordem alfabética; o estado mostra a fatia filtrada. */
    private List<Item> allItems = new ArrayList<>();
    private int unlockedCount;
    private int totalCount;
    private Filter filter = Filter.UNLOCKED;
    private String query = "";
    private boolean started = false;
    private boolean loaded = false;
    private String accountId;

    public CatalogViewModel(AccountStore accountStore, CollectionStore collectionStore, CharacterRepository repository,
                            Executor ioExecutor, Executor mainExecutor) {
        this.accountStore = accountStore;
        this.collectionStore = collectionStore;
        this.repository = repository;
        this.ioExecutor = ioExecutor;
        this.mainExecutor = mainExecutor;
    }

    public LiveData<CatalogUiState> state() { return state; }

    public void start() {
        if (started) return;
        started = true;
        ioExecutor.execute(() -> {
            String account = accountStore.currentAccountId();
            List<CollectionStore.Entry> unlocked = account == null ? null : collectionStore.list(account);
            mainExecutor.execute(() -> {
                accountId = account;
                if (unlocked == null) {
                    state.setValue(CatalogUiState.of(Status.SIGNED_OUT));
                    return;
                }
                repository.loadCharacters(new CharacterRepository.Callback() {
                    @Override
                    public void onSuccess(List<CharacterProfile> profiles, Map<String, String> questionTextByKey) {
                        build(unlocked, profiles);
                    }

                    @Override
                    public void onError(CharacterRepository.LoadError error) {
                        build(unlocked, null);
                    }
                });
            });
        });
    }

    public void setFilter(Filter newFilter) {
        if (newFilter == filter) return;
        filter = newFilter;
        publish();
    }

    public void setQuery(String newQuery) {
        String normalized = newQuery == null ? "" : newQuery;
        if (normalized.equals(query)) return;
        query = normalized;
        publish();
    }

    private void build(List<CollectionStore.Entry> unlocked, List<CharacterProfile> roster) {
        Map<Integer, CollectionStore.Entry> unlockedById = new HashMap<>();
        for (CollectionStore.Entry e : unlocked) unlockedById.put(e.characterId, e);

        List<Item> items = new ArrayList<>();
        if (roster != null) {
            for (CharacterProfile p : roster) {
                CollectionStore.Entry e = unlockedById.remove(p.id);
                items.add(e != null
                        ? new Item(p.id, true, p.name, p.thumbnailUrl, e.savedAt, !e.seenInCatalog)
                        : new Item(p.id, false, p.name, p.thumbnailUrl, 0, false));
            }
            totalCount = roster.size() + unlockedById.size();
        } else {
            totalCount = 0;
        }
        // Desbloqueados que não estão no elenco carregado (ou todos, sem elenco).
        for (CollectionStore.Entry e : unlockedById.values()) {
            items.add(new Item(e.characterId, true, e.name, e.imageUrl, e.savedAt, !e.seenInCatalog));
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        // Bloqueados não têm nome no item: ordena pelo nome real, que só serve à ordem.
        Map<Integer, String> sortName = new HashMap<>();
        if (roster != null) for (CharacterProfile p : roster) sortName.put(p.id, String.valueOf(p.name));
        for (CollectionStore.Entry e : unlocked) sortName.putIfAbsent(e.characterId, String.valueOf(e.name));
        items.sort((a, b) -> collator.compare(sortName.getOrDefault(a.characterId, ""),
                sortName.getOrDefault(b.characterId, "")));

        allItems = items;
        loaded = true;
        unlockedCount = unlocked.size();
        publish();

        // A revelação toca uma vez: os novos já contam como vistos para a próxima visita.
        List<Integer> revealed = new ArrayList<>();
        for (CollectionStore.Entry e : unlocked) {
            if (!e.seenInCatalog) revealed.add(e.characterId);
        }
        String account = accountId;
        if (!revealed.isEmpty() && account != null) {
            ioExecutor.execute(() -> collectionStore.markSeenInCatalog(account, revealed));
        }
    }

    private void publish() {
        // Antes de carregar, filtro e busca só ficam guardados.
        if (!loaded) return;
        String needle = SearchText.fold(query);
        List<Item> visible = new ArrayList<>();
        for (Item item : allItems) {
            if (filter == Filter.UNLOCKED && !item.unlocked) continue;
            // Buscar só encontra quem já foi desbloqueado: não revela nomes bloqueados.
            if (!needle.isEmpty() && (!item.unlocked || !SearchText.fold(item.name).contains(needle))) continue;
            visible.add(item);
        }
        state.setValue(new CatalogUiState(Status.READY, visible, unlockedCount, totalCount, filter, query));
    }

    public static final class Factory implements ViewModelProvider.Factory {

        private final AccountStore accountStore;
        private final CollectionStore collectionStore;
        private final CharacterRepository repository;
        private final Executor ioExecutor;
        private final Executor mainExecutor;

        public Factory(AccountStore accountStore, CollectionStore collectionStore, CharacterRepository repository,
                       Executor ioExecutor, Executor mainExecutor) {
            this.accountStore = accountStore;
            this.collectionStore = collectionStore;
            this.repository = repository;
            this.ioExecutor = ioExecutor;
            this.mainExecutor = mainExecutor;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new CatalogViewModel(accountStore, collectionStore, repository, ioExecutor, mainExecutor);
        }
    }
}
