package com.ovigia.app.ui.profile;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.ovigia.app.AppContainer;
import com.ovigia.app.OVigiaApplication;
import com.ovigia.app.R;
import com.ovigia.app.databinding.FragmentProfileBinding;
import com.ovigia.app.databinding.ItemProfileCharacterBinding;
import com.ovigia.app.databinding.ItemProfileStatBinding;
import com.ovigia.app.learning.LearningStore.Outcome;
import com.ovigia.app.profile.PlayerRank;
import com.ovigia.app.profile.ProfileUiState;
import com.ovigia.app.profile.ProfileViewModel;
import com.ovigia.app.ui.ConfirmDialog;
import com.ovigia.app.ui.FadeNavOptions;
import com.ovigia.app.ui.Motion;
import com.ovigia.app.ui.PlayerImages;
import com.ovigia.app.ui.Portraits;
import com.ovigia.app.ui.RelativeTime;
import com.ovigia.app.ui.SystemBarInsets;
import com.ovigia.app.ui.achievements.AchievementViews;
import com.ovigia.app.ui.auth.AuthFragment;
import com.ovigia.app.ui.catalog.HeroDetailFragment;
import com.ovigia.app.ui.home.HomeFragment;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Perfil do jogador: banner e foto, nível, placar Vigia × você, números,
 * prévia do catálogo, personagens mais pensados e partidas recentes. A edição fica em
 * {@link EditProfileFragment}; "esquecer o que aprendi" fica em
 * {@link com.ovigia.app.ui.settings.SettingsFragment}.
 */
public class ProfileFragment extends Fragment {

    /** Retratos na prévia do catálogo. */
    private static final int CATALOG_PREVIEW_SIZE = 6;
    private static final String STATE_ENTERED = "entered";
    private static final String STATE_ACHIEVEMENTS = "achievements_expanded";

    private FragmentProfileBinding binding;
    private ProfileViewModel viewModel;
    /** O jogador tocou em "Sair da conta" (e não chegou aqui já sem sessão). */
    private boolean signOutRequested = false;
    /** Heróis desbloqueados: linhas deles abrem a ficha. */
    private Set<Integer> unlockedIds = Collections.emptySet();
    /** O jogador abriu a lista completa de conquistas. */
    private boolean achievementsExpanded = false;
    private final Motion motion = new Motion();
    /** A entrada animada (cascata, anel, contadores) toca só na primeira carga, não ao girar nem ao recarregar. */
    private boolean animateEntrance;

    public ProfileFragment() {
        super(R.layout.fragment_profile);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentProfileBinding.bind(view);
        animateEntrance = savedInstanceState == null || !savedInstanceState.getBoolean(STATE_ENTERED, false);
        // Banner imersivo: cresce por trás da barra de status e os ícones descem junto.
        SystemBarInsets.extendHeight(binding.bannerFrame);
        SystemBarInsets.marginTop(binding.btnBack);
        SystemBarInsets.marginTop(binding.btnEdit);
        SystemBarInsets.extendHeight(binding.statusScrim);
        int bannerHeight = getResources().getDimensionPixelSize(R.dimen.profile_banner_height);
        binding.scroll.setOnScrollChangeListener((View.OnScrollChangeListener) (v, x, y, oldX, oldY) -> {
            if (binding == null) return;
            // Paralaxe: a imagem do banner sobe na metade da velocidade do conteúdo.
            binding.imageBanner.setTranslationY(y * 0.5f);
            // A barra de status ganha fundo quando o banner sai de cena.
            binding.statusScrim.setAlpha(Math.min(1f, (float) y / bannerHeight));
        });
        binding.btnBack.setOnClickListener(v -> nav().popBackStack());
        binding.btnEdit.setOnClickListener(v -> {
            if (isCurrent()) nav().navigate(R.id.editProfileFragment, null, FadeNavOptions.builder().build());
        });
        binding.btnOpenCatalog.setOnClickListener(v -> {
            if (isCurrent()) nav().navigate(R.id.catalogFragment, null, FadeNavOptions.builder().build());
        });
        achievementsExpanded = savedInstanceState != null && savedInstanceState.getBoolean(STATE_ACHIEVEMENTS, false);
        binding.btnAllAchievements.setOnClickListener(v -> {
            achievementsExpanded = true;
            ProfileUiState current = viewModel.state().getValue();
            if (current != null) {
                AchievementViews.fill(binding.achievementsList, binding.btnAllAchievements, current.achievements,
                        true, motion);
            }
        });

        AppContainer container = ((OVigiaApplication) requireActivity().getApplication()).container();
        viewModel = new ViewModelProvider(this, new ProfileViewModel.Factory(
                container.characterRepository, container.learningStore,
                container.accountStore, container.collectionStore,
                container::rosterCatalog, container.ioExecutor, container.mainExecutor)).get(ProfileViewModel.class);
        binding.btnSignOut.setOnClickListener(v -> {
            if (signOutRequested) return;
            ConfirmDialog.with(requireContext())
                    .icon(R.drawable.ic_logout)
                    .title(R.string.profile_sign_out_confirm_title)
                    .message(R.string.profile_sign_out_confirm_message)
                    .confirm(R.string.profile_sign_out, () -> {
                        // O popup pode ter ficado aberto enquanto a tela saía (ou o jogador tocou duas vezes).
                        if (signOutRequested || binding == null) return;
                        signOutRequested = true;
                        viewModel.signOut();
                    })
                    .show();
        });
        // O que aparece aqui é o que os amigos veem: mantém a versão online em dia.
        container.socialRepository.publishQuietly();
        container.achievements.sync();
        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.start();

        // A edição avisa quando mudou algo: recarrega sem piscar a tela.
        NavBackStackEntry entry = nav().getCurrentBackStackEntry();
        if (entry != null && entry.getDestination().getId() == R.id.profileFragment) {
            SavedStateHandle handle = entry.getSavedStateHandle();
            handle.<Boolean>getLiveData(EditProfileFragment.KEY_PROFILE_CHANGED).observe(getViewLifecycleOwner(), changed -> {
                if (!Boolean.TRUE.equals(changed)) return;
                handle.set(EditProfileFragment.KEY_PROFILE_CHANGED, null);
                viewModel.reload();
            });
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_ENTERED, !animateEntrance);
        outState.putBoolean(STATE_ACHIEVEMENTS, achievementsExpanded);
    }

    @Override
    public void onDestroyView() {
        motion.cancelAll();
        super.onDestroyView();
        binding = null;
    }

    private NavController nav() {
        return NavHostFragment.findNavController(this);
    }

    private boolean isCurrent() {
        NavDestination current = nav().getCurrentDestination();
        return isAdded() && current != null && current.getId() == R.id.profileFragment;
    }

    private void render(ProfileUiState state) {
        if (state.status == ProfileUiState.Status.SIGNED_OUT) {
            onSignedOut();
            return;
        }
        boolean loading = state.isLoading();
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.scroll.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        binding.btnEdit.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        if (loading) return;

        boolean animate = animateEntrance;
        animateEntrance = false;

        renderIdentity(state);
        renderRank(state, animate);
        renderScore(state, animate);
        bindStat(binding.statGames, state.gamesPlayed, getString(R.string.profile_stat_games), animate);
        bindStat(binding.statCharacters, state.distinctCharacters, getString(R.string.profile_stat_characters), animate);
        bindStat(binding.statCollection, state.collection.size(), getString(R.string.profile_stat_collection), animate);
        binding.tvAchievementsCount.setText(AchievementViews.countText(requireContext(), state.achievements));
        AchievementViews.fill(binding.achievementsList, binding.btnAllAchievements, state.achievements,
                achievementsExpanded);
        unlockedIds = new HashSet<>();
        for (ProfileUiState.CollectionItem item : state.collection) unlockedIds.add(item.characterId);
        renderCatalog(state);
        renderFavorites(state);
        renderRecent(state);

        if (animate) {
            motion.popIn((View) binding.imageAvatar.getParent(), 60);
            motion.staggerIn(140, binding.tvAccountName, binding.tvAccountUsername, binding.tvAccountEmail,
                    binding.tvAccountBio, binding.rankCard, binding.tvEmpty, binding.scoreCard, binding.statsRow,
                    binding.achievementsCard, binding.catalogCard,
                    binding.tvFavoritesTitle, binding.favoritesList, binding.tvRecentTitle, binding.recentList,
                    binding.btnSignOut);
        }
    }

    private void renderIdentity(ProfileUiState state) {
        binding.tvAccountName.setText(state.accountName);
        binding.tvAccountUsername.setVisibility(state.accountUsername != null ? View.VISIBLE : View.GONE);
        if (state.accountUsername != null) {
            binding.tvAccountUsername.setText(getString(R.string.friends_username_format, state.accountUsername));
        }
        binding.tvAccountEmail.setText(state.accountEmail);
        binding.tvAccountBio.setText(state.accountBio);
        binding.tvAccountBio.setVisibility(state.accountBio != null ? View.VISIBLE : View.GONE);
        PlayerImages.bindBanner(this, binding.imageBanner, binding.bannerTint, state.banner);
        PlayerImages.bindAvatar(this, binding.imageAvatar, state.avatar);
    }

    /**
     * Sem sessão. Se foi o jogador que saiu, volta ao início com um aviso; se a
     * tela abriu sem ninguém logado (ex.: sessão encerrada em outra tela), troca
     * o perfil pelo login.
     */
    private void onSignedOut() {
        if (!isCurrent()) return;
        NavController nav = nav();
        if (signOutRequested) {
            nav.getBackStackEntry(R.id.homeFragment).getSavedStateHandle()
                    .set(HomeFragment.KEY_RESULT_MESSAGE, getString(R.string.profile_signed_out_message));
            nav.popBackStack(R.id.homeFragment, false);
        } else {
            Bundle args = new Bundle();
            args.putInt(AuthFragment.ARG_NEXT_DESTINATION, R.id.profileFragment);
            nav.navigate(R.id.authFragment, args, FadeNavOptions.popUpTo(R.id.profileFragment, true));
        }
    }

    private void renderRank(ProfileUiState state, boolean animate) {
        PlayerRank rank = state.rank;
        PlayerRank next = rank.next();
        binding.tvRank.setText(rankName(rank));
        if (next == null) {
            binding.tvRankCount.setVisibility(View.GONE);
            binding.tvRankProgress.setText(R.string.profile_rank_max);
            binding.rankProgress.setProgressCompat(100, animate);
            binding.rankCard.setContentDescription(getString(rankName(rank)) + ". "
                    + getString(R.string.profile_rank_max));
            return;
        }
        int missing = next.minGames - state.gamesPlayed;
        String progressText = getResources().getQuantityString(
                R.plurals.profile_rank_next, missing, missing, getString(rankName(next)));
        binding.tvRankCount.setVisibility(View.VISIBLE);
        binding.tvRankCount.setText(getString(R.string.profile_rank_count, state.gamesPlayed, next.minGames));
        binding.tvRankProgress.setText(progressText);
        int span = next.minGames - rank.minGames;
        binding.rankProgress.setProgressCompat(100 * (state.gamesPlayed - rank.minGames) / span, animate);
        binding.rankCard.setContentDescription(getString(rankName(rank)) + ". " + progressText);
    }

    private void renderScore(ProfileUiState state, boolean animate) {
        boolean empty = state.hasNoGames();
        binding.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.scoreCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;

        binding.scoreRing.setProgressCompat(state.engineWinPercent, animate);
        if (animate) {
            motion.countUp(binding.tvWinPercent, state.engineWinPercent,
                    value -> getString(R.string.percent_value, value), 300);
            motion.countUp(binding.tvEngineWins, state.engineWins, String::valueOf, 380);
            motion.countUp(binding.tvPlayerWins, state.playerWins, String::valueOf, 440);
        } else {
            binding.tvWinPercent.setText(getString(R.string.percent_value, state.engineWinPercent));
            binding.tvEngineWins.setText(String.valueOf(state.engineWins));
            binding.tvPlayerWins.setText(String.valueOf(state.playerWins));
        }
        binding.scoreCard.setContentDescription(getString(R.string.profile_score_description,
                state.engineWins, state.gamesPlayed, state.engineWinPercent, state.playerWins));
    }

    private void bindStat(ItemProfileStatBinding stat, int value, String label, boolean animate) {
        if (animate) {
            motion.countUp(stat.tvValue, value, String::valueOf, 420);
        } else {
            stat.tvValue.setText(String.valueOf(value));
        }
        stat.tvLabel.setText(label);
        stat.getRoot().setContentDescription(value + " " + label);
    }

    /** Prévia do catálogo: retratos redondos dos heróis desbloqueados mais recentes. */
    private void renderCatalog(ProfileUiState state) {
        int count = state.collection.size();
        binding.tvCatalogCount.setText(String.valueOf(count));
        binding.tvCatalogEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        LinearLayout preview = binding.catalogPreview;
        preview.removeAllViews();
        preview.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        int size = getResources().getDimensionPixelSize(R.dimen.catalog_preview_avatar);
        int overlap = getResources().getDimensionPixelSize(R.dimen.catalog_preview_overlap);
        int shown = Math.min(CATALOG_PREVIEW_SIZE, count);
        for (int i = 0; i < shown; i++) {
            ProfileUiState.CollectionItem item = state.collection.get(i);
            ImageView avatar = new ImageView(requireContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            if (i > 0) params.setMarginStart(-overlap);
            avatar.setLayoutParams(params);
            avatar.setBackgroundResource(R.drawable.bg_profile_avatar);
            int ring = getResources().getDimensionPixelSize(R.dimen.catalog_preview_ring);
            avatar.setPadding(ring, ring, ring, ring);
            avatar.setContentDescription(item.name);
            avatar.setOnClickListener(v -> openHero(item.characterId));
            Glide.with(this).load(item.imageUrl).transform(new CircleCrop())
                    .placeholder(R.drawable.ic_character_placeholder)
                    .error(R.drawable.ic_character_placeholder)
                    .into(avatar);
            preview.addView(avatar);
        }
        if (count > shown) {
            TextView more = new TextView(requireContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginStart(-overlap);
            more.setLayoutParams(params);
            more.setBackgroundResource(R.drawable.bg_profile_avatar);
            more.setGravity(Gravity.CENTER);
            more.setTextColor(requireContext().getColor(R.color.vigia_gold));
            more.setTypeface(null, Typeface.BOLD);
            more.setText(getString(R.string.profile_catalog_more, count - shown));
            more.setOnClickListener(v -> binding.btnOpenCatalog.performClick());
            preview.addView(more);
        }
    }

    private void openHero(int characterId) {
        if (!isCurrent() || !unlockedIds.contains(characterId)) return;
        Bundle args = new Bundle();
        args.putInt(HeroDetailFragment.ARG_CHARACTER_ID, characterId);
        nav().navigate(R.id.heroDetailFragment, args, FadeNavOptions.builder().build());
    }

    /** Linhas de heróis desbloqueados abrem a ficha. */
    private void makeOpenable(ItemProfileCharacterBinding row, int characterId) {
        if (!unlockedIds.contains(characterId)) return;
        row.getRoot().setForeground(AppCompatResources.getDrawable(requireContext(), R.drawable.ripple_card));
        row.getRoot().setOnClickListener(v -> openHero(characterId));
    }

    private void renderFavorites(ProfileUiState state) {
        LinearLayout list = binding.favoritesList;
        list.removeAllViews();
        boolean visible = !state.favorites.isEmpty();
        binding.tvFavoritesTitle.setVisibility(visible ? View.VISIBLE : View.GONE);
        list.setVisibility(visible ? View.VISIBLE : View.GONE);
        for (int i = 0; i < state.favorites.size(); i++) {
            ProfileUiState.FavoriteItem item = state.favorites.get(i);
            ItemProfileCharacterBinding row = inflateRow(list);
            bindCharacter(row, item.name, item.thumbnailUrl);
            row.tvDetail.setText(getResources().getQuantityString(
                    R.plurals.profile_times_picked, item.timesPicked, item.timesPicked));
            row.tvTrailing.setText(getString(R.string.profile_favorite_position, i + 1));
            makeOpenable(row, item.characterId);
        }
    }

    private void renderRecent(ProfileUiState state) {
        LinearLayout list = binding.recentList;
        list.removeAllViews();
        boolean visible = !state.recentGames.isEmpty();
        binding.tvRecentTitle.setVisibility(visible ? View.VISIBLE : View.GONE);
        list.setVisibility(visible ? View.VISIBLE : View.GONE);
        long now = System.currentTimeMillis();
        for (ProfileUiState.RecentItem item : state.recentGames) {
            ItemProfileCharacterBinding row = inflateRow(list);
            bindCharacter(row, item.name, item.thumbnailUrl);
            row.tvDetail.setText(outcomeText(item.outcome));
            row.tvTrailing.setTextColor(requireContext().getColor(R.color.white_70));
            row.tvTrailing.setTypeface(null, Typeface.NORMAL);
            row.tvTrailing.setText(RelativeTime.format(requireContext(), item.timestamp, now));
            makeOpenable(row, item.characterId);
        }
    }

    private ItemProfileCharacterBinding inflateRow(LinearLayout parent) {
        return ItemProfileCharacterBinding.inflate(LayoutInflater.from(parent.getContext()), parent, true);
    }

    private void bindCharacter(ItemProfileCharacterBinding row, @Nullable String name, @Nullable String thumbnailUrl) {
        row.tvName.setText(name != null ? name : getString(R.string.profile_unknown_character));
        int radius = getResources().getDimensionPixelSize(R.dimen.gap);
        Glide.with(this)
                .load(thumbnailUrl)
                .placeholder(R.drawable.ic_character_placeholder)
                .error(R.drawable.ic_character_placeholder)
                .fallback(R.drawable.ic_character_placeholder)
                .transform(Portraits.roundedCrop(radius))
                .into(row.image);
    }

    @StringRes
    private static int outcomeText(Outcome outcome) {
        switch (outcome) {
            case ENGINE_GUESSED: return R.string.profile_outcome_engine;
            case PICKED_FROM_ALTERNATIVES: return R.string.profile_outcome_alternatives;
            case REVEALED_AFTER_LOSS:
            case LOST_UNREVEALED:
            default: return R.string.profile_outcome_revealed;
        }
    }

    @StringRes
    private static int rankName(PlayerRank rank) {
        switch (rank) {
            case CURIOUS: return R.string.rank_curious;
            case CHALLENGER: return R.string.rank_challenger;
            case VETERAN: return R.string.rank_veteran;
            case LEGEND: return R.string.rank_legend;
            case NEWCOMER:
            default: return R.string.rank_newcomer;
        }
    }
}
