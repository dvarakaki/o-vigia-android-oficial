package com.ovigia.app.catalog;

import androidx.annotation.Nullable;

import com.ovigia.app.data.roster.Rarity;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Estado do catálogo de heróis. Imutável. */
public final class CatalogUiState {

    public enum Status {
        LOADING,
        READY,
        /** Não há sessão: a tela deve mandar para o login. */
        SIGNED_OUT
    }

    public enum Filter { UNLOCKED, ALL }

    public final Status status;
    /** Itens visíveis com o filtro e a busca atuais. */
    public final List<Item> items;
    public final int unlockedCount;
    /** Tamanho do elenco jogável; 0 se o elenco não pôde ser carregado. */
    public final int totalCount;
    /** Progresso em cada raridade, da comum à lendária; vazio sem o elenco. */
    public final List<RarityProgress> rarities;
    public final Filter filter;
    public final String query;

    CatalogUiState(Status status, List<Item> items, int unlockedCount, int totalCount, List<RarityProgress> rarities,
                   Filter filter, String query) {
        this.status = status;
        this.items = Collections.unmodifiableList(items);
        this.unlockedCount = unlockedCount;
        this.totalCount = totalCount;
        this.rarities = Collections.unmodifiableList(rarities);
        this.filter = filter;
        this.query = query;
    }

    static CatalogUiState of(Status status) {
        return new CatalogUiState(status, Collections.emptyList(), 0, 0, Collections.emptyList(),
                Filter.UNLOCKED, "");
    }

    /** O elenco completo está disponível (dá para mostrar os bloqueados e o total). */
    public boolean hasRoster() {
        return totalCount > 0;
    }

    /** Progresso em [0,100]. */
    public int progressPercent() {
        return totalCount == 0 ? 0 : Math.round(100f * unlockedCount / totalCount);
    }

    /** Quantos de uma raridade já estão na coleção. */
    public static final class RarityProgress {
        public final Rarity rarity;
        public final int unlocked;
        public final int total;

        RarityProgress(Rarity rarity, int unlocked, int total) {
            this.rarity = rarity;
            this.unlocked = unlocked;
            this.total = total;
        }
    }

    /**
     * Um herói do catálogo. Bloqueados não revelam nome nem imagem. Lacrados (um
     * lendário que o Vigia acertou para quem ainda não é Vigia do Infinito)
     * mostram o nome, mas não a imagem.
     */
    public static final class Item {
        public final int characterId;
        public final boolean unlocked;
        /** Encontrado, à espera do Vigia do Infinito para entrar na coleção. */
        public final boolean sealed;
        /** {@code null} se bloqueado. */
        public final String name;
        /** {@code null} se não desbloqueado. */
        public final String imageUrl;
        /** 0 se não desbloqueado. */
        public final long unlockedAt;
        /** Desbloqueado desde a última visita ao catálogo: a carta revela com o cadeado sumindo. */
        public final boolean revealNow;
        /** {@code null} se o elenco não carregou (não dá para saber). */
        @Nullable public final Rarity rarity;

        Item(int characterId, boolean unlocked, boolean sealed, String name, String imageUrl, long unlockedAt,
             boolean revealNow, @Nullable Rarity rarity) {
            this.characterId = characterId;
            this.unlocked = unlocked;
            this.sealed = !unlocked && sealed;
            this.name = unlocked || this.sealed ? name : null;
            this.imageUrl = unlocked ? imageUrl : null;
            this.unlockedAt = unlocked ? unlockedAt : 0;
            this.revealNow = unlocked && revealNow;
            this.rarity = rarity;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Item)) return false;
            Item other = (Item) o;
            return characterId == other.characterId && unlocked == other.unlocked && sealed == other.sealed
                    && rarity == other.rarity
                    && Objects.equals(name, other.name)
                    && Objects.equals(imageUrl, other.imageUrl);
        }

        @Override
        public int hashCode() {
            return Objects.hash(characterId, unlocked, sealed, rarity, name, imageUrl);
        }
    }
}
