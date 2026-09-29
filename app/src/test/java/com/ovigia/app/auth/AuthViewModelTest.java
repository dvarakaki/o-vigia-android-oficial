package com.ovigia.app.auth;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.util.Event;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class AuthViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    private final Executor direct = Runnable::run;
    private FakeCloud cloud;
    private AccountStore store;

    @Before
    public void setUp() {
        cloud = new FakeCloud();
        store = new AccountStore(cloud);
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
        AuthViewModel vm = new AuthViewModel(store, direct, direct);

        vm.submit("", "ana@b.com", "errada1");

        assertEquals(AccountStore.Error.WRONG_CREDENTIALS, vm.state().getValue().error);
        assertNull(vm.signedIn().getValue());
        assertNull(store.currentAccount());
    }

    @Test
    public void accountCreatedOnAnotherDevice_signsInHere() {
        new AccountStore(cloud).signUp("Davi", "davi@exemplo.com", "segredo#1");
        store.signOut();
        AuthViewModel vm = new AuthViewModel(store, direct, direct);

        vm.submit("", "davi@exemplo.com", "segredo#1");

        Event<AccountStore.Account> event = vm.signedIn().getValue();
        assertNotNull("a conta é a do servidor: entra em qualquer aparelho", event);
        assertEquals("Davi", event.consume().name);
    }

    @Test
    public void withoutInternet_saysSo() {
        cloud.offline = true;
        AuthViewModel vm = new AuthViewModel(store, direct, direct);

        vm.submit("", "davi@exemplo.com", "segredo#1");

        assertEquals(AccountStore.Error.OFFLINE, vm.state().getValue().error);
        assertFalse(vm.state().getValue().loading);
    }

    @Test
    public void unexpectedFailure_endsTheLoadingWithAnError() {
        cloud.operationFailure = new IllegalStateException("SDK sem inicializar");
        AuthViewModel vm = new AuthViewModel(store, direct, direct);

        vm.submit("", "davi@exemplo.com", "segredo#1");

        assertEquals(AccountStore.Error.FAILED, vm.state().getValue().error);
        assertFalse(vm.state().getValue().loading);
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
