package com.ovigia.app.profile;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.auth.AccountStore.Error;
import com.ovigia.app.auth.AccountStore.ImageKind;
import com.ovigia.app.profile.EditProfileUiState.Busy;
import com.ovigia.app.profile.EditProfileUiState.Message;
import com.ovigia.app.profile.EditProfileUiState.Status;
import com.ovigia.app.util.Event;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Edição do perfil da conta logada: nome e bio, e-mail (com link de
 * confirmação), senha, foto, banner e exclusão da conta. Tudo vai para a conta
 * online no {@code ioExecutor}; uma operação por vez.
 */
public class EditProfileViewModel extends ViewModel {

    private static final String TAG = "EditProfileViewModel";

    private final AccountStore accountStore;
    private final ProfileImages images;
    /** Nome, bio, foto ou banner mudaram: o que os amigos veem precisa ser publicado de novo. */
    private final Runnable profileChanged;
    private final Executor ioExecutor;
    private final Executor mainExecutor;

    private final MutableLiveData<EditProfileUiState> state = new MutableLiveData<>(EditProfileUiState.of(Status.LOADING));
    private final MutableLiveData<Event<Message>> messages = new MutableLiveData<>();
    private boolean started = false;

    public EditProfileViewModel(AccountStore accountStore, ProfileImages images, Runnable profileChanged,
                                Executor ioExecutor, Executor mainExecutor) {
        this.accountStore = accountStore;
        this.images = images;
        this.profileChanged = profileChanged;
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
            mainExecutor.execute(() -> {
                if (current == null) {
                    state.setValue(EditProfileUiState.of(Status.SIGNED_OUT));
                } else {
                    publish(current, Busy.NONE);
                }
            });
        });
    }

    /**
     * Grava nome e bio. Se o e-mail mudou, pede a troca (com a senha atual): o
     * servidor manda um link para o e-mail novo, que só passa a valer depois
     * que o jogador clicar nele.
     */
    public void saveProfile(String name, String bio, String email, String currentPassword) {
        EditProfileUiState current = state.getValue();
        if (!canAct(current)) return;
        boolean emailChanged = !AccountStore.normalizeEmail(email).equals(AccountStore.normalizeEmail(current.email));
        submit(Busy.PROFILE, emailChanged ? Message.EMAIL_CHANGE_SENT : Message.PROFILE_SAVED, true, () -> {
            AccountStore.Result saved = accountStore.updateProfile(name, bio);
            if (!saved.isSuccess() || !emailChanged) return saved;
            return accountStore.requestEmailChange(email, currentPassword);
        });
    }

    public void changePassword(String currentPassword, String newPassword) {
        submit(Busy.PASSWORD, Message.PASSWORD_CHANGED, false,
                () -> accountStore.changePassword(currentPassword, newPassword));
    }

    /** Reduz a imagem escolhida e troca a foto ou o banner. */
    public void changeImage(ImageKind kind, Uri source) {
        EditProfileUiState current = state.getValue();
        if (!canAct(current)) return;
        markBusy(current, Busy.IMAGE);
        ioExecutor.execute(() -> {
            String encoded = null;
            try {
                encoded = images.encode(source, kind);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Falha ao importar imagem", e);
            }
            if (encoded == null) {
                AccountStore.Result signedOut = accountStore.currentAccountId() == null
                        ? AccountStore.Result.failed(Error.NOT_SIGNED_IN) : null;
                mainExecutor.execute(() -> finish(signedOut, Busy.IMAGE, Message.IMAGE_FAILED, false));
                return;
            }
            AccountStore.Result result = accountStore.setImage(kind, encoded);
            mainExecutor.execute(() -> finish(result, Busy.IMAGE, Message.IMAGE_UPDATED, true));
        });
    }

    public void removeImage(ImageKind kind) {
        submit(Busy.IMAGE, Message.IMAGE_REMOVED, true, () -> accountStore.setImage(kind, null));
    }

    /**
     * Exclui a conta logada — os dados dela, o perfil que os amigos viam e o
     * login. Precisa de internet: sem ela, nada é excluído.
     */
    public void deleteAccount(String currentPassword) {
        EditProfileUiState current = state.getValue();
        if (!canAct(current)) return;
        markBusy(current, Busy.DELETE);
        ioExecutor.execute(() -> {
            AccountStore.Result result = accountStore.deleteCurrentAccount(currentPassword);
            mainExecutor.execute(() -> {
                if (result.isSuccess()) {
                    state.setValue(EditProfileUiState.of(Status.DELETED));
                } else {
                    finish(result, Busy.DELETE, null, false);
                }
            });
        });
    }

    /** Limpa o erro de exclusão (ex.: ao fechar o diálogo). */
    public void clearDeleteError() {
        EditProfileUiState s = state.getValue();
        if (s == null || s.deleteError == null) return;
        state.setValue(new EditProfileUiState(s.status, s.busy, s.name, s.email, s.bio,
                s.avatar, s.banner, s.profileError, s.passwordError, null));
    }

    /**
     * @param changesProfile o sucesso muda o que os amigos veem (nome, bio,
     *                       imagens): o perfil público é publicado de novo
     */
    private void submit(Busy section, Message successMessage, boolean changesProfile,
                        Supplier<AccountStore.Result> work) {
        EditProfileUiState current = state.getValue();
        if (!canAct(current)) return;
        markBusy(current, section);
        ioExecutor.execute(() -> {
            AccountStore.Result result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                Log.w(TAG, "Falha inesperada ao salvar o perfil", e);
                result = AccountStore.Result.failed(Error.FAILED);
            }
            AccountStore.Result finished = result;
            mainExecutor.execute(() -> finish(finished, section, successMessage, changesProfile));
        });
    }

    private static boolean canAct(EditProfileUiState current) {
        return current != null && current.status == Status.READY && !current.isBusy();
    }

    private void markBusy(EditProfileUiState current, Busy busy) {
        state.setValue(new EditProfileUiState(current.status, busy, current.name, current.email, current.bio,
                current.avatar, current.banner, null, null, null));
    }

    /**
     * @param result {@code null} = nada mudou (ex.: a imagem escolhida não abriu),
     *               mas a operação terminou e a mensagem vale
     */
    private void finish(@Nullable AccountStore.Result result, Busy section, @Nullable Message message,
                        boolean changesProfile) {
        if (result != null && result.error == Error.NOT_SIGNED_IN) {
            state.setValue(EditProfileUiState.of(Status.SIGNED_OUT));
            return;
        }
        EditProfileUiState s = state.getValue();
        if (result == null || result.isSuccess()) {
            if (result != null && result.account != null) {
                publish(result.account, Busy.NONE);
            } else {
                state.setValue(new EditProfileUiState(Status.READY, Busy.NONE, s.name, s.email, s.bio,
                        s.avatar, s.banner, null, null, null));
            }
            if (message != null) messages.setValue(new Event<>(message));
            if (result != null && changesProfile) profileChanged.run();
            return;
        }
        // Falhou: mantém os dados já na tela e mostra o erro na seção.
        state.setValue(new EditProfileUiState(Status.READY, Busy.NONE, s.name, s.email, s.bio,
                s.avatar, s.banner,
                section == Busy.PROFILE ? result.error : null,
                section == Busy.PASSWORD ? result.error : null,
                section == Busy.DELETE ? result.error : null));
    }

    private void publish(AccountStore.Account a, Busy busy) {
        state.setValue(new EditProfileUiState(Status.READY, busy, a.name, a.email, a.bio,
                a.avatar, a.banner, null, null, null));
    }

    public static final class Factory implements ViewModelProvider.Factory {

        private final AccountStore accountStore;
        private final ProfileImages images;
        private final Runnable profileChanged;
        private final Executor ioExecutor;
        private final Executor mainExecutor;

        public Factory(AccountStore accountStore, ProfileImages images, Runnable profileChanged,
                       Executor ioExecutor, Executor mainExecutor) {
            this.accountStore = accountStore;
            this.images = images;
            this.profileChanged = profileChanged;
            this.ioExecutor = ioExecutor;
            this.mainExecutor = mainExecutor;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new EditProfileViewModel(accountStore, images, profileChanged, ioExecutor, mainExecutor);
        }
    }
}
