package com.ovigia.app.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fala com a API do O Vigia (o repositório ms-o-vigia): JSON por HTTPS, com o
 * token de acesso da sessão aberta.
 *
 * O token de acesso dura poucos minutos. Antes de vencer (ou quando o servidor
 * recusa), ele é renovado com o refresh token — que também é trocado a cada
 * renovação — e a chamada é refeita uma vez. Se o servidor não aceitar mais o
 * refresh token (senha trocada em outro aparelho, conta excluída), a sessão
 * deste aparelho acaba.
 *
 * Os corpos são lidos e montados como árvore JSON, sem reflexão: o R8 pode
 * renomear à vontade sem quebrar nada.
 *
 * Bloqueante: chamar fora da main thread. Thread-safe.
 */
public final class ApiHttp {

    /** Código de erro de quando o app foi compilado sem o endereço da API. */
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    /** Código de erro de quando a rede falhou (sem internet, servidor fora do ar, tempo esgotado). */
    public static final String OFFLINE = "OFFLINE";
    public static final String NOT_SIGNED_IN = "NOT_SIGNED_IN";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    /** Renova um pouco antes de vencer, para a chamada não chegar ao servidor com o token já vencido. */
    private static final long REFRESH_MARGIN_MS = 30_000;

    @Nullable private final HttpUrl base;
    private final OkHttpClient client;
    private final SessionStore sessions;
    private final LongSupplier clock;
    /** Uma renovação por vez: duas threads com o mesmo refresh token derrubariam a sessão (o servidor vê reúso). */
    private final Object sessionLock = new Object();

    /** @param baseUrl endereço da API (ex.: {@code https://api.ovigia.app/}), ou vazio se o app não tem servidor */
    public ApiHttp(String baseUrl, OkHttpClient client, SessionStore sessions, LongSupplier clock) {
        String clean = baseUrl == null ? "" : baseUrl.trim();
        if (!clean.isEmpty() && !clean.endsWith("/")) clean += "/";
        this.base = clean.isEmpty() ? null : HttpUrl.parse(clean);
        this.client = client;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** Tempos de rede do app: sem resposta nesse tempo, vale o que o aparelho já tem. */
    public static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    public boolean isConfigured() {
        return base != null;
    }

    public SessionStore sessions() {
        return sessions;
    }

    /** Falha de uma chamada: o status HTTP e o código de erro da API (status 0: a rede falhou). */
    public static final class Failure extends Exception {
        public final int status;
        public final String code;

        Failure(int status, String code, @Nullable String message, @Nullable Throwable cause) {
            super(code + (message == null ? "" : ": " + message), cause);
            this.status = status;
            this.code = code;
        }

        public boolean isOffline() {
            return OFFLINE.equals(code);
        }

        /** Vale tentar de novo mais tarde (rede, servidor fora do ar, pressa demais, sessão a renovar). */
        public boolean isTransient() {
            return isOffline() || status >= 500 || status == 429 || status == 401;
        }
    }

    // ---------------------------------------------------------------- chamadas com a sessão

    public JsonElement get(String path) throws Failure {
        return authed(new Request.Builder().url(url(path)).get());
    }

    public JsonElement post(String path, @Nullable JsonElement body) throws Failure {
        return authed(new Request.Builder().url(url(path)).post(json(body)));
    }

    public JsonElement put(String path, @Nullable JsonElement body) throws Failure {
        return authed(new Request.Builder().url(url(path)).put(json(body)));
    }

    public JsonElement patch(String path, @Nullable JsonElement body) throws Failure {
        return authed(new Request.Builder().url(url(path)).patch(json(body)));
    }

    public JsonElement delete(String path) throws Failure {
        return authed(new Request.Builder().url(url(path)).delete());
    }

    /** Envia uma imagem (campo {@code image} de um formulário multipart). */
    public JsonElement upload(String path, byte[] image, String mimeType) throws Failure {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "image", RequestBody.create(image, MediaType.get(mimeType)))
                .build();
        return authed(new Request.Builder().url(url(path)).put(body));
    }

    // ---------------------------------------------------------------- chamadas sem sessão

    /** Login, cadastro e afins: não levam token. */
    public JsonElement postPublic(String path, JsonElement body) throws Failure {
        return execute(new Request.Builder().url(url(path)).post(json(body)).build());
    }

    /**
     * GET público que respeita o cache do servidor.
     *
     * @return o corpo, ou {@code null} se o servidor disse que nada mudou desde {@code etag}
     */
    @Nullable
    public Conditional getPublic(String path, @Nullable String etag) throws Failure {
        Request.Builder request = new Request.Builder().url(url(path)).get();
        if (etag != null) request.header("If-None-Match", etag);
        try (Response response = client.newCall(request.build()).execute()) {
            if (response.code() == 304) return null;
            JsonElement body = read(response);
            return new Conditional(body, response.header("ETag"));
        } catch (IOException e) {
            throw new Failure(0, OFFLINE, e.getMessage(), e);
        }
    }

    /** Resposta de um GET condicional. */
    public static final class Conditional {
        public final JsonElement body;
        @Nullable public final String etag;

        Conditional(JsonElement body, @Nullable String etag) {
            this.body = body;
            this.etag = etag;
        }
    }

    // ---------------------------------------------------------------- sessão

    /** Guarda a sessão que o login (ou o cadastro) devolveu: {@code {tokens, user}}. */
    public SessionStore.Saved openSession(JsonObject session) throws Failure {
        JsonObject user = Json.obj(session, "user");
        JsonObject tokens = Json.obj(session, "tokens");
        if (user == null || tokens == null) throw new Failure(200, "FAILED", "sessão sem tokens", null);
        SessionStore.Saved saved = new SessionStore.Saved(Json.str(user, "id"), Json.str(user, "email"),
                Json.str(tokens, "accessToken"), expiresAt(tokens), Json.str(tokens, "refreshToken"));
        synchronized (sessionLock) {
            sessions.save(saved);
        }
        return saved;
    }

    /** Troca os tokens da sessão aberta pelos que o servidor devolveu (troca de senha). */
    public void replaceTokens(JsonObject tokens) {
        synchronized (sessionLock) {
            SessionStore.Saved current = sessions.load();
            if (current == null) return;
            sessions.save(current.withTokens(Json.str(tokens, "accessToken"), expiresAt(tokens),
                    Json.str(tokens, "refreshToken")));
        }
    }

    /** O e-mail da conta mudou (link confirmado em outro lugar): a sessão passa a mostrar o novo. */
    public void updateEmail(String uid, String email) {
        synchronized (sessionLock) {
            SessionStore.Saved current = sessions.load();
            if (current != null && current.uid.equals(uid) && !current.email.equals(email)) {
                sessions.save(current.withEmail(email));
            }
        }
    }

    /** Esquece a sessão; devolve o refresh token que ela tinha (para avisar o servidor), ou {@code null}. */
    @Nullable
    public String closeSession() {
        synchronized (sessionLock) {
            SessionStore.Saved current = sessions.load();
            sessions.clear();
            return current == null ? null : current.refreshToken;
        }
    }

    private long expiresAt(JsonObject tokens) {
        return clock.getAsLong() + 1000L * Json.longOr(tokens, "expiresIn", 0);
    }

    // ---------------------------------------------------------------- apoio

    private JsonElement authed(Request.Builder request) throws Failure {
        SessionStore.Saved session = validSession();
        try {
            return execute(request.header("Authorization", "Bearer " + session.accessToken).build());
        } catch (Failure f) {
            if (f.status != 401) throw f;
            // O servidor não aceitou o token (vencido antes da hora, chave trocada): renova e tenta uma vez.
            SessionStore.Saved renewed = refresh(session);
            return execute(request.header("Authorization", "Bearer " + renewed.accessToken).build());
        }
    }

    private SessionStore.Saved validSession() throws Failure {
        SessionStore.Saved session = sessions.load();
        if (session == null) throw new Failure(401, NOT_SIGNED_IN, null, null);
        if (session.accessExpiresAt - REFRESH_MARGIN_MS > clock.getAsLong()) return session;
        return refresh(session);
    }

    /** Renova os tokens de {@code used} — a não ser que outra thread já tenha renovado. */
    private SessionStore.Saved refresh(SessionStore.Saved used) throws Failure {
        synchronized (sessionLock) {
            SessionStore.Saved current = sessions.load();
            if (current == null || !current.uid.equals(used.uid)) throw new Failure(401, NOT_SIGNED_IN, null, null);
            if (!current.refreshToken.equals(used.refreshToken)) return current;
            JsonObject body = new JsonObject();
            body.addProperty("refreshToken", current.refreshToken);
            JsonElement tokens;
            try {
                tokens = execute(new Request.Builder().url(url("v1/auth/refresh")).post(json(body)).build());
            } catch (Failure f) {
                if (f.isTransient() && f.status != 401) throw f;
                // Refresh token recusado: a sessão acabou (senha trocada, conta excluída, aparelho desconectado).
                sessions.clear();
                throw new Failure(401, NOT_SIGNED_IN, "sessão encerrada pelo servidor", f);
            }
            if (!tokens.isJsonObject()) throw new Failure(200, "FAILED", "renovação sem tokens", null);
            SessionStore.Saved renewed = current.withTokens(Json.str(tokens.getAsJsonObject(), "accessToken"),
                    expiresAt(tokens.getAsJsonObject()), Json.str(tokens.getAsJsonObject(), "refreshToken"));
            sessions.save(renewed);
            return renewed;
        }
    }

    private JsonElement execute(Request request) throws Failure {
        try (Response response = client.newCall(request).execute()) {
            return read(response);
        } catch (IOException e) {
            throw new Failure(0, OFFLINE, e.getMessage(), e);
        }
    }

    private static JsonElement read(Response response) throws IOException, Failure {
        ResponseBody body = response.body();
        String text = body == null ? "" : body.string();
        JsonElement json;
        try {
            json = text.isEmpty() ? JsonNull.INSTANCE : JsonParser.parseString(text);
        } catch (JsonParseException e) {
            if (response.isSuccessful()) throw new Failure(response.code(), "FAILED", "resposta ilegível", e);
            json = JsonNull.INSTANCE;
        }
        if (response.isSuccessful()) return json;
        String code = json.isJsonObject() ? Json.str(json.getAsJsonObject(), "error") : null;
        String message = json.isJsonObject() ? Json.str(json.getAsJsonObject(), "message") : null;
        if (code == null || code.isEmpty()) code = response.code() == 401 ? NOT_SIGNED_IN : "FAILED";
        throw new Failure(response.code(), code, message, null);
    }

    private HttpUrl url(String path) throws Failure {
        if (base == null) throw new Failure(0, NOT_CONFIGURED, null, null);
        HttpUrl url = base.resolve(path.startsWith("/") ? path.substring(1) : path);
        if (url == null) throw new Failure(0, "FAILED", "caminho inválido: " + path, null);
        return url;
    }

    private static RequestBody json(@Nullable JsonElement body) {
        return RequestBody.create(body == null ? "" : body.toString(), JSON);
    }
}
