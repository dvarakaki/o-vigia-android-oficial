package com.ovigia.app.ui.auth;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.textfield.TextInputLayout;
import com.ovigia.app.R;
import com.ovigia.app.auth.AccountStore;

/**
 * Verificação ao vivo do e-mail, no login e no cadastro.
 *
 * <ul>
 *   <li>Enquanto o jogador digita, nada de bronca: quando o formato fecha, um
 *   certo verde se desenha no fim do campo; deixou de fechar, ele se apaga.</li>
 *   <li>Ao sair do campo com um e-mail que não fecha (ou ao tentar entrar com
 *   ele), o aviso aparece e o campo treme uma vez. Daí em diante o aviso
 *   acompanha a digitação e some assim que o e-mail fica certo.</li>
 * </ul>
 *
 * A regra é {@link AccountStore#isValidEmail}, a mesma do cadastro.
 */
final class EmailFieldCheck {

    private static final long MARK_IN_MS = 460;
    private static final long MARK_OUT_MS = 180;
    private static final long SHAKE_MS = 380;
    private static final int MARK_DP = 20;

    private final TextInputLayout layout;
    private final EditText field;
    private final CheckMarkDrawable mark;
    @Nullable private ValueAnimator markAnimator;
    @Nullable private Animator shake;
    /** O jogador já foi avisado de que o e-mail não fecha: o aviso segue a digitação até ficar certo. */
    private boolean flagged;
    private boolean valid;

    EmailFieldCheck(TextInputLayout layout, EditText field) {
        this.layout = layout;
        this.field = field;
        Context context = layout.getContext();
        int size = Math.round(MARK_DP * context.getResources().getDisplayMetrics().density);
        mark = new CheckMarkDrawable(size, ContextCompat.getColor(context, R.color.white_20),
                ContextCompat.getColor(context, R.color.vigia_success),
                ContextCompat.getColor(context, R.color.vigia_space));
        layout.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        layout.setEndIconDrawable(mark);
        layout.setEndIconContentDescription(R.string.auth_email_valid_cd);
        layout.setEndIconVisible(false);

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                onEdited(s.toString());
            }
        });
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && !text().trim().isEmpty() && !valid) flag();
        });
        valid = AccountStore.isValidEmail(text());
        showMark(valid, false);
    }

    /** O e-mail foi recusado ao tentar entrar: o mesmo aviso de sair do campo. */
    void flag() {
        flagged = true;
        layout.setError(layout.getContext().getString(R.string.auth_error_invalid_email));
        shakeField();
    }

    /** Tira o aviso (ex.: ao trocar entre entrar e criar conta). */
    void clearError() {
        flagged = false;
        layout.setError(null);
        // Sem isso o espaço da mensagem fica reservado embaixo do campo, vazio.
        layout.setErrorEnabled(false);
    }

    void cancel() {
        if (markAnimator != null) markAnimator.cancel();
        if (shake != null) shake.cancel();
    }

    private String text() {
        return field.getText() == null ? "" : field.getText().toString();
    }

    private void onEdited(String email) {
        boolean nowValid = AccountStore.isValidEmail(email);
        if (nowValid != valid) showMark(nowValid, true);
        valid = nowValid;
        if (flagged && (nowValid || email.trim().isEmpty())) clearError();
    }

    // ------------------------------------------------------------ certo

    private void showMark(boolean show, boolean animate) {
        if (markAnimator != null) markAnimator.cancel();
        float target = show ? 1f : 0f;
        if (!animate) {
            mark.setProgress(target);
            layout.setEndIconVisible(show);
            return;
        }
        if (show) layout.setEndIconVisible(true);
        ValueAnimator animator = ValueAnimator.ofFloat(mark.getProgress(), target);
        animator.setDuration(show ? MARK_IN_MS : MARK_OUT_MS);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(a -> mark.setProgress((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                // Apagado de vez: o espaço do ícone volta para o texto.
                if (!cancelled && !show) layout.setEndIconVisible(false);
            }
        });
        markAnimator = animator;
        animator.start();
    }

    // ------------------------------------------------------------ aviso

    private void shakeField() {
        if (shake != null) shake.cancel();
        float d = layout.getResources().getDisplayMetrics().density;
        ObjectAnimator animator = ObjectAnimator.ofFloat(layout, View.TRANSLATION_X,
                0f, -8 * d, 8 * d, -6 * d, 6 * d, -3 * d, 3 * d, 0f);
        animator.setDuration(SHAKE_MS);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                layout.setTranslationX(0f);
            }
        });
        shake = animator;
        animator.start();
    }
}
