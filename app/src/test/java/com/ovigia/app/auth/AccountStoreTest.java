package com.ovigia.app.auth;

import com.ovigia.app.auth.AccountStore.Error;
import com.ovigia.app.auth.AccountStore.ImageKind;
import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.cloud.PlayerBackend;
import com.ovigia.app.legacy.LegacyFiles;
import com.ovigia.app.legacy.LegacyMigration;
import com.ovigia.app.learning.LearningStore.Outcome;
import com.ovigia.app.profile.FakeProfileImages;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A conta do jogador é a conta online: login, perfil e a migração das versões antigas. */
public class AccountStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeCloud cloud;
    private LegacyFiles legacy;
    private AccountStore store;

    @Before
    public void setUp() {
        cloud = new FakeCloud();
        legacy = new LegacyFiles(tmp.getRoot());
        store = newStore();
    }

    /** Uma instalação do app (neste aparelho, com os arquivos antigos que houver nele). */
    private AccountStore newStore() {
        return new AccountStore(cloud, new LegacyMigration(cloud, legacy.data(), new FakeProfileImages()));
    }

    private String uid() {
        return store.currentAccountId();
    }

    // ---------------------------------------------------------------- cadastro e login

    @Test
    public void signUp_opensSessionAndNormalizesInput() {
        AccountStore.Result result = store.signUp("  Davi  ", "  DAVI@Exemplo.com ", "segredo#1");

        assertTrue(result.isSuccess());
        assertEquals("Davi", result.account.name);
        assertEquals("davi@exemplo.com", result.account.email);
        assertEquals(result.account.id, store.currentAccountId());
        assertEquals("o nome vai para a conta online", "Davi", cloud.account(uid()).name);
    }

    @Test
    public void signUp_validatesFieldsBeforeTouchingTheServer() {
        assertEquals(Error.NAME_REQUIRED, store.signUp("  ", "a@b.com", "segredo#1").error);
        assertEquals(Error.NAME_TOO_LONG, store.signUp("x".repeat(41), "a@b.com", "segredo#1").error);
        assertEquals(Error.INVALID_EMAIL, store.signUp("Ana", "sem-arroba", "segredo#1").error);
        assertEquals(Error.WEAK_PASSWORD, store.signUp("Ana", "a@b.com", "curta#").error);
        assertEquals(Error.WEAK_PASSWORD, store.signUp("Ana", "a@b.com", "semespecial").error);
        assertFalse("nada chegou ao servidor", cloud.accountExists("a@b.com"));
    }

    @Test
    public void signUp_rejectsEmailAlreadyInUse() {
        store.signUp("Davi", "davi@exemplo.com", "segredo#1");
        store.signOut();
        assertEquals(Error.EMAIL_IN_USE, store.signUp("Outro", "DAVI@exemplo.com", "outra#senha").error);
    }

    @Test
    public void signIn_doesNotRevealWhichPartIsWrong() {
        store.signUp("Davi", "davi@exemplo.com", "segredo#1");
        store.signOut();

        assertEquals(Error.WRONG_CREDENTIALS, store.signIn("davi@exemplo.com", "errada#1").error);
        assertEquals(Error.WRONG_CREDENTIALS, store.signIn("ninguem@exemplo.com", "segredo#1").error);
        assertNull(store.currentAccountId());

        assertTrue(store.signIn(" Davi@Exemplo.com ", "segredo#1").isSuccess());
        assertEquals("Davi", store.currentAccount().name);
    }

    @Test
    public void withoutInternetOrServer_signInSaysWhy() {
        cloud.offline = true;
        assertEquals(Error.OFFLINE, store.signIn("davi@exemplo.com", "segredo#1").error);
        assertEquals(Error.OFFLINE, store.signUp("Davi", "davi@exemplo.com", "segredo#1").error);

        cloud.offline = false;
        cloud.configured = false;
        assertFalse(store.isAvailable());
        assertEquals(Error.UNAVAILABLE, store.signIn("davi@exemplo.com", "segredo#1").error);
    }

    @Test
    public void signOut_endsTheSession_andListenersHearAboutEveryChange() {
        AtomicInteger changes = new AtomicInteger();
        store.addSessionListener(changes::incrementAndGet);

        store.signUp("Davi", "davi@exemplo.com", "segredo#1");
        assertEquals(1, changes.get());
        store.signOut();
        assertEquals(2, changes.get());
        assertNull(store.currentAccount());
        assertEquals(1, cloud.signOutCount);
    }

    @Test
    public void theSameAccount_showsTheSameDataOnAnotherDevice() {
        store.signUp("Davi", "davi@exemplo.com", "segredo#1");
        store.updateProfile("Davi Souza", "Fã do Surfista");
        store.setImage(ImageKind.AVATAR, "foto-base64");
        store.signOut();

        // Outro aparelho (ou o app reinstalado): nada local, tudo vem da conta.
        AccountStore other = new AccountStore(cloud);
        assertTrue(other.signIn("davi@exemplo.com", "segredo#1").isSuccess());
        AccountStore.Account account = other.currentAccount();
        assertEquals("Davi Souza", account.name);
        assertEquals("Fã do Surfista", account.bio);
        assertEquals("a foto fica guardada na API", FakeCloud.urlOf("foto-base64"), account.avatar);
    }

    // ---------------------------------------------------------------- perfil

    @Test
    public void updateProfile_isAllOrNothing() {
        store.signUp("Davi", "davi@exemplo.com", "segredo#1");

        assertEquals(Error.BIO_TOO_LONG, store.updateProfile("Novo nome", "x".repeat(121)).error);
        assertEquals("nada mudou", "Davi", cloud.account(uid()).name);

        AccountStore.Result ok = store.updateProfile(" Davi S. ", "  ");
        assertTrue(ok.isSuccess());
        assertEquals("Davi S.", ok.account.name);
        assertNull("bio vazia vira sem bio", ok.account.bio);
    }

    @Test
    public void images_goToTheAccount_andNullRemovesThem() {
        store.signUp("Davi", "davi@exemplo.com", "segredo#1");

        store.setImage(ImageKind.BANNER, "banner-base64");
        assertEquals(FakeCloud.urlOf("banner-base64"), cloud.account(uid()).banner);
        assertEquals(FakeCloud.urlOf("banner-base64"), store.currentAccount().image(ImageKind.BANNER));

        store.setImage(ImageKind.BANNER, null);
        assertNull(cloud.account(uid()).banner);
    }

    @Test
    public void operationsWithoutSession_fail() {
        assertEquals(Error.NOT_SIGNED_IN, store.updateProfile("Ana", null).error);
        assertEquals(Error.NOT_SIGNED_IN, store.setImage(ImageKind.AVATAR, "x").error);
        assertEquals(Error.NOT_SIGNED_IN, store.changePassword("a", "segredo#2").error);
        assertEquals(Error.NOT_SIGNED_IN, store.requestEmailChange("a@b.com", "a").error);
        assertEquals(Error.NOT_SIGNED_IN, store.deleteCurrentAccount("a").error);
    }

    @Test
    public void changePassword_checksTheCurrentOne_andTheNewRules() {
        store.signUp("Davi", "davi@exemplo.com", "segredo#1");

        assertEquals(Error.WRONG_PASSWORD, store.changePassword("errada#1", "nova#senha").error);
        assertEquals(Error.WEAK_PASSWORD, store.changePassword("segredo#1", "fraca").error);
        assertTrue(store.changePassword("segredo#1", "nova#senha").isSuccess());

        store.signOut();
        assertFalse(store.signIn("davi@exemplo.com", "segredo#1").isSuccess());
        assertTrue(store.signIn("davi@exemplo.com", "nova#senha").isSuccess());
    }

    @Test
    public void emailChange_sendsALink_andOnlyChangesAfterConfirmation() {
        store.signUp("Davi", "davi@exemplo.com", "segredo#1");

        assertEquals(Error.INVALID_EMAIL, store.requestEmailChange("sem-arroba", "segredo#1").error);
        assertEquals(Error.WRONG_PASSWORD, store.requestEmailChange("novo@exemplo.com", "errada#1").error);
        assertTrue(store.requestEmailChange(" Novo@Exemplo.com ", "segredo#1").isSuccess());
        assertEquals("novo@exemplo.com", cloud.pendingEmail);
        assertEquals("ainda não confirmou", "davi@exemplo.com", store.currentAccount().email);

        cloud.confirmEmailChange();
        assertEquals("novo@exemplo.com", store.currentAccount().email);
    }

    @Test
    public void deleteAccount_needsThePassword_andErasesEverything() {
        store.signUp("Davi", "davi@exemplo.com", "segredo#1");
        String id = uid();

        assertEquals(Error.WRONG_PASSWORD, store.deleteCurrentAccount("errada#1").error);
        assertNotNull(cloud.account(id));

        AccountStore.Result deleted = store.deleteCurrentAccount("segredo#1");
        assertTrue(deleted.isSuccess());
        assertEquals(id, deleted.account.id);
        assertNull(store.currentAccountId());
        assertNull(cloud.account(id));
        assertFalse(store.signIn("davi@exemplo.com", "segredo#1").isSuccess());
    }

    @Test
    public void withoutInternetAndNothingCached_theAccountIsNotRecreatedEmpty() {
        store.signUp("Davi", "davi@exemplo.com", "segredo#1");
        store.updateProfile("Davi Souza", "Bio de verdade");
        String id = uid();

        cloud.accountUnreachable = true;
        AccountStore reopened = newStore();
        AccountStore.Account shown = reopened.currentAccount();
        assertEquals("mostra o que a sessão sabe", "davi", shown.name);
        assertEquals("Davi Souza", cloud.account(id).name);
        assertEquals("nada foi gravado por cima", "Bio de verdade", cloud.account(id).bio);

        cloud.accountUnreachable = false;
        assertEquals("a rede voltou: a conta de verdade", "Davi Souza", reopened.currentAccount().name);
    }

    // ---------------------------------------------------------------- migração das versões antigas

    @Test
    public void localOnlyAccountFromAnOldVersion_becomesAnOnlineAccount_withEverything() throws Exception {
        legacy.account("local-1", "Davi", "davi@exemplo.com", "velha1", null, null, "avatar_local.jpg");
        legacy.image("avatar_local.jpg");
        legacy.collection("local-1", "[{\"characterId\":1455,\"name\":\"Iron Man\",\"imageUrl\":\"img\","
                + "\"savedAt\":100,\"seenInCatalog\":true}]");
        legacy.learning("local-1", "{\"picksById\":{\"1455\":2},\"beliefsById\":{\"1455\":{\"power_voo\":[2.0,2.0]}},"
                + "\"gameLog\":[{\"timestamp\":50,\"correctId\":1455,\"answers\":[{\"key\":\"power_voo\",\"value\":1.0}],"
                + "\"outcome\":\"ENGINE_GUESSED\"}],\"gamesPlayed\":3,\"engineWins\":2}");
        legacy.achievements("local-1", "[\"FIRST_HERO\"]");

        // A senha da época (6 caracteres) ainda vale: é a do jogador.
        AccountStore.Result result = store.signIn("davi@exemplo.com", "velha1");

        assertTrue(result.isSuccess());
        assertTrue("a conta online nasceu com a mesma senha", cloud.accountExists("davi@exemplo.com"));
        String id = uid();
        PlayerBackend.Account account = cloud.account(id);
        assertEquals("Davi", account.name);
        assertEquals("Bio antiga", account.bio);
        assertEquals("a foto antiga subiu para a API", FakeCloud.urlOf("avatar-avatar_local.jpg"), account.avatar);
        assertEquals(Arrays.asList("FIRST_HERO"), account.celebrated);
        assertEquals(1, cloud.heroesOf(id).size());
        assertEquals(1455, cloud.heroesOf(id).get(0).characterId);
        PlayerBackend.Learning learning = cloud.learningOf(id);
        assertEquals(3, learning.gamesPlayed);
        assertEquals(2, learning.engineWins);
        assertEquals(Integer.valueOf(2), learning.picks.get(1455));
        assertEquals(1, cloud.gamesOf(id).size());
        assertEquals(Outcome.ENGINE_GUESSED, cloud.gamesOf(id).get(0).outcome);
        assertFalse("os arquivos antigos somem", legacy.anyLeft());
    }

    @Test
    public void localAccount_withTheWrongPassword_staysWhereItIs() throws Exception {
        legacy.account("local-1", "Davi", "davi@exemplo.com", "velha1", null, null, null);

        assertEquals(Error.WRONG_CREDENTIALS, store.signIn("davi@exemplo.com", "outra1").error);
        assertFalse(cloud.accountExists("davi@exemplo.com"));
        assertTrue("nada foi apagado", legacy.anyLeft());
    }

    @Test
    public void linkedAccountFromAnOldVersion_joinsWhatTheServerImported_withWhatStayedOnTheDevice()
            throws Exception {
        // O que a conta tinha no Firebase já foi levado para a API pela importação do servidor.
        String uid = cloud.createUser("davi@exemplo.com", "segredo#1");
        cloud.putAccount(uid, new PlayerBackend.Account("Davi", "Bio publicada", "https://media.test/foto.jpg",
                "https://media.test/banner.jpg", "davi", null));
        cloud.grantHero(uid, new PlayerBackend.Hero(1455, "Iron Man", null, 100, true));
        cloud.grantHero(uid, new PlayerBackend.Hero(1699, "Wolverine", null, 200, true));
        cloud.importLearning(uid, new PlayerBackend.Learning(9, 4, new HashMap<>(), new HashMap<>()),
                new ArrayList<>());
        // E este aparelho tinha 2 partidas e um herói que o outro não publicou.
        legacy.account("local-1", "Davi", "davi@exemplo.com", "segredo#1", uid, "davi", null);
        legacy.collection("local-1", "[{\"characterId\":1455,\"name\":\"Iron Man\",\"savedAt\":100},"
                + "{\"characterId\":1502,\"name\":\"Thor\",\"savedAt\":300}]");
        legacy.learning("local-1", "{\"picksById\":{\"1502\":1},\"gameLog\":[],\"gamesPlayed\":2,\"engineWins\":1}");

        assertTrue(store.signIn("davi@exemplo.com", "segredo#1").isSuccess());

        PlayerBackend.Account account = cloud.account(uid);
        assertEquals("davi", account.username);
        assertEquals("nada que já estava na conta muda", "Bio publicada", account.bio);
        assertEquals("https://media.test/foto.jpg", account.avatar);
        assertEquals("https://media.test/banner.jpg", account.banner);
        assertEquals("heróis importados + o que só estava aqui", 3, cloud.heroesOf(uid).size());
        assertEquals("o que já estava na conta fica com a data de lá", 200,
                cloud.heroesOf(uid).stream().filter(h -> h.characterId == 1699).findFirst().get().unlockedAt);
        PlayerBackend.Learning learning = cloud.learningOf(uid);
        assertEquals("os totais ficam com o maior, sem contar em dobro", 9, learning.gamesPlayed);
        assertEquals(4, learning.engineWins);
        assertEquals(Integer.valueOf(1), learning.picks.get(1502));
        assertFalse(legacy.anyLeft());
    }

    @Test
    public void learningFileWrittenByTheBrokenReleases_isRead() throws Exception {
        // 1.1.0 a 1.2.2: o R8 renomeou os campos do bloco (a..f) e as chaves viraram "1455.0".
        legacy.account("local-1", "Davi", "davi@exemplo.com", "segredo#1", null, null, null);
        legacy.learning("local-1", "{\"a\":{\"1455\":3.0},\"b\":{},\"c\":[],\"d\":5.0,\"e\":2.0,\"f\":0.0}");

        assertTrue(store.signIn("davi@exemplo.com", "segredo#1").isSuccess());

        PlayerBackend.Learning learning = cloud.learningOf(uid());
        assertEquals(5, learning.gamesPlayed);
        assertEquals(2, learning.engineWins);
        assertEquals(Integer.valueOf(3), learning.picks.get(1455));
    }
}
