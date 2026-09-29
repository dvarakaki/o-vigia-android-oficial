package com.ovigia.app.profile;

import com.ovigia.app.learning.LearningStore.Outcome;
import com.ovigia.app.social.Achievement;
import com.ovigia.app.social.AchievementProgress;

import java.util.Collections;
import java.util.List;

/** Estado da tela de perfil. Imutável. */
public final class ProfileUiState {

    public enum Status {
        LOADING,
        LOADED,
        /** Não há sessão: a tela deve mandar para o login. */
        SIGNED_OUT
    }

    public final Status status;
    public final String accountName;
    public final String accountEmail;
    /** @usuario dos amigos online, ou {@code null} se a conta nunca conectou. */
    public final String accountUsername;
    /** {@code null} quando o jogador não escreveu bio. */
    public final String accountBio;
    /** Foto e banner em JPEG Base64; {@code null} usa o padrão. */
    public final String avatar;
    public final String banner;
    public final int gamesPlayed;
    public final int engineWins;
    /** Partidas em que o Vigia não acertou de primeira. */
    public final int playerWins;
    /** Taxa de acerto do Vigia em [0,100]. */
    public final int engineWinPercent;
    public final int distinctCharacters;
    public final PlayerRank rank;
    public final List<CollectionItem> collection;
    public final List<FavoriteItem> favorites;
    public final List<RecentItem> recentGames;
    /** Na ordem do enum {@link Achievement}. */
    public final List<AchievementProgress> achievements;

    private ProfileUiState(Status status, Identity identity, int gamesPlayed, int engineWins,
                           int engineWinPercent, int distinctCharacters, List<CollectionItem> collection,
                           List<FavoriteItem> favorites, List<RecentItem> recentGames,
                           List<AchievementProgress> achievements) {
        this.status = status;
        this.accountName = identity.name;
        this.accountEmail = identity.email;
        this.accountUsername = identity.username;
        this.accountBio = identity.bio;
        this.avatar = identity.avatar;
        this.banner = identity.banner;
        this.gamesPlayed = gamesPlayed;
        this.engineWins = engineWins;
        this.playerWins = gamesPlayed - engineWins;
        this.engineWinPercent = engineWinPercent;
        this.distinctCharacters = distinctCharacters;
        this.rank = PlayerRank.forGames(gamesPlayed);
        this.collection = Collections.unmodifiableList(collection);
        this.favorites = Collections.unmodifiableList(favorites);
        this.recentGames = Collections.unmodifiableList(recentGames);
        this.achievements = Collections.unmodifiableList(achievements);
    }

    static ProfileUiState loading() {
        return new ProfileUiState(Status.LOADING, Identity.EMPTY, 0, 0, 0, 0,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    static ProfileUiState signedOut() {
        return new ProfileUiState(Status.SIGNED_OUT, Identity.EMPTY, 0, 0, 0, 0,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    static ProfileUiState loaded(Identity identity, int gamesPlayed, int engineWins,
                                 int engineWinPercent, int distinctCharacters, List<CollectionItem> collection,
                                 List<FavoriteItem> favorites, List<RecentItem> recentGames,
                                 List<AchievementProgress> achievements) {
        return new ProfileUiState(Status.LOADED, identity, gamesPlayed, engineWins,
                engineWinPercent, distinctCharacters, collection, favorites, recentGames, achievements);
    }

    public boolean isLoading() {
        return status == Status.LOADING;
    }

    /** Carregado e sem nenhuma partida jogada. */
    public boolean hasNoGames() {
        return status == Status.LOADED && gamesPlayed == 0;
    }

    /** Quem é o jogador: dados da conta com as imagens já resolvidas. */
    static final class Identity {
        static final Identity EMPTY = new Identity(null, null, null, null, null, null);

        final String name;
        final String email;
        final String username;
        final String bio;
        final String avatar;
        final String banner;

        Identity(String name, String email, String username, String bio, String avatar, String banner) {
            this.name = name;
            this.email = email;
            this.username = username;
            this.bio = bio;
            this.avatar = avatar;
            this.banner = banner;
        }
    }

    /** Personagem salvo na coleção da conta. */
    public static final class CollectionItem {
        public final int characterId;
        public final String name;
        public final String imageUrl;
        public final long savedAt;

        CollectionItem(int characterId, String name, String imageUrl, long savedAt) {
            this.characterId = characterId;
            this.name = name;
            this.imageUrl = imageUrl;
            this.savedAt = savedAt;
        }
    }

    /** Personagem pensado com frequência. {@code name}/{@code thumbnailUrl} são null se o elenco não carregou. */
    public static final class FavoriteItem {
        public final int characterId;
        public final String name;
        public final String thumbnailUrl;
        public final int timesPicked;

        FavoriteItem(int characterId, String name, String thumbnailUrl, int timesPicked) {
            this.characterId = characterId;
            this.name = name;
            this.thumbnailUrl = thumbnailUrl;
            this.timesPicked = timesPicked;
        }
    }

    /** Partida recente. {@code name}/{@code thumbnailUrl} são null se o elenco não carregou. */
    public static final class RecentItem {
        public final int characterId;
        public final String name;
        public final String thumbnailUrl;
        public final Outcome outcome;
        public final long timestamp;

        RecentItem(int characterId, String name, String thumbnailUrl, Outcome outcome, long timestamp) {
            this.characterId = characterId;
            this.name = name;
            this.thumbnailUrl = thumbnailUrl;
            this.outcome = outcome;
            this.timestamp = timestamp;
        }
    }
}
