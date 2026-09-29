package com.ovigia.app.profile;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.auth.AccountStore.Error;
import com.ovigia.app.auth.AccountStore.ImageKind;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.social.AchievementsStore;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.profile.EditProfileUiState.Busy;
import com.ovigia.app.profile.EditProfileUiState.Message;
import com.ovigia.app.profile.EditProfileUiState.Status;
import com.ovigia.app.social.SocialException;
import com.ovigia.app.util.Event;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Edição do perfil da conta logada: dados (nome, bio, e-mail), senha, foto,
 * banner e exclusão da conta. Todo acesso a disco (inclusive resolver o caminho
 * das imagens) e o hash de senha rodam no {@code ioExecutor}; uma operação por vez.
 */
public class EditProfileViewModel extends ViewModel {

    private static final String TAG = "EditProfileViewModel";

    /** O que a edição precisa avisar à conta online (amigos), se houver uma. */
    public interface OnlineAccount {
        OnlineAccount NONE = new OnlineAccount() {
            @Override public void profileChanged() { }
            @Override public void passwordChanged(AccountStore.Account account, String current, String newPassword) { }
            @Override public void deleteOnline(AccountStore.Account account, String password) { }
        };

        /** Nome, bio, foto ou banner mudaram. Não bloqueia. */
        void profileChanged();

        /** A senha local mudou. Não bloqueia. */
        void passwordChanged(AccountStore.Account account, String currentPassword, String newPassword);

        /** Bloqueante (rede). Lança quando a conta local não deve ser excluída ainda. */
        void deleteOnline(AccountStore.Account account, String password) throws SocialException;
    }

    private final AccountStore accountStore;
    private final CollectionStore collectionStore;
    private final LearningStore learningStore;
    private final AchievementsStore achievementsStore;
    private final ProfileImages images;
    private final OnlineAccount online;
    private final Executor onlineExecutor;
    private final Executor ioExecutor;
    private final Executor mainExecutor;

    private final MutableLiveData<EditProfileUiState> state = new MutableLiveData<>(EditProfileUiState.of(Status.LOADING));
    private final MutableLiveData<Event<Message>> messages = new MutableLiveData<>();
    private boolean started = false;

    public EditProfileViewModel(AccountStore accountStore, CollectionStore collectionStore,
                                LearningStore learningStore, AchievementsStore achievementsStore,
                                ProfileImages images, Executor ioExecutor, Executor mainExecutor) {
        this(accountStore, collectionStore, learningStore, achievementsStore, images, OnlineAccount.NONE,
                ioExecutor, ioExecutor, mainExecutor);
    }

    /** @param onlineExecutor onde roda a exclusão da conta online (rede) */
    public EditProfileViewModel(AccountStore accountStore, CollectionStore collectionStore,
                                LearningStore learningStore, AchievementsStore achievementsStore,
                                ProfileImages images, OnlineAccount online, Executor onlineExecutor,
                                Executor ioExecutor, Executor mainExecutor) {
        this.accountStore = accountStore;
        this.collectionStore = collectionStore;
        this.learningStore = learningStore;
        this.achievementsStore = achievementsStore;
        this.images = images;
        this.online = online;
        this.onlineExecutor = onlineExecutor;
        this.ioExecutor = ioExecutor;
        this.mainExecutor = mainExecutor;
    }

    public LiveData<EditProfileUiState> state() { return state; }

    public LiveData<Event<Message>> messages() { return messages; }

    public void start() {
        if (started) return;
        started = true;
        ioExecutor.execute(() -> {
            AccountStore.Account current = accountStore.currentAccount();
            Outcome outcome = current == null ? null : Outcome.of(current, null, images);
            mainExecutor.execute(() -> {
                if (outcome == null) {
                    state.setValue(EditProfileUiState.of(Status.SIGNED_OUT));
                } else {
                    publish(outcome, Busy.NONE);
                }
            });
        });
    }

    public void saveProfile(String name, String bio, String email, String currentPassword) {
        submit(Busy.PROFILE, Message.PROFILE_SAVED,
                () -> accountStore.updateProfile(name, bio, email, currentPassword));
    }

    public void changePassword(String currentPassword, String newPassword) {
        submit(Busy.PASSWORD, Message.PASSWORD_CHANGED, () -> {
            AccountStore.Result result = accountStore.changePassword(currentPassword, newPassword);
            if (result.isSuccess()) online.passwordChanged(result.account, currentPassword, newPassword);
            return result;
        });
    }

    /** Importa a imagem escolhida e troca a foto ou o banner, apagando o arquivo anterior. */
    public void changeImage(ImageKind kind, Uri source) {
        EditProfileUiState current = state.getValue();
        if (!canAct(current)) return;
        markBusy(current, Busy.IMAGE);
        ioExecutor.execute(() -> {
            AccountStore.Account before = accountStore.currentAccount();
            Outcome outcome;
            Message message;
            if (before == null) {
                outcome = new Outcome(null, Error.NOT_SIGNED_IN, null, null);
                message = null;
            } else {
                String imported = null;
                try {
                    imported = images.importImage(source, kind, before.id);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "Falha ao importar imagem", e);
                }
                if (imported == null) {
                    outcome = Outcome.of(before, null, images);
                    message = Message.IMAGE_FAILED;
                } else {
                    AccountStore.Result result = accountStore.setImage(kind, imported);
                    images.delete(result.isSuccess() ? before.imageFile(kind) : imported);
                    outcome = Outcome.of(result, images);
                    message = Message.IMAGE_UPDATED;
                }
            }
            mainExecutor.execute(() -> finish(outcome, Busy.IMAGE, message));
        });
    }

    public void removeImage(ImageKind kind) {
        EditProfileUiState current = state.getValue();
        if (!canAct(current)) return;
        markBusy(current, Busy.IMAGE);
        ioExecutor.execute(() -> {
            AccountStore.Account before = accountStore.currentAccount();
            AccountStore.Result result = accountStore.setImage(kind, null);
            if (result.isSuccess() && before != null) images.delete(before.imageFile(kind));
            Outcome outcome = Outcome.of(result, images);
            mainExecutor.execute(() -> finish(outcome, Busy.IMAGE, Message.IMAGE_REMOVED));
        });
    }

    /**
     * Exclui a conta logada, a coleção dela e as imagens do perfil. Se ela está
     * ligada aos amigos online, a conta online é apagada antes; sem conexão, nada
     * é excluído ({@link Error#ONLINE_UNAVAILABLE}).
     */
    public void deleteAccount(String currentPassword) {
        EditProfileUiState current = state.getValue();
        if (!canAct(current)) return;
        markBusy(current, Busy.DELETE);
        ioExecutor.execute(() -> {
            AccountStore.Account account = accountStore.currentAccount();
            if (account == null) {
                failDelete(Error.NOT_SIGNED_IN);
                return;
            }
            if (!accountStore.verifyPassword(currentPassword)) {
                failDelete(Error.WRONG_PASSWORD);
                return;
            }
            if (account.cloudUid == null) {
                deleteLocal(currentPassword);
                return;
            }
            onlineExecutor.execute(() -> {
                try {
                    online.deleteOnline(account, currentPassword);
                } catch (SocialException e) {
                    failDelete(Error.ONLINE_UNAVAILABLE);
                    return;
                }
                ioExecutor.execute(() -> deleteLocal(currentPassword));
            });
        });
    }

    private void deleteLocal(String currentPassword) {
        AccountStore.Result result = accountStore.deleteCurrentAccount(currentPassword);
        if (result.isSuccess()) {
            collectionStore.deleteAccount(result.account.id);
            learningStore.deleteAccount(result.account.id);
            achievementsStore.deleteAccount(result.account.id);
            images.delete(result.account.avatarFile);
            images.delete(result.account.bannerFile);
        }
        mainExecutor.execute(() -> {
            if (result.isSuccess()) {
                state.setValue(EditProfileUiState.of(Status.DELETED));
            } else {
                finish(Outcome.of(result, null), Busy.DELETE, null);
            }
        });
    }

    private void failDelete(Error error) {
        mainExecutor.execute(() -> finish(new Outcome(null, error, null, null), Busy.DELETE, null));
    }

    /** Limpa o erro de exclusão (ex.: ao fechar o diálogo). */
    public void clearDeleteError() {
        EditProfileUiState s = state.getValue();
        if (s == null || s.deleteError == null) return;
        state.setValue(new EditProfileUiState(s.status, s.busy, s.name, s.email, s.bio,
                s.avatarFile, s.bannerFile, s.profileError, s.passwordError, null));
    }

    private void submit(Busy section, Message successMessage, Supplier<AccountStore.Result> work) {
        EditProfileUiState current = state.getValue();
        if (!canAct(current)) return;
        markBusy(current, section);
        ioExecutor.execute(() -> {
            Outcome outcome = Outcome.of(work.get(), images);
            mainExecutor.execute(() -> finish(outcome, section, successMessage));
        });
    }

    private static boolean canAct(EditProfileUiState current) {
        return current != null && current.status == Status.READY && !current.isBusy();
    }

    private void markBusy(EditProfileUiState current, Busy busy) {
        state.setValue(new EditProfileUiState(current.status, busy, current.name, current.email, current.bio,
                current.avatarFile, current.bannerFile, null, null, null));
    }

    private void finish(Outcome outcome, Busy section, Message successMessage) {
        if (outcome.error == Error.NOT_SIGNED_IN) {
            state.setValue(EditProfileUiState.of(Status.SIGNED_OUT));
            return;
        }
        if (outcome.error == null) {
            publish(outcome, Busy.NONE);
            if (successMessage != null) messages.setValue(new Event<>(successMessage));
            if (successMessage != Message.PASSWORD_CHANGED) online.profileChanged();
            return;
        }
        // Falhou: mantém os dados já na tela e mostra o erro na seção.
        EditProfileUiState s = state.getValue();
        state.setValue(new EditProfileUiState(Status.READY, Busy.NONE, s.name, s.email, s.bio,
                s.avatarFile, s.bannerFile,
                section == Busy.PROFILE ? outcome.error : null,
                section == Busy.PASSWORD ? outcome.error : null,
                section == Busy.DELETE ? outcome.error : null));
    }

    private void publish(Outcome outcome, Busy busy) {
        AccountStore.Account a = outcome.account;
        state.setValue(new EditProfileUiState(Status.READY, busy, a.name, a.email, a.bio,
                outcome.avatarFile, outcome.bannerFile, null, null, null));
    }

    /** Resultado de uma operação com os arquivos de imagem já resolvidos (no I/O). */
    private static final class Outcome {
        final AccountStore.Account account;
        final Error error;
        final File avatarFile;
        final File bannerFile;

        private Outcome(AccountStore.Account account, Error error, File avatarFile, File bannerFile) {
            this.account = account;
            this.error = error;
            this.avatarFile = avatarFile;
            this.bannerFile = bannerFile;
        }

        static Outcome of(AccountStore.Account account, Error error, ProfileImages images) {
            return new Outcome(account, error, images.file(account.avatarFile), images.file(account.bannerFile));
        }

        /** {@code images} pode ser null quando o resultado é sabidamente uma falha. */
        static Outcome of(AccountStore.Result result, ProfileImages images) {
            if (!result.isSuccess() || images == null) return new Outcome(result.account, result.error, null, null);
            return of(result.account, null, images);
        }
    }

    public static final class Factory implements ViewModelProvider.Factory {

        private final AccountStore accountStore;
        private final CollectionStore collectionStore;
        private final LearningStore learningStore;
        private final AchievementsStore achievementsStore;
        private final ProfileImages images;
        private final OnlineAccount online;
        private final Executor onlineExecutor;
        private final Executor ioExecutor;
        private final Executor mainExecutor;

        public Factory(AccountStore accountStore, CollectionStore collectionStore, LearningStore learningStore,
                       AchievementsStore achievementsStore, ProfileImages images, OnlineAccount online,
                       Executor onlineExecutor, Executor ioExecutor, Executor mainExecutor) {
            this.accountStore = accountStore;
            this.collectionStore = collectionStore;
            this.learningStore = learningStore;
            this.achievementsStore = achievementsStore;
            this.images = images;
            this.online = online;
            this.onlineExecutor = onlineExecutor;
            this.ioExecutor = ioExecutor;
            this.mainExecutor = mainExecutor;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new EditProfileViewModel(accountStore, collectionStore, learningStore, achievementsStore,
                    images, online, onlineExecutor, ioExecutor, mainExecutor);
        }
    }
}
