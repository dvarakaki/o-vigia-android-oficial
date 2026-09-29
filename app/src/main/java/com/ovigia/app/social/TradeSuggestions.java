package com.ovigia.app.social;

import androidx.annotation.Nullable;

import com.ovigia.app.data.roster.RosterCatalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ordena os heróis que dá para pegar numa troca, do mais interessante para quem
 * recebe ao menos interessante. Interessa mais o herói que fecha uma conquista
 * de equipe ou de vilões que ainda falta; depois o que deixa uma dessas mais
 * perto; depois os mais conhecidos. No empate vale a ordem de entrada (os
 * catálogos vêm do desbloqueio mais recente para o mais antigo).
 *
 * Classe Java pura para ser testada na JVM.
 */
public final class TradeSuggestions {

    /** Por que o herói foi sugerido (o motivo aparece embaixo da carta). */
    public enum Reason {
        /** Com ele, {@link Pick#achievement} desbloqueia. */
        COMPLETES,
        /** Ele conta para {@link Pick#achievement}, que ainda não fecha. */
        ADVANCES,
        /** Um dos personagens mais conhecidos do elenco. */
        POPULAR,
        NONE
    }

    /** Herói candidato e o motivo. Imutável. */
    public static final class Pick {
        public final PublicProfile.Hero hero;
        public final Reason reason;
        /** A conquista do motivo, em {@link Reason#COMPLETES} e {@link Reason#ADVANCES}. */
        @Nullable public final Achievement achievement;
        final double score;

        Pick(PublicProfile.Hero hero, Reason reason, @Nullable Achievement achievement, double score) {
            this.hero = hero;
            this.reason = reason;
            this.achievement = achievement;
            this.score = score;
        }
    }

    private static final double COMPLETES_SCORE = 1000;
    private static final double ADVANCES_SCORE = 100;
    private static final double POPULAR_SCORE = 10;

    /**
     * @param candidates heróis do outro lado da troca
     * @param owned      heróis que quem vai receber já tem (esses ficam de fora)
     * @param roster     equipes, vilania e fama; {@code null} se não carregou (fica a ordem de entrada)
     */
    public static List<Pick> rank(Collection<PublicProfile.Hero> candidates, Set<Integer> owned,
                                  @Nullable RosterCatalog roster) {
        List<AchievementProgress> progress = Achievements.evaluate(owned, roster, 0, 0);
        List<Pick> picks = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (PublicProfile.Hero hero : candidates) {
            if (owned.contains(hero.characterId) || !seen.add(hero.characterId)) continue;
            picks.add(evaluate(hero, progress, roster));
        }
        // Estável: no empate fica a ordem de entrada.
        picks.sort((a, b) -> Double.compare(b.score, a.score));
        return picks;
    }

    private static Pick evaluate(PublicProfile.Hero hero, List<AchievementProgress> progress,
                                 @Nullable RosterCatalog roster) {
        RosterCatalog.Entry entry = roster == null ? null : roster.get(hero.characterId);
        if (entry == null) return new Pick(hero, Reason.NONE, null, 0);

        Reason reason = Reason.NONE;
        Achievement best = null;
        double bestScore = 0;
        double total = 0;
        for (AchievementProgress p : progress) {
            if (p.isUnlocked() || !countsFor(p.achievement, entry)) continue;
            Achievement a = p.achievement;
            boolean completes = p.current + 1 >= a.target;
            double score = completes
                    ? COMPLETES_SCORE * rarityWeight(a.rarity)
                    : ADVANCES_SCORE * (p.current + 1) / a.target;
            total += score;
            if (score > bestScore) {
                bestScore = score;
                best = a;
                reason = completes ? Reason.COMPLETES : Reason.ADVANCES;
            }
        }
        if (entry.mainstream) {
            total += POPULAR_SCORE;
            if (reason == Reason.NONE) reason = Reason.POPULAR;
        }
        return new Pick(hero, reason, best, total);
    }

    /** Só as conquistas que dependem de qual herói é (as de quantidade valem para qualquer um). */
    private static boolean countsFor(Achievement a, RosterCatalog.Entry entry) {
        switch (a.metric) {
            case TEAM: return entry.teams.contains(a.team);
            case VILLAINS: return entry.villain >= Achievements.VILLAIN_BELIEF;
            default: return false;
        }
    }

    private static double rarityWeight(Achievement.Rarity rarity) {
        switch (rarity) {
            case LEGENDARY: return 3;
            case RARE: return 2;
            case COMMON:
            default: return 1;
        }
    }

    private TradeSuggestions() { }
}
