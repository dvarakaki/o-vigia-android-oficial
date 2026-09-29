package com.ovigia.app.social;

import android.util.Log;

import com.google.gson.Gson;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Quais conquistas já foram comemoradas, por conta, neste aparelho.
 *
 * Serve para a festa tocar uma única vez: as conquistas são recalculadas do zero
 * a cada consulta ({@link Achievements#evaluate}), então é este arquivo que
 * diferencia "acabou de cair" de "já era sua".
 *
 * A primeira consulta de uma conta é um marco zero silencioso: tudo que já está
 * desbloqueado entra como comemorado sem festa nenhuma. É o que impede uma
 * enxurrada de cartões em quem atualizou o app com meia coleção pronta, ou em
 * quem acabou de entrar e trouxe os heróis de outro aparelho. Por isso o marco
 * precisa ser cravado cedo — na abertura do app e ao entrar na conta, antes de
 * qualquer partida (ver {@link AchievementsTracker}).
 *
 * Operações bloqueantes (disco): chamar fora da main thread. Thread-safe.
 */
public final class AchievementsStore {

    private static final String TAG = "AchievementsStore";

    private final Supplier<File> fileSupplier;
    private final Gson gson = new Gson();
    private State state;

    public AchievementsStore(Supplier<File> fileSupplier) {
        this.fileSupplier = fileSupplier;
    }

    /**
     * Marca as conquistas desbloqueadas como comemoradas e devolve as que ainda
     * não tinham sido — na ordem do enum. Chamar de novo com o mesmo progresso
     * devolve lista vazia.
     *
     * @return o que falta comemorar; vazio na primeira consulta da conta (marco zero)
     */
    public synchronized List<Achievement> claimNewlyUnlocked(String accountId, List<AchievementProgress> progress) {
        ensureLoaded();
        Set<String> celebrated = state.byAccount.get(accountId);
        boolean firstTime = celebrated == null;
        if (firstTime) {
            celebrated = new LinkedHashSet<>();
            state.byAccount.put(accountId, celebrated);
        }
        List<Achievement> fresh = new ArrayList<>();
        for (AchievementProgress p : progress) {
            if (!p.isUnlocked()) continue;
            if (celebrated.add(p.achievement.name()) && !firstTime) fresh.add(p.achievement);
        }
        // O marco zero também é gravado: sem isso a próxima abertura seria "a primeira" de novo.
        persist();
        return fresh;
    }

    /** Se a conta já tem marco zero (ou seja, se uma conquista nova dela rende festa). */
    public synchronized boolean isTracking(String accountId) {
        ensureLoaded();
        return state.byAccount.containsKey(accountId);
    }

    /** Apaga o histórico da conta (usado ao excluir a conta). */
    public synchronized void deleteAccount(String accountId) {
        ensureLoaded();
        if (state.byAccount.remove(accountId) != null) persist();
    }

    private void ensureLoaded() {
        if (state == null) state = load();
    }

    private State load() {
        File file = fileSupplier.get();
        if (!file.exists()) return new State();
        try {
            State loaded = gson.fromJson(AtomicFiles.readUtf8(file), State.class);
            if (loaded == null) return new State();
            if (loaded.byAccount == null) loaded.byAccount = new HashMap<>();
            loaded.byAccount.values().removeIf(ids -> ids == null);
            for (Set<String> ids : loaded.byAccount.values()) ids.removeIf(id -> id == null);
            return loaded;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Falha ao ler as conquistas comemoradas; começando do zero", e);
            return new State();
        }
    }

    private void persist() {
        try {
            AtomicFiles.writeUtf8(fileSupplier.get(), gson.toJson(state, State.class));
        } catch (IOException e) {
            Log.w(TAG, "Falha ao salvar as conquistas comemoradas", e);
        }
    }

    /**
     * Formato serializado: id da conta -> ids de conquistas já comemoradas. A
     * conta existir aqui é o marco zero; ids que esta versão não conhece ficam
     * guardados como estão (o jogador pode voltar para a versão que os tinha).
     */
    private static final class State {
        Map<String, Set<String>> byAccount = new HashMap<>();
    }
}
