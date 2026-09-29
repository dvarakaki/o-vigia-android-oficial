package com.ovigia.app.settings;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.settings.SettingsUiState.Message;
import com.ovigia.app.util.Event;

import java.util.concurrent.Executor;

/**
 * Tela de configurações: preferências da partida ({@link SettingsStore}), cache
 * limpável ({@link AppCache}) e o botão de esquecer o aprendizado. O idioma é
 * trocado direto pela tela, via {@link AppLocales}, porque o sistema recria a
 * Activity. Disco só no {@code ioExecutor}.
 */
public class SettingsViewModel extends ViewModel {

    private final SettingsStore settings;
    private final LearningStore learningStore;
    private final AccountStore accountStore;
    private final AppCache cache;
    private final Executor ioExecutor;
    private final Executor mainExecutor;

    private final MutableLiveData<SettingsUiState> state = new MutableLiveData<>(SettingsUiState.loading());
    private final MutableLiveData<Event<Message>> messages = new MutableLiveData<>();
    private boolean started = false;

    public SettingsViewModel(SettingsStore settings, LearningStore learningStore, AccountStore accountStore,
                             AppCache cache, Executor ioExecutor, Executor mainExecutor) {
        this.settings = settings;
        this.learningStore = learningStore;
        this.accountStore = accountStore;
        this.cache = cache;
        this.ioExecutor = ioExecutor;
        this.mainExecutor = mainExecutor;
    }

    public LiveData<SettingsUiState> state() { return state; }

    public LiveData<Event<Message>> messages() { return messages; }

    /** Idempotente: lê as preferências e mede o cache uma vez por ViewModel. */
    public void start() {
        if (started) return;
        started = true;
        ioExecutor.execute(() -> {
            boolean haptics = settings.hapticFeedback();
            int level = settings.hapticStrength().level;
            boolean keepScreenOn = settings.keepScreenOn();
            mainExecutor.execute(() -> state.setValue(current().withPreferences(haptics, level, keepScreenOn)));
            long size = cache.sizeBytes();
            mainExecutor.execute(() -> {
                // Uma limpeza pedida enquanto media publica o próprio tamanho ao terminar.
                if (!current().clearingCache) state.setValue(current().withCache(size, false));
            });
        });
    }

    public void setHapticFeedback(boolean enabled) {
        SettingsUiState current = current();
        if (!current.loaded || current.hapticFeedback == enabled) return;
        settings.setHapticFeedback(enabled);
        state.setValue(current.withPreferences(enabled, current.hapticLevel, current.keepScreenOn));
    }

    /** Só vale com a vibração ligada: desligada, a barra fica parada onde estava. */
    public void setHapticLevel(int level) {
        SettingsUiState current = current();
        int clamped = HapticStrength.clamp(level);
        if (!current.loaded || !current.hapticFeedback || current.hapticLevel == clamped) return;
        settings.setHapticLevel(clamped);
        state.setValue(current.withPreferences(current.hapticFeedback, clamped, current.keepScreenOn));
    }

    public void setKeepScreenOn(boolean enabled) {
        SettingsUiState current = current();
        if (!current.loaded || current.keepScreenOn == enabled) return;
        settings.setKeepScreenOn(enabled);
        state.setValue(current.withPreferences(current.hapticFeedback, current.hapticLevel, enabled));
    }

    public void clearCache() {
        SettingsUiState current = current();
        if (current.clearingCache) return;
        state.setValue(current.withCache(current.cacheBytes, true));
        ioExecutor.execute(() -> {
            cache.clear();
            long size = cache.sizeBytes();
            mainExecutor.execute(() -> {
                state.setValue(current().withCache(size, false));
                messages.setValue(new Event<>(Message.CACHE_CLEARED));
            });
        });
    }

    /** Apaga as estatísticas e tudo o que o motor aprendeu com as partidas da conta ativa. */
    public void forgetLearning() {
        ioExecutor.execute(() -> {
            learningStore.reset(accountStore.currentAccountId());
            mainExecutor.execute(() -> messages.setValue(new Event<>(Message.LEARNING_FORGOTTEN)));
        });
    }

    private SettingsUiState current() {
        SettingsUiState value = state.getValue();
        return value != null ? value : SettingsUiState.loading();
    }

    public static final class Factory implements ViewModelProvider.Factory {

        private final SettingsStore settings;
        private final LearningStore learningStore;
        private final AccountStore accountStore;
        private final AppCache cache;
        private final Executor ioExecutor;
        private final Executor mainExecutor;

        public Factory(SettingsStore settings, LearningStore learningStore, AccountStore accountStore,
                       AppCache cache, Executor ioExecutor, Executor mainExecutor) {
            this.settings = settings;
            this.learningStore = learningStore;
            this.accountStore = accountStore;
            this.cache = cache;
            this.ioExecutor = ioExecutor;
            this.mainExecutor = mainExecutor;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new SettingsViewModel(settings, learningStore, accountStore, cache, ioExecutor, mainExecutor);
        }
    }
}
