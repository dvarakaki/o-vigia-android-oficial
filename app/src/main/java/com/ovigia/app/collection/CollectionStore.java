package com.ovigia.app.collection;

import android.util.Log;

import androidx.annotation.Nullable;

import com.ovigia.app.cloud.CloudException;
import com.ovigia.app.cloud.PlayerBackend;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Heróis desbloqueados da conta, guardados na conta online: o catálogo é o
 * mesmo em qualquer aparelho e em qualquer versão.
 *
 * Quem desbloqueia é o servidor — com a partida que o Vigia acertou, uma troca
 * aceita ou a importação das versões antigas. Aqui fica a cópia lida da conta,
 * atualizada na hora quando o app sabe que um herói entrou (sem esperar a
 * próxima leitura).
 *
 * Operações bloqueantes (rede, ou a cópia do aparelho sem ela): chamar fora da
 * main thread. Thread-safe.
 */
public final class CollectionStore {

    private static final String TAG = "CollectionStore";

    private final PlayerBackend backend;
    /** Coleção da última conta lida, por id do herói. */
    @Nullable private String loadedFor;
    private Map<Integer, Entry> entries = new LinkedHashMap<>();

    public CollectionStore(PlayerBackend backend) {
        this.backend = backend;
    }

    public synchronized boolean contains(String accountId, int characterId) {
        return entriesOf(accountId).containsKey(characterId);
    }

    /**
     * O Vigia acertou o personagem numa partida já gravada
     * ({@code LearningStore.recordGame}): espera a partida subir — é ela que
     * desbloqueia o herói no servidor — e garante o herói na coleção. Devolve
     * {@code false} se a conta já tinha o herói antes dessa partida (a data
     * original é mantida). Sem rede, a partida sobe depois e o herói aparece aqui
     * desde já.
     */
    public synchronized boolean save(String accountId, int characterId, String name, String imageUrl) {
        Boolean isNew = backend.awaitUnlock(accountId, characterId);
        Map<Integer, Entry> map = entriesOf(accountId);
        boolean known = map.containsKey(characterId);
        if (!known) map.put(characterId, new Entry(characterId, name, imageUrl, System.currentTimeMillis(), false));
        // A coleção pode ter sido lida depois de a partida subir (e já ter o herói): vale o que o servidor disse.
        return isNew != null ? isNew : !known;
    }

    /**
     * Um herói que o servidor deu por outro caminho (uma troca), com a data em que
     * ele chegou. Devolve {@code false} se ele já estava na coleção.
     */
    public synchronized boolean importEntry(String accountId, int characterId, String name, String imageUrl,
                                            long savedAt) {
        Map<Integer, Entry> map = entriesOf(accountId);
        if (map.containsKey(characterId)) return false;
        map.put(characterId, new Entry(characterId, name, imageUrl,
                savedAt > 0 ? savedAt : System.currentTimeMillis(), false));
        return true;
    }

    /** Coleção da conta, do mais recente para o mais antigo. */
    public synchronized List<Entry> list(String accountId) {
        List<Entry> copy = new ArrayList<>(entriesOf(accountId).values());
        copy.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
        return Collections.unmodifiableList(copy);
    }

    /**
     * Marca heróis como já vistos no catálogo: a animação do cadeado sumindo toca
     * uma única vez por herói.
     */
    public synchronized void markSeenInCatalog(String accountId, Collection<Integer> characterIds) {
        Map<Integer, Entry> map = entriesOf(accountId);
        List<Integer> changed = new ArrayList<>();
        for (Integer id : characterIds) {
            Entry e = map.get(id);
            if (e == null || e.seenInCatalog) continue;
            map.put(id, new Entry(e.characterId, e.name, e.imageUrl, e.savedAt, true));
            changed.add(id);
        }
        if (!changed.isEmpty()) backend.markHeroesSeen(accountId, changed);
    }

    /** Esquece a coleção lida (ex.: ao entrar numa conta): a próxima consulta vai ao servidor. */
    public synchronized void invalidate() {
        loadedFor = null;
        entries = new LinkedHashMap<>();
    }

    private Map<Integer, Entry> entriesOf(String accountId) {
        if (accountId.equals(loadedFor)) return entries;
        Map<Integer, Entry> map = new LinkedHashMap<>();
        try {
            for (PlayerBackend.Hero h : backend.loadHeroes(accountId)) {
                map.put(h.characterId, new Entry(h.characterId, h.name, h.imageUrl, h.unlockedAt, h.seen));
            }
        } catch (CloudException e) {
            // Sem rede e sem cópia: mostra vazio agora e tenta de novo na próxima consulta.
            Log.i(TAG, "Coleção indisponível (" + e.reason + ")");
            return map;
        }
        loadedFor = accountId;
        entries = map;
        return map;
    }

    /** Herói desbloqueado. */
    public static final class Entry {
        public final int characterId;
        public final String name;
        public final String imageUrl;
        public final long savedAt;
        /** Já apareceu no catálogo depois de desbloqueado (a revelação animada já tocou). */
        public final boolean seenInCatalog;

        public Entry(int characterId, String name, String imageUrl, long savedAt, boolean seenInCatalog) {
            this.characterId = characterId;
            this.name = name;
            this.imageUrl = imageUrl;
            this.savedAt = savedAt;
            this.seenInCatalog = seenInCatalog;
        }
    }
}
