package com.ovigia.app.learning;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Estado persistente de aprendizado entre partidas, num único arquivo JSON.
 *
 * Cada conta tem seu próprio bloco (estatísticas, favoritos, crenças aprendidas):
 * o motor aprende com o dono da sessão e nada vaza para outra conta no mesmo
 * aparelho. Sem sessão aberta, leituras devolvem valores neutros e gravações
 * são ignoradas — jogar deslogado não conta pra ninguém.
 *
 * O bloco de cada conta guarda:
 *
 * 1. Contagem de acertos por personagem — boost no prior inicial do
 *    {@code GameEngine}: personagens que ESTE jogador escolhe com frequência
 *    sobem no ranking desde a primeira pergunta.
 * 2. Correção de crenças por atributo — sempre que a partida termina com o
 *    personagem certo conhecido, as respostas dadas são agregadas e misturadas
 *    com a crença curada (inclusive para atributos que a curadoria não listou).
 * 3. Estatísticas e histórico bruto de partidas — para a tela de estatísticas
 *    e para recalibrar o modelo de ruído das respostas offline.
 *    O histórico também alimenta a tela de perfil ({@link #history}).
 *
 * Thread-safety: todos os acessos ao estado são sincronizados. A leitura do
 * disco ({@link #ensureLoaded()}) deve rodar fora da main thread; a gravação é
 * sempre despachada para o {@code ioExecutor} e é atômica (arquivo temporário +
 * rename), então um app morto no meio da escrita não corrompe o aprendizado.
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

    /** Máximo de partidas guardadas no histórico bruto (as mais antigas saem primeiro). */
    private static final int MAX_GAME_LOG_ENTRIES = 500;

    /**
     * Nomes que o R8 deu aos campos de {@link PerAccountState} nos releases 1.1.0 a 1.2.2
     * (na ordem em que são declarados) e os nomes de verdade, na mesma ordem.
     */
    private static final String[] OBFUSCATED_KEYS = {"a", "b", "c", "d", "e", "f"};
    private static final String[] REAL_KEYS =
            {"picksById", "beliefsById", "gameLog", "gamesPlayed", "engineWins", "baseDistinctCharacters"};

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

    private final Supplier<File> fileSupplier;
    private final Object fileLock = new Object();
    private final Executor ioExecutor;
    private final Gson gson = new Gson();
    private State state;

    /**
     * @param fileSupplier resolvido só no executor de I/O — obter o diretório de
     *                     arquivos do app já é acesso a disco.
     */
    public LearningStore(Supplier<File> fileSupplier, Executor ioExecutor) {
        this.fileSupplier = fileSupplier;
        this.ioExecutor = ioExecutor;
    }

    /** Carrega do disco na primeira chamada. Bloqueante: chamar fora da main thread. */
    public synchronized void ensureLoaded() {
        if (state == null) state = load();
    }

    /** Boost multiplicativo pro prior de popularidade. 1.0 = neutro (sem conta ou sem acertos). */
    public synchronized double popularityBoost(@Nullable String accountId, int characterId) {
        ensureLoaded();
        PerAccountState perAccount = perAccount(accountId, false);
        if (perAccount == null) return 1.0;
        Integer picks = perAccount.picksById.get(characterId);
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
        ensureLoaded();
        PerAccountState perAccount = perAccount(accountId, false);
        if (perAccount == null) return null;
        Map<String, double[]> perAttr = perAccount.beliefsById.get(characterId);
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
        // "Não sei" (NaN) não é evidência — e o Gson nem consegue serializar NaN.
        List<AnswerRecord> answers = new ArrayList<>();
        for (AnswerRecord a : givenAnswers) {
            if (a != null && a.key != null && !Double.isNaN(a.value)) answers.add(a);
        }
        synchronized (this) {
            ensureLoaded();
            PerAccountState perAccount = perAccount(accountId, true);
            Integer prev = perAccount.picksById.get(correctId);
            perAccount.picksById.put(correctId, (prev == null ? 0 : prev) + 1);

            Map<String, double[]> perAttr = perAccount.beliefsById.get(correctId);
            if (perAttr == null) {
                perAttr = new LinkedHashMap<>();
                perAccount.beliefsById.put(correctId, perAttr);
            }
            for (AnswerRecord a : answers) {
                double[] sumCount = perAttr.get(a.key);
                if (sumCount == null) {
                    sumCount = new double[]{0.0, 0.0};
                    perAttr.put(a.key, sumCount);
                }
                sumCount[0] += a.value;
                sumCount[1] += 1;
            }

            perAccount.gameLog.add(new GameLogEntry(System.currentTimeMillis(), correctId, answers, outcome));
            while (perAccount.gameLog.size() > MAX_GAME_LOG_ENTRIES) {
                perAccount.gameLog.remove(0);
            }
            countOutcome(perAccount, outcome);
        }
        persistAsync();
    }

    /** Registra uma partida perdida sem personagem revelado — só entra nas estatísticas. */
    public void recordLoss(@Nullable String accountId) {
        if (accountId == null) return;
        synchronized (this) {
            ensureLoaded();
            countOutcome(perAccount(accountId, true), Outcome.LOST_UNREVEALED);
        }
        persistAsync();
    }

    private static void countOutcome(PerAccountState perAccount, Outcome outcome) {
        perAccount.gamesPlayed++;
        if (outcome == Outcome.ENGINE_GUESSED) perAccount.engineWins++;
    }

    /**
     * Fotografia das estatísticas da conta. Bloqueante na primeira chamada
     * (carrega do disco). Sem conta, devolve tudo em zero.
     */
    public synchronized Stats stats(@Nullable String accountId) {
        ensureLoaded();
        PerAccountState perAccount = perAccount(accountId, false);
        if (perAccount == null) return EMPTY_STATS;
        return new Stats(perAccount.gamesPlayed, perAccount.engineWins,
                Math.max(perAccount.picksById.size(), perAccount.baseDistinctCharacters));
    }

    /**
     * Recupera os números de uma conta trazida da nuvem (outro aparelho): só vale
     * para um bloco ainda sem partidas, para nunca apagar o que foi jogado aqui.
     * O aprendizado em si não vem junto — o servidor guarda só os totais.
     */
    public void importStats(String accountId, int gamesPlayed, int engineWins, int distinctCharacters) {
        if (accountId == null) return;
        synchronized (this) {
            ensureLoaded();
            PerAccountState perAccount = perAccount(accountId, true);
            if (perAccount.gamesPlayed > 0 || !perAccount.picksById.isEmpty()) return;
            perAccount.gamesPlayed = Math.max(0, gamesPlayed);
            perAccount.engineWins = Math.max(0, Math.min(engineWins, perAccount.gamesPlayed));
            perAccount.baseDistinctCharacters = Math.max(0, distinctCharacters);
        }
        persistAsync();
    }

    /**
     * Fotografia do histórico da conta para a tela de perfil: estatísticas,
     * os {@code maxFavorites} personagens mais pensados e as {@code maxRecent}
     * partidas mais recentes com personagem conhecido (da mais nova para a mais
     * antiga). Bloqueante na primeira chamada (carrega do disco). Sem conta,
     * devolve tudo vazio.
     */
    public synchronized PlayerHistory history(@Nullable String accountId, int maxFavorites, int maxRecent) {
        ensureLoaded();
        PerAccountState perAccount = perAccount(accountId, false);
        if (perAccount == null) return EMPTY_HISTORY;

        List<CharacterCount> favorites = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : perAccount.picksById.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                favorites.add(new CharacterCount(e.getKey(), e.getValue()));
            }
        }
        // Empate: id menor primeiro, para a ordem não mudar entre aberturas da tela.
        favorites.sort((a, b) -> a.count != b.count
                ? Integer.compare(b.count, a.count)
                : Integer.compare(a.characterId, b.characterId));
        if (favorites.size() > maxFavorites) favorites = new ArrayList<>(favorites.subList(0, maxFavorites));

        List<GameRecord> recent = new ArrayList<>();
        for (int i = perAccount.gameLog.size() - 1; i >= 0 && recent.size() < maxRecent; i--) {
            GameLogEntry entry = perAccount.gameLog.get(i);
            if (entry == null || entry.outcome == null) continue;
            recent.add(new GameRecord(entry.timestamp, entry.correctId, entry.outcome));
        }
        return new PlayerHistory(stats(accountId), favorites, recent);
    }

    /** Apaga tudo que foi aprendido pela conta. Sem conta, não faz nada. */
    public void reset(@Nullable String accountId) {
        if (accountId == null) return;
        synchronized (this) {
            ensureLoaded();
            state.byAccount.remove(accountId);
        }
        persistAsync();
    }

    /** Apaga o bloco da conta (usado ao excluir a conta). */
    public void deleteAccount(String accountId) {
        reset(accountId);
    }

    @Nullable
    private PerAccountState perAccount(@Nullable String accountId, boolean create) {
        if (accountId == null) return null;
        PerAccountState perAccount = state.byAccount.get(accountId);
        if (perAccount == null) {
            if (!create) return null;
            perAccount = new PerAccountState();
            state.byAccount.put(accountId, perAccount);
        } else {
            perAccount.normalize();
        }
        return perAccount;
    }

    private State load() {
        File file = fileSupplier.get();
        if (!file.exists()) return new State();
        try {
            JsonElement json = JsonParser.parseString(AtomicFiles.readUtf8(file));
            restoreObfuscatedKeys(json);
            State loaded = gson.fromJson(json, State.class);
            if (loaded == null || loaded.byAccount == null) return new State();
            loaded.byAccount.values().removeIf(v -> v == null);
            for (PerAccountState perAccount : loaded.byAccount.values()) {
                perAccount.normalize();
            }
            return loaded;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Falha ao ler estado de aprendizado; começando do zero", e);
            return new State();
        }
    }

    /**
     * Os releases 1.1.0 a 1.2.2 gravaram o bloco de cada conta com os nomes de campo
     * ofuscados ({@code a} a {@code f}): faltava a regra de keep de {@link PerAccountState}.
     * Devolve os nomes de verdade antes de ler, para quem já jogou nessas versões não
     * perder partidas, favoritos e aprendizado. Um arquivo gravado com os nomes certos
     * não tem essas chaves e passa intacto.
     */
    private static void restoreObfuscatedKeys(JsonElement root) {
        if (root == null || !root.isJsonObject()) return;
        JsonElement byAccount = root.getAsJsonObject().get("byAccount");
        if (byAccount == null || !byAccount.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> account : byAccount.getAsJsonObject().entrySet()) {
            if (!account.getValue().isJsonObject()) continue;
            JsonObject block = account.getValue().getAsJsonObject();
            for (int i = 0; i < OBFUSCATED_KEYS.length; i++) {
                if (block.has(OBFUSCATED_KEYS[i]) && !block.has(REAL_KEYS[i])) {
                    block.add(REAL_KEYS[i], block.remove(OBFUSCATED_KEYS[i]));
                }
            }
        }
    }

    private void persistAsync() {
        ioExecutor.execute(() -> {
            String json;
            synchronized (this) {
                json = gson.toJson(state, State.class);
            }
            synchronized (fileLock) {
                try {
                    AtomicFiles.writeUtf8(fileSupplier.get(), json);
                } catch (IOException e) {
                    Log.w(TAG, "Falha ao salvar estado de aprendizado", e);
                }
            }
        });
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

    /** Snapshot de uma partida terminada — só para análise offline. */
    private static final class GameLogEntry {
        final long timestamp;
        final int correctId;
        final List<AnswerRecord> answers;
        final Outcome outcome;

        GameLogEntry(long timestamp, int correctId, List<AnswerRecord> answers, Outcome outcome) {
            this.timestamp = timestamp;
            this.correctId = correctId;
            this.answers = answers;
            this.outcome = outcome;
        }
    }

    /** Bloco por conta no arquivo serializado. */
    private static final class PerAccountState {
        Map<Integer, Integer> picksById = new HashMap<>();
        /** id -> (chave do atributo -> [soma das respostas, quantidade]). */
        Map<Integer, Map<String, double[]>> beliefsById = new HashMap<>();
        List<GameLogEntry> gameLog = new ArrayList<>();
        int gamesPlayed;
        int engineWins;
        /** Personagens distintos trazidos da nuvem ao recuperar a conta (ver {@code importStats}). */
        int baseDistinctCharacters;

        /** Blindagem contra JSON antigo com campos nulos. */
        void normalize() {
            if (picksById == null) picksById = new HashMap<>();
            if (beliefsById == null) beliefsById = new HashMap<>();
            if (gameLog == null) gameLog = new ArrayList<>();
        }
    }

    /** Formato serializado: id da conta -> bloco. */
    private static final class State {
        Map<String, PerAccountState> byAccount = new HashMap<>();
    }
}
