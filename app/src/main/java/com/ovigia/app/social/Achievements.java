package com.ovigia.app.social;

import androidx.annotation.Nullable;

import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.learning.LearningStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcula as conquistas a partir do que o app já guarda: heróis desbloqueados
 * (com equipes e vilania vindas do roster.json) e estatísticas de partidas.
 * Classe Java pura para ser testada na JVM.
 */
public final class Achievements {

    /** A partir desta crença de vilania o herói conta como vilão. */
    static final double VILLAIN_BELIEF = 0.5;

    /** Progresso em todas as conquistas da conta, com os números das partidas dela. */
    public static List<AchievementProgress> evaluate(Collection<Integer> unlockedHeroIds, @Nullable RosterCatalog roster,
                                                     LearningStore.Stats stats) {
        return evaluate(unlockedHeroIds, roster, stats.gamesPlayed, stats.playerWins());
    }

    /**
     * Progresso em todas as conquistas, na ordem do enum.
     *
     * @param unlockedHeroIds heróis desbloqueados da conta
     * @param roster          equipes e vilania; {@code null} se não carregou (as de equipe ficam em 0)
     */
    public static List<AchievementProgress> evaluate(Collection<Integer> unlockedHeroIds, @Nullable RosterCatalog roster,
                                                     int gamesPlayed, int playerWins) {
        int villains = 0;
        Map<String, Integer> byTeam = new HashMap<>();
        if (roster != null) {
            for (Integer id : unlockedHeroIds) {
                RosterCatalog.Entry entry = id == null ? null : roster.get(id);
                if (entry == null) continue;
                if (entry.villain >= VILLAIN_BELIEF) villains++;
                for (String team : entry.teams) byTeam.merge(team, 1, Integer::sum);
            }
        }

        List<AchievementProgress> progress = new ArrayList<>();
        for (Achievement a : Achievement.values()) {
            int current;
            switch (a.metric) {
                case HEROES: current = unlockedHeroIds.size(); break;
                case TEAM: current = byTeam.getOrDefault(a.team, 0); break;
                case VILLAINS: current = villains; break;
                case GAMES: current = gamesPlayed; break;
                case PLAYER_WINS:
                default: current = playerWins; break;
            }
            progress.add(new AchievementProgress(a, current));
        }
        return progress;
    }

    /** Quantas estão desbloqueadas. */
    public static int unlockedCount(List<AchievementProgress> progress) {
        int n = 0;
        for (AchievementProgress p : progress) {
            if (p.isUnlocked()) n++;
        }
        return n;
    }

    /**
     * Converte o que veio do servidor (id → valor) numa lista na ordem do enum.
     * Conquistas que o amigo ainda não tinha publicado aparecem zeradas.
     */
    public static List<AchievementProgress> fromPublished(@Nullable Map<String, Integer> published) {
        List<AchievementProgress> progress = new ArrayList<>();
        for (Achievement a : Achievement.values()) {
            Integer value = published == null ? null : published.get(a.name());
            progress.add(new AchievementProgress(a, value == null ? 0 : value));
        }
        return progress;
    }

    /** Formato publicado: id → valor atual. */
    public static Map<String, Integer> toPublished(List<AchievementProgress> progress) {
        Map<String, Integer> map = new HashMap<>();
        for (AchievementProgress p : progress) map.put(p.achievement.name(), p.current);
        return map;
    }

    private Achievements() { }
}
