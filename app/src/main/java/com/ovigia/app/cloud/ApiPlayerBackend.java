package com.ovigia.app.cloud;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ovigia.app.catalog.UnlockRules;
import com.ovigia.app.cloud.CloudException.Reason;
import com.ovigia.app.collection.HeroPortraits;
import com.ovigia.app.learning.LearningStore.AnswerRecord;
import com.ovigia.app.learning.LearningStore.Outcome;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link PlayerBackend} com a API do O Vigia ({@code ms-o-vigia}): login por
 * e-mail e senha, com tokens guardados no aparelho, e os dados da conta em
 * PostgreSQL no servidor.
 *
 * <ul>
 *   <li><b>Leituras</b> vão ao servidor e guardam a resposta no aparelho
 *   ({@link LocalMirror}); sem rede, vale a última cópia.</li>
 *   <li><b>Gravações do jogo</b> (partidas, heróis vistos, conquistas
 *   comemoradas, aprendizado esquecido) entram numa fila em disco
 *   ({@link PendingWrites}) e sobem em segundo plano, na ordem, assim que der.
 *   A cópia lida sem rede já conta com o que está na fila.</li>
 *   <li><b>Conta</b> (perfil, senha, e-mail, exclusão) vai direto e precisa de rede.</li>
 * </ul>
 *
 * O servidor é quem decide: a partida em que o Vigia acertou desbloqueia o herói
 * lá, e as somas do aprendizado são feitas lá (dois aparelhos não se atropelam).
 */
public final class ApiPlayerBackend implements PlayerBackend {

    private static final String TAG = "ApiPlayerBackend";
    /** Quanto {@link #flushPendingWrites} espera a fila subir antes de seguir com o que o aparelho tem. */
    private static final long FLUSH_WAIT_SECONDS = 20;
    /** Limites da importação na API. */
    private static final int MAX_LEGACY_HEROES = 2000;
    private static final int MAX_LEGACY_GAMES = 5000;

    private static final String OP_GAME = "game";
    private static final String OP_SEEN = "seen";
    private static final String OP_CLAIM = "claim";
    private static final String OP_RESET = "reset";

    private static final String MIRROR_ME = "me";
    private static final String MIRROR_ACHIEVEMENTS = "achievements";
    private static final String MIRROR_HEROES = "heroes";
    private static final String MIRROR_LEARNING = "learning";
    private static final String MIRROR_GAMES = "games";

    private final ApiHttp http;
    private final HeroPortraits portraits;
    private final LocalMirror mirror;
    private final PendingWrites pending;
    /** Uma thread só: a fila sobe na ordem, e uma gravação nunca corre com outra. */
    private final ExecutorService sync;
    private final String device;
    private final LongSupplier clock;

    /** A última {@code /v1/me} lida (da conta com sessão aberta). */
    @Nullable private volatile JsonObject me;
    /** O que o servidor respondeu às partidas que subiram: personagem -> o herói era novo. */
    private final Map<Integer, Boolean> grants = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * @param dataDir pasta do app onde ficam a cópia dos dados e a fila (resolvida no primeiro uso)
     * @param sync    executor de uma thread só para subir a fila
     * @param device  nome do aparelho, para o jogador reconhecer a sessão
     */
    public ApiPlayerBackend(ApiHttp http, HeroPortraits portraits, Supplier<File> dataDir, ExecutorService sync,
                            String device, LongSupplier clock) {
        this.http = http;
        this.portraits = portraits;
        this.mirror = new LocalMirror(() -> new File(dataDir.get(), "api_cache"));
        this.pending = new PendingWrites(() -> new File(dataDir.get(), "pending_writes.json"));
        this.sync = sync;
        this.device = device;
        this.clock = clock;
    }

    @Override
    public boolean isConfigured() {
        return http.isConfigured();
    }

    // ---------------------------------------------------------------- sessão

    @Nullable
    @Override
    public Session currentSession() {
        if (!http.isConfigured()) return null;
        SessionStore.Saved saved = http.sessions().load();
        return saved == null ? null : new Session(saved.uid, saved.email);
    }

    @Override
    public Session signIn(String email, String password) throws CloudException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        body.addProperty("device", device);
        return open(call(() -> http.postPublic("v1/auth/login", body)));
    }

    @Override
    public Session signUp(String email, String password, String displayName) throws CloudException {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        body.addProperty("displayName", displayName);
        body.addProperty("device", device);
        return open(call(() -> http.postPublic("v1/auth/signup", body)));
    }

    private Session open(JsonElement response) throws CloudException {
        if (!response.isJsonObject()) throw new CloudException(Reason.FAILED);
        SessionStore.Saved saved;
        try {
            saved = http.openSession(response.getAsJsonObject());
        } catch (ApiHttp.Failure f) {
            throw cloud(f);
        }
        JsonObject user = Json.obj(response.getAsJsonObject(), "user");
        if (user != null) remember(saved.uid, user);
        // O que ficou na fila numa sessão anterior desta conta sobe agora.
        uploadPendingInBackground();
        return new Session(saved.uid, saved.email);
    }

    @Override
    public void signOut() {
        me = null;
        String refreshToken = http.closeSession();
        if (refreshToken == null) return;
        // Avisa o servidor para o refresh token deste aparelho não valer mais. Sem rede, ele vence sozinho.
        JsonObject body = new JsonObject();
        body.addProperty("refreshToken", refreshToken);
        sync.execute(() -> {
            try {
                http.postPublic("v1/auth/logout", body);
            } catch (ApiHttp.Failure ignored) {
                // Melhor esforço.
            }
        });
    }

    @Override
    public void changePassword(String currentPassword, String newPassword) throws CloudException {
        JsonObject body = new JsonObject();
        body.addProperty("currentPassword", currentPassword);
        body.addProperty("newPassword", newPassword);
        body.addProperty("device", device);
        JsonElement tokens = call(() -> http.post("v1/me/password", body));
        // O servidor encerra as outras sessões e devolve tokens novos para esta.
        if (tokens.isJsonObject()) http.replaceTokens(tokens.getAsJsonObject());
    }

    @Override
    public void requestEmailChange(String currentPassword, String newEmail) throws CloudException {
        JsonObject body = new JsonObject();
        body.addProperty("currentPassword", currentPassword);
        body.addProperty("newEmail", newEmail);
        call(() -> http.post("v1/me/email", body));
    }

    @Override
    public void deleteAccount(String password) throws CloudException {
        Session session = currentSession();
        if (session == null) throw new CloudException(Reason.NOT_SIGNED_IN);
        JsonObject body = new JsonObject();
        body.addProperty("password", password);
        call(() -> http.post("v1/me/delete", body));
        me = null;
        http.closeSession();
        sync.execute(() -> {
            mirror.forget(session.uid);
            pending.forget(session.uid);
        });
    }

    // ---------------------------------------------------------------- perfil

    @Nullable
    @Override
    public Account loadAccount(String uid) throws CloudException {
        requireSession(uid);
        try {
            JsonObject user = object(http.get("v1/me"));
            JsonObject achievements = object(http.get("v1/me/achievements"));
            remember(uid, user);
            mirror.write(uid, MIRROR_ME, user);
            mirror.write(uid, MIRROR_ACHIEVEMENTS, achievements);
            return accountOf(user, achievements);
        } catch (ApiHttp.Failure f) {
            if (!f.isOffline()) throw cloud(f);
            JsonElement user = mirror.read(uid, MIRROR_ME);
            if (user == null || !user.isJsonObject()) throw new CloudException(Reason.OFFLINE, f);
            JsonElement achievements = mirror.read(uid, MIRROR_ACHIEVEMENTS);
            return accountOf(user.getAsJsonObject(),
                    achievements != null && achievements.isJsonObject() ? achievements.getAsJsonObject() : null);
        }
    }

    @Override
    public Account saveAccount(String uid, Account account) throws CloudException {
        requireSession(uid);
        JsonObject current = currentMe(uid);
        String bio = account.bio == null || account.bio.trim().isEmpty() ? null : account.bio;
        if (!eq(account.name, Json.str(current, "displayName")) || !eq(bio, Json.str(current, "bio"))) {
            JsonObject body = new JsonObject();
            body.addProperty("displayName", account.name);
            // Bio vazia apaga; ausente manteria a de antes.
            body.addProperty("bio", bio == null ? "" : bio);
            current = object(call(() -> http.patch("v1/me", body)));
        }
        current = applyImage(current, "avatar", "avatarUrl", account.avatar);
        current = applyImage(current, "banner", "bannerUrl", account.banner);
        remember(uid, current);
        mirror.write(uid, MIRROR_ME, current);
        return accountOf(current, null).withCelebrated(account.celebrated);
    }

    /** Grava a foto ou o banner se mudou: {@code null} apaga, Base64 envia, endereço igual não faz nada. */
    private JsonObject applyImage(JsonObject current, String path, String field, @Nullable String value)
            throws CloudException {
        String url = Json.str(current, field);
        if (eq(value, url)) return current;
        if (value == null) return object(call(() -> http.delete("v1/me/" + path)));
        // Um endereço que não é o guardado aqui (não deveria acontecer): não há o que enviar.
        if (isRemote(value)) return current;
        byte[] jpeg;
        try {
            jpeg = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new CloudException(Reason.FAILED, e);
        }
        return object(call(() -> http.upload("v1/me/" + path, jpeg, "image/jpeg")));
    }

    @Override
    public void addCelebrated(String uid, Collection<String> ids, boolean baseline) {
        if (ids.isEmpty() && !baseline) return;
        sync.execute(() -> {
            markCelebratedInMirror(uid, ids);
            // O servidor recalcula as conquistas e marca as desbloqueadas como comemoradas
            // (criando o marco zero na primeira vez): o mesmo conjunto que o app acabou de ver.
            JsonObject op = new JsonObject();
            op.addProperty("type", OP_CLAIM);
            pending.add(uid, op);
            flush(uid);
        });
    }

    // ---------------------------------------------------------------- heróis

    @Override
    public List<Hero> loadHeroes(String uid) throws CloudException {
        requireSession(uid);
        flushPendingWrites();
        JsonArray heroes;
        try {
            heroes = Json.arr(http.get("v1/me/heroes"));
            mirror.write(uid, MIRROR_HEROES, heroes);
        } catch (ApiHttp.Failure f) {
            if (!f.isOffline()) throw cloud(f);
            JsonElement saved = mirror.read(uid, MIRROR_HEROES);
            if (saved == null) throw new CloudException(Reason.OFFLINE, f);
            heroes = Json.arr(saved);
        }
        for (JsonObject op : pending.of(uid)) heroes = applyToHeroes(heroes, op, null);
        List<Hero> list = new ArrayList<>();
        for (JsonObject h : Json.objects(heroes)) {
            // Lacrado: achado, mas só abre para o Vigia do Infinito (fora do catálogo desta versão).
            if (!"unlocked".equals(Json.str(h, "state"))) continue;
            int id = Json.intOr(h, "characterId", -1);
            if (id < 0) continue;
            String name = Json.str(h, "name");
            portraits.remember(id, name, null);
            long at = Json.millis(h, "unlockedAt");
            list.add(new Hero(id, name != null ? name : orEmpty(portraits.name(id)), portraits.imageUrl(id),
                    at > 0 ? at : Json.millis(h, "foundAt"), Json.bool(h, "seen")));
        }
        return list;
    }

    @Override
    public void markHeroesSeen(String uid, Collection<Integer> characterIds) {
        if (characterIds.isEmpty()) return;
        JsonObject op = new JsonObject();
        op.addProperty("type", OP_SEEN);
        JsonArray ids = new JsonArray();
        for (Integer id : characterIds) ids.add(id);
        op.add("characterIds", ids);
        enqueue(uid, op);
    }

    @Nullable
    @Override
    public Boolean awaitUnlock(String uid, int characterId) {
        flushPendingWrites();
        Boolean answered = grants.remove(characterId);
        if (answered != null) return answered;
        // A partida ainda está na fila (sem rede): é novo se a última cópia do servidor não tinha o herói.
        for (JsonObject op : pending.of(uid)) {
            JsonObject body = OP_GAME.equals(Json.str(op, "type")) ? Json.obj(op, "body") : null;
            if (body == null || Json.intOr(body, "characterId", -1) != characterId) continue;
            JsonElement saved = mirror.read(uid, MIRROR_HEROES);
            if (saved == null) return null;
            for (JsonObject h : Json.objects(Json.arr(saved))) {
                if (Json.intOr(h, "characterId", -1) == characterId && "unlocked".equals(Json.str(h, "state"))) {
                    return false;
                }
            }
            return true;
        }
        return null;
    }

    /** Espera a fila subir (até um limite de tempo; sem rede, ela continua lá). */
    private void flushPendingWrites() {
        Session session = currentSession();
        if (session == null) return;
        Future<?> done = sync.submit(() -> flush(session.uid));
        try {
            done.get(FLUSH_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Log.i(TAG, "A fila ainda está subindo: segue com o que o aparelho tem");
        } catch (ExecutionException e) {
            Log.w(TAG, "Falha ao subir a fila", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sobe o que estiver na fila da conta com sessão aberta, sem esperar (abertura do app, rede de volta). */
    public void uploadPendingInBackground() {
        Session session = currentSession();
        if (session != null) sync.execute(() -> flush(session.uid));
    }

    // ---------------------------------------------------------------- memória do Vigia

    @Override
    public Learning loadLearning(String uid) throws CloudException {
        requireSession(uid);
        flushPendingWrites();
        JsonObject learning;
        try {
            learning = object(http.get("v1/me/learning"));
            mirror.write(uid, MIRROR_LEARNING, learning);
        } catch (ApiHttp.Failure f) {
            if (!f.isOffline()) throw cloud(f);
            JsonElement saved = mirror.read(uid, MIRROR_LEARNING);
            if (saved == null || !saved.isJsonObject()) throw new CloudException(Reason.OFFLINE, f);
            learning = saved.getAsJsonObject();
        }
        for (JsonObject op : pending.of(uid)) learning = applyToLearning(learning, op);
        return learningOf(learning);
    }

    @Override
    public void recordGame(String uid, @Nullable Game game, boolean engineWin) {
        JsonObject body = new JsonObject();
        body.addProperty("outcome", game == null ? Outcome.LOST_UNREVEALED.name() : game.outcome.name());
        JsonArray answers = new JsonArray();
        if (game != null) {
            body.addProperty("characterId", game.characterId);
            for (AnswerRecord a : game.answers) answers.add(answer(a));
        }
        body.add("answers", answers);
        // A hora da partida vai junto: é por ela que o servidor reconhece um reenvio.
        body.addProperty("playedAt", Json.iso(game == null ? clock.getAsLong() : game.timestamp));
        JsonObject op = new JsonObject();
        op.addProperty("type", OP_GAME);
        op.add("body", body);
        enqueue(uid, op);
    }

    @Override
    public List<Game> recentGames(String uid, int limit) throws CloudException {
        requireSession(uid);
        flushPendingWrites();
        JsonArray games;
        try {
            games = Json.arr(http.get("v1/me/games?limit=" + Math.max(1, limit)));
            mirror.write(uid, MIRROR_GAMES, games);
        } catch (ApiHttp.Failure f) {
            if (!f.isOffline()) throw cloud(f);
            JsonElement saved = mirror.read(uid, MIRROR_GAMES);
            if (saved == null) throw new CloudException(Reason.OFFLINE, f);
            games = Json.arr(saved);
        }
        for (JsonObject op : pending.of(uid)) games = applyToGames(games, op);
        List<Game> list = new ArrayList<>();
        for (JsonObject g : Json.objects(games)) {
            Game game = gameOf(g);
            if (game != null) list.add(game);
            if (list.size() >= limit) break;
        }
        return list;
    }

    @Override
    public void resetLearning(String uid) {
        JsonObject op = new JsonObject();
        op.addProperty("type", OP_RESET);
        enqueue(uid, op);
    }

    @Override
    public void importLegacy(String uid, LegacyImport data) throws CloudException {
        requireSession(uid);
        JsonObject body = new JsonObject();
        JsonArray heroes = new JsonArray();
        for (Hero h : data.heroes) {
            if (heroes.size() >= MAX_LEGACY_HEROES) break;
            portraits.remember(h.characterId, h.name, h.imageUrl);
            JsonObject hero = new JsonObject();
            hero.addProperty("characterId", h.characterId);
            hero.addProperty("unlockedAt", Json.iso(h.unlockedAt > 0 ? h.unlockedAt : clock.getAsLong()));
            hero.addProperty("seen", h.seen);
            heroes.add(hero);
        }
        body.add("heroes", heroes);
        body.addProperty("gamesPlayed", data.learning.gamesPlayed);
        body.addProperty("engineWins", data.learning.engineWins);
        JsonObject picks = new JsonObject();
        for (Map.Entry<Integer, Integer> e : data.learning.picks.entrySet()) {
            picks.addProperty(String.valueOf(e.getKey()), e.getValue());
        }
        body.add("picks", picks);
        body.add("beliefs", beliefsJson(data.learning.beliefs));
        // As mais novas primeiro, se passar do limite.
        List<Game> sorted = new ArrayList<>(data.games);
        sorted.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        JsonArray games = new JsonArray();
        for (Game g : sorted.subList(0, Math.min(MAX_LEGACY_GAMES, sorted.size()))) {
            JsonObject game = new JsonObject();
            game.addProperty("playedAt", Json.iso(g.timestamp));
            game.addProperty("characterId", g.characterId);
            game.addProperty("outcome", g.outcome.name());
            JsonArray answers = new JsonArray();
            for (AnswerRecord a : g.answers) answers.add(answer(a));
            game.add("answers", answers);
            games.add(game);
        }
        body.add("games", games);
        if (data.celebrated != null) {
            JsonArray celebrated = new JsonArray();
            for (String id : data.celebrated) celebrated.add(id);
            body.add("celebrated", celebrated);
        }
        call(() -> http.post("v1/me/import/legacy", body));
    }

    // ---------------------------------------------------------------- para os amigos

    /** Cartão da própria conta (com @usuario), ou {@code null} se ainda não escolheu ou nada foi lido. */
    @Nullable
    public com.ovigia.app.social.UserCard myCard(String uid) {
        JsonObject user = me;
        if (user == null || !uid.equals(Json.str(user, "id"))) {
            JsonElement saved = mirror.read(uid, MIRROR_ME);
            user = saved != null && saved.isJsonObject() ? saved.getAsJsonObject() : null;
        }
        if (user == null) return null;
        String username = Json.str(user, "username");
        if (username == null) return null;
        return new com.ovigia.app.social.UserCard(uid, username, orEmpty(Json.str(user, "displayName")),
                Json.str(user, "avatarUrl"));
    }

    /** A API devolveu a conta atualizada (ex.: depois de reservar o @usuario). */
    public void remember(String uid, JsonObject user) {
        if (!uid.equals(Json.str(user, "id"))) return;
        me = user;
        String email = Json.str(user, "email");
        if (email != null) http.updateEmail(uid, email);
        sync.execute(() -> mirror.write(uid, MIRROR_ME, user));
    }

    // ---------------------------------------------------------------- fila

    private void enqueue(String uid, JsonObject op) {
        sync.execute(() -> {
            pending.add(uid, op);
            flush(uid);
        });
    }

    /**
     * Sobe a fila da conta, na ordem, enquanto der. Roda só no {@link #sync}.
     * Uma gravação que o servidor recusa de vez (dado inválido) sai da fila; uma que
     * falhou por rede, servidor fora do ar ou sessão a renovar fica para a próxima.
     */
    private void flush(String uid) {
        SessionStore.Saved session = http.sessions().load();
        if (session == null || !session.uid.equals(uid)) return;
        for (JsonObject op : pending.of(uid)) {
            JsonElement response = null;
            try {
                response = send(op);
            } catch (ApiHttp.Failure f) {
                if (f.isTransient() || ApiHttp.NOT_CONFIGURED.equals(f.code)) return;
                Log.w(TAG, "A API recusou uma gravação (" + f.code + "): fica de fora", f);
            }
            if (response != null) {
                rememberGrant(op, response);
                applyToMirror(uid, op, response);
            }
            pending.remove(uid, op);
        }
    }

    private JsonElement send(JsonObject op) throws ApiHttp.Failure {
        String type = Json.str(op, "type");
        if (OP_GAME.equals(type)) return http.post("v1/games", Json.obj(op, "body"));
        if (OP_SEEN.equals(type)) {
            JsonObject body = new JsonObject();
            body.add("characterIds", Json.arr(op, "characterIds"));
            return http.post("v1/me/heroes/seen", body);
        }
        if (OP_CLAIM.equals(type)) return http.post("v1/me/achievements/claim", null);
        if (OP_RESET.equals(type)) return http.delete("v1/me/learning");
        Log.w(TAG, "Gravação desconhecida na fila: " + type);
        return null;
    }

    /** Se a partida pôs o herói na coleção agora ou se a conta já tinha (ver {@link #awaitUnlock}). */
    private void rememberGrant(JsonObject op, JsonElement response) {
        JsonObject hero = response.isJsonObject() ? Json.obj(response.getAsJsonObject(), "hero") : null;
        if (!OP_GAME.equals(Json.str(op, "type")) || hero == null) return;
        int id = Json.intOr(hero, "characterId", -1);
        if (id >= 0 && "unlocked".equals(Json.str(hero, "state"))) grants.put(id, Json.bool(hero, "isNew"));
    }

    /** O servidor gravou {@code op}: a cópia do aparelho passa a contar com ela (para abrir sem rede). */
    private void applyToMirror(String uid, JsonObject op, JsonElement response) {
        JsonElement learning = mirror.read(uid, MIRROR_LEARNING);
        if (learning != null && learning.isJsonObject()) {
            mirror.write(uid, MIRROR_LEARNING, applyToLearning(learning.getAsJsonObject(), op));
        }
        JsonElement games = mirror.read(uid, MIRROR_GAMES);
        if (games != null) mirror.write(uid, MIRROR_GAMES, applyToGames(Json.arr(games), op));
        JsonElement heroes = mirror.read(uid, MIRROR_HEROES);
        if (heroes != null) {
            JsonObject answer = response.isJsonObject() ? response.getAsJsonObject() : new JsonObject();
            mirror.write(uid, MIRROR_HEROES, applyToHeroes(Json.arr(heroes), op, answer));
        }
    }

    // ---------------------------------------------------------------- cópias + fila

    /**
     * Os heróis com {@code op} aplicada: a partida que desbloqueia põe o herói,
     * "vistos" marca.
     *
     * @param answer o que o servidor respondeu à partida (aí vale o herói que ele deu, ou nenhum),
     *               ou {@code null} se ela ainda está na fila (vale a regra do app)
     */
    private JsonArray applyToHeroes(JsonArray heroes, JsonObject op, @Nullable JsonObject answer) {
        String type = Json.str(op, "type");
        Map<Integer, JsonObject> byId = new LinkedHashMap<>();
        for (JsonObject h : Json.objects(heroes)) byId.put(Json.intOr(h, "characterId", -1), h.deepCopy());
        if (OP_GAME.equals(type)) {
            JsonObject body = Json.obj(op, "body");
            Outcome outcome = body == null ? null : outcome(Json.str(body, "outcome"));
            int id = body == null ? -1 : Json.intOr(body, "characterId", -1);
            JsonObject grant = answer == null ? null : Json.obj(answer, "hero");
            String state = answer == null ? "unlocked" : grant == null ? null : Json.str(grant, "state");
            if (outcome != null && UnlockRules.unlocks(outcome) && id >= 0 && !byId.containsKey(id)
                    && state != null) {
                JsonObject hero = new JsonObject();
                hero.addProperty("characterId", id);
                hero.addProperty("name", portraits.name(id));
                hero.addProperty("state", state);
                hero.addProperty("foundAt", Json.str(body, "playedAt"));
                if ("unlocked".equals(state)) hero.addProperty("unlockedAt", Json.str(body, "playedAt"));
                hero.addProperty("seen", false);
                byId.put(id, hero);
            }
        } else if (OP_SEEN.equals(type)) {
            for (JsonElement e : Json.arr(op, "characterIds")) {
                JsonObject h = e.isJsonPrimitive() ? byId.get(e.getAsInt()) : null;
                if (h != null) h.addProperty("seen", true);
            }
        }
        JsonArray result = new JsonArray();
        for (JsonObject h : byId.values()) result.add(h);
        return result;
    }

    /** O aprendizado com {@code op} aplicada (as mesmas somas que o servidor faz). */
    private static JsonObject applyToLearning(JsonObject learning, JsonObject op) {
        String type = Json.str(op, "type");
        if (OP_RESET.equals(type)) return emptyLearning();
        if (!OP_GAME.equals(type)) return learning;
        JsonObject body = Json.obj(op, "body");
        Outcome outcome = body == null ? null : outcome(Json.str(body, "outcome"));
        if (outcome == null) return learning;
        JsonObject result = learning.deepCopy();
        result.addProperty("gamesPlayed", Json.intOr(learning, "gamesPlayed", 0) + 1);
        result.addProperty("engineWins", Json.intOr(learning, "engineWins", 0)
                + (outcome == Outcome.ENGINE_GUESSED ? 1 : 0));
        int id = Json.intOr(body, "characterId", -1);
        if (id < 0) return result;
        String key = String.valueOf(id);
        JsonObject picks = Json.obj(result, "picks");
        if (picks == null) result.add("picks", picks = new JsonObject());
        picks.addProperty(key, Json.intOr(picks, key, 0) + 1);
        JsonObject beliefs = Json.obj(result, "beliefs");
        if (beliefs == null) result.add("beliefs", beliefs = new JsonObject());
        JsonObject attrs = Json.obj(beliefs, key);
        if (attrs == null) beliefs.add(key, attrs = new JsonObject());
        for (JsonObject a : Json.objects(Json.arr(body, "answers"))) {
            String question = Json.str(a, "key");
            if (question == null) continue;
            JsonArray sumCount = Json.arr(attrs, question);
            double sum = sumCount.size() > 0 ? sumCount.get(0).getAsDouble() : 0;
            double count = sumCount.size() > 1 ? sumCount.get(1).getAsDouble() : 0;
            JsonArray updated = new JsonArray();
            updated.add(sum + Json.doubleOr(a, "value", 0));
            updated.add(count + 1);
            attrs.add(question, updated);
        }
        return result;
    }

    /** O histórico com {@code op} aplicada: a partida com personagem entra no topo. */
    private static JsonArray applyToGames(JsonArray games, JsonObject op) {
        String type = Json.str(op, "type");
        if (OP_RESET.equals(type)) return new JsonArray();
        JsonObject body = OP_GAME.equals(type) ? Json.obj(op, "body") : null;
        if (body == null || Json.intOr(body, "characterId", -1) < 0) return games;
        JsonArray result = new JsonArray();
        result.add(body.deepCopy());
        result.addAll(games);
        return result;
    }

    private void markCelebratedInMirror(String uid, Collection<String> ids) {
        JsonElement saved = mirror.read(uid, MIRROR_ACHIEVEMENTS);
        JsonObject achievements = saved != null && saved.isJsonObject() ? saved.getAsJsonObject() : new JsonObject();
        if (Json.str(achievements, "baselineAt") == null) {
            achievements.addProperty("baselineAt", Json.iso(clock.getAsLong()));
        }
        JsonArray items = Json.arr(achievements, "achievements");
        for (String id : ids) {
            JsonObject item = null;
            for (JsonObject i : Json.objects(items)) if (id.equals(Json.str(i, "id"))) item = i;
            if (item == null) {
                item = new JsonObject();
                item.addProperty("id", id);
                items.add(item);
            }
            item.addProperty("celebrated", true);
        }
        achievements.add("achievements", items);
        mirror.write(uid, MIRROR_ACHIEVEMENTS, achievements);
    }

    // ---------------------------------------------------------------- conversões

    private static Account accountOf(JsonObject user, @Nullable JsonObject achievements) {
        List<String> celebrated = null;
        if (achievements != null && Json.str(achievements, "baselineAt") != null) {
            celebrated = new ArrayList<>();
            for (JsonObject item : Json.objects(Json.arr(achievements, "achievements"))) {
                String id = Json.str(item, "id");
                if (id != null && Json.bool(item, "celebrated")) celebrated.add(id);
            }
        }
        return new Account(orEmpty(Json.str(user, "displayName")), Json.str(user, "bio"),
                Json.str(user, "avatarUrl"), Json.str(user, "bannerUrl"), Json.str(user, "username"), celebrated);
    }

    private static Learning learningOf(JsonObject json) {
        Map<Integer, Integer> picks = new HashMap<>();
        JsonObject rawPicks = Json.obj(json, "picks");
        if (rawPicks != null) {
            for (Map.Entry<String, JsonElement> e : rawPicks.entrySet()) {
                int id = parseId(e.getKey());
                if (id >= 0 && e.getValue().isJsonPrimitive()) picks.put(id, e.getValue().getAsInt());
            }
        }
        Map<Integer, Map<String, double[]>> beliefs = new HashMap<>();
        JsonObject rawBeliefs = Json.obj(json, "beliefs");
        if (rawBeliefs != null) {
            for (Map.Entry<String, JsonElement> perCharacter : rawBeliefs.entrySet()) {
                int id = parseId(perCharacter.getKey());
                if (id < 0 || !perCharacter.getValue().isJsonObject()) continue;
                Map<String, double[]> attrs = new HashMap<>();
                for (Map.Entry<String, JsonElement> attr : perCharacter.getValue().getAsJsonObject().entrySet()) {
                    JsonArray sumCount = Json.arr(attr.getValue());
                    if (sumCount.size() < 2) continue;
                    attrs.put(attr.getKey(), new double[]{sumCount.get(0).getAsDouble(), sumCount.get(1).getAsDouble()});
                }
                beliefs.put(id, attrs);
            }
        }
        return new Learning(Json.intOr(json, "gamesPlayed", 0), Json.intOr(json, "engineWins", 0), picks, beliefs);
    }

    @Nullable
    private static Game gameOf(JsonObject json) {
        Outcome outcome = outcome(Json.str(json, "outcome"));
        int characterId = Json.intOr(json, "characterId", -1);
        if (outcome == null || characterId < 0) return null;
        List<AnswerRecord> answers = new ArrayList<>();
        for (JsonObject a : Json.objects(Json.arr(json, "answers"))) {
            String key = Json.str(a, "key");
            if (key != null) answers.add(new AnswerRecord(key, Json.doubleOr(a, "value", 0)));
        }
        return new Game(Json.millis(json, "playedAt"), characterId, outcome, answers);
    }

    private static JsonObject beliefsJson(Map<Integer, Map<String, double[]>> beliefs) {
        JsonObject json = new JsonObject();
        for (Map.Entry<Integer, Map<String, double[]>> e : beliefs.entrySet()) {
            JsonObject attrs = new JsonObject();
            for (Map.Entry<String, double[]> attr : e.getValue().entrySet()) {
                JsonArray sumCount = new JsonArray();
                sumCount.add(attr.getValue()[0]);
                sumCount.add(attr.getValue()[1]);
                attrs.add(attr.getKey(), sumCount);
            }
            json.add(String.valueOf(e.getKey()), attrs);
        }
        return json;
    }

    private static JsonObject answer(AnswerRecord a) {
        JsonObject answer = new JsonObject();
        answer.addProperty("key", a.key);
        answer.addProperty("value", a.value);
        return answer;
    }

    private static JsonObject emptyLearning() {
        JsonObject learning = new JsonObject();
        learning.addProperty("gamesPlayed", 0);
        learning.addProperty("engineWins", 0);
        learning.add("picks", new JsonObject());
        learning.add("beliefs", new JsonObject());
        return learning;
    }

    // ---------------------------------------------------------------- apoio

    private interface Call {
        JsonElement run() throws ApiHttp.Failure;
    }

    private static JsonElement call(Call call) throws CloudException {
        try {
            return call.run();
        } catch (ApiHttp.Failure f) {
            throw cloud(f);
        }
    }

    /** O código de erro da API é o mesmo nome do {@link Reason}; o resto vira {@link Reason#FAILED}. */
    static CloudException cloud(ApiHttp.Failure f) {
        Reason reason;
        try {
            reason = Reason.valueOf(f.code);
        } catch (IllegalArgumentException e) {
            reason = f.status == 401 ? Reason.NOT_SIGNED_IN : Reason.FAILED;
        }
        if (reason == Reason.FAILED) Log.w(TAG, "Falha inesperada na API", f);
        return new CloudException(reason, f);
    }

    private JsonObject currentMe(String uid) throws CloudException {
        JsonObject user = me;
        if (user != null && uid.equals(Json.str(user, "id"))) return user;
        user = object(call(() -> http.get("v1/me")));
        remember(uid, user);
        return user;
    }

    private void requireSession(String uid) throws CloudException {
        if (!http.isConfigured()) throw new CloudException(Reason.NOT_CONFIGURED);
        Session session = currentSession();
        if (session == null || !session.uid.equals(uid)) throw new CloudException(Reason.NOT_SIGNED_IN);
    }

    private static JsonObject object(JsonElement json) throws CloudException {
        if (!json.isJsonObject()) throw new CloudException(Reason.FAILED);
        return json.getAsJsonObject();
    }

    @Nullable
    private static Outcome outcome(@Nullable String name) {
        if (name == null) return null;
        try {
            return Outcome.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isRemote(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }

    private static int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
