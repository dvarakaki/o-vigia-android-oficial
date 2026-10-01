package com.ovigia.app.social;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.ovigia.app.cloud.CloudException;
import com.ovigia.app.cloud.PlayerBackend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Quais conquistas já foram comemoradas, guardado na conta online: a festa de
 * uma conquista toca uma única vez, não uma vez por aparelho.
 *
 * As conquistas são recalculadas do zero a cada consulta
 * ({@link Achievements#evaluate}), então é esta lista que diferencia "acabou de
 * cair" de "já era sua".
 *
 * A primeira consulta de uma conta é um marco zero silencioso: tudo que já está
 * desbloqueado entra como comemorado sem festa nenhuma. É o que impede uma
 * enxurrada de cartões em quem acabou de entrar com a coleção pronta (ver
 * {@link AchievementsTracker}).
 *
 * Operações bloqueantes (rede, ou a cópia do aparelho sem ela): chamar fora da
 * main thread. Thread-safe.
 */
public final class AchievementsStore {

    private static final String TAG = "AchievementsStore";

    private final PlayerBackend backend;
    @Nullable private String loadedFor;
    /** Comemoradas da conta lida; {@code null} = a conta ainda não tem marco zero. */
    @Nullable private Set<String> celebrated;

    public AchievementsStore(PlayerBackend backend) {
        this.backend = backend;
    }

    /**
     * Marca as conquistas desbloqueadas como comemoradas e devolve as que ainda
     * não tinham sido — na ordem do enum. Chamar de novo com o mesmo progresso
     * devolve lista vazia.
     *
     * @return o que falta comemorar; vazio na primeira consulta da conta (marco
     *     zero) ou se a conta ainda não pôde ser lida (sem rede, sem cópia)
     */
    public synchronized List<Achievement> claimNewlyUnlocked(String accountId, List<AchievementProgress> progress) {
        if (!load(accountId)) return Collections.emptyList();
        boolean firstTime = celebrated == null;
        Set<String> known = firstTime ? new LinkedHashSet<>() : celebrated;
        List<Achievement> fresh = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (AchievementProgress p : progress) {
            if (!p.isUnlocked() || !known.add(p.achievement.name())) continue;
            added.add(p.achievement.name());
            if (!firstTime) fresh.add(p.achievement);
        }
        celebrated = known;
        // O marco zero também é gravado: sem isso a próxima abertura seria "a primeira" de novo.
        if (firstTime || !added.isEmpty()) backend.addCelebrated(accountId, added, firstTime);
        return fresh;
    }

    /** Se a conta já tem marco zero (ou seja, se uma conquista nova dela rende festa). */
    @VisibleForTesting
    public synchronized boolean isTracking(String accountId) {
        return load(accountId) && celebrated != null;
    }

    /** Esquece o que foi lido (ex.: ao entrar numa conta): a próxima consulta vai ao servidor. */
    public synchronized void invalidate() {
        loadedFor = null;
        celebrated = null;
    }

    /** Lê a lista da conta; {@code false} se não deu (sem rede e sem cópia no aparelho). */
    private boolean load(String accountId) {
        if (accountId.equals(loadedFor)) return true;
        try {
            PlayerBackend.Account account = backend.loadAccount(accountId);
            celebrated = account == null || account.celebrated == null ? null
                    : new LinkedHashSet<>(account.celebrated);
            loadedFor = accountId;
            return true;
        } catch (CloudException | RuntimeException e) {
            Log.i(TAG, "Conquistas comemoradas indisponíveis", e);
            return false;
        }
    }
}
