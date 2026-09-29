package com.ovigia.app.auth;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.util.Event;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class AuthViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;
    private AccountStore store;

    @Before
    public void setUp() {
        store = new AccountStore(() -> new File(tmp.getRoot(), "accounts.json"), 1_000);
    }

    @Test
    public void startsInSignIn_andSignUpCreatesAccountOnce() {
        AuthViewModel vm = new AuthViewModel(store, direct, direct);
        assertEquals(AuthUiState.Mode.SIGN_IN, vm.state().getValue().mode);

        vm.setMode(AuthUiState.Mode.SIGN_UP);
        vm.submit("Ana", "ana@b.com", "segredo#1");

        Event<AccountStore.Account> event = vm.signedIn().getValue();
        assertNotNull(event);
        assertEquals("Ana", event.consume().name);
        assertNull("evento de sucesso é consumido uma vez", event.consume());
        assertFalse(vm.state().getValue().loading);
        assertNotNull(store.currentAccount());
    }

    @Test
    public void wrongPassword_reportsErrorAndStaysSignedOut() {
        store.signUp("Ana", "ana@b.com", "segredo#1");
        store.signOut();

        // A conta está aqui: só a senha está errada, não adianta procurar no servidor.
        RecordingOnline online = new RecordingOnline((email, password) -> {
            throw new AssertionError("procurou no servidor uma conta que existe neste aparelho");
        });
        AuthViewModel vm = new AuthViewModel(store, direct, direct, direct, online);
        vm.submit("", "ana@b.com", "errada1");

        assertEquals(AccountStore.Error.WRONG_CREDENTIALS, vm.state().getValue().error);
        assertNull(vm.signedIn().getValue());
        assertNull(store.currentAccount());
    }

    @Test
    public void unknownEmail_isRecoveredFromTheServerBeforeFailing() {
        RecordingOnline online = new RecordingOnline(
                (email, password) -> store.restore("Davi", email, password, "vigia", "uid-1", email, "davi").account);
        AuthViewModel vm = new AuthViewModel(store, direct, direct, direct, online);

        vm.submit("", "davi@exemplo.com", "segredo#1");

        Event<AccountStore.Account> event = vm.signedIn().getValue();
        assertNotNull("entrar com um e-mail já cadastrado traz a conta de volta", event);
        AccountStore.Account account = event.consume();
        assertEquals("Davi", account.name);
        assertEquals("vigia", account.bio);
        assertNotNull(store.currentAccount());
        assertNull(vm.state().getValue().error);
    }

    @Test
    public void unknownEmail_withoutOnlineAccount_keepsTheCredentialsError() {
        RecordingOnline online = new RecordingOnline((email, password) -> null);
        AuthViewModel vm = new AuthViewModel(store, direct, direct, direct, online);

        vm.submit("", "davi@exemplo.com", "segredo#1");

        assertEquals(AccountStore.Error.WRONG_CREDENTIALS, vm.state().getValue().error);
        assertNull(vm.signedIn().getValue());
        assertNull(store.currentAccount());
    }

    @Test
    public void signIn_reopensTheOnlineSessionWithTheTypedPassword() {
        store.signUp("Ana", "ana@b.com", "segredo#1");
        store.signOut();
        RecordingOnline online = new RecordingOnline((email, password) -> {
            throw new AssertionError("conta local existe: não recupera do servidor");
        });
        AuthViewModel vm = new AuthViewModel(store, direct, direct, direct, online);

        vm.submit("", "ana@b.com", "segredo#1");

        assertEquals("Ana", online.resumedAccount.name);
        assertEquals("segredo#1", online.resumedPassword);
    }

    private interface Recovery {
        AccountStore.Account recover(String email, String password);
    }

    /** {@link AuthViewModel.OnlineAccounts} que guarda o que recebeu. */
    private static final class RecordingOnline implements AuthViewModel.OnlineAccounts {

        private final Recovery recovery;
        AccountStore.Account resumedAccount;
        String resumedPassword;

        RecordingOnline(Recovery recovery) {
            this.recovery = recovery;
        }

        @Override
        public void onSignedIn(AccountStore.Account account, String password) {
            resumedAccount = account;
            resumedPassword = password;
        }

        @Override
        public AccountStore.Account recover(String email, String password) {
            return recovery.recover(email, password);
        }
    }

    @Test
    public void switchingMode_clearsPreviousError() {
        AuthViewModel vm = new AuthViewModel(store, direct, direct);
        vm.submit("", "sem-arroba", "x");
        assertEquals(AccountStore.Error.INVALID_EMAIL, vm.state().getValue().error);

        vm.setMode(AuthUiState.Mode.SIGN_UP);

        assertNull(vm.state().getValue().error);
        assertEquals(AuthUiState.Mode.SIGN_UP, vm.state().getValue().mode);
    }
}
