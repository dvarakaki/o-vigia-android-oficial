package com.ovigia.app.social;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.MemoryCacheSettings;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link SocialBackend} com Firebase Auth (e-mail e senha) e Cloud Firestore.
 * Só usa Auth e Firestore, que cabem no plano gratuito; foto e banner vão
 * reduzidos dentro dos documentos.
 *
 * Coleções (as permissões estão em {@code firestore.rules}):
 * <ul>
 *   <li>{@code usernames/{username}} → {@code uid}: garante @usuario único.</li>
 *   <li>{@code users/{uid}}: cartão público (qualquer conta online lê).</li>
 *   <li>{@code users/{uid}/friends/{friendUid}}: amizades, dos dois lados.</li>
 *   <li>{@code profiles/{uid}}: perfil completo (só o dono e os amigos leem).</li>
 *   <li>{@code friendRequests/{from}_{to}}: pedidos pendentes.</li>
 *   <li>{@code trades/{from}_{to}_{heroId}}: propostas de troca de heróis entre amigos.</li>
 * </ul>
 *
 * Tudo roda com {@link Tasks#await} numa thread de fundo; o cache do Firestore
 * fica só na memória, então sem rede as operações falham com
 * {@link SocialException.Error#OFFLINE} em vez de mostrar dados velhos.
 */
public final class FirebaseSocialBackend implements SocialBackend {

    private static final String TAG = "FirebaseSocialBackend";
    private static final long TIMEOUT_SECONDS = 15;
    /** Limite de valores de um filtro "in" do Firestore. */
    private static final int IN_QUERY_LIMIT = 10;
    /** Projeto "demo-*": o Emulator Suite aceita sem projeto real no console. */
    private static final String EMULATOR_PROJECT_ID = "demo-ovigia";
    private static final String TRADE_PENDING = "pending";
    private static final String TRADE_ACCEPTED = "accepted";

    private final Context context;
    private final String emulatorHost;
    private FirebaseAuth auth;
    private FirebaseFirestore db;

    /** @param emulatorHost host do Firebase Local Emulator Suite, ou vazio para o projeto real */
    public FirebaseSocialBackend(Context context, String emulatorHost) {
        this.context = context.getApplicationContext();
        this.emulatorHost = emulatorHost == null ? "" : emulatorHost.trim();
    }

    @Override
    public boolean isConfigured() {
        return !emulatorHost.isEmpty() || !FirebaseApp.getApps(context).isEmpty();
    }

    /** Liga Auth e Firestore na primeira operação (ler a sessão salva toca o disco). */
    private synchronized void init() throws SocialException {
        if (auth != null) return;
        if (!isConfigured()) throw new SocialException(SocialException.Error.NOT_CONFIGURED);
        FirebaseApp app;
        if (!FirebaseApp.getApps(context).isEmpty()) {
            app = FirebaseApp.getInstance();
        } else {
            app = FirebaseApp.initializeApp(context, new FirebaseOptions.Builder()
                    .setProjectId(EMULATOR_PROJECT_ID)
                    .setApplicationId("1:000000000000:android:0000000000000000")
                    .setApiKey("emulator")
                    .build());
        }
        FirebaseAuth newAuth = FirebaseAuth.getInstance(app);
        FirebaseFirestore newDb = FirebaseFirestore.getInstance(app);
        if (!emulatorHost.isEmpty()) {
            newAuth.useEmulator(emulatorHost, 9099);
            newDb.useEmulator(emulatorHost, 8080);
        }
        newDb.setFirestoreSettings(new FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build());
        auth = newAuth;
        db = newDb;
    }

    // ---------------------------------------------------------------- sessão

    @Nullable
    @Override
    public String signedInUid() {
        try {
            init();
        } catch (SocialException e) {
            return null;
        }
        FirebaseUser user = auth.getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    @Override
    public String signIn(String email, String password, boolean createIfMissing) throws SocialException {
        init();
        try {
            return Tasks.await(auth.signInWithEmailAndPassword(email, password), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .getUser().getUid();
        } catch (ExecutionException e) {
            // Com a proteção contra enumeração de e-mails, "senha errada" e "conta
            // não existe" chegam como o mesmo erro: só criando dá para saber.
            if (!createIfMissing || !isBadCredentials(e.getCause())) throw mapped(e);
        } catch (InterruptedException | TimeoutException e) {
            throw mapped(e);
        }
        try {
            return Tasks.await(auth.createUserWithEmailAndPassword(email, password), TIMEOUT_SECONDS,
                    TimeUnit.SECONDS).getUser().getUid();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof FirebaseAuthUserCollisionException) {
                throw new SocialException(SocialException.Error.WRONG_PASSWORD, e);
            }
            throw mapped(e);
        } catch (InterruptedException | TimeoutException e) {
            throw mapped(e);
        }
    }

    @Override
    public void signOut() {
        try {
            init();
            auth.signOut();
        } catch (SocialException ignored) {
            // Sem servidor não há sessão para encerrar.
        }
    }

    // ---------------------------------------------------------------- cartões

    @Nullable
    @Override
    public UserCard loadCard(String uid) throws SocialException {
        init();
        DocumentSnapshot doc = await(users().document(uid).get());
        return doc.exists() ? cardFrom(doc) : null;
    }

    @Override
    public void claimUsername(UserCard card, @Nullable String previousUsername) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(card.uid)) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        DocumentReference nameRef = usernames().document(card.username);
        DocumentReference userRef = users().document(uid);
        await(db.runTransaction(tx -> {
            DocumentSnapshot existing = tx.get(nameRef);
            if (existing.exists() && !uid.equals(existing.getString("uid"))) {
                throw new FirebaseFirestoreException("@" + card.username + " já é de outra conta",
                        FirebaseFirestoreException.Code.ALREADY_EXISTS);
            }
            if (!existing.exists()) tx.set(nameRef, Collections.singletonMap("uid", uid));
            if (previousUsername != null && !previousUsername.equals(card.username)) {
                tx.delete(usernames().document(previousUsername));
            }
            tx.set(userRef, cardData(card));
            return null;
        }));
    }

    @Override
    public void publish(PublicProfile profile) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(profile.card.uid)) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        WriteBatch batch = db.batch();
        batch.set(users().document(uid), cardData(profile.card));
        batch.set(profiles().document(uid), profileData(profile));
        await(batch.commit());
    }

    @Nullable
    @Override
    public UserCard findByUsername(String username) throws SocialException {
        requireUid();
        DocumentSnapshot name = await(usernames().document(username).get());
        String uid = name.exists() ? name.getString("uid") : null;
        return uid == null ? null : loadCard(uid);
    }

    // ---------------------------------------------------------------- amizades

    @Override
    public FriendsHub loadHub() throws SocialException {
        String uid = requireUid();
        Task<QuerySnapshot> friendsTask = friendsOf(uid).get();
        Task<QuerySnapshot> incomingTask = requests().whereEqualTo("to", uid).get();
        Task<QuerySnapshot> outgoingTask = requests().whereEqualTo("from", uid).get();

        List<String> friendIds = new ArrayList<>();
        for (DocumentSnapshot doc : await(friendsTask).getDocuments()) friendIds.add(doc.getId());
        List<UserCard> friends = loadCards(friendIds);
        Collator collator = Collator.getInstance(Locale.getDefault());
        friends.sort((a, b) -> collator.compare(String.valueOf(a.name), String.valueOf(b.name)));

        List<FriendRequest> incoming = requestsFrom(await(incomingTask));
        List<FriendRequest> outgoing = requestsFrom(await(outgoingTask));
        return new FriendsHub(friends, incoming, outgoing);
    }

    @Override
    public void sendRequest(UserCard from, UserCard to) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(from.uid)) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        Map<String, Object> data = new HashMap<>();
        data.put("from", from.uid);
        data.put("to", to.uid);
        data.put("fromName", from.name);
        data.put("fromUsername", from.username);
        data.put("fromAvatar", from.avatar);
        data.put("toName", to.name);
        data.put("toUsername", to.username);
        data.put("toAvatar", to.avatar);
        data.put("createdAt", System.currentTimeMillis());
        await(requests().document(requestId(from.uid, to.uid)).set(data));
    }

    @Override
    public void acceptRequest(String fromUid) throws SocialException {
        String uid = requireUid();
        Map<String, Object> since = Collections.singletonMap("since", System.currentTimeMillis());
        // As regras só deixam criar a amizade enquanto o pedido existe: tudo num lote só.
        WriteBatch batch = db.batch();
        batch.set(friendsOf(uid).document(fromUid), since);
        batch.set(friendsOf(fromUid).document(uid), since);
        batch.delete(requests().document(requestId(fromUid, uid)));
        await(batch.commit());
    }

    @Override
    public void deleteRequest(String fromUid, String toUid) throws SocialException {
        requireUid();
        await(requests().document(requestId(fromUid, toUid)).delete());
    }

    @Override
    public void removeFriend(String friendUid) throws SocialException {
        String uid = requireUid();
        WriteBatch batch = db.batch();
        batch.delete(friendsOf(uid).document(friendUid));
        batch.delete(friendsOf(friendUid).document(uid));
        await(batch.commit());
    }

    // ---------------------------------------------------------------- trocas

    @Override
    public List<TradeOffer> loadTrades() throws SocialException {
        String uid = requireUid();
        Task<QuerySnapshot> sentTask = trades().whereEqualTo("from", uid).get();
        Task<QuerySnapshot> receivedTask = trades().whereEqualTo("to", uid).get();
        List<TradeOffer> list = tradesFrom(await(sentTask));
        list.addAll(tradesFrom(await(receivedTask)));
        list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return list;
    }

    @Override
    public void proposeTrade(TradeOffer trade) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(trade.from.uid)) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        Map<String, Object> data = new HashMap<>();
        data.put("from", trade.from.uid);
        data.put("to", trade.to.uid);
        data.put("fromName", trade.from.name);
        data.put("fromUsername", trade.from.username);
        data.put("toName", trade.to.name);
        data.put("toUsername", trade.to.username);
        putHero(data, "want", trade.want);
        putHero(data, "offer", trade.offer);
        data.put("status", TRADE_PENDING);
        data.put("createdAt", System.currentTimeMillis());
        await(trades().document(trade.id).set(data));
    }

    @Override
    public void acceptTrade(String tradeId, PublicProfile.Hero chosenOffer) throws SocialException {
        requireUid();
        Map<String, Object> data = new HashMap<>();
        putHero(data, "offer", chosenOffer);
        data.put("status", TRADE_ACCEPTED);
        data.put("respondedAt", System.currentTimeMillis());
        await(trades().document(tradeId).update(data));
    }

    @Override
    public void deleteTrade(String tradeId) throws SocialException {
        requireUid();
        await(trades().document(tradeId).delete());
    }

    @Override
    public PublicProfile loadProfile(String uid) throws SocialException {
        requireUid();
        Task<DocumentSnapshot> cardTask = users().document(uid).get();
        Task<DocumentSnapshot> profileTask = profiles().document(uid).get();
        DocumentSnapshot cardDoc = await(cardTask);
        DocumentSnapshot profileDoc = await(profileTask);
        if (!cardDoc.exists() || !profileDoc.exists()) throw new SocialException(SocialException.Error.NOT_FOUND);
        return profileFrom(cardFrom(cardDoc), profileDoc);
    }

    // ---------------------------------------------------------------- conta

    @Override
    public void changePassword(String email, String currentPassword, String newPassword) throws SocialException {
        FirebaseUser user = reauthenticate(email, currentPassword);
        await(user.updatePassword(newPassword));
    }

    @Override
    public void deleteAccount(String email, String password) throws SocialException {
        FirebaseUser user = reauthenticate(email, password);
        String uid = user.getUid();
        DocumentSnapshot card = await(users().document(uid).get());
        QuerySnapshot friends = await(friendsOf(uid).get());
        QuerySnapshot incoming = await(requests().whereEqualTo("to", uid).get());
        QuerySnapshot outgoing = await(requests().whereEqualTo("from", uid).get());
        QuerySnapshot tradesSent = await(trades().whereEqualTo("from", uid).get());
        QuerySnapshot tradesReceived = await(trades().whereEqualTo("to", uid).get());

        WriteBatch batch = db.batch();
        for (DocumentSnapshot f : friends.getDocuments()) {
            batch.delete(friendsOf(f.getId()).document(uid));
            batch.delete(f.getReference());
        }
        for (DocumentSnapshot r : incoming.getDocuments()) batch.delete(r.getReference());
        for (DocumentSnapshot r : outgoing.getDocuments()) batch.delete(r.getReference());
        for (DocumentSnapshot t : tradesSent.getDocuments()) batch.delete(t.getReference());
        for (DocumentSnapshot t : tradesReceived.getDocuments()) batch.delete(t.getReference());
        batch.delete(profiles().document(uid));
        String username = card.exists() ? card.getString("username") : null;
        if (username != null) batch.delete(usernames().document(username));
        batch.delete(users().document(uid));
        await(batch.commit());
        await(user.delete());
    }

    /** Confirma a senha com o servidor; abre a sessão se ela não estiver aberta nessa conta. */
    private FirebaseUser reauthenticate(String email, String password) throws SocialException {
        init();
        FirebaseUser user = auth.getCurrentUser();
        if (user != null && email.equalsIgnoreCase(user.getEmail())) {
            try {
                Tasks.await(user.reauthenticate(EmailAuthProvider.getCredential(email, password)),
                        TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return user;
            } catch (ExecutionException e) {
                throw isBadCredentials(e.getCause())
                        ? new SocialException(SocialException.Error.WRONG_PASSWORD, e) : mapped(e);
            } catch (InterruptedException | TimeoutException e) {
                throw mapped(e);
            }
        }
        signIn(email, password, false);
        FirebaseUser signedIn = auth.getCurrentUser();
        if (signedIn == null) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        return signedIn;
    }

    // ---------------------------------------------------------------- apoio

    private String requireUid() throws SocialException {
        init();
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        return user.getUid();
    }

    private CollectionReference usernames() { return db.collection("usernames"); }

    private CollectionReference users() { return db.collection("users"); }

    private CollectionReference profiles() { return db.collection("profiles"); }

    private CollectionReference requests() { return db.collection("friendRequests"); }

    private CollectionReference trades() { return db.collection("trades"); }

    private CollectionReference friendsOf(String uid) { return users().document(uid).collection("friends"); }

    static String requestId(String fromUid, String toUid) {
        return fromUid + "_" + toUid;
    }

    private List<UserCard> loadCards(List<String> uids) throws SocialException {
        List<UserCard> cards = new ArrayList<>();
        for (int i = 0; i < uids.size(); i += IN_QUERY_LIMIT) {
            List<String> chunk = uids.subList(i, Math.min(uids.size(), i + IN_QUERY_LIMIT));
            // Contas excluídas simplesmente não voltam.
            for (DocumentSnapshot doc : await(users().whereIn(FieldPath.documentId(), chunk).get()).getDocuments()) {
                cards.add(cardFrom(doc));
            }
        }
        return cards;
    }

    private static Map<String, Object> cardData(UserCard card) {
        Map<String, Object> data = new HashMap<>();
        data.put("username", card.username);
        data.put("name", card.name);
        data.put("avatar", card.avatar);
        data.put("updatedAt", System.currentTimeMillis());
        return data;
    }

    private static UserCard cardFrom(DocumentSnapshot doc) {
        return new UserCard(doc.getId(), doc.getString("username"), doc.getString("name"), doc.getString("avatar"));
    }

    private static Map<String, Object> profileData(PublicProfile profile) {
        List<Map<String, Object>> heroes = new ArrayList<>();
        for (PublicProfile.Hero h : profile.heroes) {
            Map<String, Object> hero = new HashMap<>();
            hero.put("id", h.characterId);
            hero.put("name", h.name);
            hero.put("image", h.imageUrl);
            hero.put("at", h.unlockedAt);
            heroes.add(hero);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("bio", profile.bio);
        data.put("banner", profile.banner);
        data.put("gamesPlayed", profile.gamesPlayed);
        data.put("engineWins", profile.engineWins);
        data.put("distinctCharacters", profile.distinctCharacters);
        data.put("heroes", heroes);
        data.put("achievements", Achievements.toPublished(profile.achievements));
        data.put("updatedAt", profile.updatedAt);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static PublicProfile profileFrom(UserCard card, DocumentSnapshot doc) {
        List<PublicProfile.Hero> heroes = new ArrayList<>();
        Object rawHeroes = doc.get("heroes");
        if (rawHeroes instanceof List) {
            for (Object item : (List<Object>) rawHeroes) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> hero = (Map<String, Object>) item;
                Object name = hero.get("name");
                Object image = hero.get("image");
                heroes.add(new PublicProfile.Hero((int) asLong(hero.get("id")),
                        name instanceof String ? (String) name : null,
                        image instanceof String ? (String) image : null,
                        asLong(hero.get("at"))));
            }
        }
        heroes.sort((a, b) -> Long.compare(b.unlockedAt, a.unlockedAt));

        Map<String, Integer> achievements = new HashMap<>();
        Object rawAchievements = doc.get("achievements");
        if (rawAchievements instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) rawAchievements).entrySet()) {
                achievements.put(e.getKey(), (int) asLong(e.getValue()));
            }
        }
        return new PublicProfile(card, doc.getString("bio"), doc.getString("banner"),
                (int) asLong(doc.get("gamesPlayed")), (int) asLong(doc.get("engineWins")),
                (int) asLong(doc.get("distinctCharacters")), heroes, Achievements.fromPublished(achievements),
                asLong(doc.get("updatedAt")));
    }

    private static List<FriendRequest> requestsFrom(QuerySnapshot snapshot) {
        List<FriendRequest> list = new ArrayList<>();
        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            UserCard from = new UserCard(doc.getString("from"), doc.getString("fromUsername"),
                    doc.getString("fromName"), doc.getString("fromAvatar"));
            UserCard to = new UserCard(doc.getString("to"), doc.getString("toUsername"),
                    doc.getString("toName"), doc.getString("toAvatar"));
            if (from.uid == null || to.uid == null) continue;
            list.add(new FriendRequest(from, to, asLong(doc.get("createdAt"))));
        }
        list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return list;
    }

    /** Herói da troca em campos soltos ({@code wantId}, {@code wantName}…): as regras validam cada um. */
    private static void putHero(Map<String, Object> data, String prefix, PublicProfile.Hero hero) {
        data.put(prefix + "Id", hero.characterId);
        data.put(prefix + "Name", hero.name);
        data.put(prefix + "Image", hero.imageUrl);
    }

    private static PublicProfile.Hero heroFrom(DocumentSnapshot doc, String prefix) {
        return new PublicProfile.Hero((int) asLong(doc.get(prefix + "Id")), doc.getString(prefix + "Name"),
                doc.getString(prefix + "Image"), 0L);
    }

    private static List<TradeOffer> tradesFrom(QuerySnapshot snapshot) {
        List<TradeOffer> list = new ArrayList<>();
        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            String from = doc.getString("from");
            String to = doc.getString("to");
            if (from == null || to == null) continue;
            TradeOffer.Status status = TRADE_ACCEPTED.equals(doc.getString("status"))
                    ? TradeOffer.Status.ACCEPTED : TradeOffer.Status.PENDING;
            // Sem foto: a tela usa o cartão atual do amigo, que já vem com ela.
            list.add(new TradeOffer(doc.getId(),
                    new UserCard(from, doc.getString("fromUsername"), doc.getString("fromName"), null),
                    new UserCard(to, doc.getString("toUsername"), doc.getString("toName"), null),
                    heroFrom(doc, "want"), heroFrom(doc, "offer"), status, asLong(doc.get("createdAt"))));
        }
        return list;
    }

    private static long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static boolean isBadCredentials(Throwable cause) {
        return cause instanceof FirebaseAuthInvalidCredentialsException
                || cause instanceof FirebaseAuthInvalidUserException;
    }

    private static <T> T await(Task<T> task) throws SocialException {
        try {
            return Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            throw mapped(e);
        }
    }

    private static SocialException mapped(Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        SocialException.Error error;
        if (cause instanceof TimeoutException || cause instanceof InterruptedException
                || cause instanceof FirebaseNetworkException) {
            error = SocialException.Error.OFFLINE;
        } else if (isBadCredentials(cause)) {
            error = SocialException.Error.WRONG_PASSWORD;
        } else if (cause instanceof FirebaseFirestoreException) {
            switch (((FirebaseFirestoreException) cause).getCode()) {
                case UNAVAILABLE:
                case DEADLINE_EXCEEDED:
                    error = SocialException.Error.OFFLINE;
                    break;
                case PERMISSION_DENIED:
                case UNAUTHENTICATED:
                    error = SocialException.Error.PERMISSION_DENIED;
                    break;
                case NOT_FOUND:
                    error = SocialException.Error.NOT_FOUND;
                    break;
                case ALREADY_EXISTS:
                    error = SocialException.Error.USERNAME_TAKEN;
                    break;
                default:
                    error = SocialException.Error.UNKNOWN;
                    break;
            }
        } else {
            error = SocialException.Error.UNKNOWN;
        }
        if (error == SocialException.Error.UNKNOWN) Log.w(TAG, "Falha online inesperada", cause);
        return new SocialException(error, cause);
    }
}
