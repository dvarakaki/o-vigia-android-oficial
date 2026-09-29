package com.ovigia.app.game;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.data.CharacterRepository;
import com.ovigia.app.engine.Answer;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.util.Event;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Fluxo da partida no ViewModel, com um repositório falso e aprendizado em disco temporário. */
public class GameViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeRepository repository;
    private LearningStore learningStore;
    private AccountStore accountStore;
    private String accountId;

    @Before
    public void setUp() {
        repository = new FakeRepository();
        learningStore = new LearningStore(() -> tmp.getRoot().toPath().resolve("learning.json").toFile(), Runnable::run);
        accountStore = new AccountStore(() -> tmp.getRoot().toPath().resolve("accounts.json").toFile(), 1_000);
        accountStore.signUp("Davi", "davi@exemplo.com", "segredo#1");
        accountId = accountStore.currentAccount().id;
    }

    /** Oito personagens distinguíveis por 4 atributos binários (código de 3 bits + ruído). */
    private static List<CharacterProfile> cast() {
        List<CharacterProfile> list = new ArrayList<>();
        for (int id = 0; id < 8; id++) {
            Map<String, Double> attrs = new HashMap<>();
            attrs.put("a", (id & 1) != 0 ? 0.92 : 0.08);
            attrs.put("b", (id & 2) != 0 ? 0.92 : 0.08);
            attrs.put("c", (id & 4) != 0 ? 0.92 : 0.08);
            attrs.put("d", 0.5);
            list.add(new CharacterProfile(id, "Personagem " + id, "img", null, attrs, 0, false));
        }
        return list;
    }

    private static Map<String, String> questions() {
        Map<String, String> q = new LinkedHashMap<>();
        for (String k : new String[]{"a", "b", "c", "d"}) q.put(k, "Pergunta " + k + "?");
        return q;
    }

    private GameViewModel newViewModel(SavedStateHandle handle) {
        return new GameViewModel(repository, learningStore, accountStore, handle, new Random(7));
    }

    private static GameEvent lastEvent(GameViewModel vm) {
        Event<GameEvent> event = vm.events().getValue();
        return event == null ? null : event.consume();
    }

    @Test
    public void start_isIdempotent() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        vm.start();
        vm.start();

        assertEquals("tela voltando ao primeiro plano não pode recarregar/re-sortear", 1, repository.loads);
        assertEquals(GameUiState.Phase.ASKING, vm.state().getValue().phase);
    }

    @Test
    public void startAgain_keepsTheSameQuestionOnScreen() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        String before = vm.state().getValue().questionText;

        vm.start();
        vm.onQuestionsVisible();

        assertEquals(before, vm.state().getValue().questionText);
    }

    @Test
    public void loadError_isExposedAndRetryWorks() {
        repository.failWith = CharacterRepository.LoadError.NO_CONNECTION;
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        assertEquals(GameUiState.Phase.ERROR, vm.state().getValue().phase);
        assertEquals(CharacterRepository.LoadError.NO_CONNECTION, vm.state().getValue().error);

        vm.start();
        assertEquals("start não reentra em loop de erro sozinho", 1, repository.loads);

        repository.failWith = null;
        vm.retry();
        assertEquals(GameUiState.Phase.ASKING, vm.state().getValue().phase);
    }

    @Test
    public void goBack_showsTheSameQuestionAgain() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        String first = vm.state().getValue().questionText;

        vm.answer(Answer.SIM);
        assertTrue(vm.state().getValue().canGoBack);
        assertTrue(vm.goBackOneQuestion());

        assertEquals(first, vm.state().getValue().questionText);
        assertEquals(1, vm.state().getValue().questionNumber);
    }

    @Test
    public void processDeath_restoresTheExactQuestion() {
        SavedStateHandle handle = new SavedStateHandle();
        GameViewModel vm = newViewModel(handle);
        vm.start();
        vm.answer(Answer.SIM);
        vm.answer(Answer.NAO_SEI);
        GameUiState before = vm.state().getValue();

        // Novo ViewModel com o mesmo estado salvo e outra semente de sorteio.
        SavedStateHandle restoredHandle = new SavedStateHandle(snapshot(handle));
        GameViewModel restored = new GameViewModel(repository, learningStore, accountStore, restoredHandle,
                new Random(999));
        restored.start();
        GameUiState after = restored.state().getValue();

        assertEquals(GameUiState.Phase.ASKING, after.phase);
        assertEquals(before.questionText, after.questionText);
        assertEquals(before.questionNumber, after.questionNumber);
        assertTrue(after.canGoBack);
    }

    @Test
    public void confirmGuessTwice_recordsLearningOnlyOnce() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        int guessed = playUntilGuess(vm, 5);

        vm.confirmGuess();
        vm.confirmGuess();

        GameEvent event = lastEvent(vm);
        assertNotNull(event);
        assertEquals(GameEvent.Type.FINISHED, event.type);
        assertEquals(LearningStore.Outcome.ENGINE_GUESSED, event.outcome);
        assertEquals(guessed, event.characterId);
        assertEquals(1, learningStore.stats(accountId).gamesPlayed);
    }

    @Test
    public void continueGuessing_alwaysAsksInsteadOfGuessing() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        playUntilGuess(vm, 5);

        vm.rejectGuess();
        vm.continueGuessing();

        GameEvent event = lastEvent(vm);
        if (vm.state().getValue().phase == GameUiState.Phase.ASKING) {
            assertEquals(GameEvent.Type.RETURN_TO_QUESTIONS, event.type);
        } else {
            // Só se não sobrou pergunta: oferece alternativas, nunca um novo chute direto.
            assertEquals(GameEvent.Type.SHOW_ALTERNATIVES, event.type);
        }
    }

    @Test
    public void stopGuessing_offersAlternativesWithoutTheRejectedGuess() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        int guessed = playUntilGuess(vm, 5);

        vm.rejectGuess();
        vm.stopGuessing();

        assertEquals(GameEvent.Type.SHOW_ALTERNATIVES, lastEvent(vm).type);
        List<CharacterProfile> alternatives = vm.alternatives();
        assertFalse(alternatives.isEmpty());
        assertTrue(alternatives.size() <= GameViewModel.MAX_ALTERNATIVES);
        for (CharacterProfile c : alternatives) assertTrue(c.id != guessed);
    }

    @Test
    public void reveal_teachesTheStoreWhoItWas() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        playUntilGuess(vm, 5);
        vm.rejectGuess();

        vm.reveal(3);

        GameEvent event = lastEvent(vm);
        assertEquals(LearningStore.Outcome.REVEALED_AFTER_LOSS, event.outcome);
        assertEquals("Personagem 3", event.characterName);
        assertTrue(learningStore.popularityBoost(accountId, 3) > 1.0);
        assertEquals(0, learningStore.stats(accountId).engineWins);
    }

    @Test
    public void giveUp_countsALossWithoutLearningACharacter() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        vm.giveUp();
        vm.giveUp();

        assertEquals(LearningStore.Outcome.LOST_UNREVEALED, lastEvent(vm).outcome);
        assertEquals(1, learningStore.stats(accountId).gamesPlayed);
    }

    @Test
    public void answersAfterFinishing_areIgnored() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        vm.giveUp();
        vm.answer(Answer.SIM);

        assertEquals(GameUiState.Phase.FINISHED, vm.state().getValue().phase);
        assertNull(vm.getProfile(-1));
    }

    @Test
    public void newGame_startsThinking() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();

        assertEquals(WatcherMood.THINKING, vm.state().getValue().mood);
    }

    @Test
    public void answerThatCrushesTheFavorite_makesTheWatcherAngry() {
        useFavoriteCast();
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        assertEquals("elenco montado para o favorito começar forte", WatcherMood.CONFIDENT, vm.state().getValue().mood);
        assertEquals("Pergunta x?", vm.state().getValue().questionText);

        vm.answer(Answer.NAO);

        assertEquals(WatcherMood.ANGRY, vm.state().getValue().mood);
    }

    @Test
    public void answerThatConfirmsTheFavorite_keepsHimConfident() {
        useFavoriteCast();
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();

        vm.answer(Answer.SIM);

        assertEquals(WatcherMood.CONFIDENT, vm.state().getValue().mood);
    }

    @Test
    public void dontKnow_keepsTheCurrentReaction() {
        useFavoriteCast();
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        vm.answer(Answer.NAO);

        vm.answer(Answer.NAO_SEI);

        assertEquals(WatcherMood.ANGRY, vm.state().getValue().mood);
    }

    @Test
    public void undoingTheAnswer_undoesTheReaction() {
        useFavoriteCast();
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        vm.answer(Answer.NAO);

        assertTrue(vm.goBackOneQuestion());

        assertEquals(WatcherMood.CONFIDENT, vm.state().getValue().mood);
    }

    @Test
    public void processDeath_keepsTheReaction() {
        useFavoriteCast();
        SavedStateHandle handle = new SavedStateHandle();
        GameViewModel vm = newViewModel(handle);
        vm.start();
        vm.answer(Answer.NAO);

        GameViewModel restored = new GameViewModel(repository, learningStore, accountStore,
                new SavedStateHandle(snapshot(handle)), new Random(999));
        restored.start();

        assertEquals(WatcherMood.ANGRY, restored.state().getValue().mood);
    }

    @Test
    public void rejectingAConfidentGuess_makesTheWatcherAngry() {
        GameViewModel vm = newViewModel(new SavedStateHandle());
        vm.start();
        playUntilGuess(vm, 5);
        assertEquals(WatcherMood.CONFIDENT, vm.state().getValue().mood);

        vm.rejectGuess();

        assertEquals(WatcherMood.ANGRY, vm.state().getValue().mood);
    }

    /**
     * Quatro personagens: o 0 é mainstream (prior 4x, ~57%) e é o único com o
     * traço "x"; "y" e "z" não distinguem ninguém. O Vigia começa apostando no 0
     * e a primeira pergunta é "x" (a que confirma ou derruba o favorito).
     */
    private void useFavoriteCast() {
        List<CharacterProfile> list = new ArrayList<>();
        for (int id = 0; id < 4; id++) {
            Map<String, Double> attrs = new HashMap<>();
            attrs.put("x", id == 0 ? 0.92 : 0.08);
            attrs.put("y", 0.5);
            attrs.put("z", 0.5);
            list.add(new CharacterProfile(id, "Personagem " + id, "img", null, attrs, 0, id == 0));
        }
        Map<String, String> q = new LinkedHashMap<>();
        for (String k : new String[]{"x", "y", "z"}) q.put(k, "Pergunta " + k + "?");
        repository.cast = list;
        repository.questions = q;
    }

    /** Responde como se o jogador pensasse em {@code targetId} até o motor chutar. Devolve o id chutado. */
    private static int playUntilGuess(GameViewModel vm, int targetId) {
        CharacterProfile target = null;
        for (int guard = 0; guard < 30; guard++) {
            Event<GameEvent> pending = vm.events().getValue();
            if (pending != null && pending.peek().type == GameEvent.Type.SHOW_GUESS && vm.state().getValue().phase
                    == GameUiState.Phase.DECIDING) {
                return pending.consume().characterId;
            }
            if (target == null) target = vm.getProfile(targetId);
            String text = vm.state().getValue().questionText;
            String key = text.substring("Pergunta ".length(), text.length() - 1);
            double belief = target.attributes.get(key);
            vm.answer(belief > 0.5 ? Answer.SIM : belief < 0.5 ? Answer.NAO : Answer.NAO_SEI);
        }
        throw new AssertionError("o motor nunca chutou");
    }

    private static Map<String, Object> snapshot(SavedStateHandle handle) {
        Map<String, Object> values = new HashMap<>();
        for (String key : handle.keys()) values.put(key, handle.get(key));
        return values;
    }

    private static final class FakeRepository implements CharacterRepository {
        int loads = 0;
        LoadError failWith = null;
        List<CharacterProfile> cast = null;
        Map<String, String> questions = null;

        @Override
        public void loadCharacters(Callback callback) {
            loads++;
            if (failWith != null) {
                callback.onError(failWith);
            } else {
                // Perfis novos a cada carga, como o repositório real: o motor muta as probabilidades.
                callback.onSuccess(cast != null ? copy(cast) : cast(), questions != null ? questions : questions());
            }
        }

        private static List<CharacterProfile> copy(List<CharacterProfile> profiles) {
            List<CharacterProfile> copies = new ArrayList<>();
            for (CharacterProfile p : profiles) copies.add(p.withAttributes(p.attributes));
            return copies;
        }
    }
}
