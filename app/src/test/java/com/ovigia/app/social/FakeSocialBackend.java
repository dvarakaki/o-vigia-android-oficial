package com.ovigia.app.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Servidor de amigos em memória com as mesmas garantias do firestore.rules:
 * @usuario único, amizade só a partir de um pedido pendente (aceito por quem
 * recebeu) e perfil completo visível só para o dono e os amigos.
 */
public final class FakeSocialBackend implements SocialBackend {

    boolean configured = true;
    /** Toda operação falha com OFFLINE. */
    boolean offline = false;
    /** Ler a sessão estoura com essa exceção (algo que ninguém previu, como um bug ao iniciar o SDK). */
    RuntimeException sessionFailure = null;

    private final Map<String, String> uidByEmail = new HashMap<>();
    private final Map<String, String> passwordByUid = new HashMap<>();
    private final Map<String, String> uidByUsername = new HashMap<>();
    private final Map<String, UserCard> cards = new HashMap<>();
    private final Map<String, PublicProfile> profiles = new HashMap<>();
    private final Map<String, Set<String>> friends = new HashMap<>();
    private final Map<String, FriendRequest> requests = new HashMap<>();
    private String signedInUid;
    private int nextUid = 1;

    int publishCount = 0;
    int signOutCount = 0;
    PublicProfile lastPublished;

    // ---------------------------------------------------------------- apoio dos testes

    /** Cria uma conta online já com @usuario e perfil publicado (sem abrir sessão nela). */
    UserCard registerOther(String email, String password, String username, String name) {
        String uid = "uid-" + nextUid++;
        uidByEmail.put(email, uid);
        passwordByUid.put(uid, password);
        UserCard card = new UserCard(uid, username, name, null);
        uidByUsername.put(username, uid);
        cards.put(uid, card);
        profiles.put(uid, new PublicProfile(card, null, null, 0, 0, 0, new ArrayList<>(),
                Achievements.fromPublished(null), 1L));
        return card;
    }

    /** Troca a sessão para outra conta, como se ela usasse o app em outro aparelho. */
    void actAs(String uid) {
        signedInUid = uid;
    }

    void putProfile(PublicProfile profile) {
        profiles.put(profile.card.uid, profile);
        cards.put(profile.card.uid, profile.card);
    }

    boolean areFriends(String a, String b) {
        return friendsOf(a).contains(b) && friendsOf(b).contains(a);
    }

    boolean hasRequest(String fromUid, String toUid) {
        return requests.containsKey(fromUid + "_" + toUid);
    }

    boolean accountExists(String email) {
        return uidByEmail.containsKey(email);
    }

    // ---------------------------------------------------------------- SocialBackend

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Nullable
    @Override
    public String signedInUid() {
        if (sessionFailure != null) throw sessionFailure;
        return signedInUid;
    }

    @Override
    public String signIn(String email, String password, boolean createIfMissing) throws SocialException {
        check();
        String uid = uidByEmail.get(email);
        if (uid == null) {
            if (!createIfMissing) throw new SocialException(SocialException.Error.WRONG_PASSWORD);
            uid = "uid-" + nextUid++;
            uidByEmail.put(email, uid);
            passwordByUid.put(uid, password);
        } else if (!passwordByUid.get(uid).equals(password)) {
            throw new SocialException(SocialException.Error.WRONG_PASSWORD);
        }
        signedInUid = uid;
        return uid;
    }

    @Override
    public void signOut() {
        signOutCount++;
        signedInUid = null;
    }

    @Nullable
    @Override
    public UserCard loadCard(String uid) throws SocialException {
        check();
        return cards.get(uid);
    }

    @Override
    public void claimUsername(UserCard card, @Nullable String previousUsername) throws SocialException {
        String uid = requireUid();
        String owner = uidByUsername.get(card.username);
        if (owner != null && !owner.equals(uid)) throw new SocialException(SocialException.Error.USERNAME_TAKEN);
        if (previousUsername != null && !previousUsername.equals(card.username)) uidByUsername.remove(previousUsername);
        uidByUsername.put(card.username, uid);
        cards.put(uid, card);
    }

    @Override
    public void publish(PublicProfile profile) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(profile.card.uid)) throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        cards.put(uid, profile.card);
        profiles.put(uid, profile);
        publishCount++;
        lastPublished = profile;
    }

    @Nullable
    @Override
    public UserCard findByUsername(String username) throws SocialException {
        requireUid();
        String uid = uidByUsername.get(username);
        return uid == null ? null : cards.get(uid);
    }

    @Override
    public FriendsHub loadHub() throws SocialException {
        String uid = requireUid();
        List<UserCard> list = new ArrayList<>();
        for (String f : friendsOf(uid)) {
            if (cards.containsKey(f)) list.add(cards.get(f));
        }
        list.sort((a, b) -> a.name.compareTo(b.name));
        List<FriendRequest> incoming = new ArrayList<>();
        List<FriendRequest> outgoing = new ArrayList<>();
        for (FriendRequest r : requests.values()) {
            if (r.to.uid.equals(uid)) incoming.add(r);
            if (r.from.uid.equals(uid)) outgoing.add(r);
        }
        return new FriendsHub(list, incoming, outgoing);
    }

    @Override
    public void sendRequest(UserCard from, UserCard to) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(from.uid) || uid.equals(to.uid) || friendsOf(uid).contains(to.uid)
                || !cards.containsKey(to.uid)) {
            throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        }
        requests.put(from.uid + "_" + to.uid, new FriendRequest(from, to, System.currentTimeMillis()));
    }

    @Override
    public void acceptRequest(String fromUid) throws SocialException {
        String uid = requireUid();
        if (requests.remove(fromUid + "_" + uid) == null) {
            throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        }
        friendsOf(uid).add(fromUid);
        friendsOf(fromUid).add(uid);
    }

    @Override
    public void deleteRequest(String fromUid, String toUid) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(fromUid) && !uid.equals(toUid)) throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        requests.remove(fromUid + "_" + toUid);
    }

    @Override
    public void removeFriend(String friendUid) throws SocialException {
        String uid = requireUid();
        friendsOf(uid).remove(friendUid);
        friendsOf(friendUid).remove(uid);
    }

    @Override
    public PublicProfile loadProfile(String uid) throws SocialException {
        String me = requireUid();
        if (!me.equals(uid) && !friendsOf(uid).contains(me)) {
            throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        }
        PublicProfile profile = profiles.get(uid);
        if (profile == null) throw new SocialException(SocialException.Error.NOT_FOUND);
        return profile;
    }

    @Override
    public void changePassword(String email, String currentPassword, String newPassword) throws SocialException {
        check();
        String uid = uidByEmail.get(email);
        if (uid == null || !passwordByUid.get(uid).equals(currentPassword)) {
            throw new SocialException(SocialException.Error.WRONG_PASSWORD);
        }
        passwordByUid.put(uid, newPassword);
    }

    @Override
    public void deleteAccount(String email, String password) throws SocialException {
        check();
        String uid = uidByEmail.get(email);
        if (uid == null || !passwordByUid.get(uid).equals(password)) {
            throw new SocialException(SocialException.Error.WRONG_PASSWORD);
        }
        for (String f : new HashSet<>(friendsOf(uid))) friendsOf(f).remove(uid);
        friends.remove(uid);
        requests.values().removeIf(r -> r.from.uid.equals(uid) || r.to.uid.equals(uid));
        UserCard card = cards.remove(uid);
        if (card != null) uidByUsername.remove(card.username);
        profiles.remove(uid);
        uidByEmail.remove(email);
        passwordByUid.remove(uid);
        if (uid.equals(signedInUid)) signedInUid = null;
    }

    private Set<String> friendsOf(String uid) {
        return friends.computeIfAbsent(uid, k -> new HashSet<>());
    }

    private void check() throws SocialException {
        if (!configured) throw new SocialException(SocialException.Error.NOT_CONFIGURED);
        if (offline) throw new SocialException(SocialException.Error.OFFLINE);
    }

    private String requireUid() throws SocialException {
        check();
        if (signedInUid == null) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        return signedInUid;
    }
}
