package com.ovigia.app.cloud;

import androidx.annotation.Nullable;

import com.ovigia.app.learning.LearningStore.AnswerRecord;
import com.ovigia.app.social.Achievements;
import com.ovigia.app.social.FriendRequest;
import com.ovigia.app.social.FriendsHub;
import com.ovigia.app.social.PublicProfile;
import com.ovigia.app.social.SocialBackend;
import com.ovigia.app.social.SocialException;
import com.ovigia.app.social.TradeOffer;
import com.ovigia.app.social.UserCard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A API do O Vigia inteira em memória, para os testes: login, os dados da conta
 * ({@link PlayerBackend}) e os amigos ({@link SocialBackend}), com as mesmas
 * garantias do servidor — @usuario único, amizade só a partir de um pedido
 * pendente (aceito por quem recebeu), perfil completo visível só para o dono e
 * os amigos, troca só entre amigos (aceita uma vez, por quem recebeu, e os dois
 * ganham o herói na hora), herói desbloqueado só pela partida que o Vigia
 * acertou (ou troca, ou importação) e dados da conta só para o dono.
 */
public final class FakeCloud implements PlayerBackend, SocialBackend {

    public boolean configured = true;
    /** Login, amigos e operações da conta que exigem rede falham com OFFLINE. */
    public boolean offline = false;
    /** Sem rede e sem cópia no aparelho: ler a conta falha com OFFLINE (o aparelho não tem cópia). */
    public boolean accountUnreachable = false;
    /** Toda operação de rede estoura com essa exceção (um bug do SDK, por exemplo). */
    public RuntimeException operationFailure = null;

    public int publishCount = 0;
    public int signOutCount = 0;
    public int flushCount = 0;
    public PublicProfile lastPublished;
    /** E-mail novo pedido por {@link #requestEmailChange}, esperando a confirmação pelo link. */
    public String pendingEmail;

    private final Map<String, String> uidByEmail = new HashMap<>();
    private final Map<String, String> emailByUid = new HashMap<>();
    private final Map<String, String> passwordByUid = new HashMap<>();
    private String signedInUid;
    private int nextUid = 1;

    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<String, Map<Integer, Hero>> heroes = new HashMap<>();
    private final Map<String, Map<Integer, Hero>> sealed = new HashMap<>();
    private final Map<String, Learning> learning = new HashMap<>();
    private final Map<String, List<Game>> games = new HashMap<>();

    private final Map<String, String> uidByUsername = new HashMap<>();
    private final Map<String, UserCard> cards = new HashMap<>();
    private final Map<String, PublicProfile> profiles = new HashMap<>();
    private final Map<String, Set<String>> friends = new HashMap<>();
    private final Map<String, FriendRequest> requests = new HashMap<>();
    private final Map<String, TradeOffer> trades = new HashMap<>();

    // ================================================================ apoio dos testes

    /** Cria uma conta online já com @usuario e perfil publicado (sem abrir sessão nela). */
    public UserCard registerOther(String email, String password, String username, String name) {
        String uid = createUser(email, password);
        UserCard card = new UserCard(uid, username, name, null);
        uidByUsername.put(username, uid);
        cards.put(uid, card);
        profiles.put(uid, new PublicProfile(card, null, null, 0, 0, 0, new ArrayList<>(),
                Achievements.fromPublished(null), 1L));
        accounts.put(uid, new Account(name, null, null, null, username, null));
        return card;
    }

    /** Cria o login de uma conta (sem dados, como uma conta de uma versão antiga do app). */
    public String createUser(String email, String password) {
        String uid = "uid-" + nextUid++;
        uidByEmail.put(email, uid);
        emailByUid.put(uid, email);
        passwordByUid.put(uid, password);
        return uid;
    }

    /** Põe os dados de uma conta direto no "servidor" (como a importação do Firebase deixou). */
    public void putAccount(String uid, Account account) {
        accounts.put(uid, account);
    }

    /** Troca a sessão para outra conta, como se ela usasse o app em outro aparelho. */
    public void actAs(String uid) {
        signedInUid = uid;
    }

    /** Publica um perfil como as versões antigas faziam (cartão + perfil completo). */
    public void putProfile(PublicProfile profile) {
        profiles.put(profile.card.uid, profile);
        cards.put(profile.card.uid, profile.card);
        if (profile.card.username != null) uidByUsername.put(profile.card.username, profile.card.uid);
    }

    public boolean areFriends(String a, String b) {
        return friendsOf(a).contains(b) && friendsOf(b).contains(a);
    }

    public boolean hasRequest(String fromUid, String toUid) {
        return requests.containsKey(fromUid + "_" + toUid);
    }

    @Nullable
    public TradeOffer trade(String id) {
        return trades.get(id);
    }

    public int tradeCount() {
        return trades.size();
    }

    public boolean accountExists(String email) {
        return uidByEmail.containsKey(email);
    }

    @Nullable
    public String uidOf(String email) {
        return uidByEmail.get(email);
    }

    @Nullable
    public Account account(String uid) {
        return accounts.get(uid);
    }

    public List<Hero> heroesOf(String uid) {
        return new ArrayList<>(heroes.getOrDefault(uid, new LinkedHashMap<>()).values());
    }

    public List<Hero> sealedOf(String uid) {
        return new ArrayList<>(sealed.getOrDefault(uid, new LinkedHashMap<>()).values());
    }

    public Learning learningOf(String uid) {
        return learning.getOrDefault(uid, Learning.empty());
    }

    public List<Game> gamesOf(String uid) {
        return new ArrayList<>(games.getOrDefault(uid, new ArrayList<>()));
    }

    // ================================================================ PlayerBackend: sessão

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Nullable
    @Override
    public Session currentSession() {
        if (!configured || signedInUid == null) return null;
        return new Session(signedInUid, emailByUid.get(signedInUid));
    }

    @Override
    public Session signIn(String email, String password) throws CloudException {
        checkOnline();
        String uid = uidByEmail.get(email);
        if (uid == null || !passwordByUid.get(uid).equals(password)) {
            throw new CloudException(CloudException.Reason.WRONG_CREDENTIALS);
        }
        signedInUid = uid;
        return currentSession();
    }

    /** Nome com que cada conta foi criada pelo app (a API guarda junto com o login). */
    public final Map<String, String> signUpNames = new HashMap<>();

    @Override
    public Session signUp(String email, String password, String displayName) throws CloudException {
        checkOnline();
        if (uidByEmail.containsKey(email)) throw new CloudException(CloudException.Reason.EMAIL_IN_USE);
        if (password == null || password.length() < 6) throw new CloudException(CloudException.Reason.WEAK_PASSWORD);
        signedInUid = createUser(email, password);
        signUpNames.put(signedInUid, displayName);
        accounts.put(signedInUid, new Account(displayName, null, null, null, null, null));
        return currentSession();
    }

    @Override
    public void signOut() {
        signOutCount++;
        signedInUid = null;
    }

    @Override
    public void changePassword(String currentPassword, String newPassword) throws CloudException {
        String uid = reauthenticate(currentPassword);
        passwordByUid.put(uid, newPassword);
    }

    @Override
    public void requestEmailChange(String currentPassword, String newEmail) throws CloudException {
        reauthenticate(currentPassword);
        pendingEmail = newEmail;
    }

    /** O jogador clicou no link: o e-mail da conta muda. */
    public void confirmEmailChange() {
        String uid = signedInUid;
        String old = emailByUid.get(uid);
        uidByEmail.remove(old);
        uidByEmail.put(pendingEmail, uid);
        emailByUid.put(uid, pendingEmail);
        pendingEmail = null;
    }

    @Override
    public void deleteAccount(String password) throws CloudException {
        String uid = reauthenticate(password);
        accounts.remove(uid);
        heroes.remove(uid);
        sealed.remove(uid);
        learning.remove(uid);
        games.remove(uid);
        for (String f : new HashSet<>(friendsOf(uid))) friendsOf(f).remove(uid);
        friends.remove(uid);
        requests.values().removeIf(r -> r.from.uid.equals(uid) || r.to.uid.equals(uid));
        trades.values().removeIf(t -> t.involves(uid));
        UserCard card = cards.remove(uid);
        if (card != null) uidByUsername.remove(card.username);
        profiles.remove(uid);
        uidByEmail.remove(emailByUid.remove(uid));
        passwordByUid.remove(uid);
        signedInUid = null;
    }

    private String reauthenticate(String password) throws CloudException {
        checkOnline();
        if (signedInUid == null) throw new CloudException(CloudException.Reason.NOT_SIGNED_IN);
        if (!passwordByUid.get(signedInUid).equals(password)) {
            throw new CloudException(CloudException.Reason.WRONG_PASSWORD);
        }
        return signedInUid;
    }

    // ================================================================ PlayerBackend: dados

    @Nullable
    @Override
    public Account loadAccount(String uid) throws CloudException {
        checkOwner(uid);
        if (accountUnreachable) throw new CloudException(CloudException.Reason.OFFLINE);
        return accounts.get(uid);
    }

    /** Endereço que a "API" dá a uma imagem enviada em Base64. */
    public static String urlOf(String base64) {
        return "https://media.test/" + Integer.toHexString(base64.hashCode()) + ".jpg";
    }

    @Override
    public Account saveAccount(String uid, Account account) throws CloudException {
        checkOwner(uid);
        checkOnline();
        Account old = accounts.get(uid);
        // O @usuario é do SocialBackend (claimUsername); as imagens novas viram endereço.
        Account stored = new Account(account.name, account.bio, stored(account.avatar), stored(account.banner),
                old == null ? null : old.username, old == null ? null : old.celebrated);
        accounts.put(uid, stored);
        return stored.withCelebrated(account.celebrated);
    }

    private static String stored(@Nullable String image) {
        return image == null || image.startsWith("https://") ? image : urlOf(image);
    }

    @Override
    public void addCelebrated(String uid, Collection<String> ids, boolean baseline) {
        Account old = accounts.get(uid);
        if (old == null) old = new Account("", null, null, null, null, null);
        List<String> list = old.celebrated == null ? (baseline ? new ArrayList<>() : null)
                : new ArrayList<>(old.celebrated);
        if (list == null) list = new ArrayList<>();
        for (String id : ids) if (!list.contains(id)) list.add(id);
        accounts.put(uid, old.withCelebrated(list));
    }

    @Override
    public List<Hero> loadHeroes(String uid) throws CloudException {
        checkOwner(uid);
        return heroesOf(uid);
    }

    /** Põe um herói direto na conta (como se viesse de antes, ou de outro aparelho). */
    public void grantHero(String uid, Hero hero) {
        heroes.computeIfAbsent(uid, k -> new LinkedHashMap<>()).putIfAbsent(hero.characterId, hero);
    }

    /** Nome dos personagens no elenco do "servidor". */
    public final Map<Integer, String> rosterNames = new HashMap<>();

    /** O que a última partida de cada personagem fez com o herói. */
    private final Map<Integer, Grant> grants = new HashMap<>();
    /** Lendários do elenco do "servidor": só entram na coleção de quem é Vigia do Infinito. */
    public final Set<Integer> legendary = new HashSet<>();
    /** Contas que são Vigia do Infinito. */
    public final Set<String> infiniteWatchers = new HashSet<>();
    /** Tokens de compra que o "Google Play" reconhece como pagos. */
    public final Set<String> paidTokens = new HashSet<>();

    @Nullable
    @Override
    public Grant awaitUnlock(String uid, int characterId) {
        flushCount++;
        return grants.remove(characterId);
    }

    @Override
    public void markHeroesSeen(String uid, Collection<Integer> characterIds) {
        Map<Integer, Hero> map = heroes.computeIfAbsent(uid, k -> new LinkedHashMap<>());
        for (Integer id : characterIds) {
            Hero h = map.get(id);
            if (h != null) map.put(id, new Hero(h.characterId, h.name, h.imageUrl, h.unlockedAt, true));
        }
    }

    @Override
    public List<Hero> loadSealed(String uid) throws CloudException {
        checkOwner(uid);
        return sealedOf(uid);
    }

    @Nullable
    @Override
    public Boolean loadInfiniteWatcher(String uid) throws CloudException {
        checkOwner(uid);
        return infiniteWatchers.contains(uid);
    }

    /** Como a API: confere o token e, se a conta virou Vigia do Infinito, os lacrados entram na coleção. */
    @Override
    public boolean verifyPurchase(String uid, String productId, String purchaseToken) throws CloudException {
        checkOwner(uid);
        if (!paidTokens.contains(purchaseToken)) throw new CloudException(CloudException.Reason.PURCHASE_INVALID);
        infiniteWatchers.add(uid);
        Map<Integer, Hero> waiting = sealed.remove(uid);
        if (waiting != null) {
            for (Hero h : waiting.values()) grantHero(uid, new Hero(h.characterId, h.name, h.imageUrl, h.unlockedAt, false));
        }
        return true;
    }

    @Override
    public Learning loadLearning(String uid) throws CloudException {
        checkOwner(uid);
        return copy(learningOf(uid));
    }

    @Override
    public void recordGame(String uid, @Nullable Game game, boolean engineWin) {
        Learning l = learningOf(uid);
        Map<Integer, Integer> picks = new HashMap<>(l.picks);
        Map<Integer, Map<String, double[]>> beliefs = copy(l).beliefs;
        if (game != null) {
            picks.merge(game.characterId, 1, Integer::sum);
            Map<String, double[]> attrs = beliefs.computeIfAbsent(game.characterId, k -> new HashMap<>());
            for (AnswerRecord a : game.answers) {
                double[] sumCount = attrs.computeIfAbsent(a.key, k -> new double[2]);
                sumCount[0] += a.value;
                sumCount[1] += 1;
            }
            games.computeIfAbsent(uid, k -> new ArrayList<>()).add(game);
            // Como a API: a partida em que o Vigia acertou desbloqueia o herói (ou lacra o lendário).
            if (com.ovigia.app.catalog.UnlockRules.unlocks(game.outcome)) {
                boolean has = heroes.getOrDefault(uid, new LinkedHashMap<>()).containsKey(game.characterId);
                Hero hero = new Hero(game.characterId, rosterNames.getOrDefault(game.characterId, ""), null,
                        game.timestamp, false);
                if (!has && legendary.contains(game.characterId) && !infiniteWatchers.contains(uid)) {
                    sealed.computeIfAbsent(uid, k -> new LinkedHashMap<>()).putIfAbsent(game.characterId, hero);
                    grants.put(game.characterId, Grant.SEALED);
                } else {
                    grantHero(uid, hero);
                    grants.put(game.characterId, has ? Grant.EXISTING : Grant.NEW);
                }
            }
        }
        learning.put(uid, new Learning(l.gamesPlayed + 1, l.engineWins + (engineWin ? 1 : 0), picks, beliefs));
    }

    @Override
    public List<Game> recentGames(String uid, int limit) throws CloudException {
        checkOwner(uid);
        List<Game> list = gamesOf(uid);
        // Da mais nova para a mais antiga; no mesmo milissegundo, a gravada por último primeiro.
        java.util.Collections.reverse(list);
        list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return new ArrayList<>(list.subList(0, Math.min(limit, list.size())));
    }

    @Override
    public void resetLearning(String uid) {
        learning.remove(uid);
        games.remove(uid);
    }

    /** Quantas vezes a importação das versões antigas rodou. */
    public int legacyImports = 0;

    @Override
    public void importLegacy(String uid, LegacyImport data) throws CloudException {
        checkOwner(uid);
        checkOnline();
        legacyImports++;
        for (Hero h : data.heroes) grantHero(uid, h);
        importLearning(uid, data.learning, data.games);
        if (data.celebrated != null) addCelebrated(uid, data.celebrated, true);
    }

    /** Soma aprendizado na conta (como a importação faz com o que veio das versões antigas). */
    public void importLearning(String uid, Learning imported, List<Game> importedGames) {
        Learning l = learningOf(uid);
        Map<Integer, Integer> picks = new HashMap<>(l.picks);
        imported.picks.forEach((id, n) -> picks.merge(id, n, Integer::sum));
        Map<Integer, Map<String, double[]>> beliefs = copy(l).beliefs;
        imported.beliefs.forEach((id, attrs) -> {
            Map<String, double[]> mine = beliefs.computeIfAbsent(id, k -> new HashMap<>());
            attrs.forEach((key, sumCount) -> {
                double[] target = mine.computeIfAbsent(key, k -> new double[2]);
                target[0] += sumCount[0];
                target[1] += sumCount[1];
            });
        });
        learning.put(uid, new Learning(Math.max(l.gamesPlayed, imported.gamesPlayed),
                Math.max(l.engineWins, imported.engineWins), picks, beliefs));
        games.computeIfAbsent(uid, k -> new ArrayList<>()).addAll(importedGames);
    }

    private static Learning copy(Learning l) {
        Map<Integer, Map<String, double[]>> beliefs = new HashMap<>();
        l.beliefs.forEach((id, attrs) -> {
            Map<String, double[]> copy = new HashMap<>();
            attrs.forEach((key, sumCount) -> copy.put(key, sumCount.clone()));
            beliefs.put(id, copy);
        });
        return new Learning(l.gamesPlayed, l.engineWins, new HashMap<>(l.picks), beliefs);
    }

    /** Só o dono lê os dados da conta (como nas regras). */
    private void checkOwner(String uid) throws CloudException {
        if (operationFailure != null) throw operationFailure;
        if (!configured) throw new CloudException(CloudException.Reason.NOT_CONFIGURED);
        if (!uid.equals(signedInUid)) throw new CloudException(CloudException.Reason.NOT_SIGNED_IN);
    }

    private void checkOnline() throws CloudException {
        if (operationFailure != null) throw operationFailure;
        if (!configured) throw new CloudException(CloudException.Reason.NOT_CONFIGURED);
        if (offline) throw new CloudException(CloudException.Reason.OFFLINE);
    }

    // ================================================================ SocialBackend

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
        Account old = accounts.get(uid);
        if (old != null) accounts.put(uid, old.withUsername(card.username));
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
    public List<TradeOffer> loadTrades() throws SocialException {
        String uid = requireUid();
        List<TradeOffer> list = new ArrayList<>();
        for (TradeOffer t : trades.values()) {
            if (t.involves(uid)) list.add(t);
        }
        list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return list;
    }

    @Override
    public TradeOffer proposeTrade(TradeOffer trade) throws SocialException {
        String uid = requireUid();
        if (!uid.equals(trade.from.uid) || uid.equals(trade.to.uid) || !friendsOf(uid).contains(trade.to.uid)
                || trade.want.characterId == trade.offer.characterId
                || !trade.id.equals(TradeOffer.idFor(trade.from.uid, trade.to.uid, trade.want.characterId))
                || trades.containsKey(trade.id)) {
            throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        }
        TradeOffer stored = new TradeOffer(trade.id, trade.from, trade.to, trade.want, trade.offer,
                TradeOffer.Status.PENDING, trade.createdAt);
        trades.put(trade.id, stored);
        return stored;
    }

    @Override
    public void acceptTrade(String tradeId, PublicProfile.Hero chosenOffer) throws SocialException {
        String uid = requireUid();
        TradeOffer t = trades.get(tradeId);
        if (t == null) throw new SocialException(SocialException.Error.NOT_FOUND);
        if (!t.to.uid.equals(uid) || t.status != TradeOffer.Status.PENDING
                || !friendsOf(uid).contains(t.from.uid) || chosenOffer.characterId == t.want.characterId) {
            throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        }
        trades.put(tradeId, new TradeOffer(t.id, t.from, t.to, t.want, chosenOffer, TradeOffer.Status.ACCEPTED,
                t.createdAt));
        // Como a API: os dois ganham o herói do outro na hora.
        long now = System.currentTimeMillis();
        grantHero(uid, new Hero(chosenOffer.characterId, chosenOffer.name, chosenOffer.imageUrl, now, false));
        grantHero(t.from.uid, new Hero(t.want.characterId, t.want.name, t.want.imageUrl, now, false));
    }

    @Override
    public void deleteTrade(String tradeId) throws SocialException {
        String uid = requireUid();
        TradeOffer t = trades.get(tradeId);
        if (t == null) return;
        if (!t.involves(uid)) throw new SocialException(SocialException.Error.PERMISSION_DENIED);
        trades.remove(tradeId);
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

    private Set<String> friendsOf(String uid) {
        return friends.computeIfAbsent(uid, k -> new HashSet<>());
    }

    private void check() throws SocialException {
        if (operationFailure != null) throw operationFailure;
        if (!configured) throw new SocialException(SocialException.Error.NOT_CONFIGURED);
        if (offline) throw new SocialException(SocialException.Error.OFFLINE);
    }

    private String requireUid() throws SocialException {
        check();
        if (signedInUid == null) throw new SocialException(SocialException.Error.NOT_CONNECTED);
        return signedInUid;
    }
}
