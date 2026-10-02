package com.ovigia.app.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ovigia.app.cloud.PlayerBackend.Game;
import com.ovigia.app.collection.HeroPortraits;
import com.ovigia.app.learning.LearningStore.AnswerRecord;
import com.ovigia.app.learning.LearningStore.Outcome;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class ApiPlayerBackendTest {

    private static final long NOW = 1_700_000_000_000L;

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private final SessionStore.InMemory sessions = new SessionStore.InMemory();
    private MockWebServer server = new MockWebServer();
    private ExecutorService sync;
    private File dataDir;
    private ApiPlayerBackend backend;

    @Before
    public void setUp() throws Exception {
        server.start();
        dataDir = folder.newFolder("data");
        backend = newBackend(server);
        signIn();
    }

    @After
    public void tearDown() throws Exception {
        sync.shutdownNow();
        server.shutdown();
    }

    @Test
    public void gameSendsPlayedAtAndReturnsTheServerVerdict() throws Exception {
        server.enqueue(json(201, "{\"gameId\":1,\"hero\":{\"characterId\":7,\"state\":\"unlocked\",\"isNew\":true}}"));

        backend.recordGame("u1", game(7), false);

        assertEquals(PlayerBackend.Grant.NEW, backend.awaitUnlock("u1", 7));
        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/games", request.getPath());
        JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
        assertEquals(Json.iso(NOW), body.get("playedAt").getAsString());
        assertEquals(7, body.get("characterId").getAsInt());
        assertEquals("REVEALED_AFTER_LOSS", body.get("outcome").getAsString());
    }

    @Test
    public void heroTheAccountAlreadyHadIsNotNew() throws Exception {
        server.enqueue(json(201, "{\"gameId\":1,\"hero\":{\"characterId\":7,\"state\":\"unlocked\",\"isNew\":false}}"));
        backend.recordGame("u1", game(7), false);
        assertEquals(PlayerBackend.Grant.EXISTING, backend.awaitUnlock("u1", 7));
    }

    @Test
    public void legendaryForAnAccountThatIsNotInfinite_isSealedByTheServer() throws Exception {
        server.enqueue(json(201, "{\"gameId\":1,\"hero\":{\"characterId\":7,\"state\":\"sealed\",\"isNew\":true}}"));
        backend.recordGame("u1", game(7), false);
        assertEquals(PlayerBackend.Grant.SEALED, backend.awaitUnlock("u1", 7));
    }

    @Test
    public void heroesAndSealedComeFromTheSameList() throws Exception {
        String heroes = "[{\"characterId\":7,\"name\":\"Wolverine\",\"state\":\"unlocked\",\"unlockedAt\":\"2026-01-01T00:00:00Z\"},"
                + "{\"characterId\":42,\"name\":\"Squirrel Girl\",\"state\":\"sealed\",\"foundAt\":\"2026-01-02T00:00:00Z\"}]";
        for (int i = 0; i < 3; i++) server.enqueue(json(200, heroes));

        assertEquals(7, backend.loadHeroes("u1").get(0).characterId);
        assertEquals(1, backend.loadHeroes("u1").size());
        assertEquals("Squirrel Girl", backend.loadSealed("u1").get(0).name);
    }

    @Test
    public void purchaseGoesToTheServer_andTheAnswerSurvivesWithoutNetwork() throws Exception {
        server.enqueue(json(200, "{\"state\":\"purchased\",\"infiniteWatcher\":true,\"changedHeroes\":[42]}"));

        assertTrue(backend.verifyPurchase("u1", "vigia_do_infinito", "token-1"));
        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/purchases", request.getPath());
        JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
        assertEquals("vigia_do_infinito", body.get("productId").getAsString());
        assertEquals("token-1", body.get("purchaseToken").getAsString());

        server.shutdown();
        assertEquals(Boolean.TRUE, backend.loadInfiniteWatcher("u1"));
    }

    @Test
    public void purchaseOfAnotherAccount_isInUse() {
        server.enqueue(json(409, "{\"error\":\"PURCHASE_IN_USE\",\"message\":\"x\"}"));
        try {
            backend.verifyPurchase("u1", "vigia_do_infinito", "token-1");
            throw new AssertionError("devia recusar");
        } catch (CloudException e) {
            assertEquals(CloudException.Reason.PURCHASE_IN_USE, e.reason);
        }
    }

    @Test
    public void offlineLegendary_isGuessedSealed() throws Exception {
        server.enqueue(json(200, "[]"));
        backend.loadHeroes("u1");
        server.shutdown();
        backend.setSealsOffline((uid, id) -> id == 7);

        backend.recordGame("u1", winningGame(7), true);

        assertEquals(PlayerBackend.Grant.SEALED, backend.awaitUnlock("u1", 7));
        assertTrue(backend.loadHeroes("u1").isEmpty());
        assertEquals(7, backend.loadSealed("u1").get(0).characterId);
    }

    @Test
    public void offlineGameStaysQueuedAndCountsLocally() throws Exception {
        server.enqueue(json(200, "{\"gamesPlayed\":3,\"engineWins\":1,\"picks\":{},\"beliefs\":{}}"));
        assertEquals(3, backend.loadLearning("u1").gamesPlayed);
        server.enqueue(json(200, "[]"));
        backend.loadHeroes("u1");
        server.enqueue(json(200, "[]"));
        backend.recentGames("u1", 10);
        server.shutdown();

        backend.recordGame("u1", game(7), false);

        // Sem rede: o herói é novo porque a última cópia do servidor não o tinha.
        assertEquals(PlayerBackend.Grant.NEW, backend.awaitUnlock("u1", 7));
        assertEquals(4, backend.loadLearning("u1").gamesPlayed);
        assertEquals(7, backend.recentGames("u1", 10).get(0).characterId);

        // O app reabre com rede: a partida sobe uma vez só.
        sync.shutdown();
        sync.awaitTermination(5, TimeUnit.SECONDS);
        server = new MockWebServer();
        server.start();
        server.enqueue(json(201, "{\"gameId\":2,\"hero\":null}"));
        server.enqueue(json(200, "{\"gamesPlayed\":4,\"engineWins\":1,\"picks\":{},\"beliefs\":{}}"));
        backend = newBackend(server);

        assertEquals(4, backend.loadLearning("u1").gamesPlayed);
        assertEquals("/v1/games", server.takeRequest().getPath());
        assertEquals("/v1/me/learning", server.takeRequest().getPath());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void rejectedWriteLeavesTheQueue() throws Exception {
        server.enqueue(json(400, "{\"error\":\"VALIDATION\"}"));
        backend.recordGame("u1", game(7), false);
        assertNull(backend.awaitUnlock("u1", 7));

        server.enqueue(json(200, "{\"gamesPlayed\":0,\"engineWins\":0}"));
        assertEquals(0, backend.loadLearning("u1").gamesPlayed);
        assertEquals("/v1/games", server.takeRequest().getPath());
        assertEquals("/v1/me/learning", server.takeRequest().getPath());
    }

    @Test
    public void signOutForgetsTheSessionAndTellsTheServer() throws Exception {
        server.enqueue(json(204, ""));
        backend.signOut();

        assertNull(backend.currentSession());
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertEquals("/v1/auth/logout", request.getPath());
        assertTrue(request.getBody().readUtf8().contains("refresh-1"));
        assertFalse(request.getHeaders().names().contains("Authorization"));
    }

    private ApiPlayerBackend newBackend(MockWebServer on) {
        sync = Executors.newSingleThreadExecutor();
        ApiHttp http = new ApiHttp(on.url("/").toString(), new OkHttpClient(), sessions, () -> NOW);
        HeroPortraits portraits = new HeroPortraits(() -> new File(dataDir, "portraits.json"), Runnable::run);
        return new ApiPlayerBackend(http, portraits, () -> dataDir, sync, "test", () -> NOW);
    }

    private void signIn() {
        sessions.save(new SessionStore.Saved("u1", "a@b.c", "access-1", NOW + 900_000, "refresh-1"));
    }

    private static Game game(int characterId) {
        return new Game(NOW, characterId, Outcome.REVEALED_AFTER_LOSS,
                Collections.singletonList(new AnswerRecord("is_human", 1.0)));
    }

    private static Game winningGame(int characterId) {
        return new Game(NOW, characterId, Outcome.ENGINE_GUESSED,
                Collections.singletonList(new AnswerRecord("is_human", 1.0)));
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }
}
