package com.ovigia.app.ui.friends;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.ovigia.app.AppContainer;
import com.ovigia.app.OVigiaApplication;
import com.ovigia.app.R;
import com.ovigia.app.databinding.FragmentFriendProfileBinding;
import com.ovigia.app.databinding.ItemCatalogHeroBinding;
import com.ovigia.app.databinding.ItemProfileStatBinding;
import com.ovigia.app.profile.PlayerRank;
import com.ovigia.app.social.FriendProfileUiState;
import com.ovigia.app.social.FriendProfileUiState.Status;
import com.ovigia.app.social.FriendProfileViewModel;
import com.ovigia.app.social.FriendProfileViewModel.TradeMessage;
import com.ovigia.app.social.PublicProfile;
import com.ovigia.app.social.SocialException;
import com.ovigia.app.social.TradeOffer;
import com.ovigia.app.social.TradeSuggestions;
import com.ovigia.app.ui.ConfirmDialog;
import com.ovigia.app.ui.FadeNavOptions;
import com.ovigia.app.ui.Motion;
import com.ovigia.app.ui.PlayerImages;
import com.ovigia.app.ui.RelativeTime;
import com.ovigia.app.ui.SystemBarInsets;
import com.ovigia.app.ui.achievements.AchievementViews;
import com.ovigia.app.ui.catalog.HeroDetailFragment;

/**
 * Perfil de um amigo: identidade, título, números, troca de heróis, conquistas
 * e heróis desbloqueados. Heróis que o jogador também desbloqueou abrem a ficha;
 * os que ele não tem abrem a troca pedindo aquele herói.
 */
public class FriendProfileFragment extends Fragment {

    public static final String ARG_FRIEND_UID = "friendUid";

    /** Cartas mostradas antes de "Ver todos". */
    private static final int HERO_PREVIEW = 12;
    private static final String STATE_ALL_HEROES = "all_heroes";
    private static final String STATE_ALL_ACHIEVEMENTS = "all_achievements";

    private FragmentFriendProfileBinding binding;
    private FriendProfileViewModel viewModel;
    private final Motion motion = new Motion();
    private boolean showAllHeroes = false;
    private boolean showAllAchievements = false;
    private boolean entered = false;
    /** Gaveta da troca aberta, espelhando {@code state.proposal}. */
    @Nullable private TradeSheet tradeSheet;

    public FriendProfileFragment() {
        super(R.layout.fragment_friend_profile);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentFriendProfileBinding.bind(view);
        showAllHeroes = savedInstanceState != null && savedInstanceState.getBoolean(STATE_ALL_HEROES, false);
        showAllAchievements = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_ALL_ACHIEVEMENTS, false);
        entered = savedInstanceState != null;
        SystemBarInsets.extendHeight(binding.bannerFrame);
        SystemBarInsets.marginTop(binding.btnBack);
        SystemBarInsets.extendHeight(binding.statusScrim);
        int bannerHeight = getResources().getDimensionPixelSize(R.dimen.profile_banner_height);
        binding.scroll.setOnScrollChangeListener((View.OnScrollChangeListener) (v, x, y, oldX, oldY) -> {
            if (binding == null) return;
            binding.imageBanner.setTranslationY(y * 0.5f);
            binding.statusScrim.setAlpha(Math.min(1f, (float) y / bannerHeight));
        });

        String friendUid = requireArguments().getString(ARG_FRIEND_UID, "");
        AppContainer container = ((OVigiaApplication) requireActivity().getApplication()).container();
        viewModel = new ViewModelProvider(this, new FriendProfileViewModel.Factory(friendUid,
                container.socialRepository, container.socialExecutor, container.mainExecutor))
                .get(FriendProfileViewModel.class);

        binding.btnBack.setOnClickListener(v -> nav().popBackStack());
        binding.btnRetry.setOnClickListener(v -> viewModel.retry());
        binding.btnRemoveFriend.setOnClickListener(v -> confirmRemove());
        binding.btnAllAchievements.setOnClickListener(v -> {
            showAllAchievements = true;
            FriendProfileUiState state = viewModel.state().getValue();
            if (state != null && state.profile != null) {
                AchievementViews.fill(binding.achievementsList, binding.btnAllAchievements,
                        state.profile.achievements, true, motion);
            }
        });
        binding.btnAllHeroes.setOnClickListener(v -> {
            showAllHeroes = true;
            FriendProfileUiState state = viewModel.state().getValue();
            if (state != null && state.profile != null) renderHeroes(state);
        });
        View.OnClickListener proposeSuggested = v -> {
            FriendProfileUiState state = viewModel.state().getValue();
            TradeSuggestions.Pick want = state == null ? null : state.suggestedWant();
            if (want != null) viewModel.startProposal(want.hero.characterId);
        };
        binding.btnProposeTrade.setOnClickListener(proposeSuggested);
        binding.tradeSuggestion.setOnClickListener(proposeSuggested);

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.removeFailures().observe(getViewLifecycleOwner(), event -> {
            SocialException.Error error = event.consume();
            if (error == null) return;
            Snackbar.make(binding.getRoot(), error == SocialException.Error.OFFLINE
                    ? R.string.friends_error_offline : R.string.friends_error_generic, Snackbar.LENGTH_SHORT).show();
        });
        viewModel.tradeMessages().observe(getViewLifecycleOwner(), event -> {
            TradeMessage message = event.consume();
            if (message != null) Snackbar.make(binding.getRoot(), tradeMessageText(message), Snackbar.LENGTH_SHORT).show();
        });
        viewModel.start();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_ALL_HEROES, showAllHeroes);
        outState.putBoolean(STATE_ALL_ACHIEVEMENTS, showAllAchievements);
    }

    @Override
    public void onDestroyView() {
        motion.cancelAll();
        // A proposta continua no ViewModel: a gaveta volta ao recriar a tela.
        if (tradeSheet != null) tradeSheet.dismissQuietly();
        tradeSheet = null;
        super.onDestroyView();
        binding = null;
    }

    private NavController nav() {
        return NavHostFragment.findNavController(this);
    }

    private boolean isCurrent() {
        NavDestination current = nav().getCurrentDestination();
        return isAdded() && current != null && current.getId() == R.id.friendProfileFragment;
    }

    private void confirmRemove() {
        FriendProfileUiState state = viewModel.state().getValue();
        if (state == null || state.profile == null || state.removing) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.friend_profile_remove_title, state.profile.card.name))
                .setMessage(R.string.friend_profile_remove_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.friend_profile_remove_confirm, (d, w) -> viewModel.removeFriend())
                .show();
    }

    private void render(FriendProfileUiState state) {
        renderTradeSheet(state);
        if (state.status == Status.REMOVED) {
            if (isCurrent()) nav().popBackStack();
            return;
        }
        binding.progress.setVisibility(state.status == Status.LOADING ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(state.status == Status.ERROR ? View.VISIBLE : View.GONE);
        binding.scroll.setVisibility(state.status == Status.READY ? View.VISIBLE : View.INVISIBLE);
        if (state.status == Status.ERROR) {
            binding.tvError.setText(errorText(state.error));
            boolean retryable = state.error != SocialException.Error.PERMISSION_DENIED
                    && state.error != SocialException.Error.NOT_FOUND;
            binding.btnRetry.setVisibility(retryable ? View.VISIBLE : View.GONE);
            return;
        }
        if (state.status != Status.READY || state.profile == null) return;

        PublicProfile profile = state.profile;
        binding.tvName.setText(profile.card.name);
        binding.tvUsername.setText(getString(R.string.friends_username_format, profile.card.username));
        binding.tvBio.setText(profile.bio);
        binding.tvBio.setVisibility(profile.bio != null && !profile.bio.isEmpty() ? View.VISIBLE : View.GONE);
        binding.tvUpdated.setVisibility(profile.updatedAt > 0 ? View.VISIBLE : View.GONE);
        if (profile.updatedAt > 0) {
            binding.tvUpdated.setText(getString(R.string.friend_profile_updated,
                    RelativeTime.format(requireContext(), profile.updatedAt, System.currentTimeMillis())));
        }
        PlayerImages.bindSharedBanner(this, binding.imageBanner, binding.bannerTint, profile.banner);
        PlayerImages.bindSharedAvatar(this, binding.imageAvatar, profile.card.avatar,
                getResources().getDimensionPixelSize(R.dimen.avatar_icon_padding));

        PlayerRank rank = state.rank();
        binding.tvRank.setText(rankName(rank));
        int winPercent = profile.gamesPlayed == 0 ? 0 : Math.round(100f * profile.engineWins / profile.gamesPlayed);
        binding.tvWinRate.setVisibility(profile.gamesPlayed > 0 ? View.VISIBLE : View.GONE);
        binding.tvWinRate.setText(getString(R.string.friend_profile_watcher_rate, winPercent));
        binding.rankCard.setContentDescription(getString(rankName(rank)) + ". "
                + (profile.gamesPlayed > 0 ? binding.tvWinRate.getText() : ""));

        bindStat(binding.statGames, profile.gamesPlayed, R.string.profile_stat_games);
        bindStat(binding.statPlayerWins, profile.playerWins(), R.string.friend_profile_stat_player_wins);
        bindStat(binding.statHeroes, profile.heroes.size(), R.string.profile_stat_collection);

        binding.tvAchievementsCount.setText(AchievementViews.countText(requireContext(), profile.achievements));
        AchievementViews.fill(binding.achievementsList, binding.btnAllAchievements, profile.achievements,
                showAllAchievements);

        binding.btnRemoveFriend.setEnabled(!state.removing);
        binding.btnRemoveFriend.setText(state.removing ? R.string.action_saving : R.string.friend_profile_remove);
        renderTradeCard(state);
        renderHeroes(state);

        if (!entered) {
            entered = true;
            motion.popIn(binding.avatarFrame, 60);
            motion.staggerIn(140, binding.tvName, binding.tvUsername, binding.tvBio, binding.tvUpdated,
                    binding.rankCard, binding.statsRow, binding.tradeCard, binding.achievementsCard,
                    binding.heroesGrid, binding.btnRemoveFriend);
        }
    }

    // ---------------------------------------------------------------- troca

    /** O que dá para trocar com o amigo e a sugestão da vez. */
    private void renderTradeCard(FriendProfileUiState state) {
        PublicProfile profile = state.profile;
        String friend = profile.card.name;
        int theyHave = state.wantPicks.size();
        int iHave = state.offerPicks.size();
        String body;
        if (theyHave == 0) {
            body = getString(R.string.trade_body_nothing_to_get, friend);
        } else if (iHave == 0) {
            body = getString(R.string.trade_body_nothing_to_give, friend);
        } else {
            body = getResources().getQuantityString(R.plurals.trade_body_they_have, theyHave, friend, theyHave)
                    + " " + getResources().getQuantityString(R.plurals.trade_body_you_have, iHave, iHave);
        }
        binding.tvTradeBody.setText(body);

        TradeSuggestions.Pick suggestion = iHave == 0 ? null : state.suggestedWant();
        binding.tradeSuggestion.setVisibility(suggestion != null ? View.VISIBLE : View.GONE);
        binding.btnProposeTrade.setVisibility(suggestion != null ? View.VISIBLE : View.GONE);
        binding.tvTradeHint.setVisibility(suggestion != null ? View.VISIBLE : View.GONE);
        if (suggestion == null) return;
        String name = TradeSheet.heroName(requireContext(), suggestion.hero);
        binding.tvSuggestionName.setText(name);
        String reason = TradeSheet.reasonText(requireContext(), suggestion);
        binding.tvSuggestionReason.setText(reason);
        binding.tvSuggestionReason.setVisibility(reason != null ? View.VISIBLE : View.GONE);
        binding.tradeSuggestion.setContentDescription(getString(R.string.trade_suggestion_label) + ": " + name
                + (reason != null ? ". " + reason : ""));
        Glide.with(this)
                .load(suggestion.hero.imageUrl)
                .placeholder(R.color.vigia_panel_solid)
                .error(R.drawable.ic_character_placeholder)
                .centerCrop()
                .into(binding.imageSuggestion);
    }

    /** Abre, atualiza ou fecha a gaveta conforme {@code state.proposal}. */
    private void renderTradeSheet(FriendProfileUiState state) {
        FriendProfileUiState.Proposal proposal = state.status == Status.READY ? state.proposal : null;
        if (proposal == null || state.profile == null) {
            if (tradeSheet != null) tradeSheet.dismissQuietly();
            tradeSheet = null;
            return;
        }
        if (tradeSheet == null) {
            tradeSheet = new TradeSheet(this, new TradeSheet.Actions() {
                @Override public void onPick(int heroId) { viewModel.chooseOffer(heroId); }
                @Override public void onConfirm() { viewModel.sendProposal(); }
                @Override public void onSecondary() { viewModel.closeProposal(); }
                @Override public void onClosed() {
                    tradeSheet = null;
                    viewModel.closeProposal();
                }
            });
        }
        String friend = state.profile.card.name;
        TradeSuggestions.Pick offer = state.offerPick(proposal.offerId);
        TradeSheet.Model model = new TradeSheet.Model();
        model.title = getString(R.string.trade_propose_title, friend);
        model.give = offer == null ? null : offer.hero;
        model.get = proposal.want.hero;
        String forFriend = TradeSheet.reasonText(requireContext(), offer);
        model.giveReason = forFriend == null ? null : getString(R.string.trade_reason_for, friend, forFriend);
        model.getReason = TradeSheet.reasonText(requireContext(), proposal.want);
        model.pickerTitle = getString(R.string.trade_pick_offer);
        model.pickerNote = state.offerPicks.isEmpty()
                ? getString(R.string.trade_body_nothing_to_give, friend)
                : getString(R.string.trade_pick_offer_note, friend);
        model.picks = state.offerPicks;
        model.selectedId = proposal.offerId;
        model.badgeId = state.offerPicks.isEmpty() ? -1 : state.offerPicks.get(0).hero.characterId;
        model.badgeText = getString(R.string.trade_badge_suggested);
        model.busy = proposal.sending;
        model.confirmEnabled = offer != null;
        model.confirmText = R.string.trade_send;
        model.secondaryText = R.string.action_cancel;
        tradeSheet.bind(model);
    }

    private void confirmCancelProposal(TradeOffer trade) {
        ConfirmDialog.with(requireContext())
                .icon(R.drawable.ic_swap)
                .title(R.string.trade_cancel_title)
                .message(R.string.trade_cancel_message)
                .confirm(R.string.trade_cancel, () -> viewModel.cancelProposal(trade.id))
                .show();
    }

    @StringRes
    private static int tradeMessageText(TradeMessage message) {
        switch (message) {
            case PROPOSAL_SENT: return R.string.trade_message_sent;
            case PROPOSAL_CANCELED: return R.string.trade_message_canceled;
            case TRADE_INVALID: return R.string.trade_message_invalid;
            case FAILED_OFFLINE: return R.string.friends_error_offline;
            case FAILED:
            default: return R.string.friends_error_generic;
        }
    }

    private void bindStat(ItemProfileStatBinding stat, int value, @StringRes int label) {
        stat.tvValue.setText(String.valueOf(value));
        stat.tvLabel.setText(label);
        stat.getRoot().setContentDescription(value + " " + getString(label));
    }

    /** Grade de cartas em linhas, com quantas colunas couberem. */
    private void renderHeroes(FriendProfileUiState state) {
        PublicProfile profile = state.profile;
        int total = profile.heroes.size();
        binding.tvHeroesCount.setText(String.valueOf(total));
        binding.tvHeroesEmpty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        int shown = showAllHeroes ? total : Math.min(HERO_PREVIEW, total);
        binding.btnAllHeroes.setVisibility(shown < total ? View.VISIBLE : View.GONE);
        binding.btnAllHeroes.setText(getResources().getQuantityString(R.plurals.friend_profile_all_heroes, total, total));

        LinearLayout grid = binding.heroesGrid;
        grid.removeAllViews();
        int contentWidthDp = Math.min(getResources().getConfiguration().screenWidthDp - 48,
                (int) (getResources().getDimension(R.dimen.content_max_width) / getResources().getDisplayMetrics().density));
        float minCardDp = getResources().getDimension(R.dimen.friend_hero_min_width)
                / getResources().getDisplayMetrics().density;
        int columns = Math.max(3, (int) (contentWidthDp / minCardDp));
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        LinearLayout row = null;
        for (int i = 0; i < shown; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                grid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            PublicProfile.Hero hero = profile.heroes.get(i);
            ItemCatalogHeroBinding card = ItemCatalogHeroBinding.inflate(inflater, row, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            int margin = getResources().getDimensionPixelSize(R.dimen.friend_hero_card_margin);
            params.setMargins(margin, margin, margin, margin);
            row.addView(card.getRoot(), params);
            bindHero(card, hero, state);
        }
        // Completa a última linha para as cartas manterem a largura.
        if (row != null) {
            for (int i = shown % columns; i != 0 && i < columns; i++) {
                View filler = new View(requireContext());
                row.addView(filler, new LinearLayout.LayoutParams(0, 0, 1f));
            }
        }
    }

    private void bindHero(ItemCatalogHeroBinding card, PublicProfile.Hero hero, FriendProfileUiState state) {
        boolean alsoMine = state.myUnlockedIds.contains(hero.characterId);
        TradeOffer sent = alsoMine ? null : state.sentTradeFor(hero.characterId);
        TradeSuggestions.Pick suggested = state.suggestedWant();
        String name = hero.name != null ? hero.name : getString(R.string.profile_unknown_character);
        card.lockedOverlay.setVisibility(View.GONE);
        card.tvName.setText(name);
        card.card.setStrokeColor(ContextCompat.getColor(requireContext(),
                alsoMine ? R.color.vigia_gold_soft : R.color.white_10));
        String badge = null;
        if (sent != null) {
            badge = getString(R.string.trade_badge_requested);
        } else if (!alsoMine && !state.offerPicks.isEmpty() && suggested != null
                && suggested.hero.characterId == hero.characterId) {
            badge = getString(R.string.trade_badge_suggested);
        }
        card.tvBadge.setVisibility(badge != null ? View.VISIBLE : View.GONE);
        card.tvBadge.setText(badge);
        card.card.setContentDescription(badge == null ? name : name + ", " + badge);
        Glide.with(this)
                .load(hero.imageUrl)
                .placeholder(R.color.vigia_panel_solid)
                .error(R.drawable.ic_character_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .centerCrop()
                .into(card.image);
        card.card.setOnClickListener(v -> {
            if (!isCurrent()) return;
            if (alsoMine) {
                Bundle args = new Bundle();
                args.putInt(HeroDetailFragment.ARG_CHARACTER_ID, hero.characterId);
                nav().navigate(R.id.heroDetailFragment, args, FadeNavOptions.builder().build());
            } else if (sent != null) {
                confirmCancelProposal(sent);
            } else {
                // Não tem: pede numa troca (a gaveta explica se não houver o que oferecer).
                viewModel.startProposal(hero.characterId);
            }
        });
    }

    @StringRes
    private static int errorText(@Nullable SocialException.Error error) {
        if (error == null) return R.string.friends_error_generic;
        switch (error) {
            case OFFLINE: return R.string.friends_error_offline;
            case PERMISSION_DENIED: return R.string.friend_profile_not_friends;
            case NOT_FOUND: return R.string.friend_profile_not_found;
            default: return R.string.friends_error_generic;
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
