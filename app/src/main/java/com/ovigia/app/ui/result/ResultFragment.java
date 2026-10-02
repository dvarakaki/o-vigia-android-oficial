package com.ovigia.app.ui.result;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;
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
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.roster.Rarity;
import com.ovigia.app.databinding.FragmentArtPanelBinding;
import com.ovigia.app.databinding.PanelResultBinding;
import com.ovigia.app.game.WatcherMood;
import com.ovigia.app.learning.LearningStore.Outcome;
import com.ovigia.app.premium.InfiniteState;
import com.ovigia.app.ui.FadeNavOptions;
import com.ovigia.app.ui.Motion;
import com.ovigia.app.ui.Portraits;
import com.ovigia.app.ui.RarityViews;
import com.ovigia.app.ui.SystemBarInsets;
import com.ovigia.app.ui.WatcherArt;
import com.ovigia.app.ui.auth.AuthFragment;
import com.ovigia.app.ui.catalog.HeroDetailFragment;
import com.ovigia.app.ui.premium.InfiniteSheet;

/**
 * Fim de partida com personagem conhecido. Quando o Vigia acertou
 * ({@link UnlockRules}), o herói entra no catálogo da conta logada na hora; sem
 * sessão, o jogador pode entrar e o desbloqueio acontece ao voltar. Quando o
 * Vigia errou, o herói continua bloqueado.
 *
 * Um lendário só entra para o Vigia do Infinito: para os demais ele fica
 * lacrado, e a tela oferece a compra. Comprou (aqui ou em qualquer lugar, com a
 * tela aberta), o lacre se abre com a mesma animação do desbloqueio.
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
    public static final String ARG_RARITY = "rarity";

    /** Guardado no back stack entry: o desbloqueio já aconteceu (e se foi novo) ou o herói ficou lacrado. */
    private static final String KEY_UNLOCK_STATUS = "unlock_status";
    private static final String STATUS_NEW = "new";
    private static final String STATUS_EXISTING = "existing";
    private static final String STATUS_SEALED = "sealed";

    private PanelResultBinding panel;
    private boolean working = false;
    /** Lendário com a loja ainda sem resposta: o desbloqueio espera saber se a conta é Vigia do Infinito. */
    private boolean awaitingStore = false;
    @Nullable private SavedStateHandle handle;
    @Nullable private String unlockStatus;
    private Rarity rarity = Rarity.COMMON;
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
        rarity = Rarity.fromName(args.getString(ARG_RARITY));
        // Comemora quando acertou, faz pouco caso quando perdeu.
        binding.imageArt.setImageResource(WatcherArt.drawableFor(WatcherMood.forOutcome(outcome())));
        panel.tvMessage.setText(args.getString(ARG_MESSAGE));
        panel.tvCharacterName.setText(args.getString(ARG_CHARACTER_NAME));
        RarityViews.bindChip(panel.tvRarity, rarity);
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

        AppContainer container = container();
        if (savedInstanceState == null) {
            // A partida mudou as estatísticas: os amigos veem a versão nova.
            container.socialRepository.publishQuietly();
            // A partida em si já pode ter fechado uma conquista (partidas jogadas,
            // vitórias sobre o Vigia). As de coleção esperam o desbloqueio abaixo.
            container.achievements.sync();
            // Primeira vez na tela: o Vigia reage e a fala dele entra em cascata.
            motion.popIn(binding.imageArt, 0);
            motion.staggerIn(120, panel.tvMessage, panel.portraitFrame, panel.tvCharacterName, panel.tvRarity);
        }

        if (!UnlockRules.unlocks(outcome())) {
            showLocked();
            panel.tvUnlockHint.setVisibility(View.VISIBLE);
            panel.tvUnlockHint.setText(getString(R.string.result_locked_hint, args.getString(ARG_CHARACTER_NAME)));
            return;
        }

        NavBackStackEntry entry = nav().getCurrentBackStackEntry();
        handle = entry != null && entry.getDestination().getId() == R.id.resultFragment
                ? entry.getSavedStateHandle() : null;
        if (handle != null) {
            SavedStateHandle h = handle;
            h.<Boolean>getLiveData(AuthFragment.KEY_SIGNED_IN).observe(getViewLifecycleOwner(), signedIn -> {
                if (!Boolean.TRUE.equals(signedIn)) return;
                h.set(AuthFragment.KEY_SIGNED_IN, null);
                // Voltou do login: conclui o desbloqueio.
                requestUnlock();
            });
        }
        unlockStatus = handle != null ? handle.get(KEY_UNLOCK_STATUS) : null;
        if (STATUS_NEW.equals(unlockStatus) || STATUS_EXISTING.equals(unlockStatus)) {
            // Já desbloqueado nesta tela (ex.: depois de girar): mostra o estado final, sem repetir a animação.
            showUnlocked(STATUS_NEW.equals(unlockStatus), false);
        } else if (STATUS_SEALED.equals(unlockStatus)) {
            showSealed();
        } else {
            requestUnlock();
        }
        container.infinite.state().observe(getViewLifecycleOwner(), this::onInfiniteState);
    }

    @Override
    public void onDestroyView() {
        motion.cancelAll();
        super.onDestroyView();
        panel = null;
        working = false;
        awaitingStore = false;
    }

    private AppContainer container() {
        return ((OVigiaApplication) requireActivity().getApplication()).container();
    }

    /** Retrato bloqueado: em tons de cinza, com o cadeado por cima. */
    private void showLocked() {
        panel.lockOverlay.setVisibility(View.VISIBLE);
        panel.lockOverlay.setAlpha(1f);
        panel.lockIcon.setImageResource(R.drawable.ic_lock);
        tintLock(rarity.requiresInfinite() ? R.color.rarity_legendary : R.color.vigia_gold);
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

    /**
     * Desbloqueia agora — a não ser que seja um lendário e a loja ainda não tenha
     * dito se a conta é Vigia do Infinito: aí espera a resposta, para não lacrar
     * à toa o herói de quem já comprou.
     */
    private void requestUnlock() {
        InfiniteState store = container().infinite.state().getValue();
        if (rarity.requiresInfinite() && (store == null || store.status == InfiniteState.Status.CHECKING)) {
            awaitingStore = true;
            showLocked();
            return;
        }
        unlock();
    }

    private void onInfiniteState(InfiniteState state) {
        if (panel == null) return;
        if (awaitingStore) {
            if (state.status == InfiniteState.Status.CHECKING) return;
            awaitingStore = false;
            unlock();
            return;
        }
        if (!STATUS_SEALED.equals(unlockStatus)) return;
        if (state.isInfinite()) {
            // Virou Vigia do Infinito com a tela aberta: o lacre se abre.
            unlock();
        } else {
            showPurchaseButton(state);
        }
    }

    /** Desbloqueia na conta logada (ou lacra, para um lendário); sem sessão, oferece entrar. */
    private void unlock() {
        if (working || panel == null) return;
        working = true;
        AppContainer container = container();
        Bundle args = requireArguments();
        int characterId = args.getInt(ARG_CHARACTER_ID);
        String name = args.getString(ARG_CHARACTER_NAME);
        String imageUrl = args.getString(ARG_IMAGE_URL);
        boolean wasSealed = STATUS_SEALED.equals(unlockStatus);
        container.ioExecutor.execute(() -> {
            String account = container.accountStore.currentAccountId();
            CollectionStore.Unlock result = account == null ? null
                    : container.collectionStore.unlock(account, characterId, name, imageUrl);
            // Entra na fila do mesmo executor de I/O, logo atrás da gravação acima:
            // o herói novo já conta quando as conquistas forem recalculadas.
            if (result == CollectionStore.Unlock.NEW) container.achievements.sync();
            container.mainExecutor.execute(() -> {
                working = false;
                if (panel == null) return;
                if (result == null) {
                    showLocked();
                    showSignInOffer(name);
                    return;
                }
                if (result == CollectionStore.Unlock.SEALED) {
                    setUnlockStatus(STATUS_SEALED);
                    showSealed();
                    return;
                }
                if (result == CollectionStore.Unlock.NEW) container.socialRepository.publishQuietly();
                // Saiu do lacre: mesmo que a liberação dos lacrados tenha chegado antes, é novo para o jogador.
                boolean isNew = result == CollectionStore.Unlock.NEW || wasSealed;
                setUnlockStatus(isNew ? STATUS_NEW : STATUS_EXISTING);
                showUnlocked(isNew, isNew);
            });
        });
    }

    private void setUnlockStatus(String status) {
        unlockStatus = status;
        if (handle != null) handle.set(KEY_UNLOCK_STATUS, status);
    }

    /**
     * Herói no catálogo. Com {@code animate}, o cadeado balança, abre e some, a
     * imagem ganha cor e só então o selo e o botão da ficha aparecem.
     */
    private void showUnlocked(boolean isNew, boolean animate) {
        panel.tvUnlockHint.setVisibility(View.GONE);
        styleStatus(R.drawable.ic_lock_open, R.color.vigia_gold, R.drawable.bg_unlock_badge);
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

    /**
     * Lendário de quem ainda não é Vigia do Infinito: o retrato fica lacrado, a
     * tela explica o porquê e oferece virar Vigia do Infinito.
     */
    private void showSealed() {
        showLocked();
        styleStatus(R.drawable.ic_infinity, R.color.rarity_legendary, R.drawable.bg_sealed_badge);
        panel.tvUnlockStatus.setText(R.string.result_sealed_status);
        panel.tvUnlockStatus.setVisibility(View.VISIBLE);
        panel.tvUnlockHint.setVisibility(View.VISIBLE);
        panel.tvUnlockHint.setText(getString(R.string.result_sealed_hint, getString(RarityViews.label(rarity))));
        panel.btnPrimary.setVisibility(View.VISIBLE);
        panel.btnPrimary.setOnClickListener(v -> {
            if (isCurrent()) InfiniteSheet.show(this, rarity);
        });
        showPurchaseButton(container().infinite.state().getValue());
    }

    private void showPurchaseButton(@Nullable InfiniteState state) {
        String price = state != null ? state.price : null;
        panel.btnPrimary.setText(price != null
                ? getString(R.string.infinite_become_price, price) : getString(R.string.infinite_become));
    }

    private void tintLock(@ColorRes int color) {
        ImageViewCompat.setImageTintList(panel.lockIcon,
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), color)));
    }

    private void styleStatus(@DrawableRes int icon, @ColorRes int color, @DrawableRes int background) {
        int value = ContextCompat.getColor(requireContext(), color);
        panel.tvUnlockStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        TextViewCompat.setCompoundDrawableTintList(panel.tvUnlockStatus, ColorStateList.valueOf(value));
        panel.tvUnlockStatus.setTextColor(value);
        panel.tvUnlockStatus.setBackgroundResource(background);
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
