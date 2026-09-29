package com.ovigia.app.profile;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.CharacterRepository;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.social.AchievementProgress;
import com.ovigia.app.social.Achievements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Monta o perfil da conta logada: dados e imagens da conta, coleção ({@link CollectionStore}),
 * histórico do {@link LearningStore} e nomes/fotos do elenco
 * ({@link CharacterRepository}, normalmente servido do cache em disco).
 *
 * Sem sessão, publica {@link ProfileUiState.Status#SIGNED_OUT} e a tela manda
 * para o login. Se o elenco não carregar, o perfil aparece mesmo assim — só sem
 * nomes e fotos no histórico. Escopo: a própria tela de perfil.
 */
public class ProfileViewModel extends ViewModel {

    static final int MAX_FAVORITES = 5;
    static final int MAX_RECENT_GAMES = 10;

    private final CharacterRepository repository;
    private final LearningStore learningStore;
    private final AccountStore accountStore;
    private final CollectionStore collectionStore;
    private final Supplier<RosterCatalog> roster;
    private final Executor ioExecutor;
    private final Executor mainExecutor;

    private final MutableLiveData<ProfileUiState> state = new MutableLiveData<>(ProfileUiState.loading());
    private boolean started = false;

    public ProfileViewModel(CharacterRepository repository, LearningStore learningStore,
                            AccountStore accountStore, CollectionStore collectionStore,
                            Executor ioExecutor, Executor mainExecutor) {
        this(repository, learningStore, accountStore, collectionStore, () -> null, ioExecutor, mainExecutor);
    }

    /** @param roster equipes e vilania para as conquistas; lido no I/O, pode devolver {@code null} */
    public ProfileViewModel(CharacterRepository repository, LearningStore learningStore,
                            AccountStore accountStore, CollectionStore collectionStore,
                            Supplier<RosterCatalog> roster, Executor ioExecutor, Executor mainExecutor) {
        this.repository = repository;
        this.learningStore = learningStore;
        this.accountStore = accountStore;
        this.collectionStore = collectionStore;
        this.roster = roster;
        this.ioExecutor = ioExecutor;
        this.mainExecutor = mainExecutor;
    }

    public LiveData<ProfileUiState> state() { return state; }

    /** Idempotente: carrega uma vez por ViewModel (sobrevive à rotação). */
    public void start() {
        if (started) return;
        started = true;
        load();
    }

    /** Recarrega mantendo o que está na tela (ex.: ao voltar da edição do perfil). */
    public void reload() {
        started = true;
        load();
    }

    private void load() {
        ioExecutor.execute(() -> {
            AccountStore.Account account = accountStore.currentAccount();
            if (account == null) {
                mainExecutor.execute(() -> state.setValue(ProfileUiState.signedOut()));
                return;
            }
            ProfileUiState.Identity identity = new ProfileUiState.Identity(account.name, account.email,
                    account.username, account.bio, account.avatar, account.banner);
            List<CollectionStore.Entry> collection = collectionStore.list(account.id);
            LearningStore.PlayerHistory history = learningStore.history(account.id, MAX_FAVORITES, MAX_RECENT_GAMES);
            List<Integer> heroIds = new ArrayList<>();
            for (CollectionStore.Entry e : collection) heroIds.add(e.characterId);
            List<AchievementProgress> achievements = Achievements.evaluate(heroIds, RosterCatalog.orNull(roster),
                    history.stats);
            mainExecutor.execute(() -> onLocalData(identity, collection, history, achievements));
        });
    }

    public void signOut() {
        ioExecutor.execute(() -> {
            accountStore.signOut();
            mainExecutor.execute(() -> state.setValue(ProfileUiState.signedOut()));
        });
    }

    private void onLocalData(ProfileUiState.Identity identity, List<CollectionStore.Entry> collection,
                             LearningStore.PlayerHistory history, List<AchievementProgress> achievements) {
        if (history.favorites.isEmpty() && history.recentGames.isEmpty()) {
            // Nada para ilustrar: não vale carregar o elenco (nem arriscar a rede).
            publish(identity, collection, history, achievements, Collections.emptyMap());
            return;
        }
        repository.loadCharacters(new CharacterRepository.Callback() {
            @Override
            public void onSuccess(List<CharacterProfile> profiles, Map<String, String> questionTextByKey) {
                Map<Integer, CharacterProfile> byId = new HashMap<>();
                for (CharacterProfile p : profiles) byId.put(p.id, p);
                publish(identity, collection, history, achievements, byId);
            }

            @Override
            public void onError(CharacterRepository.LoadError error) {
                publish(identity, collection, history, achievements, Collections.emptyMap());
            }
        });
    }

    private void publish(ProfileUiState.Identity identity, List<CollectionStore.Entry> collection,
                         LearningStore.PlayerHistory history, List<AchievementProgress> achievements,
                         Map<Integer, CharacterProfile> byId) {
        // O jogador pode ter saído da conta enquanto o elenco carregava.
        if (state.getValue() != null && state.getValue().status == ProfileUiState.Status.SIGNED_OUT) return;

        List<ProfileUiState.CollectionItem> collected = new ArrayList<>();
        for (CollectionStore.Entry e : collection) {
            collected.add(new ProfileUiState.CollectionItem(e.characterId, e.name, e.imageUrl, e.savedAt));
        }
        List<ProfileUiState.FavoriteItem> favorites = new ArrayList<>();
        for (LearningStore.CharacterCount c : history.favorites) {
            CharacterProfile p = byId.get(c.characterId);
            favorites.add(new ProfileUiState.FavoriteItem(c.characterId,
                    p != null ? p.name : null, p != null ? p.thumbnailUrl : null, c.count));
        }
        List<ProfileUiState.RecentItem> recent = new ArrayList<>();
        for (LearningStore.GameRecord g : history.recentGames) {
            CharacterProfile p = byId.get(g.characterId);
            recent.add(new ProfileUiState.RecentItem(g.characterId,
                    p != null ? p.name : null, p != null ? p.thumbnailUrl : null, g.outcome, g.timestamp));
        }
        LearningStore.Stats stats = history.stats;
        state.setValue(ProfileUiState.loaded(identity, stats.gamesPlayed, stats.engineWins,
                (int) Math.round(stats.engineWinRate() * 100), stats.distinctCharacters,
                collected, favorites, recent, achievements));
    }

    public static final class Factory implements ViewModelProvider.Factory {

        private final CharacterRepository repository;
        private final LearningStore learningStore;
        private final AccountStore accountStore;
        private final CollectionStore collectionStore;
        private final Supplier<RosterCatalog> roster;
        private final Executor ioExecutor;
        private final Executor mainExecutor;

        public Factory(CharacterRepository repository, LearningStore learningStore,
                       AccountStore accountStore, CollectionStore collectionStore,
                       Supplier<RosterCatalog> roster, Executor ioExecutor, Executor mainExecutor) {
            this.repository = repository;
            this.learningStore = learningStore;
            this.accountStore = accountStore;
            this.collectionStore = collectionStore;
            this.roster = roster;
            this.ioExecutor = ioExecutor;
            this.mainExecutor = mainExecutor;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new ProfileViewModel(repository, learningStore, accountStore, collectionStore,
                    roster, ioExecutor, mainExecutor);
        }
    }
}
