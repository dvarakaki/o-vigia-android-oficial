package com.ovigia.app.catalog;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.catalog.HeroDetailUiState.Status;
import com.ovigia.app.catalog.HeroDetailUiState.TranslationStatus;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.CharacterRepository;
import com.ovigia.app.data.HeroDetailRepository;
import com.ovigia.app.model.CharacterDetail;
import com.ovigia.app.translation.HeroTranslation;
import com.ovigia.app.translation.HeroTranslationRepository;
import com.ovigia.app.translation.TranslationException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Ficha de um herói do catálogo. Só abre para heróis desbloqueados pela conta
 * logada; a ficha em si vem do {@link HeroDetailRepository} (cache ou API) e,
 * como a Comic Vine só escreve em inglês, passa pelo
 * {@link HeroTranslationRepository} quando o app está em outro idioma.
 *
 * A tradução roda enquanto a tela está à vista: sair dela cancela o trabalho
 * (bateria), e voltar continua de onde parou, porque cada trecho pronto fica
 * salvo.
 */
public class HeroDetailViewModel extends ViewModel {

    private final int characterId;
    private final AccountStore accountStore;
    private final CollectionStore collectionStore;
    private final HeroDetailRepository repository;
    private final HeroTranslationRepository translations;
    /** Idioma do app (ISO 639-1); vazio quando ele já é o da API. */
    private final String language;
    private final Executor ioExecutor;
    private final Executor mainExecutor;

    private final MutableLiveData<HeroDetailUiState> state = new MutableLiveData<>(HeroDetailUiState.of(Status.LOADING));
    private boolean started = false;
    private boolean visible = true;
    private HeroDetailUiState preview;
    /** Ficha carregada, guardada até a tradução responder (ver {@link #load()}). */
    private HeroDetailUiState ready;
    private CharacterDetail detail;
    private HeroTranslationRepository.Job translationJob;
    private boolean allowMeteredDownload = false;

    public HeroDetailViewModel(int characterId, AccountStore accountStore, CollectionStore collectionStore,
                               HeroDetailRepository repository, HeroTranslationRepository translations,
                               String language, Executor ioExecutor, Executor mainExecutor) {
        this.characterId = characterId;
        this.accountStore = accountStore;
        this.collectionStore = collectionStore;
        this.repository = repository;
        this.translations = translations;
        this.language = language;
        this.ioExecutor = ioExecutor;
        this.mainExecutor = mainExecutor;
    }

    public LiveData<HeroDetailUiState> state() { return state; }

    public void start() {
        if (started) return;
        started = true;
        ioExecutor.execute(() -> {
            String account = accountStore.currentAccountId();
            List<CollectionStore.Entry> unlocked = account == null ? null : collectionStore.list(account);
            mainExecutor.execute(() -> onAccess(unlocked));
        });
    }

    /** Tenta de novo depois de um erro. */
    public void retry() {
        HeroDetailUiState current = current();
        if (current.status != Status.ERROR || preview == null) return;
        load();
    }

    /** A tela voltou para o jogador: a tradução que faltava continua. */
    public void onVisible() {
        visible = true;
        if (translationJob != null) return;
        if (awaitingTranslation() || current().translationStatus == TranslationStatus.TRANSLATING) {
            startTranslation();
        }
    }

    /** A tela saiu de vista: nada de traduzir em segundo plano gastando bateria. */
    public void onHidden() {
        visible = false;
        cancelTranslation();
    }

    /** O jogador autorizou baixar o pacote do idioma pela rede móvel. */
    public void downloadTranslator() {
        allowMeteredDownload = true;
        retryTranslation();
    }

    public void retryTranslation() {
        if (detail == null) return;
        HeroDetailUiState base = base();
        state.setValue(base.withTranslation(TranslationStatus.TRANSLATING, base.translation));
        startTranslation();
    }

    /** Alterna entre a tradução e o texto original da Comic Vine. */
    public void setShowOriginal(boolean showOriginal) {
        HeroDetailUiState current = current();
        if (current.showOriginal == showOriginal) return;
        state.setValue(current.withShowOriginal(showOriginal));
    }

    private void onAccess(List<CollectionStore.Entry> unlocked) {
        if (unlocked == null) {
            state.setValue(HeroDetailUiState.of(Status.SIGNED_OUT));
            return;
        }
        Set<Integer> ids = new HashSet<>();
        CollectionStore.Entry entry = null;
        for (CollectionStore.Entry e : unlocked) {
            ids.add(e.characterId);
            if (e.characterId == characterId) entry = e;
        }
        if (entry == null) {
            state.setValue(HeroDetailUiState.of(Status.LOCKED));
            return;
        }
        preview = new HeroDetailUiState(Status.LOADING, null, false, null, entry.name, entry.imageUrl,
                entry.savedAt, ids);
        load();
    }

    private void load() {
        cancelTranslation();
        detail = null;
        ready = null;
        state.setValue(preview);
        repository.load(characterId, false, new HeroDetailRepository.Callback() {
            @Override
            public void onSuccess(CharacterDetail loaded, boolean offlineCopy) {
                detail = loaded;
                ready = new HeroDetailUiState(Status.READY, loaded, offlineCopy, null,
                        preview.previewName, preview.previewImageUrl, preview.unlockedAt, preview.unlockedIds);
                if (!translations.supports(language)) {
                    state.setValue(ready);
                    return;
                }
                // A ficha espera a primeira resposta da tradução — que vem do cache em
                // disco, rápida. Sem isso, o texto em inglês piscaria a cada abertura.
                startTranslation();
            }

            @Override
            public void onError(CharacterRepository.LoadError error) {
                state.setValue(new HeroDetailUiState(Status.ERROR, null, false, error,
                        preview.previewName, preview.previewImageUrl, preview.unlockedAt, preview.unlockedIds));
            }
        });
    }

    private void startTranslation() {
        cancelTranslation();
        if (detail == null || !visible) return;
        translationJob = translations.translate(detail, language, allowMeteredDownload,
                new HeroTranslationRepository.Callback() {
                    @Override
                    public void onUpdate(HeroTranslation translation) {
                        if (translation.complete) translationJob = null;
                        state.setValue(base().withTranslation(
                                translation.complete ? TranslationStatus.DONE : TranslationStatus.TRANSLATING,
                                translation));
                    }

                    @Override
                    public void onError(TranslationException.Reason reason, HeroTranslation partial) {
                        translationJob = null;
                        state.setValue(base().withTranslation(statusOf(reason), partial));
                    }
                });
    }

    private void cancelTranslation() {
        if (translationJob != null) {
            translationJob.cancel();
            translationJob = null;
        }
    }

    private static TranslationStatus statusOf(TranslationException.Reason reason) {
        switch (reason) {
            case NEEDS_DOWNLOAD: return TranslationStatus.NEEDS_DOWNLOAD;
            case NO_CONNECTION: return TranslationStatus.NO_CONNECTION;
            case FAILED:
            default: return TranslationStatus.FAILED;
        }
    }

    private HeroDetailUiState current() {
        HeroDetailUiState value = state.getValue();
        return value != null ? value : HeroDetailUiState.of(Status.LOADING);
    }

    /** O estado sobre o qual publicar: a ficha já carregada, mesmo que ainda não tenha aparecido. */
    private HeroDetailUiState base() {
        HeroDetailUiState current = current();
        return current.status == Status.READY || ready == null ? current : ready;
    }

    /** A ficha está carregada e só espera a primeira resposta da tradução para aparecer. */
    private boolean awaitingTranslation() {
        return ready != null && current().status != Status.READY;
    }

    @Override
    protected void onCleared() {
        cancelTranslation();
        super.onCleared();
    }

    public static final class Factory implements ViewModelProvider.Factory {

        private final int characterId;
        private final AccountStore accountStore;
        private final CollectionStore collectionStore;
        private final HeroDetailRepository repository;
        private final HeroTranslationRepository translations;
        private final String language;
        private final Executor ioExecutor;
        private final Executor mainExecutor;

        public Factory(int characterId, AccountStore accountStore, CollectionStore collectionStore,
                       HeroDetailRepository repository, HeroTranslationRepository translations,
                       String language, Executor ioExecutor, Executor mainExecutor) {
            this.characterId = characterId;
            this.accountStore = accountStore;
            this.collectionStore = collectionStore;
            this.repository = repository;
            this.translations = translations;
            this.language = language;
            this.ioExecutor = ioExecutor;
            this.mainExecutor = mainExecutor;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new HeroDetailViewModel(characterId, accountStore, collectionStore, repository,
                    translations, language, ioExecutor, mainExecutor);
        }
    }
}
