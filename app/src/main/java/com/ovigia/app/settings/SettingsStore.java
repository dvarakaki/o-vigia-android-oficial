package com.ovigia.app.settings;

import android.util.Log;

import com.google.gson.Gson;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Preferências do jogador, num arquivo JSON pequeno. O idioma não fica aqui:
 * é guardado pelo sistema (ver {@link AppLocales}).
 *
 * A leitura do disco ({@link #ensureLoaded()}) deve rodar fora da main thread;
 * depois dela, ler e alterar é só memória e a gravação vai para o
 * {@code ioExecutor}. Thread-safe.
 */
public final class SettingsStore {

    private static final String TAG = "SettingsStore";

    private final Supplier<File> fileSupplier;
    private final Executor ioExecutor;
    private final Gson gson = new Gson();
    private final Object fileLock = new Object();
    private State state;

    /** @param fileSupplier resolvido só no executor de I/O. */
    public SettingsStore(Supplier<File> fileSupplier, Executor ioExecutor) {
        this.fileSupplier = fileSupplier;
        this.ioExecutor = ioExecutor;
    }

    /** Carrega do disco na primeira chamada. Bloqueante: chamar fora da main thread. */
    public synchronized void ensureLoaded() {
        if (state == null) state = load();
    }

    /** Vibração curta ao responder durante a partida. */
    public synchronized boolean hapticFeedback() {
        ensureLoaded();
        return state.hapticFeedback;
    }

    /** Força da vibração, quando ela está ligada. */
    public synchronized HapticStrength hapticStrength() {
        ensureLoaded();
        return HapticStrength.of(state.hapticLevel);
    }

    /** A tela não apaga durante a partida. */
    public synchronized boolean keepScreenOn() {
        ensureLoaded();
        return state.keepScreenOn;
    }

    public void setHapticFeedback(boolean enabled) {
        synchronized (this) {
            ensureLoaded();
            if (state.hapticFeedback == enabled) return;
            state.hapticFeedback = enabled;
        }
        persistAsync();
    }

    /** Nível da barra, de {@link HapticStrength#MIN_LEVEL} a {@link HapticStrength#MAX_LEVEL}. */
    public void setHapticLevel(int level) {
        int clamped = HapticStrength.clamp(level);
        synchronized (this) {
            ensureLoaded();
            if (state.hapticLevel == clamped) return;
            state.hapticLevel = clamped;
        }
        persistAsync();
    }

    public void setKeepScreenOn(boolean enabled) {
        synchronized (this) {
            ensureLoaded();
            if (state.keepScreenOn == enabled) return;
            state.keepScreenOn = enabled;
        }
        persistAsync();
    }

    private State load() {
        File file = fileSupplier.get();
        if (!file.exists()) return new State();
        try {
            State loaded = gson.fromJson(AtomicFiles.readUtf8(file), State.class);
            return loaded != null ? loaded : new State();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Falha ao ler configurações; usando o padrão", e);
            return new State();
        }
    }

    private void persistAsync() {
        ioExecutor.execute(() -> {
            String json;
            synchronized (this) {
                json = gson.toJson(state, State.class);
            }
            synchronized (fileLock) {
                try {
                    AtomicFiles.writeUtf8(fileSupplier.get(), json);
                } catch (IOException e) {
                    Log.w(TAG, "Falha ao salvar configurações", e);
                }
            }
        });
    }

    /**
     * Formato serializado. Os padrões ficam nos campos: o Gson os mantém quando
     * a chave falta no arquivo (ex.: preferência criada numa versão mais nova).
     */
    private static final class State {
        boolean hapticFeedback = true;
        /** Fora da faixa (arquivo mexido) vale o nível mais próximo: ver {@link HapticStrength#of}. */
        int hapticLevel = HapticStrength.DEFAULT_LEVEL;
        boolean keepScreenOn = true;
    }
}
