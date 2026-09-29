package com.ovigia.app.ui.auth;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.ovigia.app.AppContainer;
import com.ovigia.app.OVigiaApplication;
import com.ovigia.app.R;
import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.auth.AuthUiState;
import com.ovigia.app.auth.AuthViewModel;
import com.ovigia.app.databinding.FragmentAuthBinding;
import com.ovigia.app.ui.FadeNavOptions;
import com.ovigia.app.ui.SystemBarInsets;

/**
 * Login e cadastro. Dois usos:
 * <ul>
 *   <li>{@link #ARG_NEXT_DESTINATION} com um destino (perfil, catálogo): ao entrar,
 *       abre esse destino no lugar desta tela.</li>
 *   <li>Sem destino (ex.: desbloquear herói no resultado): ao entrar, volta para quem
 *       abriu avisando pelo {@link #KEY_SIGNED_IN} no SavedStateHandle dela.</li>
 * </ul>
 */
public class AuthFragment extends Fragment {

    /** Id do destino a abrir depois do login; 0 = voltar para quem abriu. */
    public static final String ARG_NEXT_DESTINATION = "nextDestination";
    public static final String ARG_REASON = "reason";
    /** Resultado deixado na tela anterior quando o login dá certo. */
    public static final String KEY_SIGNED_IN = "signed_in";

    private FragmentAuthBinding binding;
    private AuthViewModel viewModel;
    /** Modo mostrado na tela; {@code null} antes do primeiro render (que não anima). */
    @Nullable private AuthUiState.Mode shownMode;

    public AuthFragment() {
        super(R.layout.fragment_auth);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentAuthBinding.bind(view);
        SystemBarInsets.padTop(view);

        AppContainer container = ((OVigiaApplication) requireActivity().getApplication()).container();
        viewModel = new ViewModelProvider(this, new AuthViewModel.Factory(
                container.accountStore, container.ioExecutor, container.socialExecutor, container.mainExecutor,
                new OnlineAuth(container.socialRepository)))
                .get(AuthViewModel.class);

        binding.btnBack.setOnClickListener(v -> nav().popBackStack());

        String reason = requireArguments().getString(ARG_REASON);
        binding.tvReason.setText(reason);
        binding.tvReason.setVisibility(reason != null ? View.VISIBLE : View.GONE);

        binding.modeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            clearErrors();
            viewModel.setMode(checkedId == R.id.btnModeSignUp ? AuthUiState.Mode.SIGN_UP : AuthUiState.Mode.SIGN_IN);
        });
        binding.btnSubmit.setOnClickListener(v -> submit());
        binding.passwordStrength.attachTo(binding.etPassword);
        binding.etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                // Voltou a digitar: o aviso da tentativa anterior sai, a verificação ao vivo assume.
                if (binding != null) binding.passwordLayout.setError(null);
            }
        });
        binding.etPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            submit();
            return true;
        });

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.signedIn().observe(getViewLifecycleOwner(), event -> {
            AccountStore.Account account = event.consume();
            if (account != null) onSignedIn();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        shownMode = null;
    }

    private NavController nav() {
        return NavHostFragment.findNavController(this);
    }

    private void submit() {
        clearErrors();
        viewModel.submit(text(binding.etName), text(binding.etEmail), text(binding.etPassword));
    }

    private static String text(EditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    private void render(AuthUiState state) {
        boolean signUp = state.mode == AuthUiState.Mode.SIGN_UP;
        binding.modeToggle.check(signUp ? R.id.btnModeSignUp : R.id.btnModeSignIn);
        binding.tvTitle.setText(signUp ? R.string.auth_title_sign_up : R.string.auth_title_sign_in);
        if (shownMode != null && shownMode != state.mode) {
            // Trocar entre entrar e criar conta: o nome e a verificação da senha abrem e fecham deslizando.
            TransitionManager.beginDelayedTransition((ViewGroup) binding.passwordStrength.getParent(),
                    new AutoTransition().setDuration(220));
        }
        shownMode = state.mode;
        binding.nameLayout.setVisibility(signUp ? View.VISIBLE : View.GONE);
        // A verificação é só do cadastro: quem já tem conta entra com a senha que tiver.
        binding.passwordStrength.setVisibility(signUp ? View.VISIBLE : View.GONE);

        binding.btnSubmit.setText(state.loading ? null
                : getString(signUp ? R.string.auth_submit_sign_up : R.string.auth_submit_sign_in));
        binding.btnSubmit.setEnabled(!state.loading);
        binding.progress.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        for (int i = 0; i < binding.modeToggle.getChildCount(); i++) {
            binding.modeToggle.getChildAt(i).setEnabled(!state.loading);
        }

        if (state.error != null) showError(state.error);
    }

    private void showError(AccountStore.Error error) {
        switch (error) {
            case NAME_REQUIRED:
                binding.nameLayout.setError(getString(R.string.auth_error_name_required));
                break;
            case INVALID_EMAIL:
                binding.emailLayout.setError(getString(R.string.auth_error_invalid_email));
                break;
            case EMAIL_IN_USE:
                binding.emailLayout.setError(getString(R.string.auth_error_email_in_use));
                break;
            case WEAK_PASSWORD:
                binding.passwordLayout.setError(getString(R.string.auth_error_weak_password));
                // O que falta treme na lista logo abaixo.
                binding.passwordStrength.shakeMissing();
                break;
            case WRONG_CREDENTIALS:
            default:
                binding.passwordLayout.setError(getString(R.string.auth_error_wrong_credentials));
                break;
        }
    }

    private void clearErrors() {
        binding.nameLayout.setError(null);
        binding.emailLayout.setError(null);
        binding.passwordLayout.setError(null);
    }

    private void onSignedIn() {
        WindowCompat.getInsetsController(requireActivity().getWindow(), requireView())
                .hide(WindowInsetsCompat.Type.ime());
        // Entrou: as conquistas que a conta já tinha viram o marco zero dela, e as
        // próximas é que rendem comemoração.
        ((OVigiaApplication) requireActivity().getApplication()).container().achievements.sync();
        NavController nav = nav();
        int next = requireArguments().getInt(ARG_NEXT_DESTINATION, 0);
        if (next != 0) {
            nav.navigate(next, null, FadeNavOptions.popUpTo(R.id.authFragment, true));
            return;
        }
        NavBackStackEntry previous = nav.getPreviousBackStackEntry();
        if (previous != null) previous.getSavedStateHandle().set(KEY_SIGNED_IN, true);
        nav.popBackStack();
    }
}
