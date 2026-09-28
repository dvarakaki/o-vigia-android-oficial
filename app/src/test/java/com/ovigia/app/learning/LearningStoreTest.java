package com.ovigia.app.learning;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LearningStoreTest {

    private static final String ACCOUNT = "conta-a";
    private static final String OTHER = "conta-b";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Executa na hora: gravações ficam síncronas e verificáveis. */
    private final Executor direct = Runnable::run;
    private File file;

    @Before
    public void setUp() {
        file = new File(tmp.getRoot(), "learning_store.json");
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
        LearningStore store = new LearningStore(() -> file, direct);
        assertEquals(1.0, store.popularityBoost(ACCOUNT, 7), 1e-9);
        assertNull(store.blendedBelief(ACCOUNT, 7, "power_voo", 0.1));
    }

    @Test
    public void withoutSession_readsAreNeutralAndWritesAreIgnored() {
        LearningStore store = new LearningStore(() -> file, direct);
        store.recordGame(null, 7, answers("power_voo", 1.0), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordLoss(null);

        assertEquals(1.0, store.popularityBoost(null, 7), 1e-9);
        assertEquals(0, store.stats(null).gamesPlayed);
        assertNull(store.blendedBelief(null, 7, "power_voo", 0.1));
        assertTrue(store.history(null, 5, 5).recentGames.isEmpty());
    }

    @Test
    public void popularityBoost_growsButLogarithmically() {
        LearningStore store = new LearningStore(() -> file, direct);
        store.recordGame(ACCOUNT, 7, answers(), LearningStore.Outcome.ENGINE_GUESSED);
        double one = store.popularityBoost(ACCOUNT, 7);
        for (int i = 0; i < 19; i++) store.recordGame(ACCOUNT, 7, answers(), LearningStore.Outcome.ENGINE_GUESSED);
        double twenty = store.popularityBoost(ACCOUNT, 7);

        assertTrue(one > 1.0);
        assertTrue(twenty > one);
        assertTrue("20 acertos não podem engolir o prior", twenty < 3.0);
    }

    @Test
    public void blendedBelief_movesTowardsAnswersAsEvidenceAccumulates() {
        LearningStore store = new LearningStore(() -> file, direct);
        store.recordGame(ACCOUNT, 7, answers("power_voo", 1.0), LearningStore.Outcome.REVEALED_AFTER_LOSS);
        double afterOne = store.blendedBelief(ACCOUNT, 7, "power_voo", 0.1);

        for (int i = 0; i < 9; i++) {
            store.recordGame(ACCOUNT, 7, answers("power_voo", 1.0), LearningStore.Outcome.REVEALED_AFTER_LOSS);
        }
        double afterTen = store.blendedBelief(ACCOUNT, 7, "power_voo", 0.1);

        assertEquals("K=5: uma resposta pesa 1/6", 0.1 * 5 / 6 + 1.0 / 6, afterOne, 1e-9);
        assertTrue(afterTen > afterOne);
        assertTrue("atributo ausente na curadoria é corrigido pelo aprendizado", afterTen > 0.5);
    }

    @Test
    public void naoSeiAnswers_areIgnored() {
        LearningStore store = new LearningStore(() -> file, direct);
        store.recordGame(ACCOUNT, 7, answers("power_voo", Double.NaN), LearningStore.Outcome.ENGINE_GUESSED);
        assertNull(store.blendedBelief(ACCOUNT, 7, "power_voo", 0.1));
    }

    @Test
    public void state_survivesReopeningTheStore() {
        LearningStore store = new LearningStore(() -> file, direct);
        store.recordGame(ACCOUNT, 7, answers("power_voo", 1.0), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordLoss(ACCOUNT);

        LearningStore reopened = new LearningStore(() -> file, direct);
        assertTrue(reopened.popularityBoost(ACCOUNT, 7) > 1.0);
        LearningStore.Stats stats = reopened.stats(ACCOUNT);
        assertEquals(2, stats.gamesPlayed);
        assertEquals(1, stats.engineWins);
        assertEquals(0.5, stats.engineWinRate(), 1e-9);
        assertFalse("não deve sobrar arquivo temporário", new File(tmp.getRoot(), "learning_store.json.tmp").exists());
    }

    @Test
    public void history_ranksFavoritesAndListsRecentGamesNewestFirst() {
        LearningStore store = new LearningStore(() -> file, direct);
        store.recordGame(ACCOUNT, 3, answers(), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordGame(ACCOUNT, 9, answers(), LearningStore.Outcome.REVEALED_AFTER_LOSS);
        store.recordGame(ACCOUNT, 9, answers(), LearningStore.Outcome.PICKED_FROM_ALTERNATIVES);
        store.recordGame(ACCOUNT, 5, answers(), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordLoss(ACCOUNT);

        LearningStore.PlayerHistory history = store.history(ACCOUNT, 2, 3);

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
        LearningStore store = new LearningStore(() -> file, direct);
        store.recordGame(ACCOUNT, 7, answers("power_voo", 1.0), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordLoss(ACCOUNT);

        assertEquals(2, store.stats(ACCOUNT).gamesPlayed);
        assertEquals(0, store.stats(OTHER).gamesPlayed);
        assertEquals(1.0, store.popularityBoost(OTHER, 7), 1e-9);
        assertNull(store.blendedBelief(OTHER, 7, "power_voo", 0.1));
        assertTrue(store.history(OTHER, 5, 5).recentGames.isEmpty());
    }

    @Test
    public void corruptedFile_startsFresh() throws Exception {
        Files.write(file.toPath(), "{ isto não é json".getBytes(StandardCharsets.UTF_8));
        LearningStore store = new LearningStore(() -> file, direct);
        assertEquals(0, store.stats(ACCOUNT).gamesPlayed);
    }

    @Test
    public void oldGlobalFile_isDiscardedInFavorOfFreshPerAccountState() throws Exception {
        // Formato antigo (globais no topo, sem "byAccount") não migra: começa do zero por conta.
        Files.write(file.toPath(),
                "{\"gamesPlayed\":10,\"engineWins\":5,\"picksById\":{\"7\":3}}".getBytes(StandardCharsets.UTF_8));
        LearningStore store = new LearningStore(() -> file, direct);
        assertEquals(0, store.stats(ACCOUNT).gamesPlayed);
        assertEquals(1.0, store.popularityBoost(ACCOUNT, 7), 1e-9);
    }

    /**
     * Arquivo como os releases 1.1.0 a 1.2.2 gravaram: sem regra de keep para o bloco da conta,
     * o R8 renomeou os campos (a…f) e, depois de reabrir o app, regravou os números como 2.0.
     */
    private static final String OBFUSCATED_FILE = "{\"byAccount\":{\"" + ACCOUNT + "\":{"
            + "\"a\":{\"7\":2.0},"
            + "\"b\":{\"7\":{\"power_voo\":[2.0,2.0]}},"
            + "\"c\":[{\"answers\":[{\"key\":\"power_voo\",\"value\":1.0}],\"correctId\":7,"
            + "\"outcome\":\"ENGINE_GUESSED\",\"timestamp\":1000}],"
            + "\"d\":2,\"e\":1,\"f\":4}}}";

    @Test
    public void fileWrittenWithObfuscatedFieldNames_isReadBackWithEverythingTheReleaseSaved() throws Exception {
        Files.write(file.toPath(), OBFUSCATED_FILE.getBytes(StandardCharsets.UTF_8));
        LearningStore store = new LearningStore(() -> file, direct);

        LearningStore.PlayerHistory history = store.history(ACCOUNT, 5, 5);

        assertEquals(2, history.stats.gamesPlayed);
        assertEquals(1, history.stats.engineWins);
        assertEquals("os trazidos da nuvem contam mais que os vistos aqui", 4, history.stats.distinctCharacters);
        assertEquals(1, history.favorites.size());
        assertEquals(7, history.favorites.get(0).characterId);
        assertEquals(2, history.favorites.get(0).count);
        assertEquals(1, history.recentGames.size());
        assertEquals(LearningStore.Outcome.ENGINE_GUESSED, history.recentGames.get(0).outcome);
        assertEquals(1000L, history.recentGames.get(0).timestamp);
        assertTrue(store.popularityBoost(ACCOUNT, 7) > 1.0);
        assertNotNull(store.blendedBelief(ACCOUNT, 7, "power_voo", 0.1));
    }

    @Test
    public void fileWrittenWithObfuscatedFieldNames_isRewrittenWithTheRealNamesOnTheNextSave() throws Exception {
        Files.write(file.toPath(), OBFUSCATED_FILE.getBytes(StandardCharsets.UTF_8));
        LearningStore store = new LearningStore(() -> file, direct);
        store.recordGame(ACCOUNT, 7, answers("power_voo", 1.0), LearningStore.Outcome.PICKED_FROM_ALTERNATIVES);

        String saved = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"picksById\""));
        assertFalse("não sobra o nome ofuscado", saved.contains("\"a\":"));

        LearningStore.PlayerHistory reopened = new LearningStore(() -> file, direct).history(ACCOUNT, 5, 5);
        assertEquals("as duas partidas do release mais a nova", 3, reopened.stats.gamesPlayed);
        assertEquals(3, reopened.favorites.get(0).count);
        assertEquals(LearningStore.Outcome.PICKED_FROM_ALTERNATIVES, reopened.recentGames.get(0).outcome);
    }

    @Test
    public void reset_forgetsEverythingForTheAccount_butKeepsOthers() {
        LearningStore store = new LearningStore(() -> file, direct);
        store.recordGame(ACCOUNT, 7, answers("power_voo", 1.0), LearningStore.Outcome.ENGINE_GUESSED);
        store.recordGame(OTHER, 8, answers(), LearningStore.Outcome.ENGINE_GUESSED);

        store.reset(ACCOUNT);

        assertEquals(1.0, store.popularityBoost(ACCOUNT, 7), 1e-9);
        assertEquals(0, new LearningStore(() -> file, direct).stats(ACCOUNT).gamesPlayed);
        assertEquals("outra conta não é tocada", 1,
                new LearningStore(() -> file, direct).stats(OTHER).gamesPlayed);
    }
}
