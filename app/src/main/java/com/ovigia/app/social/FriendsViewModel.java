package com.ovigia.app.social;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.ovigia.app.social.FriendsUiState.Message;
import com.ovigia.app.social.FriendsUiState.Search;
import com.ovigia.app.social.FriendsUiState.Status;
import com.ovigia.app.util.Event;

import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

/**
 * Aba de amigos: conduz a conta até ficar online (senha → @usuario) e depois
 * cuida da busca, dos pedidos e da lista de amigos. A rede roda no executor
 * social; o estado só muda na main thread.
 */
public class FriendsViewModel extends ViewModel {

    private static final String TAG = "FriendsViewModel";

    private final SocialRepository repository;
    private final Executor socialExecutor;
    private final Executor mainExecutor;

    private final MutableLiveData<FriendsUiState> state = new MutableLiveData<>(FriendsUiState.of(Status.LOADING));
    private final MutableLiveData<Event<Message>> messages = new MutableLiveData<>();
    private boolean started = false;

    public FriendsViewModel(SocialRepository repository, Executor socialExecutor, Executor mainExecutor) {
        this.repository = repository;
        this.socialExecutor = socialExecutor;
        this.mainExecutor = mainExecutor;
    }

    public LiveData<FriendsUiState> state() { return state; }

    public LiveData<Event<Message>> messages() { return messages; }

    /** Idempotente: carrega uma vez por ViewModel. */
    public void start() {
        if (started) return;
        started = true;
        socialExecutor.execute(this::loadBlocking);
    }

    /** Recarrega amigos e pedidos mantendo o que está na tela. */
    public void refresh() {
        FriendsUiState current = state.getValue();
        if (current.working || current.status == Status.LOADING) return;
        state.setValue(current.withWorking(true));
        socialExecutor.execute(this::loadBlocking);
    }

    // ---------------------------------------------------------------- conectar

    public void connect(String password) {
        FriendsUiState current = state.getValue();
        if (current.status != Status.NEEDS_CONNECTION || current.working) return;
        if (password == null || password.isEmpty()) {
            state.setValue(current.withError(SocialException.Error.WRONG_PASSWORD));
            return;
        }
        state.setValue(current.withWorking(true));
        socialExecutor.execute(() -> {
            try {
                repository.connect(password);
                message(Message.CONNECTED);
                loadBlocking();
            } catch (SocialException e) {
                post(s -> s.withError(e.error));
            }
        });
    }

    public void claimUsername(String raw) {
        FriendsUiState current = state.getValue();
        if (current.status != Status.NEEDS_USERNAME || current.working) return;
        if (!Username.isValid(Username.normalize(raw))) {
            state.setValue(current.withError(SocialException.Error.USERNAME_INVALID));
            return;
        }
        state.setValue(current.withWorking(true));
        socialExecutor.execute(() -> {
            try {
                repository.claimUsername(raw);
                message(Message.USERNAME_SAVED);
                loadBlocking();
            } catch (SocialException e) {
                post(s -> s.withError(e.error));
            }
        });
    }

    // ---------------------------------------------------------------- busca

    public void search(String raw) {
        FriendsUiState current = state.getValue();
        if (current.status != Status.READY || current.search.searching) return;
        if (!Username.isValid(Username.normalize(raw))) {
            state.setValue(current.withSearch(new Search(false, null, null, SocialException.Error.USERNAME_INVALID)));
            return;
        }
        state.setValue(current.withSearch(new Search(true, null, null, null)));
        socialExecutor.execute(() -> {
            try {
                UserCard card = repository.findByUsername(raw);
                post(s -> s.withSearch(new Search(false, card,
                        s.me == null ? FriendsHub.Relationship.NONE : s.hub.relationshipWith(s.me.uid, card.uid), null)));
            } catch (SocialException e) {
                post(s -> s.withSearch(new Search(false, null, null, e.error)));
            }
        });
    }

    public void clearSearch() {
        FriendsUiState current = state.getValue();
        if (current.search.searching) return;
        state.setValue(current.withSearch(Search.IDLE));
    }

    // ---------------------------------------------------------------- pedidos

    public void sendRequest(UserCard card) {
        FriendsUiState current = state.getValue();
        FriendsHub hub = current.hub;
        boolean theyAsked = hub.incomingFrom(card.uid) != null;
        runAction(card.uid, () -> repository.sendRequest(card, hub),
                theyAsked ? Message.BECAME_FRIENDS : Message.REQUEST_SENT);
    }

    public void accept(String fromUid) {
        FriendsHub hub = state.getValue().hub;
        runAction(fromUid, () -> repository.accept(fromUid, hub), Message.BECAME_FRIENDS);
    }

    public void decline(String fromUid) {
        runAction(fromUid, () -> repository.decline(fromUid), Message.REQUEST_DECLINED);
    }

    public void cancel(String toUid) {
        runAction(toUid, () -> repository.cancel(toUid), Message.REQUEST_CANCELED);
    }

    private interface Action {
        void run() throws SocialException;
    }

    private void runAction(String uid, Action action, Message success) {
        FriendsUiState current = state.getValue();
        if (current.status != Status.READY || current.busyUids.contains(uid)) return;
        state.setValue(current.withBusy(uid, true));
        socialExecutor.execute(() -> {
            try {
                action.run();
            } catch (SocialException e) {
                post(s -> s.withBusy(uid, false));
                if (e.error == SocialException.Error.NOT_CONNECTED) {
                    loadBlocking();
                } else {
                    message(failureMessage(e.error));
                }
                return;
            }
            message(success);
            try {
                FriendsHub hub = repository.hub();
                post(s -> s.withHub(hub).withBusy(uid, false));
            } catch (SocialException e) {
                // A ação valeu; só a lista não atualizou. O próximo "atualizar" resolve.
                post(s -> s.withBusy(uid, false));
            }
        });
    }

    // ---------------------------------------------------------------- apoio

    /**
     * Roda no executor social: descobre em que passo a conta está e, se online, carrega amigos e pedidos.
     * Uma exceção solta aqui derrubaria o app inteiro, então qualquer falha inesperada vira o passo de erro
     * (com "tentar de novo") em vez de crash.
     */
    private void loadBlocking() {
        try {
            load();
        } catch (RuntimeException e) {
            Log.e(TAG, "Falha inesperada ao carregar os amigos", e);
            mainExecutor.execute(() -> showLoadFailure(SocialException.Error.UNKNOWN, state.getValue().me));
        }
    }

    private void load() {
        SocialRepository.Session session = repository.session();
        if (session.status != SocialRepository.Status.READY) {
            String suggestion = session.account == null ? "" : Username.suggestFrom(session.account.name);
            post(s -> s.withStatus(statusFor(session.status)).withMe(null).withSuggestion(suggestion)
                    .withWorking(false).withError(null));
            return;
        }
        try {
            FriendsHub hub = repository.hub();
            // Abrir a aba mantém o próprio perfil em dia para os amigos.
            repository.publishQuietly();
            post(s -> s.withStatus(Status.READY).withMe(session.card).withHub(hub).withWorking(false).withError(null));
        } catch (SocialException e) {
            mainExecutor.execute(() -> showLoadFailure(e.error, session.card));
        }
    }

    /** Na main thread: com a lista já na tela, uma falha ao atualizar não apaga nada; senão vira o passo de erro. */
    private void showLoadFailure(SocialException.Error error, @Nullable UserCard me) {
        FriendsUiState s = state.getValue().withMe(me);
        if (s.status == Status.READY) {
            state.setValue(s.withWorking(false));
            messages.setValue(new Event<>(failureMessage(error)));
        } else {
            state.setValue(s.withStatus(Status.ERROR).withError(error));
        }
    }

    private static Message failureMessage(SocialException.Error error) {
        return error == SocialException.Error.OFFLINE ? Message.ACTION_FAILED_OFFLINE : Message.ACTION_FAILED;
    }

    private static Status statusFor(SocialRepository.Status status) {
        switch (status) {
            case NOT_CONFIGURED: return Status.NOT_CONFIGURED;
            case SIGNED_OUT: return Status.SIGNED_OUT;
            case NEEDS_CONNECTION: return Status.NEEDS_CONNECTION;
            case NEEDS_USERNAME: return Status.NEEDS_USERNAME;
            case READY:
            default: return Status.READY;
        }
    }

    private void post(UnaryOperator<FriendsUiState> change) {
        mainExecutor.execute(() -> state.setValue(change.apply(state.getValue())));
    }

    private void message(Message message) {
        mainExecutor.execute(() -> messages.setValue(new Event<>(message)));
    }

    public static final class Factory implements ViewModelProvider.Factory {

        private final SocialRepository repository;
        private final Executor socialExecutor;
        private final Executor mainExecutor;

        public Factory(SocialRepository repository, Executor socialExecutor, Executor mainExecutor) {
            this.repository = repository;
            this.socialExecutor = socialExecutor;
            this.mainExecutor = mainExecutor;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new FriendsViewModel(repository, socialExecutor, mainExecutor);
        }
    }
}
