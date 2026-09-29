package com.ovigia.app.engine;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntToDoubleFunction;

/**
 * Motor bayesiano do Akinator: mantém uma probabilidade posterior por
 * personagem e escolhe, a cada rodada, a pergunta que MINIMIZA a entropia
 * esperada da distribuição de probabilidade após a resposta — o critério
 * clássico de ganho de informação (Shannon) usado em sistemas de "20
 * perguntas". Para cada pergunta candidata, simula as quatro respostas com
 * evidência (ver {@link Answer}), pondera pela chance de cada uma ocorrer
 * dado o estado atual, e escolhe a que deixa o jogo mais "decidido" em média.
 */
public class GameEngine {

    /**
     * Crença assumida quando um personagem não tem valor para um atributo (ex.:
     * nenhum poder listado). Tratado como um "não" fraco, não como "não sei"
     * neutro — combina melhor com o dataset curado, que só lista o que o
     * personagem TEM. Público para que o aprendizado use a mesma base ao
     * corrigir atributos ausentes.
     */
    public static final double MISSING_BELIEF = 0.1;

    private static final int MAX_QUESTIONS = 20;

    /**
     * Chuta na hora se um candidato sozinho já é muito provável. Alto de propósito:
     * jogador tem preferência forte por certeza sobre velocidade — chutes precipitados
     * são o que mais quebra a sensação de "algoritmo esperto".
     */
    private static final double GUESS_THRESHOLD = 0.85;

    /**
     * Chuta mais cedo quando o líder já disparou muito à frente do segundo
     * colocado, mesmo sem ter cruzado o threshold absoluto acima — útil
     * quando ainda sobram muitos candidatos "de cauda" com probabilidade
     * residual baixa que não deveriam segurar o jogo.
     *
     * Valores altos de propósito: com um elenco de ~200 personagens e vários
     * pares/trios quase idênticos em atributos, uma folga fraca já acontece
     * por ruído com poucas perguntas — é a causa mais direta de "chute
     * alucinado". Exigir 50% absoluto, 6x sobre o 2º e 3x sobre o 3º garante
     * que o líder está genuinamente destacado.
     */
    private static final double MIN_CONFIDENT_PROBABILITY = 0.5;
    private static final double CONFIDENCE_RATIO = 6.0;
    private static final double THIRD_PLACE_RATIO = 3.0;

    /**
     * Nº mínimo de perguntas respondidas (desde o início do jogo OU desde o
     * último chute rejeitado) antes de aceitar QUALQUER chute. Sem isso,
     * rejeitar um chute podia fazer o motor chutar de novo na hora seguinte:
     * ao remover o líder errado e renormalizar, o segundo colocado às vezes
     * já cruza o threshold sozinho, mesmo tendo tido pouquíssima evidência própria.
     */
    private static final int MIN_QUESTIONS_BEFORE_GUESS = 8;

    /**
     * Em vez de sempre escolher A pergunta de menor entropia esperada, sorteia
     * entre as melhores dentro dessa tolerância relativa — evita que as
     * primeiras perguntas sejam sempre idênticas entre partidas. Quando uma
     * pergunta se destaca claramente, o pool encolhe para 1 e o motor volta a
     * ser puramente guloso.
     */
    private static final double QUESTION_POOL_TOLERANCE = 0.08;
    private static final int MAX_QUESTION_POOL = 4;

    /**
     * Quando o líder já está claramente à frente mas ainda não é confiável o
     * bastante pra chutar, o motor troca de estratégia: escolhe a pergunta que
     * melhor DISCRIMINA o líder dos concorrentes restantes ("relative
     * information gain") em vez da que corta a incerteza total.
     *
     * Trigger: líder com ≥30% de probabilidade E pelo menos 2x o 2º colocado.
     */
    private static final double CONFIRM_MODE_MIN_LEAD_PROBABILITY = 0.30;
    private static final double CONFIRM_MODE_MIN_RATIO = 2.0;

    /**
     * Multiplicador extra no prior de personagens de reconhecimento mainstream.
     * Resolve o problema em que personagens com muitas aparições em quadrinhos
     * mas pouco reconhecimento dominam a distribuição inicial.
     */
    private static final double MAINSTREAM_PRIOR_BOOST = 4.0;

    private static final double LOG2 = Math.log(2);

    private final List<CharacterProfile> candidates;
    private final Map<String, String> questionTextByKey;
    private final Set<String> askedKeys = new HashSet<>();
    private final Set<Integer> rejectedIds = new HashSet<>();
    private final Deque<Snapshot> history = new ArrayDeque<>();
    private final Random random;
    private int questionsAsked = 0;
    private int guessEligibleFrom = 0;

    /**
     * Chave a devolver na próxima chamada de {@link #nextQuestionKey()}, sem
     * sortear de novo — usado após {@link #goBack()} e ao restaurar uma partida,
     * pra preservar a MESMA pergunta que o jogador estava vendo.
     */
    private String pendingQuestionKey = null;

    public GameEngine(List<CharacterProfile> profiles, Map<String, String> questionTextByKey) {
        this(profiles, questionTextByKey, id -> 1.0, new Random());
    }

    /**
     * @param popularityBoost multiplicador extra por personagem no prior inicial
     *                        (aprendizado entre partidas); {@code 1.0} = neutro.
     * @param random          fonte do sorteio entre as melhores perguntas —
     *                        injetável para que testes sejam determinísticos.
     */
    public GameEngine(List<CharacterProfile> profiles, Map<String, String> questionTextByKey,
                      IntToDoubleFunction popularityBoost, Random random) {
        this.candidates = new ArrayList<>(profiles);
        this.questionTextByKey = new LinkedHashMap<>(questionTextByKey);
        this.random = random;
        applyPopularityPrior(popularityBoost);
    }

    /**
     * Prior inicial ponderado por popularidade real: log(2 + aparições em
     * quadrinhos) × boost mainstream × boost aprendido. A escala logarítmica
     * reduz a distância entre "muito" e "pouco" popular sem apagar o sinal, e
     * nunca gera peso zero.
     */
    private void applyPopularityPrior(IntToDoubleFunction popularityBoost) {
        if (candidates.isEmpty()) return;
        double totalWeight = 0;
        double[] weights = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            CharacterProfile c = candidates.get(i);
            double base = Math.log(2 + Math.max(0, c.issueCount));
            double weight = c.isMainstream ? base * MAINSTREAM_PRIOR_BOOST : base;
            double learned = popularityBoost.applyAsDouble(c.id);
            if (learned > 0) weight *= learned;
            weights[i] = weight;
            totalWeight += weight;
        }
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).probability = weights[i] / totalWeight;
        }
    }

    /**
     * Próxima pergunta a fazer, ou {@code null} se não sobrou nenhuma.
     * Em modo confirmação (líder destacado) mira no líder; caso contrário
     * minimiza a entropia esperada da distribuição inteira.
     */
    public String nextQuestionKey() {
        if (pendingQuestionKey != null) {
            String key = pendingQuestionKey;
            pendingQuestionKey = null;
            return key;
        }
        CharacterProfile[] top = topThree();
        if (isInConfirmMode(top)) {
            String key = pickConfirmationQuestion(top[0]);
            if (key != null) return key;
            // Líder indistinguível dos outros em tudo que sobrou: cai pro modo entropia.
        }
        return pickEntropyQuestion();
    }

    /**
     * Força a próxima {@link #nextQuestionKey()} a devolver {@code key} —
     * usado ao restaurar uma partida salva, pra mostrar a mesma pergunta de antes.
     * Ignorado se a chave não existe ou já foi perguntada.
     */
    public void setPendingQuestion(String key) {
        if (key != null && questionTextByKey.containsKey(key) && !askedKeys.contains(key)) {
            pendingQuestionKey = key;
        }
    }

    /**
     * Modo "cortar a dúvida": para cada pergunta, a entropia esperada é
     * Σ P(resposta) · H(posterior | resposta), somando sobre as quatro
     * respostas com evidência, onde P(resposta) = Σ p(personagem) · L(resposta | personagem).
     */
    private String pickEntropyQuestion() {
        List<Map.Entry<String, Double>> scored = new ArrayList<>();

        for (String key : questionTextByKey.keySet()) {
            if (askedKeys.contains(key)) continue;

            double expectedEntropy = 0;
            for (Answer answer : Answer.WITH_EVIDENCE) {
                double[] unnormalized = unnormalizedPosterior(key, answer);
                double pAnswer = sum(unnormalized);
                if (pAnswer <= 0) continue;
                expectedEntropy += pAnswer * entropy(unnormalized, pAnswer);
            }
            scored.add(new AbstractMap.SimpleEntry<>(key, expectedEntropy));
        }
        return pickFromPool(scored);
    }

    /**
     * Modo "confirmar o líder": escolhe a pergunta em que a crença do líder mais
     * difere da crença média (ponderada por probabilidade) dos concorrentes.
     */
    private String pickConfirmationQuestion(CharacterProfile leader) {
        if (leader == null) return null;
        List<Map.Entry<String, Double>> scored = new ArrayList<>();

        double othersMass = 0;
        for (CharacterProfile c : candidates) {
            if (isRejected(c) || c == leader) continue;
            othersMass += c.probability;
        }
        if (othersMass <= 0) return null;

        for (String key : questionTextByKey.keySet()) {
            if (askedKeys.contains(key)) continue;

            double leaderBelief = beliefOf(leader, key);
            double othersBelief = 0;
            for (CharacterProfile c : candidates) {
                if (isRejected(c) || c == leader) continue;
                othersBelief += (c.probability / othersMass) * beliefOf(c, key);
            }
            // Negativo pra ordenar igual à entropia (menor = melhor) e reusar pickFromPool.
            scored.add(new AbstractMap.SimpleEntry<>(key, -Math.abs(leaderBelief - othersBelief)));
        }
        return pickFromPool(scored);
    }

    /** Escolhe do pool das top-N perguntas ordenadas por score (menor = melhor). */
    private String pickFromPool(List<Map.Entry<String, Double>> scored) {
        if (scored.isEmpty()) return null;
        scored.sort(Map.Entry.comparingByValue());
        double best = scored.get(0).getValue();
        double cutoff = best + Math.max(QUESTION_POOL_TOLERANCE, Math.abs(best) * QUESTION_POOL_TOLERANCE);

        List<String> pool = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scored) {
            if (pool.size() >= MAX_QUESTION_POOL || entry.getValue() > cutoff) break;
            pool.add(entry.getKey());
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private boolean isInConfirmMode(CharacterProfile[] top) {
        CharacterProfile first = top[0];
        CharacterProfile second = top[1];
        if (first == null || second == null) return false;
        return first.probability >= CONFIRM_MODE_MIN_LEAD_PROBABILITY
                && first.probability >= second.probability * CONFIRM_MODE_MIN_RATIO;
    }

    public String questionTextFor(String key) {
        return questionTextByKey.get(key);
    }

    /** Atualiza a crença em cada candidato dado que o jogador respondeu {@code answer} para {@code key}. */
    public void answer(String key, Answer answer) {
        if (!answer.isEvidence()) {
            skipQuestion(key);
            return;
        }
        history.push(new Snapshot(key, currentProbabilities()));
        askedKeys.add(key);
        questionsAsked++;

        double[] posterior = unnormalizedPosterior(key, answer);
        double mass = sum(posterior);
        if (mass <= 0) return; // degenerado: todos rejeitados — mantém o estado
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).probability = posterior[i] / mass;
        }
    }

    /**
     * Jogador respondeu "Não sei": marca a chave como perguntada (pra não
     * repeti-la) e avança o contador de perguntas — pro jogador ver a
     * numeração seguir em frente — mas sem alterar nenhuma probabilidade.
     * Empilha um snapshot pra que {@link #goBack} consiga desfazer.
     */
    public void skipQuestion(String key) {
        history.push(new Snapshot(key, currentProbabilities()));
        askedKeys.add(key);
        questionsAsked++;
    }

    /** Se dá pra desfazer a última resposta e voltar pra pergunta anterior. */
    public boolean canGoBack() {
        return !history.isEmpty();
    }

    /**
     * Desfaz a última resposta: restaura as probabilidades de antes dela e
     * libera o atributo pra ser perguntado de novo. Chutes rejeitados continuam
     * rejeitados — se algum aconteceu depois do snapshot, a distribuição
     * restaurada é renormalizada sem eles.
     */
    public void goBack() {
        if (history.isEmpty()) return;
        Snapshot snapshot = history.pop();
        askedKeys.remove(snapshot.key);
        questionsAsked--;
        // Um chute rejeitado depois da resposta desfeita já tinha "zerado" a
        // exigência de evidência naquele ponto; como a resposta sumiu, o marco
        // não pode ficar à frente do contador (senão travaria chutes a mais).
        guessEligibleFrom = Math.min(guessEligibleFrom, questionsAsked);

        double mass = 0;
        for (int i = 0; i < candidates.size(); i++) {
            CharacterProfile c = candidates.get(i);
            c.probability = isRejected(c) ? 0 : snapshot.probabilitiesBefore[i];
            mass += c.probability;
        }
        if (mass > 0) {
            for (CharacterProfile c : candidates) c.probability /= mass;
        }
        pendingQuestionKey = snapshot.key;
    }

    /** Estado necessário pra desfazer uma resposta: a pergunta e as probabilidades de antes dela. */
    private static final class Snapshot {
        final String key;
        final double[] probabilitiesBefore;

        Snapshot(String key, double[] probabilitiesBefore) {
            this.key = key;
            this.probabilitiesBefore = probabilitiesBefore;
        }
    }

    private double[] currentProbabilities() {
        double[] probabilities = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            probabilities[i] = candidates.get(i).probability;
        }
        return probabilities;
    }

    /**
     * p(personagem) · L(resposta | personagem) para cada candidato, SEM normalizar.
     * A soma é exatamente P(resposta) sob o estado atual — o que a entropia
     * esperada precisa — e normalizar dá a posterior.
     */
    private double[] unnormalizedPosterior(String key, Answer answer) {
        double[] updated = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            CharacterProfile c = candidates.get(i);
            if (isRejected(c)) continue;
            updated[i] = c.probability * answer.likelihood(beliefOf(c, key));
        }
        return updated;
    }

    /** Entropia de Shannon (bits) de {@code weights / total}. */
    private static double entropy(double[] weights, double total) {
        double entropy = 0;
        for (double w : weights) {
            if (w <= 0) continue;
            double p = w / total;
            entropy -= p * (Math.log(p) / LOG2);
        }
        return entropy;
    }

    private static double sum(double[] values) {
        double total = 0;
        for (double v : values) total += v;
        return total;
    }

    public CharacterProfile topGuess() {
        return topThree()[0];
    }

    public boolean shouldGuessNow() {
        CharacterProfile[] topThree = topThree();
        CharacterProfile top = topThree[0];
        if (top == null) return true;

        // Saídas estruturais: não há mais nada a ganhar perguntando.
        if (activeCandidateCount() <= 1) return true;
        if (askedKeys.size() >= questionTextByKey.size()) return true;
        if (questionsAsked >= MAX_QUESTIONS) return true;

        boolean hasEnoughEvidence = (questionsAsked - guessEligibleFrom) >= MIN_QUESTIONS_BEFORE_GUESS;
        if (!hasEnoughEvidence) return false;

        if (top.probability >= GUESS_THRESHOLD) return true;

        CharacterProfile runnerUp = topThree[1];
        CharacterProfile third = topThree[2];
        return runnerUp != null
                && top.probability >= MIN_CONFIDENT_PROBABILITY
                && top.probability >= runnerUp.probability * CONFIDENCE_RATIO
                && (third == null || top.probability >= third.probability * THIRD_PLACE_RATIO);
    }

    /** [0] = mais provável, [1] = segundo, [2] = terceiro colocado (qualquer um pode ser null). */
    private CharacterProfile[] topThree() {
        CharacterProfile first = null;
        CharacterProfile second = null;
        CharacterProfile third = null;
        for (CharacterProfile c : candidates) {
            if (isRejected(c)) continue;
            if (first == null || c.probability > first.probability) {
                third = second;
                second = first;
                first = c;
            } else if (second == null || c.probability > second.probability) {
                third = second;
                second = c;
            } else if (third == null || c.probability > third.probability) {
                third = c;
            }
        }
        return new CharacterProfile[] { first, second, third };
    }

    /** Descarta o chute atual e redistribui as probabilidades entre os restantes. */
    public void rejectGuess(int characterId) {
        rejectedIds.add(characterId);
        double mass = 0;
        for (CharacterProfile c : candidates) {
            if (!isRejected(c)) mass += c.probability;
        }
        for (CharacterProfile c : candidates) {
            c.probability = isRejected(c) || mass <= 0 ? 0 : c.probability / mass;
        }
        // O segundo colocado não herda a confiança do líder errado: tem que
        // reconquistá-la com novas perguntas.
        guessEligibleFrom = questionsAsked;
    }

    public int questionsAsked() {
        return questionsAsked;
    }

    /** Até {@code limit} candidatos ainda ativos, do mais pro menos provável. */
    public List<CharacterProfile> remainingCandidates(int limit) {
        List<CharacterProfile> active = new ArrayList<>();
        for (CharacterProfile c : candidates) {
            if (!isRejected(c)) active.add(c);
        }
        active.sort((a, b) -> Double.compare(b.probability, a.probability));
        return new ArrayList<>(active.subList(0, Math.min(limit, active.size())));
    }

    /** Todos os personagens da partida (inclusive rejeitados), na ordem original. */
    public List<CharacterProfile> allCandidates() {
        return Collections.unmodifiableList(candidates);
    }

    private int activeCandidateCount() {
        int n = 0;
        for (CharacterProfile c : candidates) {
            if (!isRejected(c)) n++;
        }
        return n;
    }

    private boolean isRejected(CharacterProfile c) {
        return rejectedIds.contains(c.id);
    }

    private static double beliefOf(CharacterProfile c, String key) {
        Double belief = c.attributes.get(key);
        return belief == null ? MISSING_BELIEF : belief;
    }
}
