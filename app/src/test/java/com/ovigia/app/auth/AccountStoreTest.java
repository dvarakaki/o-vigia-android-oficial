package com.ovigia.app.auth;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file;

    @Before
    public void setUp() {
        file = new File(tmp.getRoot(), "accounts.json");
    }

    /** Poucas iterações: o teste valida a lógica, não o custo do PBKDF2. */
    private AccountStore newStore() {
        return new AccountStore(() -> file, 1_000);
    }

    @Test
    public void signUp_opensSessionAndNormalizesInput() {
        AccountStore store = newStore();
        AccountStore.Result result = store.signUp("  Davi ", " Davi@Exemplo.COM ", "segredo#1");

        assertTrue(result.isSuccess());
        assertEquals("Davi", result.account.name);
        assertEquals("davi@exemplo.com", result.account.email);
        assertEquals(result.account.id, store.currentAccount().id);
    }

    @Test
    public void signUp_validatesFields() {
        AccountStore store = newStore();
        assertEquals(AccountStore.Error.NAME_REQUIRED, store.signUp(" ", "a@b.com", "segredo#1").error);
        assertEquals(AccountStore.Error.INVALID_EMAIL, store.signUp("Ana", "ana@", "segredo#1").error);
        assertEquals(AccountStore.Error.WEAK_PASSWORD, store.signUp("Ana", "ana@b.com", "12345").error);
        assertNull(store.currentAccount());
    }

    @Test
    public void newPasswords_needEightCharactersAndASpecialOne() {
        AccountStore store = newStore();
        assertEquals("8 caracteres sem especial", AccountStore.Error.WEAK_PASSWORD,
                store.signUp("Ana", "ana@b.com", "segredo1").error);
        assertEquals("especial, mas curta", AccountStore.Error.WEAK_PASSWORD,
                store.signUp("Ana", "ana@b.com", "sen#1").error);
        assertTrue(store.signUp("Ana", "ana@b.com", "segredo#1").isSuccess());

        assertEquals("a troca de senha segue a mesma regra", AccountStore.Error.WEAK_PASSWORD,
                store.changePassword("segredo#1", "semespecial").error);
        assertTrue(store.changePassword("segredo#1", "nova-senha!").isSuccess());
    }

    @Test
    public void accountsFromBeforeTheRule_stillSignInWithTheirOldPassword() {
        AccountStore store = newStore();
        // Conta que veio do servidor com a senha curta da regra antiga.
        assertTrue(store.restore("Davi", "davi@exemplo.com", "antiga", null, "uid-1", null, null).isSuccess());
        store.signOut();

        assertTrue("quem já tinha conta não fica trancado fora dela",
                store.signIn("davi@exemplo.com", "antiga").isSuccess());
    }

    @Test
    public void signUp_rejectsEmailAlreadyInUseIgnoringCase() {
        AccountStore store = newStore();
        store.signUp("Ana", "ana@b.com", "segredo#1");
        assertEquals(AccountStore.Error.EMAIL_IN_USE, store.signUp("Outra Ana", "ANA@b.com", "outra#123").error);
    }

    @Test
    public void signIn_checksPasswordWithoutRevealingWhichPartIsWrong() {
        AccountStore store = newStore();
        store.signUp("Ana", "ana@b.com", "segredo#1");
        store.signOut();

        assertEquals(AccountStore.Error.WRONG_CREDENTIALS, store.signIn("ana@b.com", "errada1").error);
        assertEquals(AccountStore.Error.WRONG_CREDENTIALS, store.signIn("ninguem@b.com", "segredo#1").error);
        assertNull(store.currentAccount());

        AccountStore.Result ok = store.signIn("ANA@b.com", "segredo#1");
        assertTrue(ok.isSuccess());
        assertEquals("Ana", store.currentAccount().name);
    }

    @Test
    public void sessionAndAccounts_surviveReopening_andSignOutEndsSession() {
        AccountStore store = newStore();
        store.signUp("Ana", "ana@b.com", "segredo#1");

        AccountStore reopened = newStore();
        assertNotNull("sessão persiste entre aberturas do app", reopened.currentAccount());

        reopened.signOut();
        AccountStore again = newStore();
        assertNull(again.currentAccount());
        assertTrue("conta continua existindo depois de sair", again.signIn("ana@b.com", "segredo#1").isSuccess());
    }

    @Test
    public void passwordIsNeverStoredInPlainText() throws Exception {
        newStore().signUp("Ana", "ana@b.com", "senha-super-secreta");
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertFalse(json.contains("senha-super-secreta"));
    }

    @Test
    public void updateProfile_isAllOrNothing() {
        AccountStore store = newStore();
        store.signUp("Ana", "ana@b.com", "segredo#1");
        store.signUp("Bia", "bia@b.com", "segredo#2");
        // Sessão agora é da Bia.

        assertEquals(AccountStore.Error.EMAIL_IN_USE,
                store.updateProfile("Bia Nova", "bio", "ana@b.com", "segredo#2").error);
        assertEquals("Bia", store.currentAccount().name);

        String longBio = new String(new char[AccountStore.MAX_BIO_LENGTH + 1]).replace('\0', 'x');
        assertEquals(AccountStore.Error.BIO_TOO_LONG, store.updateProfile("Bia", longBio, "bia@b.com", null).error);

        AccountStore.Result ok = store.updateProfile("Bia Nova", "  ", "bia.nova@b.com", "segredo#2");
        assertTrue(ok.isSuccess());
        assertNull("bio em branco vira null", ok.account.bio);
        assertEquals("bia.nova@b.com", newStore().currentAccount().email);
    }

    @Test
    public void images_areStoredPerAccount_andDeleteRemovesAccount() {
        AccountStore store = newStore();
        store.signUp("Ana", "ana@b.com", "segredo#1");
        store.setImage(AccountStore.ImageKind.AVATAR, "a.jpg");
        store.setImage(AccountStore.ImageKind.BANNER, "b.jpg");

        AccountStore.Account reopened = newStore().currentAccount();
        assertEquals("a.jpg", reopened.avatarFile);
        assertEquals("b.jpg", reopened.imageFile(AccountStore.ImageKind.BANNER));

        assertEquals(AccountStore.Error.WRONG_PASSWORD, store.deleteCurrentAccount("errada").error);
        AccountStore.Result deleted = store.deleteCurrentAccount("segredo#1");
        assertTrue(deleted.isSuccess());
        assertEquals("a.jpg", deleted.account.avatarFile);
        assertNull(store.currentAccount());
        assertTrue("e-mail fica livre de novo", store.signUp("Ana 2", "ana@b.com", "segredo#1").isSuccess());
    }

    @Test
    public void operationsWithoutSession_fail() {
        AccountStore store = newStore();
        assertEquals(AccountStore.Error.NOT_SIGNED_IN, store.changePassword("a", "bbbbbb").error);
        assertEquals(AccountStore.Error.NOT_SIGNED_IN, store.setImage(AccountStore.ImageKind.AVATAR, "x").error);
    }

    @Test
    public void cloudLink_survivesReopening_andEmailChangesDoNotBreakIt() {
        AccountStore store = newStore();
        String id = store.signUp("Davi", "davi@exemplo.com", "segredo#1").account.id;
        assertNull(store.currentAccount().cloudUid);

        store.linkCloud(id, "uid-9", "davi@exemplo.com");
        store.setUsername(id, "davi");
        store.updateProfile("Davi", null, "outro@exemplo.com", "segredo#1");

        AccountStore.Account reopened = newStore().currentAccount();
        assertEquals("uid-9", reopened.cloudUid);
        assertEquals("o e-mail online continua o de quando conectou", "davi@exemplo.com", reopened.cloudEmail);
        assertEquals("davi", reopened.username);
    }

    @Test
    public void linkingAnotherCloudAccount_forgetsTheOldUsername() {
        AccountStore store = newStore();
        String id = store.signUp("Davi", "davi@exemplo.com", "segredo#1").account.id;
        store.linkCloud(id, "uid-1", "davi@exemplo.com");
        store.setUsername(id, "davi");

        store.linkCloud(id, "uid-1", "davi@exemplo.com");
        assertEquals("mesma conta online: mantém", "davi", store.currentAccount().username);

        store.linkCloud(id, "uid-2", "davi@exemplo.com");
        assertNull(store.currentAccount().username);
    }

    @Test
    public void verifyPassword_checksTheSignedInAccount() {
        AccountStore store = newStore();
        store.signUp("Davi", "davi@exemplo.com", "segredo#1");
        assertTrue(store.verifyPassword("segredo#1"));
        assertFalse(store.verifyPassword("errada"));
        assertFalse(store.verifyPassword(null));
        store.signOut();
        assertFalse(store.verifyPassword("segredo#1"));
    }

    @Test
    public void restore_recreatesTheOnlineAccountAndOpensTheSession() {
        AccountStore store = newStore();
        AccountStore.Result result = store.restore("Davi", " Davi@Exemplo.COM ", "segredo#1", "vigia noturno",
                "uid-1", "davi@exemplo.com", "davi");

        assertTrue(result.isSuccess());
        assertEquals("Davi", result.account.name);
        assertEquals("davi@exemplo.com", result.account.email);
        assertEquals("vigia noturno", result.account.bio);
        assertEquals("uid-1", result.account.cloudUid);
        assertEquals("davi", result.account.username);
        assertEquals("a sessão já fica aberta", result.account.id, store.currentAccount().id);
        assertTrue("entra com a mesma senha depois", newStore().signIn("davi@exemplo.com", "segredo#1").isSuccess());
    }

    @Test
    public void restore_adjustsWhatTheServerSentAndRecusesDuplicates() {
        AccountStore store = newStore();
        String longName = repeat('a', AccountStore.MAX_NAME_LENGTH + 10);
        String longBio = repeat('b', AccountStore.MAX_BIO_LENGTH + 10);

        AccountStore.Result result = store.restore(longName, "davi@exemplo.com", "segredo#1", longBio,
                "uid-1", null, null);

        assertTrue(result.isSuccess());
        assertEquals(AccountStore.MAX_NAME_LENGTH, result.account.name.length());
        assertEquals(AccountStore.MAX_BIO_LENGTH, result.account.bio.length());
        assertEquals("sem e-mail online, vale o mesmo do cadastro",
                "davi@exemplo.com", result.account.cloudEmail);

        assertEquals("e-mail já cadastrado neste aparelho", AccountStore.Error.EMAIL_IN_USE,
                store.restore("Outro", "davi@exemplo.com", "segredo#1", null, "uid-2", null, null).error);
    }

    @Test
    public void restore_withoutNameFallsBackToTheEmail() {
        AccountStore store = newStore();
        AccountStore.Result result = store.restore("  ", "davi@exemplo.com", "segredo#1", null, "uid-1", null, null);

        assertTrue(result.isSuccess());
        assertEquals("davi", result.account.name);
    }

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    public void corruptedFile_startsFresh() throws Exception {
        Files.write(file.toPath(), "{ não é json".getBytes(StandardCharsets.UTF_8));
        AccountStore store = newStore();
        assertNull(store.currentAccount());
        assertTrue(store.signUp("Ana", "ana@b.com", "segredo#1").isSuccess());
    }
}
