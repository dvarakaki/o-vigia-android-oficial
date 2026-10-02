package com.ovigia.app.ui.catalog;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.snackbar.Snackbar;
import com.ovigia.app.AppContainer;
import com.ovigia.app.OVigiaApplication;
import com.ovigia.app.R;
import com.ovigia.app.catalog.CatalogUiState;
import com.ovigia.app.catalog.CatalogUiState.Filter;
import com.ovigia.app.catalog.CatalogViewModel;
import com.ovigia.app.data.roster.Rarity;
import com.ovigia.app.databinding.FragmentCatalogBinding;
import com.ovigia.app.ui.FadeNavOptions;
import com.ovigia.app.premium.InfiniteState;
import com.ovigia.app.ui.Motion;
import com.ovigia.app.ui.RarityViews;
import com.ovigia.app.ui.SystemBarInsets;
import com.ovigia.app.ui.auth.AuthFragment;
import com.ovigia.app.ui.premium.InfiniteSheet;

/**
 * Catálogo de heróis da conta: progresso (no total e por raridade), filtro
 * (meus / todos), busca e grade. Cartas desbloqueadas abrem a ficha; bloqueadas
 * explicam como desbloquear; lacradas oferecem o Vigia do Infinito.
 */
public class CatalogFragment extends Fragment {

    /** Largura mínima de cada carta; define quantas colunas cabem. */
    private static final int MIN_CARD_WIDTH_DP = 108;

    private FragmentCatalogBinding binding;
    private CatalogViewModel viewModel;
    private CatalogAdapter adapter;
    private final Motion motion = new Motion();
    /** Filtro da última lista mostrada: trocar de filtro refaz a entrada em cascata. */
    private Filter shownFilter;
    /** Se a conta era Vigia do Infinito da última vez: virar recarrega (os lacrados entram). */
    private boolean wasInfinite;

    public CatalogFragment() {
        super(R.layout.fragment_catalog);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentCatalogBinding.bind(view);
        SystemBarInsets.padTop(view);

        AppContainer container = ((OVigiaApplication) requireActivity().getApplication()).container();
        viewModel = new ViewModelProvider(this, new CatalogViewModel.Factory(container.accountStore,
                container.collectionStore, container.characterRepository,
                container.ioExecutor, container.mainExecutor)).get(CatalogViewModel.class);

        binding.btnBack.setOnClickListener(v -> nav().popBackStack());
        binding.btnPlay.setOnClickListener(v -> {
            if (!isCurrent()) return;
            nav().popBackStack(R.id.homeFragment, false);
            nav().navigate(R.id.action_home_to_game);
        });

        int columns = Math.max(3, getResources().getConfiguration().screenWidthDp / MIN_CARD_WIDTH_DP);
        binding.grid.setLayoutManager(new GridLayoutManager(requireContext(), columns));
        binding.grid.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_grid_enter));
        adapter = new CatalogAdapter(item -> {
            if (!isCurrent()) return;
            if (item.unlocked) {
                Bundle args = new Bundle();
                args.putInt(HeroDetailFragment.ARG_CHARACTER_ID, item.characterId);
                nav().navigate(R.id.heroDetailFragment, args, FadeNavOptions.builder().build());
            } else if (item.sealed) {
                InfiniteSheet.show(this, item.rarity != null ? item.rarity : Rarity.LEGENDARY);
            } else if (item.rarity != null && item.rarity.requiresInfinite() && !container.infinite.isInfinite(
                    container.accountStore.currentAccountId())) {
                Rarity rarity = item.rarity;
                Snackbar.make(binding.getRoot(), R.string.catalog_locked_hint_legendary, Snackbar.LENGTH_LONG)
                        .setAction(R.string.catalog_learn_more, v -> InfiniteSheet.show(this, rarity))
                        .show();
            } else {
                Snackbar.make(binding.getRoot(), R.string.catalog_locked_hint, Snackbar.LENGTH_SHORT).show();
            }
        }, motion);
        binding.grid.setAdapter(adapter);

        binding.filterGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            viewModel.setFilter(checkedIds.get(0) == R.id.chipAll ? Filter.ALL : Filter.UNLOCKED);
        });
        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                viewModel.setQuery(s == null ? "" : s.toString());
            }
        });

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.start();

        InfiniteState initial = container.infinite.state().getValue();
        wasInfinite = initial != null && initial.isInfinite();
        container.infinite.state().observe(getViewLifecycleOwner(), infinite -> {
            // Virou Vigia do Infinito: a liberação dos lacrados já está na fila de I/O, antes desta leitura.
            if (infinite.isInfinite() && !wasInfinite) viewModel.reload();
            wasInfinite = infinite.isInfinite();
        });
    }

    @Override
    public void onDestroyView() {
        motion.cancelAll();
        super.onDestroyView();
        binding = null;
        adapter = null;
        shownFilter = null;
    }

    private NavController nav() {
        return NavHostFragment.findNavController(this);
    }

    private boolean isCurrent() {
        NavDestination current = nav().getCurrentDestination();
        return isAdded() && current != null && current.getId() == R.id.catalogFragment;
    }

    private void renderRarities(CatalogUiState state) {
        binding.rarityRow.setVisibility(state.rarities.isEmpty() ? View.GONE : View.VISIBLE);
        for (CatalogUiState.RarityProgress progress : state.rarities) {
            TextView view = rarityView(progress.rarity);
            int color = RarityViews.color(requireContext(), progress.rarity);
            int size = Math.round(view.getTextSize() * 1.1f);
            view.setCompoundDrawablesRelative(RarityViews.gem(requireContext(), color, size), null, null, null);
            view.setTextColor(color);
            view.setText(getString(R.string.catalog_rarity_count, progress.unlocked, progress.total));
            view.setContentDescription(getString(R.string.catalog_cd_rarity_progress,
                    getString(RarityViews.label(progress.rarity)), progress.unlocked, progress.total));
        }
    }

    private TextView rarityView(Rarity rarity) {
        switch (rarity) {
            case LEGENDARY: return binding.tvRarityLegendary;
            case EPIC: return binding.tvRarityEpic;
            case RARE: return binding.tvRarityRare;
            case COMMON:
            default: return binding.tvRarityCommon;
        }
    }

    private void render(CatalogUiState state) {
        if (state.status == CatalogUiState.Status.SIGNED_OUT) {
            if (!isCurrent()) return;
            Bundle args = new Bundle();
            args.putInt(AuthFragment.ARG_NEXT_DESTINATION, R.id.catalogFragment);
            nav().navigate(R.id.authFragment, args, FadeNavOptions.popUpTo(R.id.catalogFragment, true));
            return;
        }
        boolean loading = state.status == CatalogUiState.Status.LOADING;
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            binding.emptyState.setVisibility(View.GONE);
            return;
        }

        boolean firstShow = shownFilter == null;
        if (firstShow) {
            motion.countUp(binding.tvUnlockedCount, state.unlockedCount, String::valueOf, 150);
        } else {
            binding.tvUnlockedCount.setText(String.valueOf(state.unlockedCount));
        }
        binding.tvTotalCount.setText(state.hasRoster()
                ? getResources().getQuantityString(R.plurals.catalog_of_total, state.totalCount, state.totalCount)
                : getString(R.string.catalog_unlocked_only));
        binding.tvPercent.setVisibility(state.hasRoster() ? View.VISIBLE : View.GONE);
        binding.tvPercent.setText(getString(R.string.percent_value, state.progressPercent()));
        binding.progressBar.setVisibility(state.hasRoster() ? View.VISIBLE : View.GONE);
        binding.progressBar.setProgressCompat(state.progressPercent(), true);
        renderRarities(state);
        binding.progressCard.setContentDescription(state.hasRoster()
                ? getString(R.string.catalog_cd_progress, state.unlockedCount, state.totalCount)
                : binding.tvTotalCount.getText());

        binding.chipUnlocked.setText(getString(R.string.catalog_filter_unlocked, state.unlockedCount));
        binding.chipAll.setText(getString(R.string.catalog_filter_all, state.totalCount));
        binding.chipAll.setVisibility(state.hasRoster() ? View.VISIBLE : View.GONE);

        if (firstShow || shownFilter != state.filter) {
            // Primeira carga ou troca de filtro: as cartas entram em cascata.
            binding.grid.scheduleLayoutAnimation();
        }
        shownFilter = state.filter;
        adapter.submitList(state.items);
        boolean empty = state.items.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            boolean searching = !state.query.trim().isEmpty();
            binding.tvEmptyTitle.setText(searching ? R.string.catalog_search_empty_title : R.string.catalog_empty_title);
            binding.tvEmptyBody.setText(searching ? R.string.catalog_search_empty_body : R.string.catalog_empty_body);
            binding.btnPlay.setVisibility(searching ? View.GONE : View.VISIBLE);
        }
    }
}
