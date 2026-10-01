package com.ovigia.app.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class ApiHttpTest {

    private final MockWebServer server = new MockWebServer();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final SessionStore.InMemory sessions = new SessionStore.InMemory();
    private ApiHttp http;

    @Before
    public void setUp() throws Exception {
        server.start();
        http = new ApiHttp(server.url("/").toString(), new OkHttpClient(), sessions, now::get);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void withoutAddressNothingIsCalled() {
        ApiHttp blank = new ApiHttp("", new OkHttpClient(), sessions, now::get);
        try {
            blank.postPublic("v1/auth/login", new JsonObject());
            fail();
        } catch (ApiHttp.Failure f) {
            assertEquals(ApiHttp.NOT_CONFIGURED, f.code);
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void sendsTheAccessToken() throws Exception {
        open("access-1", "refresh-1", 900);
        server.enqueue(json(200, "{\"id\":\"u1\"}"));

        assertEquals("u1", http.get("v1/me").getAsJsonObject().get("id").getAsString());
        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/me", request.getPath());
        assertEquals("Bearer access-1", request.getHeader("Authorization"));
    }

    @Test
    public void renewsAnExpiredTokenBeforeCalling() throws Exception {
        open("access-1", "refresh-1", 900);
        now.addAndGet(900_000);
        server.enqueue(json(200, tokens("access-2", "refresh-2")));
        server.enqueue(json(200, "{}"));

        http.get("v1/me");

        RecordedRequest refresh = server.takeRequest();
        assertEquals("/v1/auth/refresh", refresh.getPath());
        assertTrue(refresh.getBody().readUtf8().contains("refresh-1"));
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"));
        assertEquals("refresh-2", sessions.load().refreshToken);
    }

    @Test
    public void retriesOnceWhenTheServerRejectsTheToken() throws Exception {
        open("access-1", "refresh-1", 900);
        server.enqueue(json(401, "{\"error\":\"NOT_SIGNED_IN\"}"));
        server.enqueue(json(200, tokens("access-2", "refresh-2")));
        server.enqueue(json(200, "{\"ok\":true}"));

        assertTrue(http.get("v1/me").getAsJsonObject().get("ok").getAsBoolean());
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"));
        assertEquals("/v1/auth/refresh", server.takeRequest().getPath());
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"));
    }

    @Test
    public void rejectedRefreshTokenEndsTheSession() throws Exception {
        open("access-1", "refresh-1", 900);
        now.addAndGet(900_000);
        server.enqueue(json(401, "{\"error\":\"INVALID_REFRESH_TOKEN\"}"));

        try {
            http.get("v1/me");
            fail();
        } catch (ApiHttp.Failure f) {
            assertEquals(ApiHttp.NOT_SIGNED_IN, f.code);
        }
        assertNull(sessions.load());
    }

    @Test
    public void serverDownKeepsTheSession() throws Exception {
        open("access-1", "refresh-1", 900);
        now.addAndGet(900_000);
        server.enqueue(json(503, ""));

        try {
            http.get("v1/me");
            fail();
        } catch (ApiHttp.Failure f) {
            assertTrue(f.isTransient());
        }
        assertEquals("refresh-1", sessions.load().refreshToken);
    }

    @Test
    public void errorBodyBecomesTheFailureCode() throws Exception {
        open("access-1", "refresh-1", 900);
        server.enqueue(json(409, "{\"error\":\"USERNAME_TAKEN\",\"message\":\"já existe\"}"));

        try {
            http.put("v1/me/username", new JsonObject());
            fail();
        } catch (ApiHttp.Failure f) {
            assertEquals(409, f.status);
            assertEquals("USERNAME_TAKEN", f.code);
        }
    }

    @Test
    public void noNetworkIsOffline() throws Exception {
        open("access-1", "refresh-1", 900);
        server.shutdown();

        try {
            http.get("v1/me");
            fail();
        } catch (ApiHttp.Failure f) {
            assertTrue(f.isOffline());
        }
    }

    @Test
    public void unchangedResourceReturnsNull() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(304));
        assertNull(http.getPublic("v1/characters", "\"v1\""));
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"));
    }

    private void open(String access, String refresh, int expiresIn) throws ApiHttp.Failure {
        JsonObject session = new JsonObject();
        session.add("tokens", JsonParser.parseString(tokens(access, refresh).replace("900", String.valueOf(expiresIn))));
        session.add("user", JsonParser.parseString("{\"id\":\"u1\",\"email\":\"a@b.c\"}"));
        http.openSession(session);
    }

    private static String tokens(String access, String refresh) {
        return "{\"accessToken\":\"" + access + "\",\"refreshToken\":\"" + refresh + "\",\"expiresIn\":900}";
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }
}
