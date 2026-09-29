package com.ovigia.app.ui;

import android.os.Bundle;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.ovigia.app.AppContainer;
import com.ovigia.app.OVigiaApplication;
import com.ovigia.app.R;
import com.ovigia.app.ui.auth.AuthFragment;

/** Opções de navegação com a transição usada em todas as trocas de tela do app (entra subindo, sai esmaecendo). */
public final class FadeNavOptions {

    public static NavOptions.Builder builder() {
        return new NavOptions.Builder()
                .setEnterAnim(R.anim.screen_enter)
                .setExitAnim(R.anim.screen_exit)
                .setPopEnterAnim(R.anim.screen_pop_enter)
                .setPopExitAnim(R.anim.screen_pop_exit);
    }

    public static NavOptions popUpTo(@IdRes int destinationId, boolean inclusive) {
        return builder().setPopUpTo(destinationId, inclusive).build();
    }

    /**
     * Abre {@code destinationId}, que exige conta: sem ninguém logado, abre o login,
     * que segue para o destino ao entrar. A sessão é lida no I/O; toques que chegam
     * depois de sair de {@code fromDestinationId} são ignorados.
     */
    public static void navigateSignedIn(Fragment from, @IdRes int fromDestinationId, @IdRes int destinationId,
                                        @Nullable Bundle args) {
        AppContainer container = ((OVigiaApplication) from.requireActivity().getApplication()).container();
        container.ioExecutor.execute(() -> {
            boolean signedIn = container.accountStore.currentAccountId() != null;
            container.mainExecutor.execute(() -> {
                if (!from.isAdded() || from.getView() == null) return;
                NavController nav = NavHostFragment.findNavController(from);
                NavDestination current = nav.getCurrentDestination();
                if (current == null || current.getId() != fromDestinationId) return;
                if (signedIn) {
                    nav.navigate(destinationId, args, builder().build());
                } else {
                    Bundle authArgs = new Bundle();
                    authArgs.putInt(AuthFragment.ARG_NEXT_DESTINATION, destinationId);
                    nav.navigate(R.id.authFragment, authArgs, builder().build());
                }
            });
        });
    }

    private FadeNavOptions() { }
}
