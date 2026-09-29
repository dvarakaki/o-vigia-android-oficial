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
 * Heróis desbloqueados da conta, guardados na conta online (um documento por
 * herói): o catálogo é o mesmo em qualquer aparelho e em qualquer versão.
 *
 * Cada item guarda nome e foto do momento do desbloqueio, para o catálogo
 * aparecer mesmo sem o elenco carregado (offline, sem cache).
 *
 * Operações bloqueantes (rede, ou o cache do Firebase sem ela): chamar fora da
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
     * Adiciona o personagem à coleção da conta. Devolve {@code false} se ele já
     * estava lá (a data original é mantida).
     */
    public synchronized boolean save(String accountId, int characterId, String name, String imageUrl) {
        return importEntry(accountId, characterId, name, imageUrl, System.currentTimeMillis());
    }

    /**
     * Traz um herói ganho por outro caminho (uma troca), com a data em que ele
     * chegou. Devolve {@code false} se ele já estava na coleção.
     */
    public synchronized boolean importEntry(String accountId, int characterId, String name, String imageUrl,
                                            long savedAt) {
        Map<Integer, Entry> map = entriesOf(accountId);
        if (map.containsKey(characterId)) return false;
        Entry entry = new Entry(characterId, name, imageUrl, savedAt > 0 ? savedAt : System.currentTimeMillis(),
                false);
        map.put(characterId, entry);
        backend.saveHero(accountId, new PlayerBackend.Hero(characterId, name, imageUrl, entry.savedAt, false));
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
