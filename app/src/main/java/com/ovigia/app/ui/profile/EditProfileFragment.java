package com.ovigia.app.ui.profile;

import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.ovigia.app.AppContainer;
import com.ovigia.app.OVigiaApplication;
import com.ovigia.app.R;
import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.auth.AccountStore.ImageKind;
import com.ovigia.app.databinding.DialogDeleteAccountBinding;
import com.ovigia.app.databinding.FragmentEditProfileBinding;
import com.ovigia.app.profile.EditProfileUiState;
import com.ovigia.app.profile.EditProfileUiState.Busy;
import com.ovigia.app.profile.EditProfileUiState.Status;
import com.ovigia.app.profile.EditProfileViewModel;
import com.ovigia.app.ui.ConfirmDialog;
import com.ovigia.app.ui.SystemBarInsets;
import com.ovigia.app.ui.home.HomeFragment;

import java.util.Locale;

/**
 * Edição do perfil: banner e foto (seletor de fotos do sistema, sem permissões),
 * nome, bio e e-mail, senha e exclusão da conta. Avisa o perfil pelo
 * {@link #KEY_PROFILE_CHANGED} quando algo muda.
 */
public class EditProfileFragment extends Fragment {

    /** Deixado no SavedStateHandle do perfil quando algo foi alterado. */
    public static final String KEY_PROFILE_CHANGED = "profile_changed";
    private static final String STATE_FIELDS_FILLED = "fields_filled";

    private FragmentEditProfileBinding binding;
    private EditProfileViewModel viewModel;
    private EditProfileUiState lastState;
    /** Os campos já receberam os dados da conta (depois disso, o texto é do jogador). */
    private boolean fieldsFilled = false;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickAvatar =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),
                    uri -> onImagePicked(ImageKind.AVATAR, uri));
    private final ActivityResultLauncher<PickVisualMediaRequest> pickBanner =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),
                    uri -> onImagePicked(ImageKind.BANNER, uri));

    public EditProfileFragment() {
        super(R.layout.fragment_edit_profile);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentEditProfileBinding.bind(view);
        SystemBarInsets.extendHeight(binding.bannerContainer);
        SystemBarInsets.marginTop(binding.btnBack);
        SystemBarInsets.marginTop(binding.tvTitle);
        SystemBarInsets.extendHeight(binding.statusScrim);
        int bannerHeight = getResources().getDimensionPixelSize(R.dimen.profile_banner_height);
        binding.scroll.setOnScrollChangeListener((View.OnScrollChangeListener) (v, x, y, oldX, oldY) -> {
            if (binding != null) binding.statusScrim.setAlpha(Math.min(1f, (float) y / bannerHeight));
        });
        // Depois de rotação, os campos restauram o próprio texto.
        fieldsFilled = savedInstanceState != null && savedInstanceState.getBoolean(STATE_FIELDS_FILLED, false);

        AppContainer container = ((OVigiaApplication) requireActivity().getApplication()).container();
        viewModel = new ViewModelProvider(this, new EditProfileViewModel.Factory(
                container.accountStore, container.collectionStore, container.learningStore,
                container.achievementsStore, container.profileImages,
                new OnlineProfile(container.socialRepository, container.socialExecutor),
                container.socialExecutor, container.ioExecutor, container.mainExecutor))
                .get(EditProfileViewModel.class);

        binding.btnBack.setOnClickListener(v -> nav().popBackStack());
        binding.avatarContainer.setOnClickListener(v -> chooseImage(ImageKind.AVATAR));
        binding.bannerContainer.setOnClickListener(v -> chooseImage(ImageKind.BANNER));
        binding.btnSaveProfile.setOnClickListener(v -> saveProfile());
        binding.btnChangePassword.setOnClickListener(v -> changePassword());
        binding.newPasswordStrength.attachTo(binding.etNewPassword);
        binding.btnDeleteAccount.setOnClickListener(v -> confirmDelete());
        onDone(binding.etEmail, this::saveProfile);
        onDone(binding.etEmailPassword, this::saveProfile);
        onDone(binding.etNewPassword, this::changePassword);
        binding.etEmail.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                updateEmailPasswordVisibility();
            }
        });

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.messages().observe(getViewLifecycleOwner(), event -> {
            EditProfileUiState.Message message = event.consume();
            if (message != null) onMessage(message);
        });
        viewModel.start();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_FIELDS_FILLED, fieldsFilled);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        lastState = null;
    }

    private NavController nav() {
        return NavHostFragment.findNavController(this);
    }

    // ------------------------------------------------------------ estado

    private void render(EditProfileUiState state) {
        lastState = state;
        if (state.status == Status.SIGNED_OUT) {
            nav().popBackStack(R.id.homeFragment, false);
            return;
        }
        if (state.status == Status.DELETED) {
            nav().getBackStackEntry(R.id.homeFragment).getSavedStateHandle()
                    .set(HomeFragment.KEY_RESULT_MESSAGE, getString(R.string.edit_account_deleted));
            nav().popBackStack(R.id.homeFragment, false);
            return;
        }
        boolean loading = state.status == Status.LOADING;
        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.scroll.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        if (loading) return;

        if (!fieldsFilled) fillFields(state);
        ProfileImageBinder.bindBanner(this, binding.imageBanner, binding.bannerTint, state.bannerFile);
        ProfileImageBinder.bindAvatar(this, binding.imageAvatar, state.avatarFile);

        boolean busy = state.isBusy();
        binding.imageProgress.setVisibility(state.busy == Busy.IMAGE ? View.VISIBLE : View.INVISIBLE);
        binding.avatarContainer.setEnabled(!busy);
        binding.bannerContainer.setEnabled(!busy);
        binding.btnSaveProfile.setEnabled(!busy);
        binding.btnChangePassword.setEnabled(!busy);
        binding.btnDeleteAccount.setEnabled(!busy);
        binding.btnSaveProfile.setText(state.busy == Busy.PROFILE ? R.string.action_saving : R.string.edit_save_profile);
        binding.btnChangePassword.setText(state.busy == Busy.PASSWORD ? R.string.action_saving : R.string.edit_change_password);
        updateEmailPasswordVisibility();

        showProfileError(state.profileError);
        showPasswordError(state.passwordError);
        if (state.deleteError != null) {
            Snackbar.make(binding.getRoot(), errorText(state.deleteError), Snackbar.LENGTH_LONG).show();
            viewModel.clearDeleteError();
        }
    }

    private void fillFields(EditProfileUiState state) {
        fieldsFilled = true;
        binding.etName.setText(state.name);
        binding.etBio.setText(state.bio);
        binding.etEmail.setText(state.email);
        binding.etEmailPassword.setText(null);
    }

    /** A senha para trocar o e-mail só aparece quando o e-mail digitado é outro. */
    private void updateEmailPasswordVisibility() {
        if (binding == null || lastState == null || lastState.email == null) return;
        String typed = text(binding.etEmail).trim().toLowerCase(Locale.ROOT);
        boolean changed = !typed.equals(lastState.email);
        binding.emailPasswordLayout.setVisibility(changed ? View.VISIBLE : View.GONE);
        if (!changed) {
            binding.etEmailPassword.setText(null);
            binding.emailPasswordLayout.setError(null);
        }
    }

    private void onMessage(EditProfileUiState.Message message) {
        @StringRes int text;
        switch (message) {
            case PROFILE_SAVED:
                text = R.string.edit_saved;
                // Mostra o que foi gravado (nome e e-mail normalizados).
                if (lastState != null) fillFields(lastState);
                break;
            case PASSWORD_CHANGED:
                text = R.string.edit_password_changed;
                binding.etCurrentPassword.setText(null);
                binding.etNewPassword.setText(null);
                break;
            case IMAGE_UPDATED:
                text = R.string.edit_image_updated;
                break;
            case IMAGE_REMOVED:
                text = R.string.edit_image_removed;
                break;
            case IMAGE_FAILED:
            default:
                Snackbar.make(binding.getRoot(), R.string.edit_image_failed, Snackbar.LENGTH_LONG).show();
                return;
        }
        markProfileChanged();
        hideKeyboard();
        Snackbar.make(binding.getRoot(), text, Snackbar.LENGTH_SHORT).show();
    }

    private void markProfileChanged() {
        NavBackStackEntry previous = nav().getPreviousBackStackEntry();
        if (previous != null && previous.getDestination().getId() == R.id.profileFragment) {
            previous.getSavedStateHandle().set(KEY_PROFILE_CHANGED, true);
        }
    }

    // ------------------------------------------------------------ ações

    private void saveProfile() {
        clearErrors(binding.nameLayout, binding.bioLayout, binding.emailLayout, binding.emailPasswordLayout);
        viewModel.saveProfile(text(binding.etName), text(binding.etBio), text(binding.etEmail),
                text(binding.etEmailPassword));
    }

    private void changePassword() {
        clearErrors(binding.currentPasswordLayout, binding.newPasswordLayout);
        viewModel.changePassword(text(binding.etCurrentPassword), text(binding.etNewPassword));
    }

    /** Sem imagem: abre direto o seletor. Com imagem: oferece trocar ou remover. */
    private void chooseImage(ImageKind kind) {
        if (lastState == null || lastState.isBusy()) return;
        boolean hasImage = kind == ImageKind.AVATAR ? lastState.avatarFile != null : lastState.bannerFile != null;
        if (!hasImage) {
            launchPicker(kind);
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(kind == ImageKind.AVATAR ? R.string.edit_photo_options_title : R.string.edit_banner_options_title)
                .setItems(new CharSequence[]{
                        getString(R.string.edit_option_choose_image),
                        getString(R.string.edit_option_remove_image)
                }, (dialog, which) -> {
                    if (which == 0) {
                        launchPicker(kind);
                    } else {
                        viewModel.removeImage(kind);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void launchPicker(ImageKind kind) {
        PickVisualMediaRequest request = new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build();
        (kind == ImageKind.AVATAR ? pickAvatar : pickBanner).launch(request);
    }

    private void onImagePicked(ImageKind kind, @Nullable Uri uri) {
        // null = o jogador fechou o seletor sem escolher.
        if (uri != null && viewModel != null) viewModel.changeImage(kind, uri);
    }

    private void confirmDelete() {
        if (lastState == null || lastState.isBusy()) return;
        DialogDeleteAccountBinding dialog = DialogDeleteAccountBinding.inflate(getLayoutInflater());
        ConfirmDialog.with(requireContext())
                .icon(R.drawable.ic_delete_forever)
                .title(R.string.edit_delete_confirm_title)
                .message(R.string.edit_delete_confirm_message)
                .extra(dialog.getRoot(), dialog.etPassword)
                .destructive()
                .confirm(R.string.edit_delete_account, () -> {
                    if (viewModel != null) viewModel.deleteAccount(text(dialog.etPassword));
                })
                .show();
    }

    // ------------------------------------------------------------ apoio

    private void showProfileError(@Nullable AccountStore.Error error) {
        if (error == null) return;
        switch (error) {
            case NAME_REQUIRED:
            case NAME_TOO_LONG:
                binding.nameLayout.setError(errorText(error));
                break;
            case BIO_TOO_LONG:
                binding.bioLayout.setError(errorText(error));
                break;
            case WRONG_PASSWORD:
                binding.emailPasswordLayout.setError(errorText(error));
                break;
            case INVALID_EMAIL:
            case EMAIL_IN_USE:
            default:
                binding.emailLayout.setError(errorText(error));
                break;
        }
    }

    private void showPasswordError(@Nullable AccountStore.Error error) {
        if (error == null) return;
        if (error == AccountStore.Error.WEAK_PASSWORD) {
            binding.newPasswordLayout.setError(errorText(error));
            binding.newPasswordStrength.shakeMissing();
        } else {
            binding.currentPasswordLayout.setError(errorText(error));
        }
    }

    private String errorText(AccountStore.Error error) {
        switch (error) {
            case NAME_REQUIRED: return getString(R.string.auth_error_name_required);
            case NAME_TOO_LONG: return getString(R.string.edit_error_name_too_long);
            case BIO_TOO_LONG: return getString(R.string.edit_error_bio_too_long);
            case INVALID_EMAIL: return getString(R.string.auth_error_invalid_email);
            case EMAIL_IN_USE: return getString(R.string.auth_error_email_in_use);
            case WEAK_PASSWORD: return getString(R.string.auth_error_weak_password);
            case ONLINE_UNAVAILABLE: return getString(R.string.edit_error_delete_online_unavailable);
            case WRONG_PASSWORD:
            case WRONG_CREDENTIALS:
            case NOT_SIGNED_IN:
            default: return getString(R.string.edit_error_wrong_password);
        }
    }

    private static void clearErrors(TextInputLayout... layouts) {
        for (TextInputLayout layout : layouts) layout.setError(null);
    }

    private static void onDone(EditText field, Runnable action) {
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            action.run();
            return true;
        });
    }

    private static String text(EditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    private void hideKeyboard() {
        WindowCompat.getInsetsController(requireActivity().getWindow(), requireView())
                .hide(WindowInsetsCompat.Type.ime());
    }
}
