package com.ovigia.app.profile;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.auth.AccountStore.ImageKind;
import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.profile.EditProfileUiState.Message;
import com.ovigia.app.profile.EditProfileUiState.Status;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Edição do perfil contra a conta online falsa e imagens falsas. */
public class EditProfileViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    private final Executor direct = Runnable::run;
    private FakeCloud cloud;
    private AccountStore accounts;
    private FakeProfileImages images;
    private final AtomicInteger published = new AtomicInteger();

    @Before
    public void setUp() {
        cloud = new FakeCloud();
        accounts = new AccountStore(cloud);
        images = new FakeProfileImages();
        accounts.signUp("Davi", "davi@exemplo.com", "segredo#1");
    }

    private EditProfileViewModel started() {
        EditProfileViewModel vm = new EditProfileViewModel(accounts, images, published::incrementAndGet,
                direct, direct);
        vm.start();
        return vm;
    }

    private static Message lastMessage(EditProfileViewModel vm) {
        return vm.messages().getValue() == null ? null : vm.messages().getValue().peek();
    }

    @Test
    public void start_loadsAccount_orSignsOut() {
        EditProfileUiState state = started().state().getValue();
        assertEquals(Status.READY, state.status);
        assertEquals("Davi", state.name);
        assertNull(state.avatar);

        accounts.signOut();
        assertEquals(Status.SIGNED_OUT, started().state().getValue().status);
    }

    @Test
    public void saveProfile_updatesNameAndBio_withoutPasswordWhenEmailIsTheSame() {
        EditProfileViewModel vm = started();
        vm.saveProfile("  Davi A. ", "Fã do Wolverine", "DAVI@exemplo.com", "");

        EditProfileUiState state = vm.state().getValue();
        assertEquals("Davi A.", state.name);
        assertEquals("Fã do Wolverine", state.bio);
        assertEquals(Message.PROFILE_SAVED, lastMessage(vm));
        assertFalse(state.isBusy());
        assertEquals("os amigos veem o nome novo", 1, published.get());
    }

    @Test
    public void changingEmail_sendsAConfirmationLink_withTheCurrentPassword() {
        EditProfileViewModel vm = started();
        vm.saveProfile("Davi", null, "novo@exemplo.com", "errada#1");

        assertEquals(AccountStore.Error.WRONG_PASSWORD, vm.state().getValue().profileError);
        assertNull(cloud.pendingEmail);

        vm.saveProfile("Davi", null, "novo@exemplo.com", "segredo#1");
        assertNull(vm.state().getValue().profileError);
        assertEquals(Message.EMAIL_CHANGE_SENT, lastMessage(vm));
        assertEquals("novo@exemplo.com", cloud.pendingEmail);
        assertEquals("só muda depois do link", "davi@exemplo.com", vm.state().getValue().email);
    }

    @Test
    public void changingEmail_withoutInternet_saysSo() {
        EditProfileViewModel vm = started();
        cloud.offline = true;

        vm.saveProfile("Davi", null, "novo@exemplo.com", "segredo#1");

        assertEquals(AccountStore.Error.OFFLINE, vm.state().getValue().profileError);
    }

    @Test
    public void changePassword_checksCurrentAndRules() {
        EditProfileViewModel vm = started();
        vm.changePassword("errada#1", "nova#senha");
        assertEquals(AccountStore.Error.WRONG_PASSWORD, vm.state().getValue().passwordError);

        vm.changePassword("segredo#1", "123");
        assertEquals(AccountStore.Error.WEAK_PASSWORD, vm.state().getValue().passwordError);

        vm.changePassword("segredo#1", "nova#senha");
        assertEquals(Message.PASSWORD_CHANGED, lastMessage(vm));
        assertEquals("a senha não aparece para os amigos", 0, published.get());
        accounts.signOut();
        assertTrue(accounts.signIn("davi@exemplo.com", "nova#senha").isSuccess());
    }

    @Test
    public void changeImage_goesToTheAccount_andRemoveClearsIt() {
        EditProfileViewModel vm = started();
        vm.changeImage(ImageKind.AVATAR, null);
        String avatar = cloud.account(accounts.currentAccountId()).avatar;
        assertNotNull(avatar);
        assertEquals(avatar, vm.state().getValue().avatar);
        assertEquals(Message.IMAGE_UPDATED, lastMessage(vm));

        vm.removeImage(ImageKind.AVATAR);
        assertNull(cloud.account(accounts.currentAccountId()).avatar);
        assertNull(vm.state().getValue().avatar);
        assertEquals(Message.IMAGE_REMOVED, lastMessage(vm));
        assertEquals(2, published.get());
    }

    @Test
    public void unreadableImage_keepsCurrentImageAndWarns_withoutPublishing() {
        EditProfileViewModel vm = started();
        vm.changeImage(ImageKind.BANNER, null);
        String banner = cloud.account(accounts.currentAccountId()).banner;
        published.set(0);

        images.failNextImport = true;
        vm.changeImage(ImageKind.BANNER, null);

        assertEquals(Message.IMAGE_FAILED, lastMessage(vm));
        assertEquals(banner, cloud.account(accounts.currentAccountId()).banner);
        assertFalse(vm.state().getValue().isBusy());
        assertEquals("nada mudou para os amigos", 0, published.get());
    }

    @Test
    public void deleteAccount_needsThePassword_andErasesTheAccount() {
        EditProfileViewModel vm = started();
        String id = accounts.currentAccountId();

        vm.deleteAccount("errada#1");
        assertEquals(AccountStore.Error.WRONG_PASSWORD, vm.state().getValue().deleteError);
        assertNotNull(cloud.account(id));

        vm.clearDeleteError();
        vm.deleteAccount("segredo#1");
        assertEquals(Status.DELETED, vm.state().getValue().status);
        assertNull(accounts.currentAccount());
        assertNull(cloud.account(id));
        assertFalse(accounts.signIn("davi@exemplo.com", "segredo#1").isSuccess());
    }

    @Test
    public void deleteAccount_withoutInternet_keepsEverything() {
        EditProfileViewModel vm = started();
        cloud.offline = true;

        vm.deleteAccount("segredo#1");

        assertEquals(AccountStore.Error.OFFLINE, vm.state().getValue().deleteError);
        assertFalse(vm.state().getValue().isBusy());
        assertNotNull("a conta continua", accounts.currentAccount());
    }

    @Test
    public void unexpectedFailure_whileSaving_endsWithAnError() {
        EditProfileViewModel vm = started();
        cloud.operationFailure = new IllegalStateException("SDK sem inicializar");

        vm.changePassword("segredo#1", "nova#senha");

        assertEquals(AccountStore.Error.FAILED, vm.state().getValue().passwordError);
        assertFalse(vm.state().getValue().isBusy());
    }
}
