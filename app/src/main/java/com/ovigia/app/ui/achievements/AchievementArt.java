package com.ovigia.app.ui.achievements;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import com.ovigia.app.R;
import com.ovigia.app.social.Achievement;

/**
 * A cara de cada conquista: nome, como conseguir e o emblema.
 *
 * Os nomes são apelidos de fã — piscadas para os filmes — e quem explica a
 * regra é a descrição. O emblema é o desenho da conquista, não um troféu
 * genérico: a teia do primeiro herói, o escudo dos Vingadores, a caveira dos
 * vilões, a claquete das partidas, o olho do próprio Vigia.
 *
 * Tudo em um lugar só porque a lista do perfil ({@link AchievementViews}) e o
 * cartão da comemoração ({@link AchievementStage}) mostram exatamente a mesma
 * conquista — mudou aqui, muda nos dois.
 */
public final class AchievementArt {

    @StringRes
    public static int titleOf(Achievement a) {
        switch (a) {
            case FIRST_HERO: return R.string.achievement_first_hero;
            case HEROES_10: return R.string.achievement_heroes_10;
            case HEROES_25: return R.string.achievement_heroes_25;
            case HEROES_50: return R.string.achievement_heroes_50;
            case AVENGERS_5: return R.string.achievement_avengers_5;
            case XMEN_5: return R.string.achievement_xmen_5;
            case GUARDIANS_3: return R.string.achievement_guardians_3;
            case FANTASTIC_FOUR: return R.string.achievement_fantastic_four;
            case VILLAINS_5: return R.string.achievement_villains_5;
            case GAMES_10: return R.string.achievement_games_10;
            case GAMES_50: return R.string.achievement_games_50;
            case BEAT_WATCHER: return R.string.achievement_beat_watcher;
            case BEAT_WATCHER_10:
            default: return R.string.achievement_beat_watcher_10;
        }
    }

    @StringRes
    public static int descriptionOf(Achievement a) {
        switch (a) {
            case FIRST_HERO: return R.string.achievement_first_hero_desc;
            case HEROES_10: return R.string.achievement_heroes_10_desc;
            case HEROES_25: return R.string.achievement_heroes_25_desc;
            case HEROES_50: return R.string.achievement_heroes_50_desc;
            case AVENGERS_5: return R.string.achievement_avengers_5_desc;
            case XMEN_5: return R.string.achievement_xmen_5_desc;
            case GUARDIANS_3: return R.string.achievement_guardians_3_desc;
            case FANTASTIC_FOUR: return R.string.achievement_fantastic_four_desc;
            case VILLAINS_5: return R.string.achievement_villains_5_desc;
            case GAMES_10: return R.string.achievement_games_10_desc;
            case GAMES_50: return R.string.achievement_games_50_desc;
            case BEAT_WATCHER: return R.string.achievement_beat_watcher_desc;
            case BEAT_WATCHER_10:
            default: return R.string.achievement_beat_watcher_10_desc;
        }
    }

    @DrawableRes
    public static int iconOf(Achievement a) {
        switch (a) {
            case FIRST_HERO: return R.drawable.ic_ach_web;
            case HEROES_10: return R.drawable.ic_ach_cards;
            case HEROES_25: return R.drawable.ic_ach_dossier;
            case HEROES_50: return R.drawable.ic_ach_worlds;
            case AVENGERS_5: return R.drawable.ic_ach_shield;
            case XMEN_5: return R.drawable.ic_ach_x;
            case GUARDIANS_3: return R.drawable.ic_ach_sprout;
            case FANTASTIC_FOUR: return R.drawable.ic_ach_four;
            case VILLAINS_5: return R.drawable.ic_ach_skull;
            case GAMES_10: return R.drawable.ic_ach_clapper;
            case GAMES_50: return R.drawable.ic_ach_hourglass;
            case BEAT_WATCHER: return R.drawable.ic_ach_eye;
            case BEAT_WATCHER_10:
            default: return R.drawable.ic_ach_mask;
        }
    }

    /** A chamada da comemoração: quanto mais rara, mais alto o anúncio. */
    @StringRes
    public static int kickerOf(Achievement.Rarity rarity) {
        switch (rarity) {
            case LEGENDARY: return R.string.achievement_unlocked_legendary;
            case RARE: return R.string.achievement_unlocked_rare;
            case COMMON:
            default: return R.string.achievement_unlocked_common;
        }
    }

    private AchievementArt() { }
}
