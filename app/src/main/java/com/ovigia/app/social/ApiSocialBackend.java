package com.ovigia.app.social;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ovigia.app.cloud.ApiHttp;
import com.ovigia.app.cloud.ApiPlayerBackend;
import com.ovigia.app.cloud.Json;
import com.ovigia.app.cloud.SessionStore;
import com.ovigia.app.collection.HeroPortraits;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * {@link SocialBackend} com a API do O Vigia: @usuario, amizades, pedidos,
 * perfis e trocas moram no servidor, que confere tudo (amizade só a partir de
 * pedido, perfil só para amigos, troca só com heróis que cada um tem de verdade).
 *
 * O perfil que os amigos veem é montado pelo servidor a partir dos dados da
 * conta: não há mais nada a publicar.
 *
 * Os amigos leem só do servidor: sem rede, falham com
 * {@link SocialException.Error#OFFLINE} em vez de mostrar dados velhos.
 * Bloqueante: chamar fora da main thread.
 */
public final class ApiSocialBackend implements SocialBackend {

    private static final String TAG = "ApiSocialBackend";
    /** Troca aceita há mais tempo que isso não vira mais aviso para quem propôs. */
    private static final long COMPLETED_NOTICE_MS = 30L * 24 * 60 * 60 * 1000;
    /** Máximo de trocas concluídas lembradas como já vistas, por conta. */
    private static final int MAX_ACKNOWLEDGED = 500;

    private final ApiHttp http;
    private final ApiPlayerBackend player;
    private final HeroPortraits portraits;
    private final AcknowledgedTrades acknowledged;
    private final LongSupplier clock;

    /** Onde ficam as trocas concluídas que quem propôs já viu. */
    public interface AcknowledgedTrades {
        Set<String> load(String uid);

        void save(String uid, Set<String> ids);
    }

    public ApiSocialBackend(ApiHttp http, ApiPlayerBackend player, HeroPortraits portraits,
                            AcknowledgedTrades acknowledged, LongSupplier clock) {
        this.http = http;
        this.player = player;
        this.portraits = portraits;
        this.acknowledged = acknowledged;
        this.clock = clock;
    }

    @Override
    public boolean isConfigured() {
        return http.isConfigured();
    }

    // ---------------------------------------------------------------- cartões

    @Nullable
    @Override
    public UserCard loadCard(String uid) throws SocialException {
        // Só a própria conta tem cartão sem amizade (os outros aparecem pelo @usuario).
        String me = requireUid();
        return me.equals(uid) ? player.myCard(uid) : null;
    }

    @Override
    public void claimUsername(UserCard card, @Nullable String previousUsername) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(card.uid)) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        JsonObject body = new JsonObject();
        body.addProperty("username", card.username);
        JsonElement user = call(() -> http.put("v1/me/username", body));
        if (user.isJsonObject()) player.remember(uid, user.getAsJsonObject());
    }

    @Override
    public void publish(PublicProfile profile) {
        // O servidor monta o perfil dos amigos a partir dos dados da conta.
    }

    @Nullable
    @Override
    public UserCard findByUsername(String username) throws SocialException {
        requireUid();
        try {
            JsonElement found = http.get("v1/users/by-username/" + username);
            JsonObject card = found.isJsonObject() ? Json.obj(found.getAsJsonObject(), "card") : null;
            return card == null ? null : cardOf(card);
        } catch (ApiHttp.Failure f) {
            if ("NOT_FOUND".equals(f.code)) return null;
            throw social(f);
        }
    }

    // ---------------------------------------------------------------- amizades

    @Override
    public FriendsHub loadHub() throws SocialException {
        String uid = requireUid();
        JsonObject hub = object(call(() -> http.get("v1/friends")));
        UserCard me = myCard(uid);
        List<UserCard> friends = new ArrayList<>();
        for (JsonObject f : Json.objects(Json.arr(hub, "friends"))) {
            JsonObject user = Json.obj(f, "user");
            if (user != null) friends.add(cardOf(user));
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        friends.sort((a, b) -> collator.compare(String.valueOf(a.name), String.valueOf(b.name)));
        List<FriendRequest> incoming = new ArrayList<>();
        for (JsonObject r : Json.objects(Json.arr(hub, "incoming"))) {
            JsonObject user = Json.obj(r, "user");
            if (user != null) incoming.add(new FriendRequest(cardOf(user), me, Json.millis(r, "createdAt")));
        }
        List<FriendRequest> outgoing = new ArrayList<>();
        for (JsonObject r : Json.objects(Json.arr(hub, "outgoing"))) {
            JsonObject user = Json.obj(r, "user");
            if (user != null) outgoing.add(new FriendRequest(me, cardOf(user), Json.millis(r, "createdAt")));
        }
        return new FriendsHub(friends, incoming, outgoing);
    }

    @Override
    public void sendRequest(UserCard from, UserCard to) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(from.uid)) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        JsonObject body = new JsonObject();
        body.addProperty("userId", to.uid);
        call(() -> http.post("v1/friends/requests", body));
    }

    @Override
    public void acceptRequest(String fromUid) throws SocialException {
        requireUid();
        call(() -> http.post("v1/friends/requests/incoming/" + fromUid + "/accept", null));
    }

    @Override
    public void deleteRequest(String fromUid, String toUid) throws SocialException {
        String uid = requireUid();
        String path = uid.equals(toUid) ? "v1/friends/requests/incoming/" + fromUid
                : "v1/friends/requests/outgoing/" + toUid;
        ignoringNotFound(() -> http.delete(path));
    }

    @Override
    public void removeFriend(String friendUid) throws SocialException {
        requireUid();
        ignoringNotFound(() -> http.delete("v1/friends/" + friendUid));
    }

    // ---------------------------------------------------------------- trocas

    @Override
    public List<TradeOffer> loadTrades() throws SocialException {
        String uid = requireUid();
        List<TradeOffer> list = new ArrayList<>();
        for (JsonObject t : Json.objects(Json.arr(call(() -> http.get("v1/trades?status=pending"))))) {
            TradeOffer trade = tradeOf(t);
            if (trade != null) list.add(trade);
        }
        // Aceitas: o servidor já deu o herói aos dois. Quem propôs vê o aviso uma vez.
        Set<String> seen = acknowledged.load(uid);
        long oldest = clock.getAsLong() - COMPLETED_NOTICE_MS;
        for (JsonObject t : Json.objects(Json.arr(call(() -> http.get("v1/trades?status=accepted"))))) {
            TradeOffer trade = tradeOf(t);
            if (trade == null || !trade.from.uid.equals(uid) || seen.contains(trade.id)) continue;
            if (Json.millis(t, "respondedAt") < oldest) continue;
            list.add(trade);
        }
        list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return list;
    }

    @Override
    public TradeOffer proposeTrade(TradeOffer trade) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(trade.from.uid)) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        JsonObject body = new JsonObject();
        body.addProperty("toUserId", trade.to.uid);
        body.addProperty("wantCharacterId", trade.want.characterId);
        body.addProperty("offerCharacterId", trade.offer.characterId);
        TradeOffer saved = tradeOf(object(call(() -> http.post("v1/trades", body))));
        if (saved == null) throw new SocialException(SocialException.Error.UNKNOWN);
        return saved;
    }

    @Override
    public void acceptTrade(String tradeId, PublicProfile.Hero chosenOffer) throws SocialException {
        requireUid();
        JsonObject body = new JsonObject();
        body.addProperty("offerCharacterId", chosenOffer.characterId);
        call(() -> http.post("v1/trades/" + tradeId + "/accept", body));
    }

    @Override
    public void deleteTrade(String tradeId) throws SocialException {
        String uid = requireUid();
        JsonObject trade;
        try {
            trade = object(http.get("v1/trades/" + tradeId));
        } catch (ApiHttp.Failure f) {
            if ("NOT_FOUND".equals(f.code) || f.status == 400) return;
            throw social(f);
        }
        String status = Json.str(trade, "status");
        JsonObject from = Json.obj(trade, "from");
        boolean mine = from != null && uid.equals(Json.str(from, "id"));
        if ("pending".equals(status)) {
            String action = mine ? "/cancel" : "/decline";
            ignoringNotFound(() -> http.post("v1/trades/" + tradeId + action, null));
        } else if ("accepted".equals(status) && mine) {
            Set<String> seen = new HashSet<>(acknowledged.load(uid));
            seen.add(tradeId);
            if (seen.size() > MAX_ACKNOWLEDGED) {
                // Os ids crescem com o tempo: os menores são os mais antigos (e já passaram do prazo do aviso).
                List<String> sorted = new ArrayList<>(seen);
                sorted.sort((a, b) -> Long.compare(parseLong(a), parseLong(b)));
                seen = new HashSet<>(sorted.subList(sorted.size() - MAX_ACKNOWLEDGED, sorted.size()));
            }
            acknowledged.save(uid, seen);
        }
    }

    @Override
    public PublicProfile loadProfile(String uid) throws SocialException {
        requireUid();
        JsonObject profile = object(call(() -> http.get("v1/users/" + uid + "/profile")));
        JsonObject card = Json.obj(profile, "card");
        if (card == null) throw new SocialException(SocialException.Error.UNKNOWN);
        List<PublicProfile.Hero> heroes = new ArrayList<>();
        for (JsonObject h : Json.objects(Json.arr(profile, "heroes"))) {
            int id = Json.intOr(h, "characterId", -1);
            if (id < 0) continue;
            heroes.add(new PublicProfile.Hero(id, nameOf(h, id), portraits.imageUrl(id), Json.millis(h, "unlockedAt")));
        }
        heroes.sort((a, b) -> Long.compare(b.unlockedAt, a.unlockedAt));
        Map<String, Integer> achievements = new HashMap<>();
        for (JsonObject a : Json.objects(Json.arr(profile, "achievements"))) {
            String id = Json.str(a, "id");
            if (id != null) achievements.put(id, Json.intOr(a, "current", 0));
        }
        return new PublicProfile(cardOf(card), Json.str(profile, "bio"), Json.str(profile, "bannerUrl"),
                Json.intOr(profile, "gamesPlayed", 0), Json.intOr(profile, "engineWins", 0),
                Json.intOr(profile, "distinctCharacters", 0), heroes, Achievements.fromPublished(achievements),
                Json.millis(profile, "updatedAt"));
    }

    // ---------------------------------------------------------------- conversões

    private static UserCard cardOf(JsonObject card) {
        return new UserCard(String.valueOf(Json.str(card, "id")), String.valueOf(Json.str(card, "username")),
                orEmpty(Json.str(card, "displayName")), Json.str(card, "avatarUrl"));
    }

    @Nullable
    private TradeOffer tradeOf(JsonObject t) {
        JsonObject from = Json.obj(t, "from");
        JsonObject to = Json.obj(t, "to");
        JsonObject want = Json.obj(t, "want");
        JsonObject offer = Json.obj(t, "offer");
        String id = Json.str(t, "id");
        TradeOffer.Status status = "pending".equals(Json.str(t, "status")) ? TradeOffer.Status.PENDING
                : "accepted".equals(Json.str(t, "status")) ? TradeOffer.Status.ACCEPTED : null;
        if (from == null || to == null || want == null || offer == null || id == null || status == null) return null;
        return new TradeOffer(id, cardOf(from), cardOf(to), heroOf(want), heroOf(offer), status,
                Json.millis(t, "createdAt"));
    }

    private PublicProfile.Hero heroOf(JsonObject h) {
        int id = Json.intOr(h, "characterId", -1);
        return new PublicProfile.Hero(id, nameOf(h, id), portraits.imageUrl(id), 0);
    }

    private String nameOf(JsonObject h, int id) {
        String name = Json.str(h, "name");
        if (name != null) return name;
        String known = portraits.name(id);
        return known == null ? "" : known;
    }

    /** O próprio cartão; sem @usuario lido ainda, um cartão só com o id (basta para separar os pedidos). */
    private UserCard myCard(String uid) {
        UserCard card = player.myCard(uid);
        return card != null ? card : new UserCard(uid, "", "", null);
    }

    // ---------------------------------------------------------------- apoio

    private interface Call {
        JsonElement run() throws ApiHttp.Failure;
    }

    private static JsonElement call(Call call) throws SocialException {
        try {
            return call.run();
        } catch (ApiHttp.Failure f) {
            throw social(f);
        }
    }

    /** Apagar o que já não existe (o outro lado apagou antes) é sucesso. */
    private static void ignoringNotFound(Call call) throws SocialException {
        try {
            call.run();
        } catch (ApiHttp.Failure f) {
            if (!"NOT_FOUND".equals(f.code)) throw social(f);
        }
    }

    static SocialException social(ApiHttp.Failure f) {
        SocialException.Error error;
        switch (f.code) {
            case ApiHttp.OFFLINE: error = SocialException.Error.OFFLINE; break;
            case ApiHttp.NOT_CONFIGURED: error = SocialException.Error.NOT_CONFIGURED; break;
            case ApiHttp.NOT_SIGNED_IN:
            case "USERNAME_REQUIRED": error = SocialException.Error.NOT_CONNECTED; break;
            case "WRONG_PASSWORD": error = SocialException.Error.WRONG_PASSWORD; break;
            case "USERNAME_TAKEN": error = SocialException.Error.USERNAME_TAKEN; break;
            case "USERNAME_INVALID": error = SocialException.Error.USERNAME_INVALID; break;
            case "NOT_FOUND": error = SocialException.Error.NOT_FOUND; break;
            case "PERMISSION_DENIED":
            case "ALREADY_FRIENDS": error = SocialException.Error.PERMISSION_DENIED; break;
            case "TRADE_INVALID": error = SocialException.Error.TRADE_INVALID; break;
            default:
                error = f.status == 401 ? SocialException.Error.NOT_CONNECTED : SocialException.Error.UNKNOWN;
        }
        if (error == SocialException.Error.UNKNOWN) Log.w(TAG, "Falha inesperada na API", f);
        return new SocialException(error, f);
    }

    private String requireUid() throws SocialException {
        if (!http.isConfigured()) throw new SocialException(SocialException.Error.NOT_CONFIGURED);
        SessionStore.Saved session = http.sessions().load();
        if (session == null) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        return session.uid;
    }

    private static JsonObject object(JsonElement json) throws SocialException {
        if (!json.isJsonObject()) throw new SocialException(SocialException.Error.UNKNOWN);
        return json.getAsJsonObject();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** Trocas já vistas guardadas num {@link JsonArray} por conta (para as preferências do app). */
    public static Set<String> idsFrom(@Nullable String json) {
        Set<String> ids = new HashSet<>();
        if (json == null) return ids;
        try {
            for (JsonElement e : com.google.gson.JsonParser.parseString(json).getAsJsonArray()) ids.add(e.getAsString());
        } catch (RuntimeException e) {
            Log.w(TAG, "Trocas vistas ilegíveis", e);
        }
        return ids;
    }

    public static String idsTo(Set<String> ids) {
        JsonArray array = new JsonArray();
        for (String id : ids) array.add(id);
        return array.toString();
    }
}
