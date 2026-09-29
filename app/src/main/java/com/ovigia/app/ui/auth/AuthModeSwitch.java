package com.ovigia.app.ui.auth;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.ovigia.app.R;
import com.ovigia.app.auth.AuthUiState.Mode;
import com.ovigia.app.databinding.ViewAuthModeSwitchBinding;

/**
 * Seletor Entrar / Criar conta: controle segmentado com uma pílula azul que
 * desliza até a opção escolhida — esticando no meio do caminho e assentando com
 * um leve passo além, como algo com peso — enquanto rótulos e ícones trocam de
 * cor junto com ela.
 *
 * Para o TalkBack, as duas opções são um par de botões de opção: o escolhido é
 * lido como marcado. Respeita a escala de animação do aparelho.
 */
public final class AuthModeSwitch extends FrameLayout {

    /** Avisado quando o jogador escolhe a outra opção (não quando o código chama {@link #setMode}). */
    public interface OnModeChangeListener {
        void onModeChange(Mode mode);
    }

    private static final long SLIDE_MS = 380;
    private static final long TINT_MS = 220;
    /** Quanto a pílula estica no meio da viagem. */
    private static final float STRETCH = 0.14f;

    private final ViewAuthModeSwitchBinding binding;
    @ColorInt private final int selectedColor;
    @ColorInt private final int idleColor;
    @Nullable private OnModeChangeListener listener;
    private Mode mode = Mode.SIGN_IN;
    @Nullable private Animator slide;
    @Nullable private Animator tint;

    public AuthModeSwitch(@NonNull Context context) {
        this(context, null);
    }

    public AuthModeSwitch(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        binding = ViewAuthModeSwitchBinding.inflate(LayoutInflater.from(context), this);
        int inset = Math.round(4 * getResources().getDisplayMetrics().density);
        setPadding(inset, inset, inset, inset);
        setBackgroundResource(R.drawable.bg_mode_switch_track);
        selectedColor = ContextCompat.getColor(context, R.color.white);
        idleColor = ContextCompat.getColor(context, R.color.white_50);

        binding.tabSignIn.setOnClickListener(v -> choose(Mode.SIGN_IN));
        binding.tabSignUp.setOnClickListener(v -> choose(Mode.SIGN_UP));
        describeAsOption(binding.tabSignIn, Mode.SIGN_IN);
        describeAsOption(binding.tabSignUp, Mode.SIGN_UP);

        // A pílula tem a largura de uma opção; muda com a tela (girar, tamanho de fonte).
        binding.tabs.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            View target = tabFor(mode);
            ViewGroup.LayoutParams params = binding.indicator.getLayoutParams();
            if (params.width != target.getWidth()) {
                params.width = target.getWidth();
                binding.indicator.setLayoutParams(params);
            }
            if (slide == null) binding.indicator.setTranslationX(targetX(mode));
        });
        paint(mode, false);
    }

    public void setOnModeChangeListener(@Nullable OnModeChangeListener listener) {
        this.listener = listener;
    }

    public Mode getMode() {
        return mode;
    }

    /** Mostra {@code newMode}; sem {@code animate} (primeira exibição, ao girar) já no lugar. */
    public void setMode(Mode newMode, boolean animate) {
        if (newMode == mode) return;
        mode = newMode;
        paint(newMode, animate);
        move(newMode, animate);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        binding.tabSignIn.setEnabled(enabled);
        binding.tabSignUp.setEnabled(enabled);
        animate().alpha(enabled ? 1f : 0.55f).setDuration(TINT_MS).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (slide != null) slide.cancel();
        if (tint != null) tint.cancel();
        super.onDetachedFromWindow();
    }

    private void choose(Mode chosen) {
        if (!isEnabled() || chosen == mode) return;
        setMode(chosen, true);
        if (listener != null) listener.onModeChange(chosen);
    }

    // ------------------------------------------------------------ pílula

    private View tabFor(Mode m) {
        return m == Mode.SIGN_UP ? binding.tabSignUp : binding.tabSignIn;
    }

    /** Onde a pílula fica para cobrir a opção (vale também da direita para a esquerda). */
    private float targetX(Mode m) {
        return binding.tabs.getLeft() + tabFor(m).getLeft() - binding.indicator.getLeft();
    }

    private void move(Mode target, boolean animate) {
        if (slide != null) slide.cancel();
        View pill = binding.indicator;
        if (!animate || !isLaidOut()) {
            pill.setTranslationX(targetX(target));
            pill.setScaleX(1f);
            return;
        }
        ObjectAnimator travel = ObjectAnimator.ofFloat(pill, View.TRANSLATION_X, pill.getTranslationX(),
                targetX(target));
        travel.setInterpolator(new OvershootInterpolator(0.9f));
        // Estica no meio do caminho e volta ao tamanho ao chegar.
        pill.setPivotX(pill.getWidth() / 2f);
        ObjectAnimator stretch = ObjectAnimator.ofFloat(pill, View.SCALE_X, 1f, 1f + STRETCH, 1f);
        stretch.setInterpolator(new FastOutSlowInInterpolator());
        AnimatorSet set = new AnimatorSet();
        set.playTogether(travel, stretch);
        set.setDuration(SLIDE_MS);
        set.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (slide == animation) slide = null;
                if (!cancelled) {
                    pill.setScaleX(1f);
                    pill.setTranslationX(targetX(mode));
                }
            }
        });
        slide = set;
        set.start();
    }

    // ------------------------------------------------------------ rótulos

    private void paint(Mode selected, boolean animate) {
        if (tint != null) tint.cancel();
        int signInTo = selected == Mode.SIGN_IN ? selectedColor : idleColor;
        int signUpTo = selected == Mode.SIGN_UP ? selectedColor : idleColor;
        binding.tabSignIn.setSelected(selected == Mode.SIGN_IN);
        binding.tabSignUp.setSelected(selected == Mode.SIGN_UP);
        if (!animate) {
            color(binding.labelSignIn, binding.iconSignIn, signInTo);
            color(binding.labelSignUp, binding.iconSignUp, signUpTo);
            return;
        }
        ValueAnimator signIn = ValueAnimator.ofArgb(binding.labelSignIn.getCurrentTextColor(), signInTo);
        signIn.addUpdateListener(a -> color(binding.labelSignIn, binding.iconSignIn, (int) a.getAnimatedValue()));
        ValueAnimator signUp = ValueAnimator.ofArgb(binding.labelSignUp.getCurrentTextColor(), signUpTo);
        signUp.addUpdateListener(a -> color(binding.labelSignUp, binding.iconSignUp, (int) a.getAnimatedValue()));
        AnimatorSet set = new AnimatorSet();
        set.playTogether(signIn, signUp);
        set.setDuration(TINT_MS);
        set.setStartDelay(SLIDE_MS / 4);
        tint = set;
        set.start();
    }

    private static void color(TextView label, ImageView icon, @ColorInt int color) {
        label.setTextColor(color);
        icon.setImageTintList(ColorStateList.valueOf(color));
    }

    /** Cada opção é lida como botão de opção, marcado quando escolhido. */
    private void describeAsOption(View tab, Mode tabMode) {
        ViewCompat.setAccessibilityDelegate(tab, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(RadioButton.class.getName());
                info.setCheckable(true);
                info.setChecked(mode == tabMode
                        ? AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                        : AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE);
            }
        });
    }
}
