package com.ovigia.app.profile;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.auth.AccountStore.ImageKind;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.social.AchievementsStore;
import com.ovigia.app.profile.EditProfileUiState.Message;
import com.ovigia.app.profile.EditProfileUiState.Status;
import com.ovigia.app.social.SocialException;

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
import static org.junit.Assert.assertTrue;

/** Edição do perfil com contas e coleção em disco temporário e imagens falsas. */
public class EditProfileViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;
    private AccountStore accounts;
    private CollectionStore collection;
    private LearningStore learning;
    private AchievementsStore achievements;
    private FakeProfileImages images;

    @Before
    public void setUp() {
        accounts = new AccountStore(() -> new File(tmp.getRoot(), "accounts.json"), 1_000);
        collection = new CollectionStore(() -> new File(tmp.getRoot(), "collection.json"));
        learning = new LearningStore(() -> new File(tmp.getRoot(), "learning.json"), direct);
        achievements = new AchievementsStore(() -> new File(tmp.getRoot(), "achievements.json"));
        images = new FakeProfileImages(tmp.getRoot());
        accounts.signUp("Davi", "davi@exemplo.com", "segredo#1");
    }

    private EditProfileViewModel started() {
        EditProfileViewModel vm = new EditProfileViewModel(accounts, collection, learning, achievements, images,
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
        assertNull(state.avatarFile);

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
    }

    @Test
    public void changingEmail_requiresTheCurrentPassword() {
        EditProfileViewModel vm = started();
        vm.saveProfile("Davi", null, "novo@exemplo.com", "errada");

        assertEquals(AccountStore.Error.WRONG_PASSWORD, vm.state().getValue().profileError);
        assertEquals("nada muda quando falha", "davi@exemplo.com", accounts.currentAccount().email);

        vm.saveProfile("Davi", null, "novo@exemplo.com", "segredo#1");
        assertNull(vm.state().getValue().profileError);
        assertEquals("novo@exemplo.com", accounts.currentAccount().email);
    }

    @Test
    public void changePassword_checksCurrentAndLength() {
        EditProfileViewModel vm = started();
        vm.changePassword("errada", "nova#senha");
        assertEquals(AccountStore.Error.WRONG_PASSWORD, vm.state().getValue().passwordError);

        vm.changePassword("segredo#1", "123");
        assertEquals(AccountStore.Error.WEAK_PASSWORD, vm.state().getValue().passwordError);

        vm.changePassword("segredo#1", "nova#senha");
        assertEquals(Message.PASSWORD_CHANGED, lastMessage(vm));
        accounts.signOut();
        assertTrue(accounts.signIn("davi@exemplo.com", "nova#senha").isSuccess());
    }

    @Test
    public void changeImage_replacesAndDeletesPreviousFile_andRemoveClearsIt() {
        EditProfileViewModel vm = started();
        vm.changeImage(ImageKind.AVATAR, null);
        String first = accounts.currentAccount().avatarFile;
        assertNotNull(first);
        assertEquals(new File(tmp.getRoot(), first), vm.state().getValue().avatarFile);

        vm.changeImage(ImageKind.AVATAR, null);
        assertTrue("a foto antiga é apagada", images.deleted.contains(first));

        vm.removeImage(ImageKind.AVATAR);
        assertNull(accounts.currentAccount().avatarFile);
        assertNull(vm.state().getValue().avatarFile);
        assertEquals(Message.IMAGE_REMOVED, lastMessage(vm));
    }

    @Test
    public void unreadableImage_keepsCurrentImageAndWarns() {
        EditProfileViewModel vm = started();
        vm.changeImage(ImageKind.BANNER, null);
        String banner = accounts.currentAccount().bannerFile;

        images.failNextImport = true;
        vm.changeImage(ImageKind.BANNER, null);

        assertEquals(Message.IMAGE_FAILED, lastMessage(vm));
        assertEquals(banner, accounts.currentAccount().bannerFile);
        assertFalse(vm.state().getValue().isBusy());
    }

    @Test
    public void deleteAccount_removesAccountCollectionAndImages() {
        String id = accounts.currentAccount().id;
        collection.save(id, 7, "Wolverine", "img");
        achievements.claimNewlyUnlocked(id, java.util.Collections.emptyList());
        learning.recordGame(id, 7, java.util.Collections.emptyList(), LearningStore.Outcome.ENGINE_GUESSED);
        EditProfileViewModel vm = started();
        vm.changeImage(ImageKind.AVATAR, null);
        String avatar = accounts.currentAccount().avatarFile;

        vm.deleteAccount("errada");
        assertEquals(AccountStore.Error.WRONG_PASSWORD, vm.state().getValue().deleteError);
        assertNotNull(accounts.currentAccount());

        vm.deleteAccount("segredo#1");
        assertEquals(Status.DELETED, vm.state().getValue().status);
        assertNull(accounts.currentAccount());
        assertFalse(accounts.signIn("davi@exemplo.com", "segredo#1").isSuccess());
        assertTrue(collection.list(id).isEmpty());
        assertEquals("aprendizado da conta some junto", 0, learning.stats(id).gamesPlayed);
        assertFalse("conquistas comemoradas somem junto", achievements.isTracking(id));
        assertTrue(images.deleted.contains(avatar));
    }

    /** Conta online de mentira: registra o que a edição avisou. */
    private static final class RecordingOnline implements EditProfileViewModel.OnlineAccount {
        int profileChanges = 0;
        String newPassword;
        boolean offline = false;
        boolean deleted = false;

        @Override
        public void profileChanged() {
            profileChanges++;
        }

        @Override
        public void passwordChanged(AccountStore.Account account, String current, String newPassword) {
            this.newPassword = newPassword;
        }

        @Override
        public void deleteOnline(AccountStore.Account account, String password) throws SocialException {
            if (offline) throw new SocialException(SocialException.Error.OFFLINE);
            deleted = true;
        }
    }

    private EditProfileViewModel startedWith(RecordingOnline online) {
        EditProfileViewModel vm = new EditProfileViewModel(accounts, collection, learning, achievements, images, online,
                direct, direct, direct);
        vm.start();
        return vm;
    }

    @Test
    public void onlineAccount_hearsAboutProfileAndPasswordChanges() {
        RecordingOnline online = new RecordingOnline();
        EditProfileViewModel vm = startedWith(online);

        vm.saveProfile("Davi", "Nova bio", "davi@exemplo.com", "");
        vm.changeImage(ImageKind.AVATAR, null);
        assertEquals(2, online.profileChanges);

        vm.changePassword("segredo#1", "nova#senha");
        assertEquals("nova#senha", online.newPassword);
        assertEquals("trocar a senha não muda o que os amigos veem", 2, online.profileChanges);
    }

    @Test
    public void deleteLinkedAccountOffline_keepsEverything() {
        accounts.linkCloud(accounts.currentAccount().id, "uid-1", "davi@exemplo.com");
        RecordingOnline online = new RecordingOnline();
        online.offline = true;
        EditProfileViewModel vm = startedWith(online);

        vm.deleteAccount("segredo#1");

        assertEquals(AccountStore.Error.ONLINE_UNAVAILABLE, vm.state().getValue().deleteError);
        assertNotNull("conta local continua", accounts.currentAccount());

        online.offline = false;
        vm.clearDeleteError();
        vm.deleteAccount("segredo#1");
        assertTrue(online.deleted);
        assertEquals(Status.DELETED, vm.state().getValue().status);
        assertNull(accounts.currentAccount());
    }

    @Test
    public void deleteWithWrongPassword_neverTouchesTheOnlineAccount() {
        accounts.linkCloud(accounts.currentAccount().id, "uid-1", "davi@exemplo.com");
        RecordingOnline online = new RecordingOnline();
        EditProfileViewModel vm = startedWith(online);

        vm.deleteAccount("errada");

        assertEquals(AccountStore.Error.WRONG_PASSWORD, vm.state().getValue().deleteError);
        assertFalse(online.deleted);
    }
}
