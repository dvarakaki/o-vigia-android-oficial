package com.ovigia.app.legacy;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ovigia.app.learning.LearningStore.AnswerRecord;
import com.ovigia.app.learning.LearningStore.Outcome;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * O que as versões até a 1.3 guardavam em arquivos do app — contas locais,
 * coleção, memória do Vigia, conquistas comemoradas e as imagens do perfil —,
 * lido só para ser levado para a conta online ({@link LegacyMigration}) e então
 * apagado.
 *
 * Os arquivos são lidos como árvore JSON, campo a campo, e não por reflexão:
 * assim não dependem das regras do R8, que nas versões 1.1.0 a 1.2.2 renomearam
 * campos ({@code a}..{@code f}) e chegaram a gravar chaves repetidas.
 *
 * Bloqueante (disco): chamar fora da main thread. Thread-safe.
 */
public final class LegacyData {

    private static final String TAG = "LegacyData";

    /** Nomes que o R8 deu aos campos do bloco de aprendizado de cada conta (1.1.0 a 1.2.2), na ordem. */
    private static final String[] OBFUSCATED_KEYS = {"a", "b", "c", "d", "e", "f"};
    private static final String[] REAL_KEYS =
            {"picksById", "beliefsById", "gameLog", "gamesPlayed", "engineWins", "baseDistinctCharacters"};

    private static final String ACCOUNTS = "accounts.json";
    private static final String COLLECTION = "collection.json";
    private static final String LEARNING = "learning_store.json";
    private static final String ACHIEVEMENTS = "achievements.json";
    private static final String MEDIA = "profile_media";
    private static final String PENDING_LINK = "pending_link.json";

    private final Supplier<File> filesDir;
    private final Supplier<File> noBackupDir;

    /** @param filesDir, noBackupDir resolvidos só fora da main thread (obter as pastas já toca o disco) */
    public LegacyData(Supplier<File> filesDir, Supplier<File> noBackupDir) {
        this.filesDir = filesDir;
        this.noBackupDir = noBackupDir;
    }

    /** Uma conta antiga deste aparelho. */
    public static final class Account {
        public final String id;
        public final String name;
        public final String email;
        @Nullable public final String bio;
        @Nullable public final String avatarFile;
        @Nullable public final String bannerFile;
        @Nullable public final String cloudUid;
        @Nullable public final String username;
        final String salt;
        final String hash;
        final int iterations;

        Account(String id, String name, String email, @Nullable String bio, @Nullable String avatarFile,
                @Nullable String bannerFile, @Nullable String cloudUid, @Nullable String username,
                String salt, String hash, int iterations) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.bio = bio;
            this.avatarFile = avatarFile;
            this.bannerFile = bannerFile;
            this.cloudUid = cloudUid;
            this.username = username;
            this.salt = salt;
            this.hash = hash;
            this.iterations = iterations;
        }
    }

    /** Herói da coleção antiga. */
    public static final class Hero {
        public final int characterId;
        public final String name;
        @Nullable public final String imageUrl;
        public final long savedAt;
        public final boolean seen;

        Hero(int characterId, String name, @Nullable String imageUrl, long savedAt, boolean seen) {
            this.characterId = characterId;
            this.name = name;
            this.imageUrl = imageUrl;
            this.savedAt = savedAt;
            this.seen = seen;
        }
    }

    /** Partida do histórico antigo. */
    public static final class Game {
        public final long timestamp;
        public final int characterId;
        public final Outcome outcome;
        public final List<AnswerRecord> answers;

        Game(long timestamp, int characterId, Outcome outcome, List<AnswerRecord> answers) {
            this.timestamp = timestamp;
            this.characterId = characterId;
            this.outcome = outcome;
            this.answers = answers;
        }
    }

    /** A memória do Vigia de uma conta antiga. */
    public static final class Learning {
        public final int gamesPlayed;
        public final int engineWins;
        public final Map<Integer, Integer> picks;
        public final Map<Integer, Map<String, double[]>> beliefs;
        public final List<Game> games;

        Learning(int gamesPlayed, int engineWins, Map<Integer, Integer> picks,
                 Map<Integer, Map<String, double[]>> beliefs, List<Game> games) {
            this.gamesPlayed = gamesPlayed;
            this.engineWins = engineWins;
            this.picks = picks;
            this.beliefs = beliefs;
            this.games = games;
        }

        public boolean isEmpty() {
            return gamesPlayed == 0 && picks.isEmpty() && games.isEmpty();
        }
    }

    // ---------------------------------------------------------------- contas

    /** Se ainda há algo das versões antigas no aparelho. */
    public synchronized boolean exists() {
        return new File(filesDir.get(), ACCOUNTS).exists();
    }

    /** A conta antiga ligada a esta conta online (pela ligação que ela guardava, ou pelo e-mail). */
    @Nullable
    public synchronized Account find(String uid, @Nullable String email) {
        List<Account> accounts = accounts();
        for (Account a : accounts) {
            if (uid.equals(a.cloudUid)) return a;
        }
        String clean = normalize(email);
        for (Account a : accounts) {
            if (!clean.isEmpty() && a.email.equals(clean)) return a;
        }
        return null;
    }

    /** A conta antiga com esse e-mail, se a senha for a dela (conta que nunca foi para o servidor). */
    @Nullable
    public synchronized Account findWithPassword(String email, String password) {
        String clean = normalize(email);
        for (Account a : accounts()) {
            if (a.email.equals(clean) && passwordMatches(a, password)) return a;
        }
        return null;
    }

    private List<Account> accounts() {
        List<Account> list = new ArrayList<>();
        JsonObject root = readObject(ACCOUNTS);
        if (root == null || !root.has("accounts") || !root.get("accounts").isJsonArray()) return list;
        for (JsonElement e : root.getAsJsonArray("accounts")) {
            if (!e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            String id = string(a, "id");
            String email = string(a, "email");
            String salt = string(a, "salt");
            String hash = string(a, "hash");
            int iterations = (int) number(a, "iterations");
            if (id == null || email == null || salt == null || hash == null || iterations <= 0) continue;
            String name = string(a, "name");
            list.add(new Account(id, name == null ? "" : name, email, string(a, "bio"), string(a, "avatarFile"),
                    string(a, "bannerFile"), string(a, "cloudUid"), string(a, "username"), salt, hash, iterations));
        }
        return list;
    }

    // ---------------------------------------------------------------- dados da conta

    public synchronized List<Hero> heroes(String accountId) {
        List<Hero> heroes = new ArrayList<>();
        JsonArray entries = accountArray(COLLECTION, accountId);
        if (entries == null) return heroes;
        for (JsonElement e : entries) {
            if (!e.isJsonObject()) continue;
            JsonObject h = e.getAsJsonObject();
            if (!h.has("characterId")) continue;
            String name = string(h, "name");
            heroes.add(new Hero((int) number(h, "characterId"), name == null ? "" : name, string(h, "imageUrl"),
                    number(h, "savedAt"), bool(h, "seenInCatalog")));
        }
        return heroes;
    }

    public synchronized Learning learning(String accountId) {
        JsonObject block = accountObject(LEARNING, accountId);
        Map<Integer, Integer> picks = new HashMap<>();
        Map<Integer, Map<String, double[]>> beliefs = new HashMap<>();
        List<Game> games = new ArrayList<>();
        if (block == null) return new Learning(0, 0, picks, beliefs, games);
        for (int i = 0; i < OBFUSCATED_KEYS.length; i++) {
            if (block.has(OBFUSCATED_KEYS[i]) && !block.has(REAL_KEYS[i])) {
                block.add(REAL_KEYS[i], block.remove(OBFUSCATED_KEYS[i]));
            }
        }
        JsonObject rawPicks = object(block, "picksById");
        if (rawPicks != null) {
            for (Map.Entry<String, JsonElement> e : rawPicks.entrySet()) {
                int id = parseId(e.getKey());
                if (id >= 0 && isNumber(e.getValue())) picks.put(id, e.getValue().getAsInt());
            }
        }
        JsonObject rawBeliefs = object(block, "beliefsById");
        if (rawBeliefs != null) {
            for (Map.Entry<String, JsonElement> perCharacter : rawBeliefs.entrySet()) {
                int id = parseId(perCharacter.getKey());
                if (id < 0 || !perCharacter.getValue().isJsonObject()) continue;
                Map<String, double[]> attrs = new HashMap<>();
                for (Map.Entry<String, JsonElement> attr : perCharacter.getValue().getAsJsonObject().entrySet()) {
                    if (!attr.getValue().isJsonArray() || attr.getValue().getAsJsonArray().size() < 2) continue;
                    JsonArray sumCount = attr.getValue().getAsJsonArray();
                    attrs.put(attr.getKey(), new double[]{sumCount.get(0).getAsDouble(), sumCount.get(1).getAsDouble()});
                }
                beliefs.put(id, attrs);
            }
        }
        if (block.has("gameLog") && block.get("gameLog").isJsonArray()) {
            for (JsonElement e : block.getAsJsonArray("gameLog")) {
                Game game = game(e);
                if (game != null) games.add(game);
            }
        }
        return new Learning((int) number(block, "gamesPlayed"), (int) number(block, "engineWins"),
                picks, beliefs, games);
    }

    /** Conquistas já comemoradas pela conta, ou {@code null} se ela nunca teve marco zero. */
    @Nullable
    public synchronized Set<String> celebrated(String accountId) {
        JsonArray ids = accountArray(ACHIEVEMENTS, accountId);
        if (ids == null) return null;
        Set<String> set = new LinkedHashSet<>();
        for (JsonElement e : ids) {
            if (e.isJsonPrimitive()) set.add(e.getAsString());
        }
        return set;
    }

    /** Arquivo de uma imagem antiga do perfil, ou {@code null} se ele não existe mais. */
    @Nullable
    public synchronized File image(@Nullable String fileName) {
        if (fileName == null) return null;
        File file = new File(new File(filesDir.get(), MEDIA), fileName);
        return file.exists() ? file : null;
    }

    // ---------------------------------------------------------------- limpeza

    /**
     * Tira a conta antiga (e tudo dela) dos arquivos. Quando não sobra conta
     * nenhuma, apaga os arquivos das versões antigas de vez.
     */
    public synchronized void remove(Account account) {
        List<Account> remaining = accounts();
        remaining.removeIf(a -> a.id.equals(account.id));
        if (remaining.isEmpty()) {
            deleteAll();
            return;
        }
        removeFromArray(ACCOUNTS, account.id);
        removeFromMap(COLLECTION, account.id);
        removeFromMap(LEARNING, account.id);
        removeFromMap(ACHIEVEMENTS, account.id);
        deleteImage(account.avatarFile);
        deleteImage(account.bannerFile);
    }

    private void deleteAll() {
        File dir = filesDir.get();
        for (String name : new String[]{ACCOUNTS, COLLECTION, LEARNING, ACHIEVEMENTS}) delete(new File(dir, name));
        File media = new File(dir, MEDIA);
        File[] images = media.listFiles();
        if (images != null) for (File f : images) delete(f);
        delete(media);
        delete(new File(noBackupDir.get(), PENDING_LINK));
    }

    private void deleteImage(@Nullable String fileName) {
        File file = image(fileName);
        if (file != null) delete(file);
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) Log.w(TAG, "Não foi possível apagar " + file.getName());
    }

    private void removeFromArray(String fileName, String accountId) {
        JsonObject root = readObject(fileName);
        if (root == null || !root.has("accounts") || !root.get("accounts").isJsonArray()) return;
        JsonArray kept = new JsonArray();
        for (JsonElement e : root.getAsJsonArray("accounts")) {
            if (!(e.isJsonObject() && accountId.equals(string(e.getAsJsonObject(), "id")))) kept.add(e);
        }
        root.add("accounts", kept);
        if (accountId.equals(string(root, "currentAccountId"))) root.remove("currentAccountId");
        write(fileName, root);
    }

    private void removeFromMap(String fileName, String accountId) {
        JsonObject root = readObject(fileName);
        JsonObject byAccount = root == null ? null : object(root, "byAccount");
        if (byAccount == null || byAccount.remove(accountId) == null) return;
        write(fileName, root);
    }

    // ---------------------------------------------------------------- JSON

    @Nullable
    private JsonObject readObject(String fileName) {
        File file = new File(filesDir.get(), fileName);
        if (!file.exists()) return null;
        try {
            JsonElement root = JsonParser.parseString(AtomicFiles.readUtf8(file));
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Arquivo antigo ilegível: " + fileName, e);
            return null;
        }
    }

    private void write(String fileName, JsonObject root) {
        try {
            AtomicFiles.writeUtf8(new File(filesDir.get(), fileName), root.toString());
        } catch (IOException e) {
            Log.w(TAG, "Não foi possível regravar " + fileName, e);
        }
    }

    @Nullable
    private JsonObject accountObject(String fileName, String accountId) {
        JsonObject root = readObject(fileName);
        JsonObject byAccount = root == null ? null : object(root, "byAccount");
        return byAccount == null ? null : object(byAccount, accountId);
    }

    @Nullable
    private JsonArray accountArray(String fileName, String accountId) {
        JsonObject root = readObject(fileName);
        JsonObject byAccount = root == null ? null : object(root, "byAccount");
        if (byAccount == null || !byAccount.has(accountId) || !byAccount.get(accountId).isJsonArray()) return null;
        return byAccount.getAsJsonArray(accountId);
    }

    @Nullable
    private static Game game(JsonElement e) {
        if (!e.isJsonObject()) return null;
        JsonObject g = e.getAsJsonObject();
        Outcome outcome;
        try {
            String name = string(g, "outcome");
            if (name == null) return null;
            outcome = Outcome.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        List<AnswerRecord> answers = new ArrayList<>();
        if (g.has("answers") && g.get("answers").isJsonArray()) {
            for (JsonElement a : g.getAsJsonArray("answers")) {
                if (!a.isJsonObject()) continue;
                JsonElement value = a.getAsJsonObject().get("value");
                String key = string(a.getAsJsonObject(), "key");
                if (key != null && isNumber(value)) answers.add(new AnswerRecord(key, value.getAsDouble()));
            }
        }
        return new Game(number(g, "timestamp"), (int) number(g, "correctId"), outcome, answers);
    }

    @Nullable
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    @Nullable
    private static String string(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static long number(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return isNumber(e) ? e.getAsLong() : 0L;
    }

    private static boolean bool(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static boolean isNumber(@Nullable JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static int parseId(String id) {
        try {
            return (int) Double.parseDouble(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String normalize(@Nullable String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** As contas antigas guardavam o hash PBKDF2-HMAC-SHA256 da senha, com salt próprio. */
    private static boolean passwordMatches(Account account, @Nullable String password) {
        if (password == null) return false;
        PBEKeySpec spec = null;
        try {
            byte[] salt = Base64.getDecoder().decode(account.salt);
            spec = new PBEKeySpec(password.toCharArray(), salt, account.iterations, 256);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(Base64.getDecoder().decode(account.hash), actual);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        } finally {
            if (spec != null) spec.clearPassword();
        }
    }
}
