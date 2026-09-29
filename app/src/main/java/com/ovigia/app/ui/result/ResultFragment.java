package com.ovigia.app.ui.result;

import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.bumptech.glide.Glide;
import com.ovigia.app.AppContainer;
import com.ovigia.app.OVigiaApplication;
import com.ovigia.app.R;
import com.ovigia.app.catalog.UnlockRules;
import com.ovigia.app.databinding.FragmentArtPanelBinding;
import com.ovigia.app.databinding.PanelResultBinding;
import com.ovigia.app.game.WatcherMood;
import com.ovigia.app.learning.LearningStore.Outcome;
import com.ovigia.app.ui.FadeNavOptions;
import com.ovigia.app.ui.Motion;
import com.ovigia.app.ui.Portraits;
import com.ovigia.app.ui.SystemBarInsets;
import com.ovigia.app.ui.WatcherArt;
import com.ovigia.app.ui.auth.AuthFragment;
import com.ovigia.app.ui.catalog.HeroDetailFragment;

/**
 * Fim de partida com personagem conhecido. Quando o Vigia acertou
 * ({@link UnlockRules}), o herói entra no catálogo da conta logada na hora; sem
 * sessão, o jogador pode entrar e o desbloqueio acontece ao voltar. Quando o
 * Vigia errou, o herói continua bloqueado.
 *
 * Fica fora do grafo da partida, logo acima da tela inicial: tudo que precisa
 * vem nos argumentos, então sobrevive à morte do processo.
 */
public class ResultFragment extends Fragment {

    public static final String ARG_CHARACTER_ID = "characterId";
    public static final String ARG_CHARACTER_NAME = "characterName";
    public static final String ARG_IMAGE_URL = "imageUrl";
    public static final String ARG_MESSAGE = "message";
    public static final String ARG_OUTCOME = "outcome";

    /** Guardado no back stack entry: o desbloqueio já aconteceu (e se foi novo). */
    private static final String KEY_UNLOCK_STATUS = "unlock_status";
    private static final String STATUS_NEW = "new";
    private static final String STATUS_EXISTING = "existing";

    private PanelResultBinding panel;
    private boolean working = false;
    private final Motion motion = new Motion();

    public ResultFragment() {
        super(R.layout.fragment_art_panel);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        SystemBarInsets.padTop(view);
        FragmentArtPanelBinding binding = FragmentArtPanelBinding.bind(view);
        panel = PanelResultBinding.inflate(getLayoutInflater(), binding.panelContainer, true);

        Bundle args = requireArguments();
        // Comemora quando acertou, faz pouco caso quando perdeu.
        binding.imageArt.setImageResource(WatcherArt.drawableFor(WatcherMood.forOutcome(outcome())));
        panel.tvMessage.setText(args.getString(ARG_MESSAGE));
        panel.tvCharacterName.setText(args.getString(ARG_CHARACTER_NAME));
        Glide.with(this)
                .load(args.getString(ARG_IMAGE_URL))
                .placeholder(R.drawable.ic_character_placeholder)
                .error(R.drawable.ic_character_placeholder)
                .fallback(R.drawable.ic_character_placeholder)
                .transform(Portraits.roundedCrop(Portraits.cardRadius(getResources())))
                .into(panel.imageCharacter);

        // Voltar daqui é voltar ao início: a partida já acabou.
        OnBackPressedCallback back = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goHome();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        binding.btnBack.setOnClickListener(v -> goHome());
        panel.btnHome.setOnClickListener(v -> goHome());

        if (savedInstanceState == null) {
            AppContainer container = ((OVigiaApplication) requireActivity().getApplication()).container();
            // A partida mudou as estatísticas: os amigos veem a versão nova.
            container.socialRepository.publishQuietly();
            // A partida em si já pode ter fechado uma conquista (partidas jogadas,
            // vitórias sobre o Vigia). As de coleção esperam o desbloqueio abaixo.
            container.achievements.sync();
            // Primeira vez na tela: o Vigia reage e a fala dele entra em cascata.
            motion.popIn(binding.imageArt, 0);
            motion.staggerIn(120, panel.tvMessage, panel.portraitFrame, panel.tvCharacterName);
        }

        if (!UnlockRules.unlocks(outcome())) {
            showLocked();
            panel.tvUnlockHint.setVisibility(View.VISIBLE);
            panel.tvUnlockHint.setText(getString(R.string.result_locked_hint, args.getString(ARG_CHARACTER_NAME)));
            return;
        }

        NavBackStackEntry entry = nav().getCurrentBackStackEntry();
        SavedStateHandle handle = entry != null && entry.getDestination().getId() == R.id.resultFragment
                ? entry.getSavedStateHandle() : null;
        if (handle != null) {
            handle.<Boolean>getLiveData(AuthFragment.KEY_SIGNED_IN).observe(getViewLifecycleOwner(), signedIn -> {
                if (!Boolean.TRUE.equals(signedIn)) return;
                handle.set(AuthFragment.KEY_SIGNED_IN, null);
                // Voltou do login: conclui o desbloqueio.
                unlock(handle);
            });
        }
        String status = handle != null ? handle.get(KEY_UNLOCK_STATUS) : null;
        if (status != null) {
            // Já desbloqueado nesta tela (ex.: depois de girar): mostra o estado final, sem repetir a animação.
            showUnlocked(STATUS_NEW.equals(status), false);
        } else {
            unlock(handle);
        }
    }

    @Override
    public void onDestroyView() {
        motion.cancelAll();
        super.onDestroyView();
        panel = null;
        working = false;
    }

    /** Retrato bloqueado: em tons de cinza, com o cadeado por cima. */
    private void showLocked() {
        panel.lockOverlay.setVisibility(View.VISIBLE);
        panel.lockOverlay.setAlpha(1f);
        panel.lockIcon.setImageResource(R.drawable.ic_lock);
        Motion.setSaturation(panel.imageCharacter, 0f);
    }

    private Outcome outcome() {
        try {
            return Outcome.valueOf(requireArguments().getString(ARG_OUTCOME, ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private NavController nav() {
        return NavHostFragment.findNavController(this);
    }

    private boolean isCurrent() {
        NavDestination current = nav().getCurrentDestination();
        return isAdded() && current != null && current.getId() == R.id.resultFragment;
    }

    private void goHome() {
        if (isCurrent()) nav().popBackStack(R.id.homeFragment, false);
    }

    /** Desbloqueia na conta logada; sem sessão, oferece entrar. */
    private void unlock(@Nullable SavedStateHandle handle) {
        if (working || panel == null) return;
        working = true;
        AppContainer container = ((OVigiaApplication) requireActivity().getApplication()).container();
        Bundle args = requireArguments();
        int characterId = args.getInt(ARG_CHARACTER_ID);
        String name = args.getString(ARG_CHARACTER_NAME);
        String imageUrl = args.getString(ARG_IMAGE_URL);
        container.ioExecutor.execute(() -> {
            String account = container.accountStore.currentAccountId();
            boolean added = account != null && container.collectionStore.save(account, characterId, name, imageUrl);
            // Entra na fila do mesmo executor de I/O, logo atrás da gravação acima:
            // o herói novo já conta quando as conquistas forem recalculadas.
            if (added) container.achievements.sync();
            container.mainExecutor.execute(() -> {
                working = false;
                if (panel == null) return;
                if (account == null) {
                    showLocked();
                    showSignInOffer(name);
                    return;
                }
                if (handle != null) handle.set(KEY_UNLOCK_STATUS, added ? STATUS_NEW : STATUS_EXISTING);
                if (added) container.socialRepository.publishQuietly();
                showUnlocked(added, added);
            });
        });
    }

    /**
     * Herói no catálogo. Com {@code animate}, o cadeado balança, abre e some, a
     * imagem ganha cor e só então o selo e o botão da ficha aparecem.
     */
    private void showUnlocked(boolean isNew, boolean animate) {
        panel.tvUnlockHint.setVisibility(View.GONE);
        panel.tvUnlockStatus.setText(isNew ? R.string.result_unlocked_new : R.string.result_unlocked_existing);
        panel.btnPrimary.setText(R.string.result_view_hero);
        if (animate) {
            showLocked();
            panel.tvUnlockStatus.setVisibility(View.INVISIBLE);
            panel.btnPrimary.setVisibility(View.INVISIBLE);
            motion.unlock(panel.lockOverlay, panel.lockIcon, R.drawable.ic_lock_open, panel.imageCharacter,
                    panel.unlockGlow, Motion.ENTER_MS, () -> {
                        if (panel == null) return;
                        panel.tvUnlockStatus.setVisibility(View.VISIBLE);
                        panel.btnPrimary.setVisibility(View.VISIBLE);
                        motion.popIn(panel.tvUnlockStatus, 0);
                        motion.fadeUp(panel.btnPrimary, 120);
                    });
        } else {
            panel.lockOverlay.setVisibility(View.GONE);
            Motion.setSaturation(panel.imageCharacter, 1f);
            panel.tvUnlockStatus.setVisibility(View.VISIBLE);
            panel.btnPrimary.setVisibility(View.VISIBLE);
        }
        panel.btnPrimary.setOnClickListener(v -> {
            if (!isCurrent()) return;
            Bundle heroArgs = new Bundle();
            heroArgs.putInt(HeroDetailFragment.ARG_CHARACTER_ID, requireArguments().getInt(ARG_CHARACTER_ID));
            nav().navigate(R.id.heroDetailFragment, heroArgs, FadeNavOptions.builder().build());
        });
    }

    private void showSignInOffer(String characterName) {
        panel.tvUnlockStatus.setVisibility(View.GONE);
        panel.tvUnlockHint.setVisibility(View.VISIBLE);
        panel.tvUnlockHint.setText(getString(R.string.result_sign_in_to_unlock_hint, characterName));
        panel.btnPrimary.setVisibility(View.VISIBLE);
        panel.btnPrimary.setText(R.string.result_sign_in_to_unlock);
        panel.btnPrimary.setOnClickListener(v -> {
            if (!isCurrent()) return;
            Bundle authArgs = new Bundle();
            authArgs.putString(AuthFragment.ARG_REASON, getString(R.string.auth_reason_unlock, characterName));
            nav().navigate(R.id.authFragment, authArgs, FadeNavOptions.builder().build());
        });
    }
}
