package com.ovigia.app.ui.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.ovigia.app.AppContainer;
import com.ovigia.app.BuildConfig;
import com.ovigia.app.OVigiaApplication;
import com.ovigia.app.R;
import com.ovigia.app.databinding.FragmentSettingsBinding;
import com.ovigia.app.settings.AppLanguage;
import com.ovigia.app.settings.AppLocales;
import com.ovigia.app.settings.HapticStrength;
import com.ovigia.app.settings.SettingsUiState;
import com.ovigia.app.settings.SettingsViewModel;
import com.ovigia.app.ui.Motion;
import com.ovigia.app.ui.SystemBarInsets;

/**
 * Configurações: idioma do app, vibração e tela ligada na partida, limpeza do
 * cache, esquecer o aprendizado e informações sobre o app.
 *
 * Trocar o idioma recria a Activity; a pilha de navegação é restaurada e o
 * jogador continua aqui, já no idioma novo.
 */
public class SettingsFragment extends Fragment {

    private static final String COMIC_VINE_URL = "https://comicvine.gamespot.com/";

    private FragmentSettingsBinding binding;
    private SettingsViewModel viewModel;
    private SettingsUiState lastState;
    private final Motion motion = new Motion();

    public SettingsFragment() {
        super(R.layout.fragment_settings);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentSettingsBinding.bind(view);
        SystemBarInsets.padTop(view);

        AppContainer container = ((OVigiaApplication) requireActivity().getApplication()).container();
        viewModel = new ViewModelProvider(this, new SettingsViewModel.Factory(container.settingsStore,
                container.learningStore, container.accountStore, container.appCache,
                container.ioExecutor, container.mainExecutor))
                .get(SettingsViewModel.class);

        // O toque ondula até os cantos arredondados do card sem vazar deles.
        for (View card : new View[]{binding.cardLanguage, binding.cardGame, binding.cardData, binding.cardAbout}) {
            card.setClipToOutline(true);
        }

        binding.btnBack.setOnClickListener(v -> NavHostFragment.findNavController(this).popBackStack());
        binding.rowLanguage.setOnClickListener(v -> chooseLanguage());
        bindSwitchRow(binding.rowHaptics, binding.switchHaptics, R.string.settings_haptics,
                R.string.settings_haptics_summary);
        bindSwitchRow(binding.rowKeepScreenOn, binding.switchKeepScreenOn, R.string.settings_keep_screen_on,
                R.string.settings_keep_screen_on_summary);
        binding.rowHaptics.setOnClickListener(v -> {
            if (lastState == null || !lastState.loaded) return;
            boolean enabling = !lastState.hapticFeedback;
            viewModel.setHapticFeedback(enabling);
            // Ligou: um pulso na força guardada confirma que voltou a vibrar.
            if (enabling) container.haptics.preview(HapticStrength.of(lastState.hapticLevel));
        });
        // A faixa vem do modelo (o layout só tem os mesmos números para a pré-visualização).
        binding.sliderHapticLevel.setValueFrom(HapticStrength.MIN_LEVEL);
        binding.sliderHapticLevel.setValueTo(HapticStrength.MAX_LEVEL);
        binding.sliderHapticLevel.setLabelFormatter(value -> String.valueOf(Math.round(value)));
        binding.sliderHapticLevel.addOnChangeListener((slider, value, fromUser) -> {
            // O render também move a bolinha: só o jogador (dedo, teclado ou TalkBack) muda a força.
            if (!fromUser) return;
            int level = Math.round(value);
            viewModel.setHapticLevel(level);
            // Cada nível que a bolinha alcança vibra nele: a força se sente enquanto arrasta.
            container.haptics.preview(HapticStrength.of(level));
        });
        binding.rowKeepScreenOn.setOnClickListener(v -> {
            if (lastState != null && lastState.loaded) viewModel.setKeepScreenOn(!lastState.keepScreenOn);
        });
        binding.rowClearCache.setOnClickListener(v -> confirmClearCache());
        binding.rowForget.setOnClickListener(v -> confirmForget());
        binding.rowDataSource.setOnClickListener(v -> openUrl(COMIC_VINE_URL));

        binding.tvLanguageValue.setText(languageName(AppLocales.current()));
        binding.tvVersionValue.setText(BuildConfig.VERSION_NAME);
        binding.rowVersion.setContentDescription(getString(R.string.settings_version) + " " + BuildConfig.VERSION_NAME);

        // Entrada em cascata só na primeira abertura (não ao girar nem ao voltar da troca de idioma).
        if (savedInstanceState == null) {
            motion.staggerIn(80, binding.tvSectionLanguage, binding.cardLanguage, binding.tvSectionGame,
                    binding.cardGame, binding.tvSectionData, binding.cardData, binding.tvSectionAbout,
                    binding.cardAbout, binding.tvDisclaimer);
        }

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.messages().observe(getViewLifecycleOwner(), event -> {
            SettingsUiState.Message message = event.consume();
            if (message == null) return;
            Snackbar.make(binding.getRoot(), message == SettingsUiState.Message.CACHE_CLEARED
                    ? R.string.settings_cache_cleared : R.string.settings_forget_done, Snackbar.LENGTH_SHORT).show();
        });
        viewModel.start();
    }

    /** A barra só responde com a vibração ligada; desligada, fica à mostra, apagada. */
    private void renderHapticStrength(SettingsUiState state) {
        boolean active = state.loaded && state.hapticFeedback;
        int level = HapticStrength.clamp(state.hapticLevel);
        if (Math.round(binding.sliderHapticLevel.getValue()) != level) binding.sliderHapticLevel.setValue(level);
        binding.sliderHapticLevel.setEnabled(active);
        binding.tvHapticLevel.setText(getString(R.string.settings_haptics_level, level, HapticStrength.MAX_LEVEL));
        binding.blockHapticStrength.setAlpha(active ? 1f : 0.5f);
    }

    @Override
    public void onDestroyView() {
        motion.cancelAll();
        super.onDestroyView();
        binding = null;
        lastState = null;
    }

    private void render(SettingsUiState state) {
        boolean firstLoad = state.loaded && (lastState == null || !lastState.loaded);
        lastState = state;
        binding.switchHaptics.setEnabled(state.loaded);
        binding.switchKeepScreenOn.setEnabled(state.loaded);
        binding.switchHaptics.setChecked(state.hapticFeedback);
        binding.switchKeepScreenOn.setChecked(state.keepScreenOn);
        renderHapticStrength(state);
        if (firstLoad) {
            // Valores lidos do disco aparecem já no lugar; só toques do jogador animam.
            binding.switchHaptics.jumpDrawablesToCurrentState();
            binding.switchKeepScreenOn.jumpDrawablesToCurrentState();
        }

        binding.cacheProgress.setVisibility(state.clearingCache ? View.VISIBLE : View.GONE);
        binding.rowClearCache.setEnabled(!state.clearingCache);
        if (state.cacheBytes == SettingsUiState.SIZE_UNKNOWN) {
            binding.tvCacheSummary.setText(R.string.settings_cache_calculating);
        } else {
            binding.tvCacheSummary.setText(getString(R.string.settings_cache_summary,
                    Formatter.formatShortFileSize(requireContext(), state.cacheBytes)));
        }
    }

    /**
     * A linha inteira é o controle: o TalkBack lê título, resumo e "ativado/desativado"
     * de uma vez, como num interruptor comum.
     */
    private void bindSwitchRow(View row, MaterialSwitch toggle, @StringRes int title, @StringRes int summary) {
        row.setContentDescription(getString(title) + ". " + getString(summary));
        ViewCompat.setAccessibilityDelegate(row, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(toggle.isChecked()
                        ? AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                        : AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE);
                info.setEnabled(toggle.isEnabled());
            }
        });
    }

    // ------------------------------------------------------------ ações

    private void chooseLanguage() {
        AppLanguage[] languages = AppLanguage.values();
        CharSequence[] names = new CharSequence[languages.length];
        for (int i = 0; i < languages.length; i++) names[i] = getString(languageName(languages[i]));
        int checked = AppLocales.current().ordinal();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    AppLanguage chosen = languages[which];
                    // Sem troca, sem recriar a tela à toa.
                    if (chosen != AppLocales.current()) AppLocales.apply(chosen);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmClearCache() {
        if (lastState == null || lastState.clearingCache) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_clear_cache)
                .setMessage(R.string.settings_clear_cache_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_clear, (dialog, which) -> viewModel.clearCache())
                .show();
    }

    private void confirmForget() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_forget_learning)
                .setMessage(R.string.settings_forget_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_forget, (dialog, which) -> viewModel.forgetLearning())
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            // Sem navegador: nada a fazer.
        }
    }

    @StringRes
    private static int languageName(AppLanguage language) {
        switch (language) {
            case PORTUGUESE_BRAZIL: return R.string.language_pt_br;
            case ENGLISH: return R.string.language_en;
            case SPANISH: return R.string.language_es;
            case FRENCH: return R.string.language_fr;
            case SYSTEM:
            default: return R.string.settings_language_system;
        }
    }
}
