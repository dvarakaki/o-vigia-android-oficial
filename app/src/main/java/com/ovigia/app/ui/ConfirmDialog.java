package com.ovigia.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ovigia.app.R;
import com.ovigia.app.databinding.ViewConfirmDialogBinding;

/**
 * Popup de confirmação das ações que tiram o jogador de onde ele está: sair da
 * conta, excluir a conta. Ícone num disco, título, explicação e os botões
 * empilhados, com a ação em cima — azul numa saída comum, vermelha
 * ({@link #destructive()}) no que não tem volta.
 *
 * Quando a confirmação depende de algo digitado (a senha, na exclusão), o campo
 * entra em {@link #extra} e o botão da ação só acende quando ele não está vazio.
 *
 * <pre>
 * ConfirmDialog.with(context)
 *         .icon(R.drawable.ic_logout)
 *         .title(R.string.profile_sign_out_confirm_title)
 *         .message(R.string.profile_sign_out_confirm_message)
 *         .confirm(R.string.profile_sign_out, this::signOut)
 *         .show();
 * </pre>
 */
public final class ConfirmDialog {

    private final Context context;
    @DrawableRes private int icon;
    @StringRes private int title;
    @StringRes private int message;
    @StringRes private int confirmText;
    @Nullable private Runnable onConfirm;
    private boolean destructive;
    @Nullable private View extra;
    @Nullable private EditText required;

    private ConfirmDialog(Context context) {
        this.context = context;
    }

    public static ConfirmDialog with(@NonNull Context context) {
        return new ConfirmDialog(context);
    }

    public ConfirmDialog icon(@DrawableRes int icon) {
        this.icon = icon;
        return this;
    }

    public ConfirmDialog title(@StringRes int title) {
        this.title = title;
        return this;
    }

    public ConfirmDialog message(@StringRes int message) {
        this.message = message;
        return this;
    }

    /** O botão da ação e o que ele faz (o popup fecha sozinho antes). */
    public ConfirmDialog confirm(@StringRes int text, @NonNull Runnable onConfirm) {
        this.confirmText = text;
        this.onConfirm = onConfirm;
        return this;
    }

    /** Ação sem volta: ícone e botão em vermelho. */
    public ConfirmDialog destructive() {
        this.destructive = true;
        return this;
    }

    /**
     * Conteúdo entre a explicação e os botões (ex.: o campo de senha).
     *
     * @param required campo que precisa estar preenchido para a ação acender;
     *                 o "Concluído" do teclado nele também confirma
     */
    public ConfirmDialog extra(@NonNull View view, @Nullable EditText required) {
        this.extra = view;
        this.required = required;
        return this;
    }

    public AlertDialog show() {
        ViewConfirmDialogBinding binding = ViewConfirmDialogBinding.inflate(LayoutInflater.from(context));
        int accent = ContextCompat.getColor(context, destructive ? R.color.vigia_danger : R.color.vigia_gold);
        int accentBg = ContextCompat.getColor(context, destructive ? R.color.vigia_danger_bg : R.color.vigia_gold_bg);
        binding.imageIcon.setImageResource(icon);
        binding.imageIcon.setImageTintList(ColorStateList.valueOf(accent));
        binding.iconDisc.setBackgroundTintList(ColorStateList.valueOf(accentBg));
        binding.tvTitle.setText(title);
        binding.tvMessage.setText(message);

        Button action = destructive ? binding.btnConfirmDanger : binding.btnConfirm;
        (destructive ? binding.btnConfirm : binding.btnConfirmDanger).setVisibility(View.GONE);
        action.setVisibility(View.VISIBLE);
        action.setText(confirmText);

        if (extra != null) {
            binding.extra.setVisibility(View.VISIBLE);
            binding.extra.addView(extra);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(binding.getRoot())
                .setBackground(AppCompatResources.getDrawable(context, R.drawable.bg_dialog))
                .create();

        Runnable confirm = () -> {
            if (!action.isEnabled()) return;
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        };
        action.setOnClickListener(v -> confirm.run());
        binding.btnCancel.setOnClickListener(v -> dialog.cancel());

        if (required != null) {
            EditText field = required;
            action.setEnabled(!field.getText().toString().trim().isEmpty());
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

                @Override
                public void afterTextChanged(Editable s) {
                    action.setEnabled(!s.toString().trim().isEmpty());
                }
            });
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            field.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                confirm.run();
                return true;
            });
        }

        dialog.show();
        return dialog;
    }
}
