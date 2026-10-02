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
 * Nem todo herói entra direto: um lendário, para quem não é Vigia do Infinito,
 * fica lacrado no servidor, fora da coleção, e entra nela quando a compra é
 * validada ({@link #releaseSealed}). O {@link Gate} só serve para adivinhar o
 * lacre enquanto a partida não sobe (sem rede).
 *
 * Operações bloqueantes (rede, ou a cópia do aparelho sem ela): chamar fora da
 * main thread. Thread-safe.
 */
public final class CollectionStore {

    private static final String TAG = "CollectionStore";

    /** Quem a conta pode levar para a coleção agora (pelo que o app sabe). */
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
     * O Vigia acertou o personagem numa partida já gravada
     * ({@code LearningStore.recordGame}). Devolve {@code false} se a conta já tinha
     * o herói antes dessa partida (a data original é mantida) ou se ele ficou lacrado.
     */
    public synchronized boolean save(String accountId, int characterId, String name, String imageUrl) {
        return unlock(accountId, characterId, name, imageUrl) == Unlock.NEW;
    }

    /**
     * O Vigia acertou o personagem numa partida já gravada: espera a partida subir —
     * é ela que desbloqueia (ou lacra) o herói no servidor — e atualiza a cópia daqui.
     * Sem rede, a partida sobe depois e vale o palpite do app desde já.
     */
    public synchronized Unlock unlock(String accountId, int characterId, String name, String imageUrl) {
        PlayerBackend.Grant grant = backend.awaitUnlock(accountId, characterId);
        Map<Integer, Entry> map = entriesOf(accountId);
        boolean known = map.containsKey(characterId);
        if (grant == null) {
            if (known) grant = PlayerBackend.Grant.EXISTING;
            else grant = gate.admits(accountId, characterId) ? PlayerBackend.Grant.NEW : PlayerBackend.Grant.SEALED;
        }
        long now = System.currentTimeMillis();
        switch (grant) {
            case SEALED:
                Map<Integer, Entry> waiting = sealedOf(accountId);
                if (!waiting.containsKey(characterId)) {
                    waiting.put(characterId, new Entry(characterId, name, imageUrl, now, false));
                }
                return Unlock.SEALED;
            case NEW:
                // A coleção pode ter sido lida depois de a partida subir (e já ter o herói): vale o que o servidor disse.
                if (!known) map.put(characterId, new Entry(characterId, name, imageUrl, now, false));
                sealedOf(accountId).remove(characterId);
                return Unlock.NEW;
            default:
                if (!known) map.put(characterId, new Entry(characterId, name, imageUrl, now, false));
                return Unlock.EXISTING;
        }
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

    /** Lacrados da conta, à espera do Vigia do Infinito, do mais recente para o mais antigo. */
    public synchronized List<Entry> listSealed(String accountId) {
        List<Entry> copy = new ArrayList<>(sealedOf(accountId).values());
        copy.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
        return Collections.unmodifiableList(copy);
    }

    /**
     * A conta virou Vigia do Infinito e o servidor já levou os lacrados para a
     * coleção: relê os dois e devolve os que entraram (como novos — o catálogo toca
     * a revelação).
     */
    public synchronized List<Entry> releaseSealed(String accountId) {
        Map<Integer, Entry> waiting = sealedOf(accountId);
        if (waiting.isEmpty()) return Collections.emptyList();
        List<Integer> before = new ArrayList<>(waiting.keySet());
        invalidate();
        Map<Integer, Entry> map = entriesOf(accountId);
        sealedOf(accountId);
        List<Entry> released = new ArrayList<>();
        for (Integer id : before) {
            Entry e = map.get(id);
            if (e != null) released.add(e);
        }
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
