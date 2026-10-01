package com.ovigia.app.ui.game;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.ovigia.app.AppContainer;
import com.ovigia.app.OVigiaApplication;
import com.ovigia.app.R;
import com.ovigia.app.data.CharacterRepository.LoadError;
import com.ovigia.app.game.GameEvent;
import com.ovigia.app.game.GameViewModel;
import com.ovigia.app.ui.Haptics;
import com.ovigia.app.ui.SystemBarInsets;

/**
 * Base das telas da partida: obtém o {@link GameViewModel} no escopo do grafo
 * {@code game_graph}, dispara a carga e encaminha os {@link GameEvent}s para o
 * {@link GameNavigator}.
 */
public abstract class GameFragment extends Fragment {

    protected GameViewModel viewModel;
    private Haptics haptics;

    protected GameFragment(int layoutId) {
        super(layoutId);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        SystemBarInsets.padTop(view);
        AppContainer container = ((OVigiaApplication) requireActivity().getApplication()).container();
        haptics = container.haptics;
        // Vale enquanto a tela da partida estiver visível; as outras telas seguem o tempo do aparelho.
        view.setKeepScreenOn(container.settingsStore.keepScreenOn());
        viewModel = obtainViewModel();
        viewModel.events().observe(getViewLifecycleOwner(), event -> {
            GameEvent gameEvent = event.consume();
            if (gameEvent != null) GameNavigator.handle(this, gameEvent);
        });
        viewModel.start();
    }

    private GameViewModel obtainViewModel() {
        NavBackStackEntry gameEntry = nav().getBackStackEntry(R.id.game_graph);
        AppContainer container = ((OVigiaApplication) requireActivity().getApplication()).container();
        GameViewModel.Factory factory = new GameViewModel.Factory(
                container.characterRepository, container.learningStore, container.accountStore);
        // O back stack entry fornece as CreationExtras com o SavedStateRegistry do grafo.
        return new ViewModelProvider(gameEntry.getViewModelStore(), factory,
                gameEntry.getDefaultViewModelCreationExtras()).get(GameViewModel.class);
    }

    protected NavController nav() {
        return NavHostFragment.findNavController(this);
    }

    /** Pulso de confirmação numa resposta, na força escolhida (se o jogador não desligou). */
    protected void answerFeedback() {
        if (haptics != null) haptics.tap();
    }

    /**
     * Se esta tela ainda é o destino atual. Cliques que chegam durante a
     * animação de saída (toque duplo) são ignorados com isso.
     */
    protected boolean isCurrentDestination(@IdRes int destinationId) {
        NavDestination current = nav().getCurrentDestination();
        return isAdded() && current != null && current.getId() == destinationId;
    }

    /** Mensagem para uma falha ao buscar as fichas dos personagens (também usada pela ficha do herói). */
    @StringRes
    public static int messageFor(LoadError error) {
        if (error == null) return R.string.error_server;
        switch (error) {
            case NO_CONNECTION: return R.string.error_no_connection;
            case RATE_LIMITED: return R.string.error_rate_limited;
            case NOT_CONFIGURED: return R.string.error_not_configured;
            case EMPTY_ROSTER: return R.string.error_empty_roster;
            case NOT_FOUND: return R.string.error_hero_not_found;
            case SERVER_ERROR:
            default: return R.string.error_server;
        }
    }
}
