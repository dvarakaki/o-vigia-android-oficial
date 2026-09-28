package com.ovigia.app.social;

import android.util.Log;

import androidx.annotation.Nullable;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.profile.ProfileImages;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Liga a conta local aos amigos online.
 *
 * O jogo continua funcionando só no aparelho; a conta online é opcional e usa o
 * mesmo e-mail e senha da local. Ela nasce quando o jogador conecta pela aba de
 * amigos e escolhe um @usuario. A partir daí o perfil (cartão, bio, banner,
 * números, heróis e conquistas) é publicado sempre que algo muda, e heróis
 * desbloqueados em outro aparelho entram na coleção local ao conectar.
 *
 * Operações bloqueantes (disco e rede): chamar no executor social.
 */
public final class SocialRepository {

    private static final String TAG = "SocialRepository";

    /** Lado maior da foto publicada no cartão. */
    static final int AVATAR_SHARE_PX = 128;
    /** Lado maior do banner publicado no perfil. */
    static final int BANNER_SHARE_PX = 720;

    public enum Status {
        /** O app não tem servidor configurado. */
        NOT_CONFIGURED,
        /** Ninguém logado neste aparelho. */
        SIGNED_OUT,
        /** Conta local sem sessão online aberta: pedir a senha. */
        NEEDS_CONNECTION,
        /** Conectado, mas ainda sem @usuario. */
        NEEDS_USERNAME,
        READY
    }

    /** Onde a conta logada está no caminho até os amigos. Imutável. */
    public static final class Session {
        public final Status status;
        @Nullable public final AccountStore.Account account;
        /** Cartão da própria conta; só em {@link Status#READY}. */
        @Nullable public final UserCard card;

        Session(Status status, @Nullable AccountStore.Account account, @Nullable UserCard card) {
            this.status = status;
            this.account = account;
            this.card = card;
        }
    }

    /** Perfil de um amigo mais os heróis que o jogador também já desbloqueou. */
    public static final class FriendProfile {
        public final PublicProfile profile;
        public final Set<Integer> myUnlockedIds;

        FriendProfile(PublicProfile profile, Set<Integer> myUnlockedIds) {
            this.profile = profile;
            this.myUnlockedIds = Collections.unmodifiableSet(myUnlockedIds);
        }
    }

    private final SocialBackend backend;
    private final AccountStore accountStore;
    private final CollectionStore collectionStore;
    private final LearningStore learningStore;
    private final ProfileImages images;
    private final Supplier<RosterCatalog> roster;
    private final Executor executor;
    private final LongSupplier clock;
    private final AtomicBoolean publishQueued = new AtomicBoolean(false);
    private String encodedAvatarFile;
    private String encodedAvatar;

    /**
     * @param roster   equipes e vilania para as conquistas; pode devolver {@code null}
     * @param executor onde rodam as publicações em segundo plano
     */
    public SocialRepository(SocialBackend backend, AccountStore accountStore, CollectionStore collectionStore,
                            LearningStore learningStore, ProfileImages images, Supplier<RosterCatalog> roster,
                            Executor executor, LongSupplier clock) {
        this.backend = backend;
        this.accountStore = accountStore;
        this.collectionStore = collectionStore;
        this.learningStore = learningStore;
        this.images = images;
        this.roster = roster;
        this.executor = executor;
        this.clock = clock;
    }

    public boolean isConfigured() {
        return backend.isConfigured();
    }

    // ---------------------------------------------------------------- sessão

    /** Sem rede: só olha a conta local e a sessão online guardada. */
    public Session session() {
        if (!backend.isConfigured()) return new Session(Status.NOT_CONFIGURED, null, null);
        AccountStore.Account account = accountStore.currentAccount();
        if (account == null) return new Session(Status.SIGNED_OUT, null, null);
        if (account.cloudUid == null || !account.cloudUid.equals(backend.signedInUid())) {
            return new Session(Status.NEEDS_CONNECTION, account, null);
        }
        if (account.username == null) return new Session(Status.NEEDS_USERNAME, account, null);
        return new Session(Status.READY, account, cardFor(account));
    }

    /**
     * Conecta a conta logada à conta online, criando-a se ainda não existir. A
     * senha precisa ser a da conta local, para as duas nunca divergirem.
     */
    public Session connect(String password) throws SocialException {
        requireConfigured();
        AccountStore.Account account = accountStore.currentAccount();
        if (account == null) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        if (!accountStore.verifyPassword(password)) throw new SocialException(SocialException.Error.WRONG_PASSWORD);
        link(account, password, true);
        Session session = session();
        if (session.status == Status.READY) publishIgnoringErrors(session);
        return session;
    }

    /**
     * Depois do login ou do cadastro local: abre a sessão online com a senha que
     * o jogador acabou de digitar, criando a conta online se ela ainda não
     * existir. Assim a aba de amigos não pede a senha de novo. Nunca falha — sem
     * rede, a aba volta a pedir a senha quando o jogador abrir.
     */
    public void resumeAfterSignIn(AccountStore.Account account, String password) {
        if (!backend.isConfigured()) return;
        // Não deixa a sessão online de outra conta aberta.
        if (account.cloudUid == null) backend.signOut();
        try {
            link(account, password, true);
            Session session = session();
            if (session.status == Status.READY) publishIgnoringErrors(session);
        } catch (SocialException ignored) {
            // A aba de amigos pede a senha quando o jogador abrir.
        }
    }

    /**
     * Entrar com um e-mail que já tem conta online, num aparelho onde ela não
     * existe mais (app reinstalado, celular novo): recria a conta local com o que
     * o servidor guardava — nome, bio, @usuario, foto, banner, heróis e números —
     * e abre a sessão nela.
     *
     * Devolve {@code null} quando não dá para recuperar: sem servidor, sem rede,
     * senha errada ou e-mail sem conta online. Nunca lança.
     */
    @Nullable
    public AccountStore.Account recover(String email, String password) {
        if (!backend.isConfigured()) return null;
        String cleanEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        try {
            String uid = backend.signIn(cleanEmail, password, false);
            UserCard card = backend.loadCard(uid);
            AccountStore.Result result = accountStore.restore(card == null ? null : card.name, cleanEmail,
                    password, null, uid, cleanEmail, card == null ? null : card.username);
            if (!result.isSuccess()) {
                backend.signOut();
                return null;
            }
            restoreFromCloud(result.account, uid, card);
            publishIgnoringErrors(session());
            return accountStore.currentAccount();
        } catch (SocialException e) {
            return null;
        }
    }

    public void onSignedOut() {
        if (backend.isConfigured()) backend.signOut();
    }

    private void link(AccountStore.Account account, String password, boolean createIfMissing) throws SocialException {
        String email = account.cloudEmail != null ? account.cloudEmail : account.email;
        String uid = backend.signIn(email, password, createIfMissing);
        if (!accountStore.linkCloud(account.id, uid, email).isSuccess()) {
            throw new SocialException(SocialException.Error.NOT_CONNECTED);
        }
        UserCard remote = backend.loadCard(uid);
        if (remote == null) return;
        accountStore.setUsername(account.id, remote.username);
        restoreFromCloud(account, uid, remote);
    }

    /**
     * Traz de volta o que a conta online guardava e ainda falta aqui: heróis,
     * números das partidas e — só quando o campo está vazio neste aparelho — bio,
     * foto e banner. Nada que já existe localmente é sobrescrito, então conectar
     * uma conta em uso não mexe no perfil dela.
     *
     * A foto e o banner voltam na versão reduzida que foi publicada; o original
     * fica no aparelho onde foi escolhido.
     *
     * {@code account} precisa ser a conta com sessão aberta: é nela que o perfil
     * e as imagens são gravados.
     */
    private void restoreFromCloud(AccountStore.Account account, String uid, @Nullable UserCard card) {
        PublicProfile profile;
        try {
            profile = backend.loadProfile(uid);
        } catch (SocialException e) {
            // Perfil ainda não publicado ou sem rede: o que está no aparelho vale.
            return;
        }
        for (PublicProfile.Hero hero : profile.heroes) {
            collectionStore.importEntry(account.id, hero.characterId, hero.name, hero.imageUrl, hero.unlockedAt);
        }
        learningStore.importStats(account.id, profile.gamesPlayed, profile.engineWins, profile.distinctCharacters);
        if (account.bio == null && profile.bio != null) {
            // E-mail inalterado: não pede a senha atual.
            accountStore.updateProfile(account.name, profile.bio, account.email, null);
        }
        if (account.avatarFile == null && card != null) {
            saveSharedImage(account, AccountStore.ImageKind.AVATAR, card.avatar);
        }
        if (account.bannerFile == null) {
            saveSharedImage(account, AccountStore.ImageKind.BANNER, profile.banner);
        }
    }

    /** Grava a imagem publicada como arquivo local do perfil. Ignora falhas: o perfil funciona sem ela. */
    private void saveSharedImage(AccountStore.Account account, AccountStore.ImageKind kind, @Nullable String shared) {
        if (shared == null) return;
        try {
            accountStore.setImage(kind, images.saveShared(shared, kind, account.id));
        } catch (IOException | RuntimeException ignored) {
            // Imagem ilegível ou sem espaço: a conta volta sem ela.
        }
    }

    /** Reserva o @usuario (ou troca o atual) e publica o perfil. */
    public Session claimUsername(String raw) throws SocialException {
        Session session = session();
        if (session.status != Status.NEEDS_USERNAME && session.status != Status.READY) {
            throw new SocialException(session.status == Status.NOT_CONFIGURED
                    ? SocialException.Error.NOT_CONFIGURED : SocialException.Error.NOT_CONNECTED);
        }
        String username = Username.normalize(raw);
        if (!Username.isValid(username)) throw new SocialException(SocialException.Error.USERNAME_INVALID);
        AccountStore.Account account = session.account;
        UserCard card = new UserCard(account.cloudUid, username, account.name, sharedAvatar(account.avatarFile));
        backend.claimUsername(card, account.username);
        accountStore.setUsername(account.id, username);
        Session ready = session();
        publishIgnoringErrors(ready);
        return ready;
    }

    // ---------------------------------------------------------------- publicação

    public void publish() throws SocialException {
        backend.publish(buildProfile(requireReady()));
    }

    /**
     * Publica em segundo plano se a conta estiver online. Pedidos que chegam
     * enquanto um já espera na fila viram um só.
     */
    public void publishQuietly() {
        if (!backend.isConfigured() || !publishQueued.compareAndSet(false, true)) return;
        executor.execute(() -> {
            publishQueued.set(false);
            try {
                publishIgnoringErrors(session());
            } catch (RuntimeException e) {
                // Roda solto numa thread de fundo: se nem a sessão der para ler, o app segue sem publicar.
                Log.w(TAG, "Não foi possível publicar o perfil", e);
            }
        });
    }

    private void publishIgnoringErrors(Session session) {
        if (session.status != Status.READY) return;
        try {
            backend.publish(buildProfile(session));
        } catch (SocialException | RuntimeException ignored) {
            // Sem rede: a próxima mudança (ou a próxima visita à aba) publica de novo.
        }
    }

    /** O que os amigos veem desta conta, montado a partir dos dados locais. */
    PublicProfile buildProfile(Session session) {
        AccountStore.Account account = session.account;
        List<PublicProfile.Hero> heroes = new ArrayList<>();
        Set<Integer> heroIds = new HashSet<>();
        for (CollectionStore.Entry e : collectionStore.list(account.id)) {
            heroes.add(new PublicProfile.Hero(e.characterId, e.name, e.imageUrl, e.savedAt));
            heroIds.add(e.characterId);
        }
        LearningStore.Stats stats = learningStore.stats(account.id);
        List<AchievementProgress> achievements = Achievements.evaluate(heroIds, rosterOrNull(),
                stats.gamesPlayed, stats.gamesPlayed - stats.engineWins);
        return new PublicProfile(session.card, account.bio,
                images.encodeForSharing(account.bannerFile, BANNER_SHARE_PX),
                stats.gamesPlayed, stats.engineWins, stats.distinctCharacters, heroes, achievements,
                clock.getAsLong());
    }

    @Nullable
    private RosterCatalog rosterOrNull() {
        try {
            return roster.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- amigos

    public FriendsHub hub() throws SocialException {
        requireReady();
        return backend.loadHub();
    }

    /** Quem usa esse @usuario. {@link SocialException.Error#NOT_FOUND} se ninguém. */
    public UserCard findByUsername(String raw) throws SocialException {
        requireReady();
        String username = Username.normalize(raw);
        if (!Username.isValid(username)) throw new SocialException(SocialException.Error.USERNAME_INVALID);
        UserCard card = backend.findByUsername(username);
        if (card == null) throw new SocialException(SocialException.Error.NOT_FOUND);
        return card;
    }

    /** Pede amizade; se o outro jogador já tinha pedido, os dois viram amigos na hora. */
    public void sendRequest(UserCard to, FriendsHub hub) throws SocialException {
        Session session = requireReady();
        if (to.uid.equals(session.card.uid)) throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        if (hub.incomingFrom(to.uid) != null) {
            accept(to.uid, hub);
            return;
        }
        backend.sendRequest(session.card, to);
    }

    public void accept(String fromUid, FriendsHub hub) throws SocialException {
        Session session = requireReady();
        backend.acceptRequest(fromUid);
        // Os dois tinham pedido: o pedido que eu mandei não serve mais.
        if (hub.relationshipWith(session.card.uid, fromUid) == FriendsHub.Relationship.REQUEST_RECEIVED) {
            for (FriendRequest r : hub.outgoing) {
                if (r.to.uid.equals(fromUid)) {
                    try {
                        backend.deleteRequest(session.card.uid, fromUid);
                    } catch (SocialException ignored) {
                        // Sobra um pedido para um amigo: a tela já mostra os dois como amigos.
                    }
                }
            }
        }
        // O novo amigo abre o perfil logo em seguida: que ele esteja em dia.
        publishIgnoringErrors(session);
    }

    public void decline(String fromUid) throws SocialException {
        backend.deleteRequest(fromUid, requireReady().card.uid);
    }

    public void cancel(String toUid) throws SocialException {
        backend.deleteRequest(requireReady().card.uid, toUid);
    }

    public void removeFriend(String friendUid) throws SocialException {
        requireReady();
        backend.removeFriend(friendUid);
    }

    public FriendProfile friendProfile(String uid) throws SocialException {
        Session session = requireReady();
        PublicProfile profile = backend.loadProfile(uid);
        Set<Integer> mine = new HashSet<>();
        for (CollectionStore.Entry e : collectionStore.list(session.account.id)) mine.add(e.characterId);
        return new FriendProfile(profile, mine);
    }

    // ---------------------------------------------------------------- conta

    /** Depois de trocar a senha local, troca a online também. Nunca falha. */
    public void onPasswordChanged(AccountStore.Account account, String currentPassword, String newPassword) {
        if (!backend.isConfigured() || account.cloudUid == null) return;
        try {
            backend.changePassword(account.cloudEmail, currentPassword, newPassword);
        } catch (SocialException ignored) {
            // A conta online continua com a senha antiga até o jogador conectar de novo.
        }
    }

    /**
     * Antes de excluir a conta local: apaga a online. Só impede a exclusão sem
     * rede ({@link SocialException.Error#OFFLINE}); se a conta online já não
     * existe ou tem outra senha, não há mais o que apagar daqui.
     */
    public void deleteOnlineAccount(AccountStore.Account account, String password) throws SocialException {
        if (!backend.isConfigured() || account.cloudUid == null) return;
        try {
            backend.deleteAccount(account.cloudEmail, password);
        } catch (SocialException e) {
            if (e.error == SocialException.Error.OFFLINE) throw e;
        }
    }

    // ---------------------------------------------------------------- apoio

    private void requireConfigured() throws SocialException {
        if (!backend.isConfigured()) throw new SocialException(SocialException.Error.NOT_CONFIGURED);
    }

    private Session requireReady() throws SocialException {
        Session session = session();
        if (session.status == Status.READY) return session;
        throw new SocialException(session.status == Status.NOT_CONFIGURED
                ? SocialException.Error.NOT_CONFIGURED : SocialException.Error.NOT_CONNECTED);
    }

    private UserCard cardFor(AccountStore.Account account) {
        return new UserCard(account.cloudUid, account.username, account.name, sharedAvatar(account.avatarFile));
    }

    /** O nome do arquivo muda a cada foto nova: dá para guardar a última conversão. */
    private synchronized String sharedAvatar(@Nullable String avatarFile) {
        if (avatarFile == null) return null;
        if (!avatarFile.equals(encodedAvatarFile)) {
            encodedAvatar = images.encodeForSharing(avatarFile, AVATAR_SHARE_PX);
            encodedAvatarFile = avatarFile;
        }
        return encodedAvatar;
    }
}
