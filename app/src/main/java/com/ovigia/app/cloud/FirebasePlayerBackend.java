package com.ovigia.app.cloud;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;
import com.ovigia.app.cloud.CloudException.Reason;
import com.ovigia.app.learning.LearningStore.AnswerRecord;
import com.ovigia.app.learning.LearningStore.Outcome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link PlayerBackend} com Firebase Auth (e-mail e senha) e Cloud Firestore.
 *
 * Documentos (as permissões estão em {@code firestore.rules}; só o dono lê e grava):
 * <ul>
 *   <li>{@code accounts/{uid}}: nome, bio, foto, banner, @usuario e conquistas comemoradas.</li>
 *   <li>{@code accounts/{uid}/heroes/{characterId}}: um documento por herói desbloqueado.</li>
 *   <li>{@code accounts/{uid}/sealed/{characterId}}: lendários encontrados, à espera do Vigia do Infinito.</li>
 *   <li>{@code accounts/{uid}/games/{id}}: histórico de partidas com personagem conhecido.</li>
 *   <li>{@code accounts/{uid}/data/learning}: números, favoritos e crenças aprendidas — somados
 *   no servidor ({@link FieldValue#increment}), então dois aparelhos não se atropelam.</li>
 * </ul>
 *
 * O que é da conta, o perfil e o cadastro público dos amigos ({@code users},
 * {@code profiles}) é publicado a partir daqui pelo {@code SocialRepository}.
 */
public final class FirebasePlayerBackend implements PlayerBackend {

    private static final String TAG = "FirebasePlayerBackend";
    private static final long TIMEOUT_SECONDS = 15;
    /** Leitura com rede lenta: depois disso vale o que está no cache do aparelho. */
    private static final long READ_TIMEOUT_SECONDS = 8;
    /** Máximo de gravações num lote do Firestore (o limite é 500; sobra folga). */
    private static final int BATCH_LIMIT = 400;

    private final FirebaseServices services;

    public FirebasePlayerBackend(FirebaseServices services) {
        this.services = services;
    }

    @Override
    public boolean isConfigured() {
        return services.isConfigured();
    }

    // ---------------------------------------------------------------- sessão

    @Nullable
    @Override
    public Session currentSession() {
        if (!isConfigured()) return null;
        try {
            FirebaseUser user = services.auth().getCurrentUser();
            return user == null ? null : new Session(user.getUid(), user.getEmail());
        } catch (CloudException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public Session signIn(String email, String password) throws CloudException {
        AuthResult result = awaitAuth(services.auth().signInWithEmailAndPassword(email, password), false);
        return sessionOf(result.getUser());
    }

    @Override
    public Session signUp(String email, String password) throws CloudException {
        AuthResult result = awaitAuth(services.auth().createUserWithEmailAndPassword(email, password), false);
        return sessionOf(result.getUser());
    }

    @Override
    public void signOut() {
        try {
            services.auth().signOut();
        } catch (CloudException | RuntimeException ignored) {
            // Sem servidor não há sessão para encerrar.
        }
    }

    @Override
    public void changePassword(String currentPassword, String newPassword) throws CloudException {
        FirebaseUser user = reauthenticate(currentPassword);
        awaitAuth(user.updatePassword(newPassword), true);
    }

    @Override
    public void requestEmailChange(String currentPassword, String newEmail) throws CloudException {
        FirebaseUser user = reauthenticate(currentPassword);
        awaitAuth(user.verifyBeforeUpdateEmail(newEmail), true);
    }

    @Override
    public void deleteAccount(String password) throws CloudException {
        FirebaseUser user = reauthenticate(password);
        String uid = user.getUid();
        FirebaseFirestore db = services.db();
        List<DocumentReference> doomed = new ArrayList<>();

        // Os dados do jogador.
        DocumentReference account = accounts(db).document(uid);
        doomed.addAll(refs(server(account.collection("heroes"))));
        doomed.addAll(refs(server(account.collection("sealed"))));
        doomed.addAll(refs(server(account.collection("games"))));
        doomed.add(learningDoc(db, uid));
        doomed.add(account);

        // E o que os amigos viam: amizades dos dois lados, pedidos, trocas, perfil e @usuario.
        CollectionReference friends = db.collection("users").document(uid).collection("friends");
        for (DocumentSnapshot f : server(friends).getDocuments()) {
            doomed.add(db.collection("users").document(f.getId()).collection("friends").document(uid));
            doomed.add(f.getReference());
        }
        doomed.addAll(refs(server(db.collection("friendRequests").whereEqualTo("to", uid))));
        doomed.addAll(refs(server(db.collection("friendRequests").whereEqualTo("from", uid))));
        doomed.addAll(refs(server(db.collection("trades").whereEqualTo("from", uid))));
        doomed.addAll(refs(server(db.collection("trades").whereEqualTo("to", uid))));
        doomed.add(db.collection("profiles").document(uid));
        DocumentSnapshot card = await(db.collection("users").document(uid).get(Source.SERVER));
        String username = card.exists() ? card.getString("username") : null;
        if (username != null) doomed.add(db.collection("usernames").document(username));
        doomed.add(db.collection("users").document(uid));

        for (int i = 0; i < doomed.size(); i += BATCH_LIMIT) {
            WriteBatch batch = db.batch();
            for (DocumentReference ref : doomed.subList(i, Math.min(doomed.size(), i + BATCH_LIMIT))) {
                batch.delete(ref);
            }
            await(batch.commit());
        }
        awaitAuth(user.delete(), true);
    }

    /** Confirma a senha da conta logada com o servidor (trocar senha, e-mail ou excluir exigem login recente). */
    private FirebaseUser reauthenticate(String password) throws CloudException {
        FirebaseUser user = services.auth().getCurrentUser();
        if (user == null || user.getEmail() == null) throw new CloudException(Reason.NOT_SIGNED_IN);
        awaitAuth(user.reauthenticate(EmailAuthProvider.getCredential(user.getEmail(), password)), true);
        return user;
    }

    // ---------------------------------------------------------------- perfil

    @Nullable
    @Override
    public Account loadAccount(String uid) throws CloudException {
        DocumentSnapshot doc = read(accounts(services.db()).document(uid));
        if (!doc.exists()) {
            // Sem rede e sem cópia no aparelho não dá para saber se a conta tem dados:
            // dizer "não tem" levaria quem chama a criar um perfil vazio por cima do de verdade.
            if (doc.getMetadata().isFromCache()) throw new CloudException(Reason.OFFLINE);
            return null;
        }
        @SuppressWarnings("unchecked")
        List<String> celebrated = doc.get("celebrated") instanceof List ? (List<String>) doc.get("celebrated") : null;
        return new Account(stringOr(doc.getString("name"), ""), doc.getString("bio"), doc.getString("avatar"),
                doc.getString("banner"), doc.getString("username"), celebrated);
    }

    @Override
    public void saveAccount(String uid, Account account) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", account.name);
        data.put("bio", account.bio);
        data.put("avatar", account.avatar);
        data.put("banner", account.banner);
        data.put("username", account.username);
        data.put("updatedAt", System.currentTimeMillis());
        write(uid, db -> accounts(db).document(uid).set(data, SetOptions.merge()));
    }

    @Override
    public void addCelebrated(String uid, Collection<String> ids, boolean baseline) {
        if (ids.isEmpty() && !baseline) return;
        Map<String, Object> data = new HashMap<>();
        data.put("celebrated", FieldValue.arrayUnion(ids.toArray()));
        write(uid, db -> accounts(db).document(uid).set(data, SetOptions.merge()));
    }

    // ---------------------------------------------------------------- heróis

    @Override
    public List<Hero> loadHeroes(String uid) throws CloudException {
        List<Hero> heroes = new ArrayList<>();
        for (DocumentSnapshot doc : read(heroes(services.db(), uid)).getDocuments()) {
            int id = parseId(doc.getId());
            if (id < 0) continue;
            heroes.add(new Hero(id, stringOr(doc.getString("name"), ""), doc.getString("image"),
                    asLong(doc.get("at")), Boolean.TRUE.equals(doc.getBoolean("seen"))));
        }
        return heroes;
    }

    @Override
    public void saveHero(String uid, Hero hero) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", hero.name);
        data.put("image", hero.imageUrl);
        data.put("at", hero.unlockedAt);
        data.put("seen", hero.seen);
        write(uid, db -> heroes(db, uid).document(String.valueOf(hero.characterId)).set(data));
    }

    @Override
    public void markHeroesSeen(String uid, Collection<Integer> characterIds) {
        if (characterIds.isEmpty()) return;
        write(uid, db -> {
            WriteBatch batch = db.batch();
            for (Integer id : characterIds) {
                batch.set(heroes(db, uid).document(String.valueOf(id)),
                        Collections.singletonMap("seen", true), SetOptions.merge());
            }
            return batch.commit();
        });
    }

    @Override
    public List<Hero> loadSealed(String uid) throws CloudException {
        List<Hero> heroes = new ArrayList<>();
        for (DocumentSnapshot doc : read(sealed(services.db(), uid)).getDocuments()) {
            int id = parseId(doc.getId());
            if (id < 0) continue;
            heroes.add(new Hero(id, stringOr(doc.getString("name"), ""), doc.getString("image"),
                    asLong(doc.get("at")), false));
        }
        return heroes;
    }

    @Override
    public void saveSealed(String uid, Hero hero) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", hero.name);
        data.put("image", hero.imageUrl);
        data.put("at", hero.unlockedAt);
        write(uid, db -> sealed(db, uid).document(String.valueOf(hero.characterId)).set(data));
    }

    @Override
    public void deleteSealed(String uid, Collection<Integer> characterIds) {
        if (characterIds.isEmpty()) return;
        write(uid, db -> {
            WriteBatch batch = db.batch();
            for (Integer id : characterIds) batch.delete(sealed(db, uid).document(String.valueOf(id)));
            return batch.commit();
        });
    }

    // ---------------------------------------------------------------- memória do Vigia

    @Override
    @SuppressWarnings("unchecked")
    public Learning loadLearning(String uid) throws CloudException {
        DocumentSnapshot doc = read(learningDoc(services.db(), uid));
        if (!doc.exists()) return Learning.empty();
        Map<Integer, Integer> picks = new HashMap<>();
        Object rawPicks = doc.get("picks");
        if (rawPicks instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) rawPicks).entrySet()) {
                int id = parseId(e.getKey());
                if (id >= 0) picks.put(id, (int) asLong(e.getValue()));
            }
        }
        Map<Integer, Map<String, double[]>> beliefs = new HashMap<>();
        Object rawBeliefs = doc.get("beliefs");
        if (rawBeliefs instanceof Map) {
            for (Map.Entry<String, Object> perCharacter : ((Map<String, Object>) rawBeliefs).entrySet()) {
                int id = parseId(perCharacter.getKey());
                if (id < 0 || !(perCharacter.getValue() instanceof Map)) continue;
                Map<String, double[]> attrs = new HashMap<>();
                for (Map.Entry<String, Object> attr : ((Map<String, Object>) perCharacter.getValue()).entrySet()) {
                    if (!(attr.getValue() instanceof Map)) continue;
                    Map<String, Object> sumCount = (Map<String, Object>) attr.getValue();
                    attrs.put(attr.getKey(), new double[]{asDouble(sumCount.get("s")), asDouble(sumCount.get("n"))});
                }
                beliefs.put(id, attrs);
            }
        }
        return new Learning((int) asLong(doc.get("gamesPlayed")), (int) asLong(doc.get("engineWins")), picks, beliefs);
    }

    @Override
    public void recordGame(String uid, @Nullable Game game, boolean engineWin) {
        Map<String, Object> learning = new HashMap<>();
        learning.put("gamesPlayed", FieldValue.increment(1));
        if (engineWin) learning.put("engineWins", FieldValue.increment(1));
        if (game != null) {
            String id = String.valueOf(game.characterId);
            learning.put("picks", Collections.singletonMap(id, FieldValue.increment(1)));
            learning.put("beliefs", Collections.singletonMap(id, beliefIncrements(game.answers)));
        }
        write(uid, db -> {
            WriteBatch batch = db.batch();
            batch.set(learningDoc(db, uid), learning, SetOptions.merge());
            if (game != null) batch.set(games(db, uid).document(), gameData(game));
            return batch.commit();
        });
    }

    @Override
    public List<Game> recentGames(String uid, int limit) throws CloudException {
        List<Game> games = new ArrayList<>();
        Query query = games(services.db(), uid).orderBy("at", Query.Direction.DESCENDING).limit(limit);
        for (DocumentSnapshot doc : read(query).getDocuments()) {
            Game game = gameFrom(doc);
            if (game != null) games.add(game);
        }
        return games;
    }

    @Override
    public void resetLearning(String uid) {
        try {
            FirebaseFirestore db = services.db();
            List<DocumentReference> doomed = refs(read(games(db, uid)));
            doomed.add(learningDoc(db, uid));
            for (int i = 0; i < doomed.size(); i += BATCH_LIMIT) {
                WriteBatch batch = db.batch();
                for (DocumentReference ref : doomed.subList(i, Math.min(doomed.size(), i + BATCH_LIMIT))) {
                    batch.delete(ref);
                }
                logFailure(batch.commit());
            }
        } catch (CloudException | RuntimeException e) {
            Log.w(TAG, "Não foi possível esquecer o aprendizado", e);
        }
    }

    @Override
    public void importLearning(String uid, Learning imported, List<Game> importedGames) {
        Map<String, Object> learning = new HashMap<>();
        // Os totais antigos podem já estar contados no servidor: fica o maior, sem somar em dobro.
        learning.put("gamesPlayed", FieldValue.maximum(imported.gamesPlayed));
        learning.put("engineWins", FieldValue.maximum(imported.engineWins));
        Map<String, Object> picks = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : imported.picks.entrySet()) {
            picks.put(String.valueOf(e.getKey()), FieldValue.increment(e.getValue()));
        }
        learning.put("picks", picks);
        Map<String, Object> beliefs = new HashMap<>();
        for (Map.Entry<Integer, Map<String, double[]>> e : imported.beliefs.entrySet()) {
            Map<String, Object> attrs = new HashMap<>();
            for (Map.Entry<String, double[]> attr : e.getValue().entrySet()) {
                Map<String, Object> sumCount = new HashMap<>();
                sumCount.put("s", FieldValue.increment(attr.getValue()[0]));
                sumCount.put("n", FieldValue.increment(attr.getValue()[1]));
                attrs.put(attr.getKey(), sumCount);
            }
            beliefs.put(String.valueOf(e.getKey()), attrs);
        }
        learning.put("beliefs", beliefs);

        write(uid, db -> {
            WriteBatch batch = db.batch();
            batch.set(learningDoc(db, uid), learning, SetOptions.merge());
            int inBatch = 1;
            for (Game game : importedGames) {
                if (inBatch >= BATCH_LIMIT) {
                    logFailure(batch.commit());
                    batch = db.batch();
                    inBatch = 0;
                }
                batch.set(games(db, uid).document(), gameData(game));
                inBatch++;
            }
            return batch.commit();
        });
    }

    // ---------------------------------------------------------------- apoio

    private interface Write {
        Task<Void> run(FirebaseFirestore db);
    }

    /**
     * Grava sem esperar o servidor: o Firestore aplica na cópia do aparelho na
     * hora (as leituras seguintes já veem) e sobe quando houver rede.
     */
    private void write(String uid, Write write) {
        try {
            logFailure(write.run(services.db()));
        } catch (CloudException | RuntimeException e) {
            Log.w(TAG, "Gravação recusada para " + uid, e);
        }
    }

    private static void logFailure(Task<Void> task) {
        task.addOnFailureListener(e -> Log.w(TAG, "O servidor recusou uma gravação", e));
    }

    private static Map<String, Object> beliefIncrements(List<AnswerRecord> answers) {
        Map<String, Object> attrs = new HashMap<>();
        Map<String, double[]> summed = new HashMap<>();
        for (AnswerRecord a : answers) {
            double[] sumCount = summed.computeIfAbsent(a.key, k -> new double[2]);
            sumCount[0] += a.value;
            sumCount[1] += 1;
        }
        for (Map.Entry<String, double[]> e : summed.entrySet()) {
            Map<String, Object> sumCount = new HashMap<>();
            sumCount.put("s", FieldValue.increment(e.getValue()[0]));
            sumCount.put("n", FieldValue.increment(e.getValue()[1]));
            attrs.put(e.getKey(), sumCount);
        }
        return attrs;
    }

    private static Map<String, Object> gameData(Game game) {
        List<Map<String, Object>> answers = new ArrayList<>();
        for (AnswerRecord a : game.answers) {
            Map<String, Object> answer = new HashMap<>();
            answer.put("k", a.key);
            answer.put("v", a.value);
            answers.add(answer);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("at", game.timestamp);
        data.put("characterId", game.characterId);
        data.put("outcome", game.outcome.name());
        data.put("answers", answers);
        return data;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Game gameFrom(DocumentSnapshot doc) {
        Outcome outcome;
        try {
            outcome = Outcome.valueOf(stringOr(doc.getString("outcome"), ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
        List<AnswerRecord> answers = new ArrayList<>();
        Object raw = doc.get("answers");
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> answer = (Map<String, Object>) item;
                Object key = answer.get("k");
                if (key instanceof String) answers.add(new AnswerRecord((String) key, asDouble(answer.get("v"))));
            }
        }
        return new Game(asLong(doc.get("at")), (int) asLong(doc.get("characterId")), outcome, answers);
    }

    private static CollectionReference accounts(FirebaseFirestore db) {
        return db.collection("accounts");
    }

    private static CollectionReference heroes(FirebaseFirestore db, String uid) {
        return accounts(db).document(uid).collection("heroes");
    }

    private static CollectionReference sealed(FirebaseFirestore db, String uid) {
        return accounts(db).document(uid).collection("sealed");
    }

    private static CollectionReference games(FirebaseFirestore db, String uid) {
        return accounts(db).document(uid).collection("games");
    }

    private static DocumentReference learningDoc(FirebaseFirestore db, String uid) {
        return accounts(db).document(uid).collection("data").document("learning");
    }

    private static Session sessionOf(@Nullable FirebaseUser user) throws CloudException {
        if (user == null) throw new CloudException(Reason.NOT_SIGNED_IN);
        return new Session(user.getUid(), user.getEmail());
    }

    private static List<DocumentReference> refs(QuerySnapshot snapshot) {
        List<DocumentReference> refs = new ArrayList<>();
        for (DocumentSnapshot doc : snapshot.getDocuments()) refs.add(doc.getReference());
        return refs;
    }

    private static int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String stringOr(@Nullable String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0d;
    }

    /** Lê do servidor; sem rede (ou com rede lenta demais), da cópia no aparelho. */
    private static DocumentSnapshot read(DocumentReference ref) throws CloudException {
        try {
            return Tasks.await(ref.get(), READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            return await(ref.get(Source.CACHE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudException(Reason.OFFLINE, e);
        }
    }

    private static QuerySnapshot read(Query query) throws CloudException {
        try {
            return Tasks.await(query.get(), READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            return await(query.get(Source.CACHE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudException(Reason.OFFLINE, e);
        }
    }

    /** Só do servidor: para apagar a conta é preciso a lista de verdade, não a do cache. */
    private static QuerySnapshot server(Query query) throws CloudException {
        return await(query.get(Source.SERVER));
    }

    private static <T> T await(Task<T> task) throws CloudException {
        try {
            return Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw mapped(e.getCause() != null ? e.getCause() : e, false);
        } catch (TimeoutException e) {
            throw new CloudException(Reason.OFFLINE, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudException(Reason.OFFLINE, e);
        }
    }

    /** @param reauth a senha conferida é a da conta logada (errar é WRONG_PASSWORD, não WRONG_CREDENTIALS) */
    private static <T> T awaitAuth(Task<T> task, boolean reauth) throws CloudException {
        try {
            return Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw mapped(e.getCause() != null ? e.getCause() : e, reauth);
        } catch (TimeoutException e) {
            throw new CloudException(Reason.OFFLINE, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudException(Reason.OFFLINE, e);
        }
    }

    private static CloudException mapped(Throwable cause, boolean reauth) {
        Reason reason;
        if (cause instanceof FirebaseNetworkException) {
            reason = Reason.OFFLINE;
        } else if (cause instanceof FirebaseTooManyRequestsException) {
            reason = Reason.TOO_MANY_ATTEMPTS;
        } else if (cause instanceof FirebaseAuthWeakPasswordException) {
            reason = Reason.WEAK_PASSWORD;
        } else if (cause instanceof FirebaseAuthUserCollisionException) {
            reason = Reason.EMAIL_IN_USE;
        } else if (cause instanceof FirebaseAuthInvalidCredentialsException) {
            String code = ((FirebaseAuthException) cause).getErrorCode();
            reason = "ERROR_INVALID_EMAIL".equals(code) ? Reason.INVALID_EMAIL
                    : reauth ? Reason.WRONG_PASSWORD : Reason.WRONG_CREDENTIALS;
        } else if (cause instanceof FirebaseAuthInvalidUserException) {
            reason = reauth ? Reason.NOT_SIGNED_IN : Reason.WRONG_CREDENTIALS;
        } else if (cause instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) cause).getCode();
            reason = code == FirebaseFirestoreException.Code.UNAVAILABLE
                    || code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ? Reason.OFFLINE : Reason.FAILED;
        } else {
            reason = Reason.FAILED;
        }
        if (reason == Reason.FAILED) Log.w(TAG, "Falha inesperada no servidor da conta", cause);
        return new CloudException(reason, cause);
    }
}
