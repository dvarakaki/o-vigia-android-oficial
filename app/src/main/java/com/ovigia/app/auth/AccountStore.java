package com.ovigia.app.auth;

import android.util.Log;

import androidx.annotation.Nullable;

import com.ovigia.app.cloud.CloudException;
import com.ovigia.app.cloud.PlayerBackend;
import com.ovigia.app.legacy.LegacyData;
import com.ovigia.app.legacy.LegacyMigration;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * A conta do jogador — que é a conta online (a API do O Vigia), a mesma em
 * qualquer aparelho e em qualquer versão do app. Nome, bio, foto, banner e
 * @usuario vêm do {@link PlayerBackend}.
 *
 * Criar a conta, entrar e mudar o perfil precisam de internet (é o servidor
 * quem confere e guarda). Depois de entrar, a sessão fica aberta e o jogo segue
 * sem rede, com a última cópia guardada no aparelho.
 *
 * Na primeira vez que uma conta aparece nesta versão, a {@link LegacyMigration}
 * traz para ela o que as versões antigas guardavam fora — inclusive contas que
 * só existiam neste aparelho, que ganham a conta online ao entrar com a senha de
 * sempre.
 *
 * Todas as operações são bloqueantes (rede): chamar fora da main thread, exceto
 * {@link #currentAccountId()}. Thread-safe.
 */
public final class AccountStore {

    private static final String TAG = "AccountStore";

    public static final int MAX_NAME_LENGTH = 40;
    public static final int MAX_BIO_LENGTH = 120;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Por que uma operação na conta falhou. A UI traduz cada caso numa mensagem. */
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
        /** Sem internet (criar conta, entrar, trocar senha ou e-mail e excluir precisam dela). */
        OFFLINE,
        /** Esta versão do app não tem servidor de contas configurado. */
        UNAVAILABLE,
        /** Tentativas demais em pouco tempo. */
        TOO_MANY_ATTEMPTS,
        FAILED
    }

    /** Imagens do perfil. */
    public enum ImageKind { AVATAR, BANNER }

    private final PlayerBackend backend;
    private final LegacyMigration migration;

    /** Quem guarda dados lidos da conta e precisa esquecê-los quando a sessão muda. */
    private final List<Runnable> sessionListeners = new CopyOnWriteArrayList<>();

    /** Dados da conta logada, lidos uma vez por sessão (e atualizados a cada alteração). */
    @Nullable private String cachedUid;
    @Nullable private PlayerBackend.Account cached;

    public AccountStore(PlayerBackend backend, LegacyMigration migration) {
        this.backend = backend;
        this.migration = migration;
    }

    /** Só a conta online, sem nada das versões antigas. */
    public AccountStore(PlayerBackend backend) {
        this(backend, LegacyMigration.none(backend));
    }

    /** O mesmo formato que o cadastro aceita. A mesma regra acende a verificação ao vivo no campo. */
    public static boolean isValidEmail(String email) {
        return EMAIL.matcher(normalizeEmail(email)).matches();
    }

    /** O e-mail como o login usa: sem espaços nas pontas e em minúsculas. */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Avisado (na thread de quem entrou ou saiu) quando a sessão muda: entrar, sair ou excluir a conta. */
    public void addSessionListener(Runnable listener) {
        sessionListeners.add(listener);
    }

    /** Se esta versão tem servidor de contas. */
    public boolean isAvailable() {
        return backend.isConfigured();
    }

    /** Id da conta com sessão aberta, ou {@code null}. Rápido (sem rede): pode ser chamado na main thread. */
    @Nullable
    public String currentAccountId() {
        PlayerBackend.Session session = backend.currentSession();
        return session == null ? null : session.uid;
    }

    /**
     * Conta com sessão aberta, ou {@code null} se ninguém entrou. Sem rede e sem
     * cópia no aparelho, devolve a conta só com o que a sessão sabe (e-mail).
     */
    @Nullable
    public synchronized Account currentAccount() {
        PlayerBackend.Session session = backend.currentSession();
        if (session == null) return null;
        PlayerBackend.Account data = load(session, null);
        return data == null ? offlineAccount(session) : toAccount(session, data);
    }

    /** Cria a conta e já abre a sessão nela. */
    public synchronized Result signUp(String name, String email, String password) {
        String cleanName = name == null ? "" : name.trim();
        String cleanEmail = normalizeEmail(email);
        Error nameError = validateName(cleanName);
        if (nameError != null) return Result.failure(nameError);
        if (!EMAIL.matcher(cleanEmail).matches()) return Result.failure(Error.INVALID_EMAIL);
        if (!PasswordRules.isStrong(password)) return Result.failure(Error.WEAK_PASSWORD);
        if (!backend.isConfigured()) return Result.failure(Error.UNAVAILABLE);
        try {
            PlayerBackend.Session session = backend.signUp(cleanEmail, password, cleanName);
            return opened(session, cleanName);
        } catch (CloudException e) {
            return Result.failure(errorOf(e));
        }
    }

    /**
     * Abre a sessão se e-mail e senha conferem. Uma conta que só existia neste
     * aparelho (versões antigas) ganha a conta online com a mesma senha.
     */
    public synchronized Result signIn(String email, String password) {
        String cleanEmail = normalizeEmail(email);
        if (!EMAIL.matcher(cleanEmail).matches()) return Result.failure(Error.INVALID_EMAIL);
        if (!backend.isConfigured()) return Result.failure(Error.UNAVAILABLE);
        PlayerBackend.Session session;
        try {
            session = backend.signIn(cleanEmail, password);
        } catch (CloudException e) {
            if (e.reason != CloudException.Reason.WRONG_CREDENTIALS) return Result.failure(errorOf(e));
            LegacyData.Account local = migration.findLocal(cleanEmail, password);
            if (local == null) return Result.failure(Error.WRONG_CREDENTIALS);
            try {
                session = backend.signUp(cleanEmail, password, localName(local, cleanEmail));
            } catch (CloudException created) {
                // Já existe conta online com esse e-mail, com outra senha: vale a de lá.
                return Result.failure(created.reason == CloudException.Reason.EMAIL_IN_USE
                        ? Error.WRONG_CREDENTIALS : errorOf(created));
            }
        }
        return opened(session, null);
    }

    public synchronized void signOut() {
        backend.signOut();
        sessionChanged();
    }

    /** Altera nome e bio da conta logada: ou os dois são válidos e gravados, ou nada muda. */
    public synchronized Result updateProfile(String name, String bio) {
        PlayerBackend.Session session = backend.currentSession();
        if (session == null) return Result.failure(Error.NOT_SIGNED_IN);
        String cleanName = name == null ? "" : name.trim();
        String cleanBio = bio == null ? "" : bio.trim();
        Error nameError = validateName(cleanName);
        if (nameError != null) return Result.failure(nameError);
        if (cleanBio.length() > MAX_BIO_LENGTH) return Result.failure(Error.BIO_TOO_LONG);
        PlayerBackend.Account data = load(session, null);
        if (data == null) return Result.failure(Error.OFFLINE);
        return save(session, data.withName(cleanName, cleanBio.isEmpty() ? null : cleanBio));
    }

    /**
     * Pede a troca do e-mail: o servidor manda um link para o e-mail novo, e ele
     * só vale depois que o jogador clicar. Confere a senha atual.
     */
    public synchronized Result requestEmailChange(String newEmail, String currentPassword) {
        PlayerBackend.Session session = backend.currentSession();
        if (session == null) return Result.failure(Error.NOT_SIGNED_IN);
        String cleanEmail = normalizeEmail(newEmail);
        if (!EMAIL.matcher(cleanEmail).matches()) return Result.failure(Error.INVALID_EMAIL);
        if (currentPassword == null || currentPassword.isEmpty()) return Result.failure(Error.WRONG_PASSWORD);
        try {
            backend.requestEmailChange(currentPassword, cleanEmail);
        } catch (CloudException e) {
            return Result.failure(errorOf(e));
        }
        return Result.success(currentAccount());
    }

    /** Troca a senha da conta logada, conferindo a atual. */
    public synchronized Result changePassword(String currentPassword, String newPassword) {
        if (backend.currentSession() == null) return Result.failure(Error.NOT_SIGNED_IN);
        if (!PasswordRules.isStrong(newPassword)) return Result.failure(Error.WEAK_PASSWORD);
        if (currentPassword == null || currentPassword.isEmpty()) return Result.failure(Error.WRONG_PASSWORD);
        try {
            backend.changePassword(currentPassword, newPassword);
        } catch (CloudException e) {
            return Result.failure(errorOf(e));
        }
        return Result.success(currentAccount());
    }

    /**
     * Define (ou remove, com {@code null}) a foto ou o banner da conta logada. A
     * imagem nova chega em JPEG Base64 e vai para o servidor, que guarda e devolve
     * o endereço dela.
     */
    public synchronized Result setImage(ImageKind kind, @Nullable String image) {
        PlayerBackend.Session session = backend.currentSession();
        if (session == null) return Result.failure(Error.NOT_SIGNED_IN);
        PlayerBackend.Account data = load(session, null);
        if (data == null) return Result.failure(Error.OFFLINE);
        return save(session, kind == ImageKind.AVATAR ? data.withAvatar(image) : data.withBanner(image));
    }

    /**
     * O @usuario que o servidor acabou de reservar para os amigos (ou {@code null} para
     * esquecê-lo). Quem grava é o {@code SocialBackend}; aqui a conta em memória só passa a
     * mostrá-lo, sem outra ida ao servidor.
     */
    public synchronized Result setUsername(String accountId, @Nullable String username) {
        PlayerBackend.Session session = backend.currentSession();
        if (session == null || !session.uid.equals(accountId)) return Result.failure(Error.NOT_SIGNED_IN);
        PlayerBackend.Account data = load(session, null);
        if (data == null) return Result.failure(Error.OFFLINE);
        cachedUid = session.uid;
        cached = data.withUsername(username);
        return Result.success(toAccount(session, cached));
    }

    /**
     * Exclui a conta logada, conferindo a senha: todos os dados dela, o perfil
     * que os amigos viam, amizades, pedidos, trocas e o login. Precisa de internet.
     */
    public synchronized Result deleteCurrentAccount(String currentPassword) {
        PlayerBackend.Session session = backend.currentSession();
        if (session == null) return Result.failure(Error.NOT_SIGNED_IN);
        Account before = currentAccount();
        if (currentPassword == null || currentPassword.isEmpty()) return Result.failure(Error.WRONG_PASSWORD);
        try {
            backend.deleteAccount(currentPassword);
        } catch (CloudException e) {
            return Result.failure(errorOf(e));
        }
        sessionChanged();
        return Result.success(before);
    }

    // ---------------------------------------------------------------- apoio

    /** Sessão recém-aberta: traz o que as versões antigas guardavam e lê a conta de novo. */
    private Result opened(PlayerBackend.Session session, @Nullable String signUpName) {
        sessionChanged();
        PlayerBackend.Account data = load(session, signUpName);
        return Result.success(data == null ? offlineAccount(session) : toAccount(session, data));
    }

    private void sessionChanged() {
        cachedUid = null;
        cached = null;
        for (Runnable listener : sessionListeners) listener.run();
    }

    @Nullable
    private PlayerBackend.Account load(PlayerBackend.Session session, @Nullable String signUpName) {
        if (session.uid.equals(cachedUid) && cached != null) return cached;
        try {
            PlayerBackend.Account data = migration.ensure(session.uid, session.email, signUpName);
            cachedUid = session.uid;
            cached = data;
            return data;
        } catch (CloudException e) {
            // Sem rede e sem cópia no aparelho: a próxima leitura tenta de novo.
            Log.i(TAG, "Conta ainda sem dados neste aparelho (" + e.reason + ")");
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Falha inesperada ao ler a conta", e);
            return null;
        }
    }

    private Result save(PlayerBackend.Session session, PlayerBackend.Account data) {
        PlayerBackend.Account stored;
        try {
            stored = backend.saveAccount(session.uid, data);
        } catch (CloudException e) {
            return Result.failure(errorOf(e));
        }
        cachedUid = session.uid;
        cached = stored;
        return Result.success(toAccount(session, stored));
    }

    /** Nome da conta online criada a partir de uma conta antiga deste aparelho. */
    private static String localName(LegacyData.Account local, String email) {
        String name = local.name == null ? "" : local.name.trim();
        if (name.isEmpty()) name = emailPrefix(email);
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH).trim();
    }

    private static Account toAccount(PlayerBackend.Session session, PlayerBackend.Account data) {
        String name = data.name.trim().isEmpty() ? emailPrefix(session.email) : data.name;
        return new Account(session.uid, name, session.email, data.bio, data.avatar, data.banner, data.username);
    }

    private static Account offlineAccount(PlayerBackend.Session session) {
        return new Account(session.uid, emailPrefix(session.email), session.email, null, null, null, null);
    }

    private static String emailPrefix(@Nullable String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static Error validateName(String cleanName) {
        if (cleanName.isEmpty()) return Error.NAME_REQUIRED;
        if (cleanName.length() > MAX_NAME_LENGTH) return Error.NAME_TOO_LONG;
        return null;
    }

    private static Error errorOf(CloudException e) {
        switch (e.reason) {
            case OFFLINE: return Error.OFFLINE;
            case WRONG_CREDENTIALS: return Error.WRONG_CREDENTIALS;
            case WRONG_PASSWORD: return Error.WRONG_PASSWORD;
            case EMAIL_IN_USE: return Error.EMAIL_IN_USE;
            case INVALID_EMAIL: return Error.INVALID_EMAIL;
            case WEAK_PASSWORD: return Error.WEAK_PASSWORD;
            case NOT_SIGNED_IN: return Error.NOT_SIGNED_IN;
            case NOT_CONFIGURED: return Error.UNAVAILABLE;
            case TOO_MANY_ATTEMPTS: return Error.TOO_MANY_ATTEMPTS;
            case FAILED:
            default: return Error.FAILED;
        }
    }

    /** Dados da conta. Imagens: o endereço delas na API; {@code null} usa o padrão. */
    public static final class Account {
        public final String id;
        public final String name;
        public final String email;
        /** {@code null} quando não há bio. */
        @Nullable public final String bio;
        @Nullable public final String avatar;
        @Nullable public final String banner;
        /** @usuario reservado para os amigos, ou {@code null} se ainda não escolheu. */
        @Nullable public final String username;

        public Account(String id, String name, String email, @Nullable String bio, @Nullable String avatar,
                       @Nullable String banner, @Nullable String username) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.bio = bio;
            this.avatar = avatar;
            this.banner = banner;
            this.username = username;
        }

        @Nullable
        public String image(ImageKind kind) {
            return kind == ImageKind.AVATAR ? avatar : banner;
        }
    }

    /** Resultado de uma operação na conta: {@link #account} ou {@link #error}. */
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

        /** Falha que aconteceu fora da conta (ex.: uma exceção inesperada em quem chamou). */
        public static Result failed(Error error) {
            return failure(error);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }
}
