package com.ovigia.app.auth;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ovigia.app.util.Event;

import java.util.concurrent.Executor;

/**
 * Tela de login/cadastro: alterna o modo, entra ou cria a conta no
 * {@link AccountStore} (fora da main thread — é rede) e avisa o sucesso uma
 * única vez.
 */
public class AuthViewModel extends ViewModel {

    private static final String TAG = "AuthViewModel";

    private final AccountStore accountStore;
    private final Executor ioExecutor;
    private final Executor mainExecutor;

    private final MutableLiveData<AuthUiState> state = new MutableLiveData<>(AuthUiState.initial(AuthUiState.Mode.SIGN_IN));
    private final MutableLiveData<Event<AccountStore.Account>> signedIn = new MutableLiveData<>();

    public AuthViewModel(AccountStore accountStore, Executor ioExecutor, Executor mainExecutor) {
        this.accountStore = accountStore;
        this.ioExecutor = ioExecutor;
        this.mainExecutor = mainExecutor;
    }

    public LiveData<AuthUiState> state() { return state; }

    /** Disparado uma vez quando o login ou o cadastro dá certo. */
    public LiveData<Event<AccountStore.Account>> signedIn() { return signedIn; }

    public void setMode(AuthUiState.Mode mode) {
        AuthUiState current = state.getValue();
        if (current.loading || current.mode == mode) return;
        state.setValue(AuthUiState.initial(mode));
    }

    public void submit(String name, String email, String password) {
        AuthUiState current = state.getValue();
        if (current.loading) return;
        AuthUiState.Mode mode = current.mode;
        state.setValue(AuthUiState.loading(mode));
        ioExecutor.execute(() -> {
            AccountStore.Result result;
            try {
                result = mode == AuthUiState.Mode.SIGN_UP
                        ? accountStore.signUp(name, email, password)
                        : accountStore.signIn(email, password);
            } catch (RuntimeException e) {
                // Solta no executor, uma falha inesperada derrubaria o app.
                Log.e(TAG, "Falha inesperada ao entrar", e);
                result = null;
            }
            AccountStore.Result finished = result;
            mainExecutor.execute(() -> finish(mode, finished));
        });
    }

    private void finish(AuthUiState.Mode mode, @Nullable AccountStore.Result result) {
        if (result != null && result.isSuccess() && result.account != null) {
            state.setValue(AuthUiState.initial(mode));
            signedIn.setValue(new Event<>(result.account));
        } else {
            state.setValue(AuthUiState.failed(mode, result == null ? AccountStore.Error.FAILED : result.error));
        }
    }

    public static final class Factory implements ViewModelProvider.Factory {

        private final AccountStore accountStore;
        private final Executor ioExecutor;
        private final Executor mainExecutor;

        public Factory(AccountStore accountStore, Executor ioExecutor, Executor mainExecutor) {
            this.accountStore = accountStore;
            this.ioExecutor = ioExecutor;
            this.mainExecutor = mainExecutor;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new AuthViewModel(accountStore, ioExecutor, mainExecutor);
        }
    }
}
