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
 * Nem todo herói entra direto: o {@link Gate} diz quem a conta pode levar. Quem
 * não pode (um lendário, para quem não é Vigia do Infinito) fica lacrado, fora
 * da coleção, e entra nela em {@link #releaseSealed} quando o portão abrir.
 *
 * Operações bloqueantes (rede, ou o cache do Firebase sem ela): chamar fora da
 * main thread. Thread-safe.
 */
public final class CollectionStore {

    private static final String TAG = "CollectionStore";

    /** Quem a conta pode levar para a coleção agora. */
    public interface Gate {
        boolean admits(String accountId, int characterId);

        /** Todo herói entra (sem raridades pagas). */
        Gate OPEN = (accountId, characterId) -> true;
    }

    /** O que aconteceu ao tentar desbloquear um herói. */
    public enum Unlock {
        /** Entrou na coleção agora. */
        NEW,
        /** Já estava na coleção. */
        EXISTING,
        /** A conta ainda não pode levá-lo: ficou lacrado à espera (ou já estava). */
        SEALED
    }

    private final PlayerBackend backend;
    private final Gate gate;
    /** Coleção da última conta lida, por id do herói. */
    @Nullable private String loadedFor;
    private Map<Integer, Entry> entries = new LinkedHashMap<>();
    /** Lacrados da última conta lida, por id do herói. */
    @Nullable private String sealedLoadedFor;
    private Map<Integer, Entry> sealed = new LinkedHashMap<>();

    public CollectionStore(PlayerBackend backend) {
        this(backend, Gate.OPEN);
    }

    public CollectionStore(PlayerBackend backend, Gate gate) {
        this.backend = backend;
        this.gate = gate;
    }

    public synchronized boolean contains(String accountId, int characterId) {
        return entriesOf(accountId).containsKey(characterId);
    }

    /**
     * Adiciona o personagem à coleção da conta. Devolve {@code false} se ele já
     * estava lá (a data original é mantida) ou se ficou lacrado.
     */
    public synchronized boolean save(String accountId, int characterId, String name, String imageUrl) {
        return unlock(accountId, characterId, name, imageUrl) == Unlock.NEW;
    }

    /** Desbloqueia o personagem que o Vigia acertou, ou o lacra se a conta ainda não pode levá-lo. */
    public synchronized Unlock unlock(String accountId, int characterId, String name, String imageUrl) {
        return add(accountId, characterId, name, imageUrl, System.currentTimeMillis());
    }

    /**
     * Traz um herói ganho por outro caminho (uma troca), com a data em que ele
     * chegou. Devolve {@code false} se ele já estava na coleção ou ficou lacrado.
     */
    public synchronized boolean importEntry(String accountId, int characterId, String name, String imageUrl,
                                            long savedAt) {
        return add(accountId, characterId, name, imageUrl, savedAt) == Unlock.NEW;
    }

    private Unlock add(String accountId, int characterId, String name, String imageUrl, long savedAt) {
        Map<Integer, Entry> map = entriesOf(accountId);
        if (map.containsKey(characterId)) return Unlock.EXISTING;
        long at = savedAt > 0 ? savedAt : System.currentTimeMillis();
        if (!gate.admits(accountId, characterId)) {
            Map<Integer, Entry> waiting = sealedOf(accountId);
            if (!waiting.containsKey(characterId)) {
                waiting.put(characterId, new Entry(characterId, name, imageUrl, at, false));
                backend.saveSealed(accountId, new PlayerBackend.Hero(characterId, name, imageUrl, at, false));
            }
            return Unlock.SEALED;
        }
        Entry entry = new Entry(characterId, name, imageUrl, at, false);
        map.put(characterId, entry);
        backend.saveHero(accountId, new PlayerBackend.Hero(characterId, name, imageUrl, entry.savedAt, false));
        return Unlock.NEW;
    }

    /** Coleção da conta, do mais recente para o mais antigo. */
    public synchronized List<Entry> list(String accountId) {
        List<Entry> copy = new ArrayList<>(entriesOf(accountId).values());
        copy.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
        return Collections.unmodifiableList(copy);
    }

    /** Lacrados da conta, à espera do Vigia do Infinito, do mais recente para o mais antigo. */
    public synchronized List<Entry> listSealed(String accountId) {
        List<Entry> copy = new ArrayList<>(sealedOf(accountId).values());
        copy.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
        return Collections.unmodifiableList(copy);
    }

    /**
     * Leva para a coleção os lacrados que o portão agora admite (a conta virou
     * Vigia do Infinito). Eles entram como novos — o catálogo toca a revelação —
     * e somem dos lacrados. Devolve os que entraram.
     */
    public synchronized List<Entry> releaseSealed(String accountId) {
        Map<Integer, Entry> waiting = sealedOf(accountId);
        if (waiting.isEmpty()) return Collections.emptyList();
        Map<Integer, Entry> map = entriesOf(accountId);
        List<Entry> released = new ArrayList<>();
        List<Integer> gone = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Entry e : new ArrayList<>(waiting.values())) {
            if (!gate.admits(accountId, e.characterId)) continue;
            waiting.remove(e.characterId);
            gone.add(e.characterId);
            if (map.containsKey(e.characterId)) continue;
            Entry entry = new Entry(e.characterId, e.name, e.imageUrl, now, false);
            map.put(e.characterId, entry);
            backend.saveHero(accountId, new PlayerBackend.Hero(e.characterId, e.name, e.imageUrl, now, false));
            released.add(entry);
        }
        backend.deleteSealed(accountId, gone);
        return Collections.unmodifiableList(released);
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
        sealedLoadedFor = null;
        sealed = new LinkedHashMap<>();
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

    private Map<Integer, Entry> sealedOf(String accountId) {
        if (accountId.equals(sealedLoadedFor)) return sealed;
        Map<Integer, Entry> map = new LinkedHashMap<>();
        try {
            for (PlayerBackend.Hero h : backend.loadSealed(accountId)) {
                map.put(h.characterId, new Entry(h.characterId, h.name, h.imageUrl, h.unlockedAt, false));
            }
        } catch (CloudException e) {
            Log.i(TAG, "Lacrados indisponíveis (" + e.reason + ")");
            return map;
        }
        sealedLoadedFor = accountId;
        sealed = map;
        return map;
    }

    /** Herói desbloqueado (ou lacrado, em {@link #listSealed}). */
    public static final class Entry {
        public final int characterId;
        public final String name;
        public final String imageUrl;
        /** Quando entrou na coleção (ou, lacrado, quando o Vigia o acertou). */
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
