package com.ovigia.app.game;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.SavedStateHandleSupport;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.data.CharacterRepository;
import com.ovigia.app.engine.Answer;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.engine.GameEngine;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.learning.LearningStore.Outcome;
import com.ovigia.app.util.Event;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Orquestra uma partida: pede o elenco ao {@link CharacterRepository}, conduz o
 * {@link GameEngine} e expõe um {@link GameUiState} + {@link GameEvent}s.
 *
 * Escopo: o grafo de navegação da partida ({@code game_graph}). Ao sair da
 * partida o ViewModel é destruído — não há "reset" manual.
 *
 * Morte de processo: cada jogada vai para um log no {@link SavedStateHandle}.
 * Ao restaurar, o elenco é recarregado (do cache em disco) e o log é
 * reaplicado no motor, reconstruindo exatamente o mesmo estado — inclusive a
 * pergunta que estava na tela.
 *
 * O humor do Vigia ({@link WatcherMood}) sai daqui: cada resposta compara a
 * probabilidade do favorito antes e depois dela. O humor também vai para o
 * {@link SavedStateHandle}, porque reações (irritado, desconfiado) dependem do
 * que acabou de acontecer e não dá para deduzi-las só do estado do motor.
 */
public class GameViewModel extends ViewModel {

    /** Quantas alternativas oferecer quando o jogador para de responder. */
    static final int MAX_ALTERNATIVES = 5;

    private static final String KEY_MOVES = "moves";
    private static final String KEY_QUESTION = "question";
    private static final String KEY_LAST_GUESS = "last_guess";
    private static final String KEY_FINISHED = "finished";
    private static final String KEY_MOOD = "mood";

    private static final String MOVE_ANSWER = "A";
    private static final String MOVE_SKIP = "S";
    private static final String MOVE_REJECT = "R";

    private final CharacterRepository repository;
    private final LearningStore learningStore;
    private final AccountStore accountStore;
    private final SavedStateHandle savedState;
    private final Random random;

    private final Map<Integer, CharacterProfile> profilesById = new HashMap<>();
    private final MutableLiveData<GameUiState> state = new MutableLiveData<>(GameUiState.loading());
    private final MutableLiveData<Event<GameEvent>> events = new MutableLiveData<>();

    private GameEngine engine;
    private String currentQuestionKey;
    /** Conta ativa quando a partida carregou; congelada até o fim para não trocar de coleção no meio. */
    @Nullable private String accountId;
    private WatcherMood mood = WatcherMood.THINKING;
    private boolean loading = false;

    public GameViewModel(CharacterRepository repository, LearningStore learningStore,
                         AccountStore accountStore, SavedStateHandle savedState, Random random) {
        this.repository = repository;
        this.learningStore = learningStore;
        this.accountStore = accountStore;
        this.savedState = savedState;
        this.random = random;
    }

    public LiveData<GameUiState> state() { return state; }

    public LiveData<Event<GameEvent>> events() { return events; }

    @Nullable
    public CharacterProfile getProfile(int characterId) {
        return profilesById.get(characterId);
    }

    // ---------------------------------------------------------------- carga

    /** Idempotente: carrega o elenco só se ainda não carregou (ou após erro, via {@link #retry}). */
    public void start() {
        if (engine != null || loading) return;
        if (state.getValue() != null && state.getValue().phase == GameUiState.Phase.ERROR) return;
        load();
    }

    public void retry() {
        if (engine != null || loading) return;
        load();
    }

    private void load() {
        loading = true;
        state.setValue(GameUiState.loading());
        repository.loadCharacters(new CharacterRepository.Callback() {
            @Override
            public void onSuccess(List<CharacterProfile> profiles, Map<String, String> questionTextByKey) {
                loading = false;
                onLoaded(profiles, questionTextByKey);
            }

            @Override
            public void onError(CharacterRepository.LoadError error) {
                loading = false;
                state.setValue(GameUiState.error(error));
            }
        });
    }

    private void onLoaded(List<CharacterProfile> profiles, Map<String, String> questionTextByKey) {
        profilesById.clear();
        for (CharacterProfile p : profiles) profilesById.put(p.id, p);
        // Só o id, que vem da sessão sem tocar na rede: isto roda na main thread.
        accountId = accountStore.currentAccountId();
        String scope = accountId;
        engine = new GameEngine(profiles, questionTextByKey,
                id -> learningStore.popularityBoost(scope, id), random);

        if (Boolean.TRUE.equals(savedState.get(KEY_FINISHED))) {
            state.setValue(GameUiState.finished(mood));
            return;
        }

        List<String> moves = moves();
        if (moves.isEmpty()) {
            setMood(WatcherMood.forConfidence(probabilityOf(engine.topGuess())));
            advance();
            return;
        }
        replay(moves);
        mood = savedMood();
        String savedQuestion = savedState.get(KEY_QUESTION);
        if (savedQuestion != null) {
            engine.setPendingQuestion(savedQuestion);
            showQuestion(engine.nextQuestionKey());
        } else {
            state.setValue(GameUiState.deciding(mood));
        }
    }

    private void replay(List<String> moves) {
        for (String move : moves) {
            String[] parts = move.split(":");
            switch (parts[0]) {
                case MOVE_ANSWER:
                    engine.answer(parts[1], Answer.valueOf(parts[2]));
                    break;
                case MOVE_SKIP:
                    engine.skipQuestion(parts[1]);
                    break;
                case MOVE_REJECT:
                    engine.rejectGuess(Integer.parseInt(parts[1]));
                    break;
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------------ perguntas

    public void answer(Answer answer) {
        if (!isAsking()) return;
        String key = currentQuestionKey;
        if (answer.isEvidence()) {
            // O motor atualiza as probabilidades nos próprios perfis: guardar o
            // líder de antes permite ver quanto ele perdeu com esta resposta.
            CharacterProfile formerLeader = engine.topGuess();
            double expectation = probabilityOf(formerLeader);
            engine.answer(key, answer);
            addMove(MOVE_ANSWER + ":" + key + ":" + answer.name());
            setMood(WatcherMood.afterAnswer(expectation, probabilityOf(formerLeader),
                    probabilityOf(engine.topGuess())));
        } else {
            // "Não sei" não traz evidência: o Vigia mantém a reação que estava.
            engine.skipQuestion(key);
            addMove(MOVE_SKIP + ":" + key);
        }
        advance();
    }

    /**
     * Desfaz a última resposta e mostra de novo a pergunta desfeita. Devolve
     * false quando não há o que desfazer.
     */
    public boolean goBackOneQuestion() {
        if (engine == null || isFinished() || !engine.canGoBack()) return false;
        List<String> moves = moves();
        for (int i = moves.size() - 1; i >= 0; i--) {
            if (!moves.get(i).startsWith(MOVE_REJECT)) {
                moves.remove(i);
                break;
            }
        }
        savedState.set(KEY_MOVES, new ArrayList<>(moves));
        savedState.set(KEY_LAST_GUESS, null);
        engine.goBack();
        // Resposta desfeita: a reação a ela some junto.
        setMood(WatcherMood.forConfidence(probabilityOf(engine.topGuess())));
        showQuestion(engine.nextQuestionKey());
        return true;
    }

    /**
     * Chamado quando a tela de perguntas volta a ficar visível. Normalmente não
     * faz nada — o estado já tem a pergunta. Só age se a tela reaparecer sem uma
     * decisão pendente em outra tela (ex.: restauração interrompida), para o
     * jogo nunca ficar parado.
     */
    public void onQuestionsVisible() {
        GameUiState current = state.getValue();
        if (engine != null && current != null && current.phase == GameUiState.Phase.DECIDING) {
            advance();
        }
    }

    private void advance() {
        if (engine.shouldGuessNow()) {
            CharacterProfile guess = engine.topGuess();
            if (guess != null) setMood(WatcherMood.forGuess(guess.probability));
            clearQuestion();
            if (guess == null) {
                emit(GameEvent.of(GameEvent.Type.SHOW_REVEAL));
                return;
            }
            savedState.set(KEY_LAST_GUESS, guess.id);
            emit(GameEvent.showGuess(guess.id));
            return;
        }
        showQuestion(engine.nextQuestionKey());
    }

    private void showQuestion(String key) {
        if (key == null) {
            clearQuestion();
            offerAlternatives();
            return;
        }
        currentQuestionKey = key;
        savedState.set(KEY_QUESTION, key);
        state.setValue(GameUiState.asking(engine.questionTextFor(key), engine.questionsAsked() + 1,
                engine.canGoBack(), mood));
    }

    private void clearQuestion() {
        currentQuestionKey = null;
        savedState.set(KEY_QUESTION, null);
        state.setValue(GameUiState.deciding(mood));
    }

    // ---------------------------------------------------------------- chute

    /** Jogador confirmou o chute. */
    public void confirmGuess() {
        Integer guessed = savedState.get(KEY_LAST_GUESS);
        if (guessed == null) return;
        finish(Outcome.ENGINE_GUESSED, guessed);
    }

    /** Jogador rejeitou o chute atual. A tela seguinte pergunta se ele quer continuar. */
    public void rejectGuess() {
        Integer guessed = savedState.get(KEY_LAST_GUESS);
        if (engine == null || isFinished() || guessed == null) return;
        // Lida antes de rejeitar: o motor zera a probabilidade do chute errado.
        double confidence = probabilityOf(profilesById.get(guessed));
        engine.rejectGuess(guessed);
        addMove(MOVE_REJECT + ":" + guessed);
        savedState.set(KEY_LAST_GUESS, null);
        setMood(WatcherMood.afterRejectedGuess(confidence));
        state.setValue(GameUiState.deciding(mood));
    }

    /**
     * Jogador quer continuar respondendo: SEMPRE volta a perguntar, nunca chuta
     * direto — o segundo colocado pode ter herdado a massa do líder errado, mas
     * o jogador pediu mais perguntas. Sem pergunta sobrando, oferece alternativas.
     */
    public void continueGuessing() {
        if (engine == null || isFinished()) return;
        String key = engine.nextQuestionKey();
        if (key == null) {
            offerAlternatives();
            return;
        }
        // Acabou de errar: volta às perguntas desconfiado, até a próxima resposta.
        setMood(WatcherMood.SKEPTICAL);
        showQuestion(key);
        emit(GameEvent.of(GameEvent.Type.RETURN_TO_QUESTIONS));
    }

    /** Jogador não quer mais perguntas. */
    public void stopGuessing() {
        if (engine == null || isFinished()) return;
        offerAlternatives();
    }

    private void offerAlternatives() {
        boolean any = !engine.remainingCandidates(1).isEmpty();
        emit(GameEvent.of(any ? GameEvent.Type.SHOW_ALTERNATIVES : GameEvent.Type.SHOW_REVEAL));
    }

    /** Candidatos restantes mais prováveis (vazio se o elenco não carregou). */
    public List<CharacterProfile> alternatives() {
        return engine == null ? new ArrayList<>() : engine.remainingCandidates(MAX_ALTERNATIVES);
    }

    /** Todo o elenco, em ordem alfabética — para o jogador revelar em quem pensou. */
    public List<CharacterProfile> allCharacters() {
        if (engine == null) return new ArrayList<>();
        List<CharacterProfile> all = new ArrayList<>(engine.allCandidates());
        // O idioma do app vira o Locale padrão (inclusive quando escolhido nas configurações).
        Collator collator = Collator.getInstance(Locale.getDefault());
        all.sort((a, b) -> collator.compare(String.valueOf(a.name), String.valueOf(b.name)));
        return all;
    }

    // ------------------------------------------------------------- desfecho

    public void confirmAlternative(int characterId) {
        finish(Outcome.PICKED_FROM_ALTERNATIVES, characterId);
    }

    /** Jogador revelou em quem pensou depois que o motor perdeu. */
    public void reveal(int characterId) {
        finish(Outcome.REVEALED_AFTER_LOSS, characterId);
    }

    /** Motor perdeu e o jogador não quis revelar. */
    public void giveUp() {
        if (isFinished()) return;
        markFinished();
        learningStore.recordLoss(accountId);
        emit(GameEvent.finished(Outcome.LOST_UNREVEALED, -1, null, null));
    }

    /** Encerra a partida uma única vez — toques repetidos não gravam aprendizado em dobro. */
    private void finish(Outcome outcome, int characterId) {
        if (engine == null || isFinished()) return;
        CharacterProfile profile = profilesById.get(characterId);
        if (profile == null) return;
        markFinished();
        learningStore.recordGame(accountId, characterId, answersGiven(), outcome);
        emit(GameEvent.finished(outcome, characterId, profile.name, profile.imageUrl));
    }

    private void markFinished() {
        savedState.set(KEY_FINISHED, true);
        currentQuestionKey = null;
        state.setValue(GameUiState.finished(mood));
    }

    private List<LearningStore.AnswerRecord> answersGiven() {
        List<LearningStore.AnswerRecord> answers = new ArrayList<>();
        for (String move : moves()) {
            String[] parts = move.split(":");
            if (MOVE_ANSWER.equals(parts[0])) {
                answers.add(new LearningStore.AnswerRecord(parts[1], Answer.valueOf(parts[2]).value));
            }
        }
        return answers;
    }

    // ---------------------------------------------------------------- apoio

    private boolean isAsking() {
        return engine != null && currentQuestionKey != null && !isFinished();
    }

    private boolean isFinished() {
        return Boolean.TRUE.equals(savedState.get(KEY_FINISHED));
    }

    private static double probabilityOf(@Nullable CharacterProfile profile) {
        return profile != null ? profile.probability : 0;
    }

    private void setMood(WatcherMood mood) {
        this.mood = mood;
        savedState.set(KEY_MOOD, mood.name());
    }

    /** Humor salvo antes da morte do processo; sem ele, o de repouso para a confiança atual. */
    private WatcherMood savedMood() {
        String name = savedState.get(KEY_MOOD);
        if (name != null) {
            try {
                return WatcherMood.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // Nome de outra versão do app: cai no humor de repouso.
            }
        }
        return WatcherMood.forConfidence(probabilityOf(engine.topGuess()));
    }

    private List<String> moves() {
        ArrayList<String> moves = savedState.get(KEY_MOVES);
        return moves != null ? new ArrayList<>(moves) : new ArrayList<>();
    }

    private void addMove(String move) {
        List<String> moves = moves();
        moves.add(move);
        savedState.set(KEY_MOVES, new ArrayList<>(moves));
    }

    private void emit(GameEvent event) {
        events.setValue(new Event<>(event));
    }

    /**
     * Cria o ViewModel com o {@link SavedStateHandle} do dono (o back stack entry
     * da partida), obtido das {@link CreationExtras} que o dono fornece.
     */
    public static final class Factory implements ViewModelProvider.Factory {

        private final CharacterRepository repository;
        private final LearningStore learningStore;
        private final AccountStore accountStore;

        public Factory(CharacterRepository repository, LearningStore learningStore, AccountStore accountStore) {
            this.repository = repository;
            this.learningStore = learningStore;
            this.accountStore = accountStore;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass, @NonNull CreationExtras extras) {
            SavedStateHandle handle = SavedStateHandleSupport.createSavedStateHandle(extras);
            return (T) new GameViewModel(repository, learningStore, accountStore, handle, new Random());
        }
    }
}
