package com.ovigia.app.learning;

import android.util.Log;

import androidx.annotation.Nullable;

import com.ovigia.app.cloud.CloudException;
import com.ovigia.app.cloud.PlayerBackend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A memória do Vigia, guardada na conta online: o que ele aprende com um
 * jogador vale em qualquer aparelho e sobrevive a qualquer atualização.
 *
 * Por conta, guarda:
 *
 * 1. Contagem de acertos por personagem — boost no prior inicial do
 *    {@code GameEngine}: personagens que ESTE jogador escolhe com frequência
 *    sobem no ranking desde a primeira pergunta.
 * 2. Correção de crenças por atributo — sempre que a partida termina com o
 *    personagem certo conhecido, as respostas dadas são agregadas e misturadas
 *    com a crença curada (inclusive para atributos que a curadoria não listou).
 * 3. Estatísticas e histórico de partidas — para o perfil e para recalibrar o
 *    modelo de ruído das respostas.
 *
 * Sem sessão aberta, leituras devolvem valores neutros e gravações são
 * ignoradas — jogar deslogado não conta pra ninguém.
 *
 * O que já foi lido fica em memória (o motor consulta milhares de crenças por
 * partida); as gravações vão direto para o servidor, que soma — dois aparelhos
 * jogando ao mesmo tempo não se atropelam. Leituras são bloqueantes na primeira
 * vez: fora da main thread. Thread-safe.
 */
public final class LearningStore {

    private static final String TAG = "LearningStore";

    /**
     * Peso do boost por acertos: {@code 1 + w·ln(1 + acertos)}. Logarítmico para
     * sentir o efeito já na 1ª–2ª partida (1 acerto ≈ 1.35×, 3 ≈ 1.7×) sem deixar
     * um personagem muito jogado engolir o prior (20 acertos ≈ 2.5×).
     */
    private static final double PICK_BOOST_WEIGHT = 0.5;

    /**
     * "Força" da crença curada ao misturar com a aprendida:
     * {@code final = original·K/(K+n) + média·n/(K+n)}. K=5: com 1 resposta o
     * original ainda pesa 83%; com 5, meio a meio.
     */
    private static final double BELIEF_PRIOR_STRENGTH = 5.0;

    private static final Stats EMPTY_STATS = new Stats(0, 0, 0);
    private static final PlayerHistory EMPTY_HISTORY = new PlayerHistory(
            EMPTY_STATS, Collections.emptyList(), Collections.emptyList());

    /** Como a partida terminou — alimenta as estatísticas. */
    public enum Outcome {
        /** O motor chutou certo. */
        ENGINE_GUESSED,
        /** O jogador escolheu o personagem certo entre as alternativas oferecidas. */
        PICKED_FROM_ALTERNATIVES,
        /** O motor perdeu e o jogador revelou em quem pensou. */
        REVEALED_AFTER_LOSS,
        /** O motor perdeu e o jogador não revelou o personagem. */
        LOST_UNREVEALED
    }

    private final PlayerBackend backend;
    /** Memória da última conta lida (a da sessão). */
    @Nullable private String loadedFor;
    private Memory memory = new Memory();

    public LearningStore(PlayerBackend backend) {
        this.backend = backend;
    }

    /** Lê a memória da conta na primeira chamada. Bloqueante: chamar fora da main thread. */
    public synchronized void ensureLoaded(@Nullable String accountId) {
        if (accountId != null) memoryOf(accountId);
    }

    /** Boost multiplicativo pro prior de popularidade. 1.0 = neutro (sem conta ou sem acertos). */
    public synchronized double popularityBoost(@Nullable String accountId, int characterId) {
        if (accountId == null) return 1.0;
        Integer picks = memoryOf(accountId).picks.get(characterId);
        if (picks == null || picks <= 0) return 1.0;
        return 1.0 + PICK_BOOST_WEIGHT * Math.log1p(picks);
    }

    /**
     * Crença final pro par (personagem, atributo), misturando {@code originalBelief}
     * com o que foi aprendido, ou {@code null} se o par nunca foi observado (ou
     * sem sessão aberta).
     */
    public synchronized Double blendedBelief(@Nullable String accountId, int characterId, String key,
                                             double originalBelief) {
        if (accountId == null) return null;
        Map<String, double[]> perAttr = memoryOf(accountId).beliefs.get(characterId);
        if (perAttr == null) return null;
        double[] sumCount = perAttr.get(key);
        if (sumCount == null || sumCount[1] <= 0) return null;
        double n = sumCount[1];
        double learnedMean = sumCount[0] / n;
        double w = n / (BELIEF_PRIOR_STRENGTH + n);
        return originalBelief * (1 - w) + learnedMean * w;
    }

    /**
     * Registra o fim de uma partida em que o personagem certo é conhecido.
     * {@code answers} deve conter só respostas com evidência ("Não sei" fica de fora).
     * Sem sessão aberta ({@code accountId == null}) a partida não é gravada.
     */
    public void recordGame(@Nullable String accountId, int correctId, List<AnswerRecord> givenAnswers,
                           Outcome outcome) {
        if (accountId == null) return;
        // "Não sei" (NaN) não é evidência — e nem o servidor aceita NaN.
        List<AnswerRecord> answers = new ArrayList<>();
        for (AnswerRecord a : givenAnswers) {
            if (a != null && a.key != null && !Double.isNaN(a.value)) answers.add(a);
        }
        synchronized (this) {
            Memory m = memoryOf(accountId);
            m.picks.merge(correctId, 1, Integer::sum);
            Map<String, double[]> perAttr = m.beliefs.computeIfAbsent(correctId, k -> new HashMap<>());
            for (AnswerRecord a : answers) {
                double[] sumCount = perAttr.computeIfAbsent(a.key, k -> new double[2]);
                sumCount[0] += a.value;
                sumCount[1] += 1;
            }
            count(m, outcome);
            m.recent = null;
        }
        backend.recordGame(accountId, new PlayerBackend.Game(System.currentTimeMillis(), correctId, outcome, answers),
                outcome == Outcome.ENGINE_GUESSED);
    }

    /** Registra uma partida perdida sem personagem revelado — só entra nas estatísticas. */
    public void recordLoss(@Nullable String accountId) {
        if (accountId == null) return;
        synchronized (this) {
            count(memoryOf(accountId), Outcome.LOST_UNREVEALED);
        }
        backend.recordGame(accountId, null, false);
    }

    private static void count(Memory m, Outcome outcome) {
        m.gamesPlayed++;
        if (outcome == Outcome.ENGINE_GUESSED) m.engineWins++;
    }

    /** Fotografia das estatísticas da conta. Bloqueante na primeira chamada. Sem conta, tudo em zero. */
    public synchronized Stats stats(@Nullable String accountId) {
        if (accountId == null) return EMPTY_STATS;
        Memory m = memoryOf(accountId);
        return new Stats(m.gamesPlayed, Math.min(m.engineWins, m.gamesPlayed), m.picks.size());
    }

    /**
     * Fotografia do histórico da conta para a tela de perfil: estatísticas,
     * os {@code maxFavorites} personagens mais pensados e as {@code maxRecent}
     * partidas mais recentes com personagem conhecido (da mais nova para a mais
     * antiga). Sem conta, devolve tudo vazio.
     */
    public synchronized PlayerHistory history(@Nullable String accountId, int maxFavorites, int maxRecent) {
        if (accountId == null) return EMPTY_HISTORY;
        Memory m = memoryOf(accountId);

        List<CharacterCount> favorites = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : m.picks.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) favorites.add(new CharacterCount(e.getKey(), e.getValue()));
        }
        // Empate: id menor primeiro, para a ordem não mudar entre aberturas da tela.
        favorites.sort((a, b) -> a.count != b.count
                ? Integer.compare(b.count, a.count)
                : Integer.compare(a.characterId, b.characterId));
        if (favorites.size() > maxFavorites) favorites = new ArrayList<>(favorites.subList(0, maxFavorites));

        if (m.recent == null || m.recent.size() < maxRecent) {
            try {
                List<GameRecord> recent = new ArrayList<>();
                for (PlayerBackend.Game g : backend.recentGames(accountId, maxRecent)) {
                    recent.add(new GameRecord(g.timestamp, g.characterId, g.outcome));
                }
                m.recent = recent;
            } catch (CloudException e) {
                Log.i(TAG, "Histórico indisponível (" + e.reason + ")");
            }
        }
        List<GameRecord> recent = m.recent == null ? Collections.emptyList()
                : m.recent.subList(0, Math.min(maxRecent, m.recent.size()));
        return new PlayerHistory(stats(accountId), favorites, new ArrayList<>(recent));
    }

    /** Apaga tudo que foi aprendido pela conta, inclusive números e histórico. Sem conta, não faz nada. */
    public void reset(@Nullable String accountId) {
        if (accountId == null) return;
        synchronized (this) {
            if (accountId.equals(loadedFor)) {
                memory = new Memory();
                memory.recent = new ArrayList<>();
            }
        }
        backend.resetLearning(accountId);
    }

    /** Esquece o que foi lido (ex.: ao entrar numa conta): a próxima consulta vai ao servidor. */
    public synchronized void invalidate() {
        loadedFor = null;
        memory = new Memory();
    }

    private Memory memoryOf(String accountId) {
        if (accountId.equals(loadedFor)) return memory;
        try {
            PlayerBackend.Learning learning = backend.loadLearning(accountId);
            Memory m = new Memory();
            m.gamesPlayed = learning.gamesPlayed;
            m.engineWins = learning.engineWins;
            m.picks.putAll(learning.picks);
            for (Map.Entry<Integer, Map<String, double[]>> e : learning.beliefs.entrySet()) {
                Map<String, double[]> copy = new HashMap<>();
                for (Map.Entry<String, double[]> attr : e.getValue().entrySet()) {
                    copy.put(attr.getKey(), attr.getValue().clone());
                }
                m.beliefs.put(e.getKey(), copy);
            }
            loadedFor = accountId;
            memory = m;
            return m;
        } catch (CloudException | RuntimeException e) {
            // Sem rede e sem cópia: neutro agora, e a próxima consulta tenta de novo.
            Log.i(TAG, "Memória do Vigia indisponível", e);
            return new Memory();
        }
    }

    /** O que já foi lido da conta, atualizado a cada partida. */
    private static final class Memory {
        int gamesPlayed;
        int engineWins;
        final Map<Integer, Integer> picks = new HashMap<>();
        final Map<Integer, Map<String, double[]>> beliefs = new HashMap<>();
        /** Partidas recentes; {@code null} = ainda não lidas (ou mudaram). */
        @Nullable List<GameRecord> recent;
    }

    /** Uma resposta dada numa partida. */
    public static final class AnswerRecord {
        public final String key;
        public final double value;

        public AnswerRecord(String key, double value) {
            this.key = key;
            this.value = value;
        }
    }

    public static final class Stats {
        public final int gamesPlayed;
        public final int engineWins;
        public final int distinctCharacters;

        Stats(int gamesPlayed, int engineWins, int distinctCharacters) {
            this.gamesPlayed = gamesPlayed;
            this.engineWins = engineWins;
            this.distinctCharacters = distinctCharacters;
        }

        /** Partidas em que o Vigia não acertou de primeira. */
        public int playerWins() {
            return gamesPlayed - engineWins;
        }

        /** Taxa de acerto do motor em [0,1], ou 0 sem partidas. */
        public double engineWinRate() {
            return gamesPlayed == 0 ? 0 : (double) engineWins / gamesPlayed;
        }
    }

    /** Quantas vezes o jogador pensou num personagem. */
    public static final class CharacterCount {
        public final int characterId;
        public final int count;

        CharacterCount(int characterId, int count) {
            this.characterId = characterId;
            this.count = count;
        }
    }

    /** Uma partida terminada, vista pela tela de perfil. */
    public static final class GameRecord {
        public final long timestamp;
        public final int characterId;
        public final Outcome outcome;

        GameRecord(long timestamp, int characterId, Outcome outcome) {
            this.timestamp = timestamp;
            this.characterId = characterId;
            this.outcome = outcome;
        }
    }

    /** Resultado de {@link #history}. Listas imutáveis. */
    public static final class PlayerHistory {
        public final Stats stats;
        public final List<CharacterCount> favorites;
        public final List<GameRecord> recentGames;

        PlayerHistory(Stats stats, List<CharacterCount> favorites, List<GameRecord> recentGames) {
            this.stats = stats;
            this.favorites = Collections.unmodifiableList(favorites);
            this.recentGames = Collections.unmodifiableList(recentGames);
        }
    }
}
