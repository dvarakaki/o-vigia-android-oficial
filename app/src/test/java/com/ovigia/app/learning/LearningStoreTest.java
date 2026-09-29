package com.ovigia.app.learning;

import com.ovigia.app.cloud.FakeCloud;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A memória do Vigia mora na conta online: o que ele aprende vale em qualquer aparelho. */
public class LearningStoreTest {

    private FakeCloud cloud;
    private String account;
    private String other;

    @Before
    public void setUp() {
        cloud = new FakeCloud();
        account = cloud.createUser("ana@exemplo.com", "segredo#1");
        other = cloud.createUser("bia@exemplo.com", "segredo#1");
        cloud.actAs(account);
    }

    private static List<LearningStore.AnswerRecord> answers(Object... keyValue) {
        LearningStore.AnswerRecord[] records = new LearningStore.AnswerRecord[keyValue.length / 2];
        for (int i = 0; i < keyValue.length; i += 2) {
            records[i / 2] = new LearningStore.AnswerRecord((String) keyValue[i], (Double) keyValue[i + 1]);
        }
        return Arrays.asList(records);
    }

    @Test
    public void unknownCharacter_isNeutral() {
        LearningStore store = new LearningStore(cloud);
        assertEquals(1.0, store.popularityBoost(account, 7), 1e-9);
        assertNull(store.blendedBelief(account, 7, "power_voo", 0.1));
    }

    @Test
    public void withoutSession_readsAreNeutralAndWritesAreIgnored() {
        LearningStore store = new LearningStore(cloud);
        store.recordGame(null, 7, answers("power_voo", 1.0), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordLoss(null);

        assertEquals(1.0, store.popularityBoost(null, 7), 1e-9);
        assertEquals(0, store.stats(null).gamesPlayed);
        assertNull(store.blendedBelief(null, 7, "power_voo", 0.1));
        assertTrue(store.history(null, 5, 5).recentGames.isEmpty());
        assertEquals("nada chegou ao servidor", 0, cloud.learningOf(account).gamesPlayed);
    }

    @Test
    public void popularityBoost_growsButLogarithmically() {
        LearningStore store = new LearningStore(cloud);
        store.recordGame(account, 7, answers(), LearningStore.Outcome.ENGINE_GUESSED);
        double one = store.popularityBoost(account, 7);
        for (int i = 0; i < 19; i++) store.recordGame(account, 7, answers(), LearningStore.Outcome.ENGINE_GUESSED);
        double twenty = store.popularityBoost(account, 7);

        assertTrue(one > 1.0);
        assertTrue(twenty > one);
        assertTrue("20 acertos não podem engolir o prior", twenty < 3.0);
    }

    @Test
    public void blendedBelief_movesTowardsAnswersAsEvidenceAccumulates() {
        LearningStore store = new LearningStore(cloud);
        store.recordGame(account, 7, answers("power_voo", 1.0), LearningStore.Outcome.REVEALED_AFTER_LOSS);
        double afterOne = store.blendedBelief(account, 7, "power_voo", 0.1);

        for (int i = 0; i < 9; i++) {
            store.recordGame(account, 7, answers("power_voo", 1.0), LearningStore.Outcome.REVEALED_AFTER_LOSS);
        }
        double afterTen = store.blendedBelief(account, 7, "power_voo", 0.1);

        assertEquals("K=5: uma resposta pesa 1/6", 0.1 * 5 / 6 + 1.0 / 6, afterOne, 1e-9);
        assertTrue(afterTen > afterOne);
        assertTrue("atributo ausente na curadoria é corrigido pelo aprendizado", afterTen > 0.5);
    }

    @Test
    public void naoSeiAnswers_areIgnored() {
        LearningStore store = new LearningStore(cloud);
        store.recordGame(account, 7, answers("power_voo", Double.NaN), LearningStore.Outcome.ENGINE_GUESSED);
        assertNull(store.blendedBelief(account, 7, "power_voo", 0.1));
        assertTrue("o servidor não recebe NaN", cloud.gamesOf(account).get(0).answers.isEmpty());
    }

    @Test
    public void whatWasLearned_isTheSameOnAnotherDevice() {
        LearningStore store = new LearningStore(cloud);
        store.recordGame(account, 7, answers("power_voo", 1.0), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordLoss(account);

        LearningStore otherDevice = new LearningStore(cloud);
        assertTrue(otherDevice.popularityBoost(account, 7) > 1.0);
        assertEquals(store.blendedBelief(account, 7, "power_voo", 0.1),
                otherDevice.blendedBelief(account, 7, "power_voo", 0.1));
        LearningStore.Stats stats = otherDevice.stats(account);
        assertEquals(2, stats.gamesPlayed);
        assertEquals(1, stats.engineWins);
        assertEquals(1, stats.distinctCharacters);
        assertEquals(0.5, stats.engineWinRate(), 1e-9);
        assertEquals(1, stats.playerWins());
    }

    @Test
    public void history_ranksFavoritesAndListsRecentGamesNewestFirst() {
        LearningStore store = new LearningStore(cloud);
        store.recordGame(account, 3, answers(), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordGame(account, 9, answers(), LearningStore.Outcome.REVEALED_AFTER_LOSS);
        store.recordGame(account, 9, answers(), LearningStore.Outcome.PICKED_FROM_ALTERNATIVES);
        store.recordGame(account, 5, answers(), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordLoss(account);

        LearningStore.PlayerHistory history = store.history(account, 2, 3);

        assertEquals(5, history.stats.gamesPlayed);
        assertEquals(2, history.favorites.size());
        assertEquals(9, history.favorites.get(0).characterId);
        assertEquals(2, history.favorites.get(0).count);
        assertEquals("empate desfeito pelo id menor", 3, history.favorites.get(1).characterId);

        assertEquals("derrota sem personagem não entra nas recentes", 3, history.recentGames.size());
        assertEquals(5, history.recentGames.get(0).characterId);
        assertEquals(LearningStore.Outcome.PICKED_FROM_ALTERNATIVES, history.recentGames.get(1).outcome);
        assertEquals(LearningStore.Outcome.REVEALED_AFTER_LOSS, history.recentGames.get(2).outcome);
    }

    @Test
    public void accounts_areIsolated() {
        LearningStore store = new LearningStore(cloud);
        store.recordGame(account, 7, answers("power_voo", 1.0), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordLoss(account);

        cloud.actAs(other);
        assertEquals(2, cloud.learningOf(account).gamesPlayed);
        assertEquals(0, store.stats(other).gamesPlayed);
        assertEquals(1.0, store.popularityBoost(other, 7), 1e-9);
        assertNull(store.blendedBelief(other, 7, "power_voo", 0.1));
        assertTrue(store.history(other, 5, 5).recentGames.isEmpty());
    }

    @Test
    public void reset_forgetsEverythingForTheAccount_butKeepsOthers() {
        LearningStore store = new LearningStore(cloud);
        store.recordGame(account, 7, answers("power_voo", 1.0), LearningStore.Outcome.ENGINE_GUESSED);
        cloud.actAs(other);
        store.recordGame(other, 8, answers(), LearningStore.Outcome.ENGINE_GUESSED);
        cloud.actAs(account);

        store.reset(account);

        assertEquals(1.0, store.popularityBoost(account, 7), 1e-9);
        assertEquals(0, new LearningStore(cloud).stats(account).gamesPlayed);
        assertTrue(store.history(account, 5, 5).recentGames.isEmpty());
        assertEquals("outra conta não é tocada", 1, cloud.learningOf(other).gamesPlayed);
    }
}
