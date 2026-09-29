package com.ovigia.app.auth;

import android.util.Log;

import com.google.gson.Gson;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Contas de jogador e sessão, guardadas só neste aparelho num arquivo JSON:
 * dados de login, perfil (nome, bio, foto e banner) e a conta logada.
 *
 * Senhas nunca são gravadas: cada conta guarda um salt aleatório e o hash
 * PBKDF2-HMAC-SHA256 da senha. A comparação é feita em tempo constante.
 *
 * Todas as operações são bloqueantes (disco + derivação de chave, que é lenta
 * de propósito): chamar fora da main thread. Thread-safe.
 */
public final class AccountStore {

    private static final String TAG = "AccountStore";

    /** Iterações padrão do PBKDF2: lento o bastante para atrapalhar força bruta, rápido para o jogador. */
    public static final int DEFAULT_ITERATIONS = 120_000;
    /**
     * Mínimo das senhas antigas, de antes da {@link PasswordRules}: só vale ao
     * recriar neste aparelho uma conta que já existia no servidor, cuja senha
     * foi aceita com a regra da época.
     */
    static final int LEGACY_MIN_PASSWORD_LENGTH = 6;
    public static final int MAX_NAME_LENGTH = 40;
    public static final int MAX_BIO_LENGTH = 120;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Por que um cadastro ou login falhou. A UI traduz cada caso numa mensagem. */
    public enum Error {
        NAME_REQUIRED,
        NAME_TOO_LONG,
        BIO_TOO_LONG,
        INVALID_EMAIL,
        WEAK_PASSWORD,
        EMAIL_IN_USE,
        /** Login: e-mail ou senha não conferem (sem dizer qual). */
        WRONG_CREDENTIALS,
        /** Operação na conta logada: a senha atual informada não confere. */
        WRONG_PASSWORD,
        NOT_SIGNED_IN,
        /** Excluir a conta: a conta online (amigos) não pôde ser apagada por falta de conexão. */
        ONLINE_UNAVAILABLE
    }

    /** Imagens do perfil. */
    public enum ImageKind { AVATAR, BANNER }

    private final Supplier<File> fileSupplier;
    private final int iterations;
    private final SecureRandom random = new SecureRandom();
    private final Gson gson = new Gson();
    private State state;

    public AccountStore(Supplier<File> fileSupplier, int iterations) {
        this.fileSupplier = fileSupplier;
        this.iterations = iterations;
    }

    /** Carrega do disco na primeira chamada. */
    public synchronized void ensureLoaded() {
        if (state == null) state = load();
    }

    /** Conta com sessão aberta, ou {@code null} se ninguém está logado. */
    public synchronized Account currentAccount() {
        ensureLoaded();
        StoredAccount stored = findById(state.currentAccountId);
        return stored != null ? stored.toAccount() : null;
    }

    /** Cria a conta e já abre a sessão nela. */
    public synchronized Result signUp(String name, String email, String password) {
        ensureLoaded();
        String cleanName = name == null ? "" : name.trim();
        String cleanEmail = normalizeEmail(email);
        Error nameError = validateName(cleanName);
        if (nameError != null) return Result.failure(nameError);
        if (!EMAIL.matcher(cleanEmail).matches()) return Result.failure(Error.INVALID_EMAIL);
        if (!PasswordRules.isStrong(password)) return Result.failure(Error.WEAK_PASSWORD);
        if (findByEmail(cleanEmail) != null) return Result.failure(Error.EMAIL_IN_USE);

        StoredAccount stored = new StoredAccount();
        stored.id = UUID.randomUUID().toString();
        stored.name = cleanName;
        stored.email = cleanEmail;
        setPassword(stored, password);
        stored.createdAt = System.currentTimeMillis();

        state.accounts.add(stored);
        state.currentAccountId = stored.id;
        persist();
        return Result.success(stored.toAccount());
    }

    /** Abre a sessão se e-mail e senha conferem. */
    public synchronized Result signIn(String email, String password) {
        ensureLoaded();
        String cleanEmail = normalizeEmail(email);
        if (!EMAIL.matcher(cleanEmail).matches()) return Result.failure(Error.INVALID_EMAIL);
        StoredAccount stored = findByEmail(cleanEmail);
        // Mesma resposta para e-mail desconhecido e senha errada: não revela quem tem conta.
        if (stored == null || !passwordMatches(stored, password)) return Result.failure(Error.WRONG_CREDENTIALS);

        state.currentAccountId = stored.id;
        persist();
        return Result.success(stored.toAccount());
    }

    /**
     * Recria neste aparelho a conta de quem já tinha cadastro no servidor e abre
     * a sessão nela: mesmo e-mail e senha, com o perfil e a ligação online que
     * vieram de volta. Quem chama repõe foto, banner, coleção e números.
     *
     * Nome e bio vêm do servidor, então são ajustados ao limite em vez de
     * recusar a conta; o nome vazio cai para a parte do e-mail antes do @.
     */
    public synchronized Result restore(String name, String email, String password, String bio,
                                       String cloudUid, String cloudEmail, String username) {
        ensureLoaded();
        String cleanEmail = normalizeEmail(email);
        if (!EMAIL.matcher(cleanEmail).matches()) return Result.failure(Error.INVALID_EMAIL);
        // Senha que o servidor já aceitou: pode ser de antes da regra atual.
        if (password == null || password.length() < LEGACY_MIN_PASSWORD_LENGTH) {
            return Result.failure(Error.WEAK_PASSWORD);
        }
        if (findByEmail(cleanEmail) != null) return Result.failure(Error.EMAIL_IN_USE);

        StoredAccount stored = new StoredAccount();
        stored.id = UUID.randomUUID().toString();
        stored.name = clip(name, MAX_NAME_LENGTH, cleanEmail.substring(0, cleanEmail.indexOf('@')));
        stored.email = cleanEmail;
        stored.bio = clip(bio, MAX_BIO_LENGTH, null);
        setPassword(stored, password);
        stored.createdAt = System.currentTimeMillis();
        stored.cloudUid = cloudUid;
        stored.cloudEmail = cloudEmail != null ? cloudEmail : cleanEmail;
        stored.username = username;

        state.accounts.add(stored);
        state.currentAccountId = stored.id;
        persist();
        return Result.success(stored.toAccount());
    }

    /** Texto aparado no limite, ou {@code fallback} se ele ficar vazio. */
    private static String clip(String text, int maxLength, String fallback) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return fallback;
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength).trim();
    }

    public synchronized void signOut() {
        ensureLoaded();
        if (state.currentAccountId == null) return;
        state.currentAccountId = null;
        persist();
    }

    /**
     * Altera nome, bio e e-mail da conta logada de uma vez: ou tudo é válido e
     * gravado, ou nada muda. Trocar o e-mail exige a senha atual.
     */
    public synchronized Result updateProfile(String name, String bio, String email, String currentPassword) {
        ensureLoaded();
        StoredAccount stored = findById(state.currentAccountId);
        if (stored == null) return Result.failure(Error.NOT_SIGNED_IN);

        String cleanName = name == null ? "" : name.trim();
        String cleanBio = bio == null ? "" : bio.trim();
        String cleanEmail = normalizeEmail(email);
        Error nameError = validateName(cleanName);
        if (nameError != null) return Result.failure(nameError);
        if (cleanBio.length() > MAX_BIO_LENGTH) return Result.failure(Error.BIO_TOO_LONG);
        if (!EMAIL.matcher(cleanEmail).matches()) return Result.failure(Error.INVALID_EMAIL);
        if (!cleanEmail.equals(stored.email)) {
            if (findByEmail(cleanEmail) != null) return Result.failure(Error.EMAIL_IN_USE);
            if (!passwordMatches(stored, currentPassword)) return Result.failure(Error.WRONG_PASSWORD);
        }

        stored.name = cleanName;
        stored.bio = cleanBio.isEmpty() ? null : cleanBio;
        stored.email = cleanEmail;
        persist();
        return Result.success(stored.toAccount());
    }

    /** Troca a senha da conta logada, conferindo a atual. */
    public synchronized Result changePassword(String currentPassword, String newPassword) {
        ensureLoaded();
        StoredAccount stored = findById(state.currentAccountId);
        if (stored == null) return Result.failure(Error.NOT_SIGNED_IN);
        if (!passwordMatches(stored, currentPassword)) return Result.failure(Error.WRONG_PASSWORD);
        if (!PasswordRules.isStrong(newPassword)) return Result.failure(Error.WEAK_PASSWORD);

        setPassword(stored, newPassword);
        persist();
        return Result.success(stored.toAccount());
    }

    /**
     * Define (ou remove, com {@code null}) a foto ou o banner da conta logada.
     * {@code fileName} é relativo à pasta de imagens do perfil; apagar o arquivo
     * antigo fica com quem chama.
     */
    public synchronized Result setImage(ImageKind kind, String fileName) {
        ensureLoaded();
        StoredAccount stored = findById(state.currentAccountId);
        if (stored == null) return Result.failure(Error.NOT_SIGNED_IN);
        if (kind == ImageKind.AVATAR) {
            stored.avatarFile = fileName;
        } else {
            stored.bannerFile = fileName;
        }
        persist();
        return Result.success(stored.toAccount());
    }

    /**
     * Se este aparelho já tem uma conta com esse e-mail. Não diz nada sobre a
     * senha: serve para saber se vale a pena procurar a conta no servidor.
     */
    synchronized boolean knowsEmail(String email) {
        ensureLoaded();
        return findByEmail(normalizeEmail(email)) != null;
    }

    /** Se {@code password} é a senha da conta logada (sem alterar nada). */
    public synchronized boolean verifyPassword(String password) {
        ensureLoaded();
        StoredAccount stored = findById(state.currentAccountId);
        return stored != null && passwordMatches(stored, password);
    }

    /**
     * Liga a conta local à conta online ({@code cloudUid}) criada com
     * {@code cloudEmail}. O e-mail online fica guardado à parte: trocar o e-mail
     * local depois não desfaz a ligação.
     */
    public synchronized Result linkCloud(String accountId, String cloudUid, String cloudEmail) {
        ensureLoaded();
        StoredAccount stored = findById(accountId);
        if (stored == null) return Result.failure(Error.NOT_SIGNED_IN);
        if (!cloudUid.equals(stored.cloudUid)) stored.username = null;
        stored.cloudUid = cloudUid;
        stored.cloudEmail = cloudEmail;
        persist();
        return Result.success(stored.toAccount());
    }

    /** Guarda o @usuario reservado para a conta online (ou {@code null} para esquecê-lo). */
    public synchronized Result setUsername(String accountId, String username) {
        ensureLoaded();
        StoredAccount stored = findById(accountId);
        if (stored == null) return Result.failure(Error.NOT_SIGNED_IN);
        stored.username = username;
        persist();
        return Result.success(stored.toAccount());
    }

    /**
     * Exclui a conta logada, conferindo a senha, e encerra a sessão. O resultado
     * traz a conta excluída para quem chama apagar coleção e imagens.
     */
    public synchronized Result deleteCurrentAccount(String currentPassword) {
        ensureLoaded();
        StoredAccount stored = findById(state.currentAccountId);
        if (stored == null) return Result.failure(Error.NOT_SIGNED_IN);
        if (!passwordMatches(stored, currentPassword)) return Result.failure(Error.WRONG_PASSWORD);

        state.accounts.remove(stored);
        state.currentAccountId = null;
        persist();
        return Result.success(stored.toAccount());
    }

    private static Error validateName(String cleanName) {
        if (cleanName.isEmpty()) return Error.NAME_REQUIRED;
        if (cleanName.length() > MAX_NAME_LENGTH) return Error.NAME_TOO_LONG;
        return null;
    }

    private void setPassword(StoredAccount stored, String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        stored.salt = Base64.getEncoder().encodeToString(salt);
        stored.iterations = iterations;
        stored.hash = Base64.getEncoder().encodeToString(hash(password, salt, iterations));
    }

    private static boolean passwordMatches(StoredAccount stored, String password) {
        if (password == null) return false;
        byte[] expected = Base64.getDecoder().decode(stored.hash);
        byte[] actual = hash(password, Base64.getDecoder().decode(stored.salt), stored.iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private StoredAccount findByEmail(String email) {
        for (StoredAccount a : state.accounts) {
            if (a.email.equals(email)) return a;
        }
        return null;
    }

    private StoredAccount findById(String id) {
        if (id == null) return null;
        for (StoredAccount a : state.accounts) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }

    private static byte[] hash(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponível", e);
        } finally {
            spec.clearPassword();
        }
    }

    private State load() {
        File file = fileSupplier.get();
        if (!file.exists()) return new State();
        try {
            State loaded = gson.fromJson(AtomicFiles.readUtf8(file), State.class);
            if (loaded == null) return new State();
            if (loaded.accounts == null) loaded.accounts = new ArrayList<>();
            loaded.accounts.removeIf(a -> a == null || a.id == null || a.email == null
                    || a.salt == null || a.hash == null || a.iterations <= 0);
            return loaded;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Falha ao ler contas; começando do zero", e);
            return new State();
        }
    }

    /** Síncrono: login e logout só valem depois de gravados. */
    private void persist() {
        try {
            AtomicFiles.writeUtf8(fileSupplier.get(), gson.toJson(state, State.class));
        } catch (IOException e) {
            Log.w(TAG, "Falha ao salvar contas", e);
        }
    }

    /** Dados públicos de uma conta. Arquivos de imagem são relativos à pasta de imagens do perfil. */
    public static final class Account {
        public final String id;
        public final String name;
        public final String email;
        /** {@code null} quando não há bio. */
        public final String bio;
        public final String avatarFile;
        public final String bannerFile;
        /** Conta online ligada a esta (amigos), ou {@code null} se nunca conectou. */
        public final String cloudUid;
        /** E-mail usado para entrar na conta online. */
        public final String cloudEmail;
        /** @usuario reservado online, ou {@code null} se ainda não escolheu. */
        public final String username;

        Account(String id, String name, String email, String bio, String avatarFile, String bannerFile,
                String cloudUid, String cloudEmail, String username) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.bio = bio;
            this.avatarFile = avatarFile;
            this.bannerFile = bannerFile;
            this.cloudUid = cloudUid;
            this.cloudEmail = cloudEmail;
            this.username = username;
        }

        public String imageFile(ImageKind kind) {
            return kind == ImageKind.AVATAR ? avatarFile : bannerFile;
        }
    }

    /** Resultado de cadastro ou login: {@link #account} ou {@link #error}. */
    public static final class Result {
        public final Account account;
        public final Error error;

        private Result(Account account, Error error) {
            this.account = account;
            this.error = error;
        }

        static Result success(Account account) {
            return new Result(account, null);
        }

        static Result failure(Error error) {
            return new Result(null, error);
        }

        public boolean isSuccess() {
            return account != null;
        }
    }

    /** Formato serializado de uma conta. */
    private static final class StoredAccount {
        String id;
        String name;
        String email;
        String salt;
        String hash;
        int iterations;
        long createdAt;
        String bio;
        String avatarFile;
        String bannerFile;
        String cloudUid;
        String cloudEmail;
        String username;

        Account toAccount() {
            return new Account(id, name, email, bio, avatarFile, bannerFile, cloudUid, cloudEmail, username);
        }
    }

    /** Formato serializado. */
    private static final class State {
        List<StoredAccount> accounts = new ArrayList<>();
        String currentAccountId;
    }
}
