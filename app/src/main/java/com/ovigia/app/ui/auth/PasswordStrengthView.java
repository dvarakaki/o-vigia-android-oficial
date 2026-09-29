package com.ovigia.app.ui.auth;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.ovigia.app.R;
import com.ovigia.app.auth.PasswordRules;
import com.ovigia.app.auth.PasswordRules.Rule;
import com.ovigia.app.databinding.ViewPasswordStrengthBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A verificação da senha nova, acompanhando o que o jogador digita.
 *
 * <ul>
 *   <li><b>Barra de força:</b> anda a cada caractere até o mínimo e dá o salto
 *   da outra metade quando entra o caractere especial ({@link PasswordRules#progress}).
 *   A cor desliza do vermelho ao dourado junto com ela; quando as duas
 *   exigências fecham, ela passa para o verde e um reflexo a atravessa uma vez.</li>
 *   <li><b>Rótulo:</b> "Fraca", "Quase lá", "Forte" — troca com um fade curto e
 *   é lido pelo TalkBack a cada mudança.</li>
 *   <li><b>Exigências:</b> cada uma tem um disco que acende em dourado com um
 *   certo que entra girando; perder a exigência (apagar um caractere) desfaz o
 *   certo. A de tamanho mostra quantos caracteres faltam ("5/8") e dá um pulinho
 *   a cada novo.</li>
 *   <li><b>{@link #shakeMissing()}:</b> ao tentar criar a conta com a senha
 *   fraca, as exigências que faltam tremem e piscam em vermelho — o jogador vê
 *   na hora o que falta, sem ler mensagem de erro.</li>
 * </ul>
 *
 * A regra em si é a {@link PasswordRules}, a mesma que o {@code AccountStore}
 * aplica: a tela e o cadastro nunca discordam. Tudo passa pelo sistema de
 * animação do Android, então respeita a escala de animação do aparelho.
 */
public final class PasswordStrengthView extends LinearLayout {

    private static final long BAR_MS = 260;
    private static final long CHECK_IN_MS = 360;
    private static final long CHECK_OUT_MS = 160;
    private static final long TINT_MS = 220;
    private static final long LABEL_OUT_MS = 90;
    private static final long LABEL_IN_MS = 170;
    private static final long SHINE_MS = 700;
    private static final long SHAKE_MS = 420;
    private static final long STRONG_MS = 320;

    private final ViewPasswordStrengthBinding binding;
    private final RuleViews length;
    private final RuleViews special;
    private final int gold;
    private final int danger;
    private final int success;
    private final int dim;
    private final List<Animator> running = new ArrayList<>();

    /** Onde a barra está (0–1), para a próxima animação sair daqui. */
    private float shownProgress = 0f;
    @Nullable private ValueAnimator barAnimator;
    /** Quanto a barra já virou verde (0–1): a passagem para "forte" é animada à parte do tamanho. */
    private float strongMix = 0f;
    @Nullable private ValueAnimator strongAnimator;
    /** Quantas exigências o rótulo mostra agora; -1 = senha vazia, sem rótulo. */
    private int shownLevel = -1;
    private int shownLength = 0;
    private boolean strong = false;

    public PasswordStrengthView(@NonNull Context context) {
        this(context, null);
    }

    public PasswordStrengthView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        binding = ViewPasswordStrengthBinding.inflate(LayoutInflater.from(context), this);
        gold = ContextCompat.getColor(context, R.color.vigia_gold);
        danger = ContextCompat.getColor(context, R.color.vigia_danger);
        success = ContextCompat.getColor(context, R.color.vigia_success);
        dim = ContextCompat.getColor(context, R.color.white_10);
        binding.tvRuleLength.setText(context.getString(R.string.password_rule_length, PasswordRules.MIN_LENGTH));
        binding.strengthFill.setPivotX(0f);
        length = new RuleViews(binding.ruleLength, binding.discLength, binding.checkLength, binding.tvRuleLength);
        special = new RuleViews(binding.ruleSpecial, binding.discSpecial, binding.checkSpecial, binding.tvRuleSpecial);
        update("", false);
    }

    /** Passa a acompanhar o campo: cada letra digitada atualiza a verificação. */
    public void attachTo(EditText field) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                update(s.toString(), true);
            }
        });
        update(field.getText() == null ? "" : field.getText().toString(), false);
    }

    /** Mostra a verificação para essa senha; sem {@code animate}, já no estado final. */
    public void update(String password, boolean animate) {
        Set<Rule> met = PasswordRules.satisfied(password);
        int count = password == null ? 0 : password.codePointCount(0, password.length());
        boolean empty = count == 0;

        boolean nowStrong = met.size() == Rule.values().length;
        animateBar(PasswordRules.progress(password), animate);
        animateStrong(nowStrong, animate);
        showLabel(empty ? -1 : met.size(), animate);
        setRule(length, met.contains(Rule.LENGTH), animate);
        setRule(special, met.contains(Rule.SPECIAL), animate);
        showCount(count, met.contains(Rule.LENGTH), animate);

        if (nowStrong && !strong && animate) shine();
        strong = nowStrong;
    }

    /** As exigências que ainda faltam tremem e piscam em vermelho. */
    public void shakeMissing() {
        float d = getResources().getDisplayMetrics().density;
        for (RuleViews rule : new RuleViews[]{length, special}) {
            if (rule.met) continue;
            ObjectAnimator shake = ObjectAnimator.ofFloat(rule.row, View.TRANSLATION_X,
                    0f, -9 * d, 9 * d, -7 * d, 7 * d, -3 * d, 3 * d, 0f);
            shake.setDuration(SHAKE_MS);
            ValueAnimator flash = ValueAnimator.ofArgb(dim, danger, danger, dim);
            flash.setDuration(SHAKE_MS + 180);
            flash.addUpdateListener(a -> rule.disc.setBackgroundTintList(
                    ColorStateList.valueOf((int) a.getAnimatedValue())));
            ValueAnimator text = ValueAnimator.ofArgb(rule.label.getCurrentTextColor(), danger, danger,
                    labelColor(false));
            text.setDuration(SHAKE_MS + 180);
            text.addUpdateListener(a -> rule.label.setTextColor((int) a.getAnimatedValue()));
            AnimatorSet set = new AnimatorSet();
            set.playTogether(shake, flash, text);
            track(set).start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        for (Animator a : new ArrayList<>(running)) a.cancel();
        running.clear();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------ barra e rótulo

    private void animateBar(float target, boolean animate) {
        if (barAnimator != null) barAnimator.cancel();
        if (!animate) {
            applyBar(target);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(shownProgress, target);
        animator.setDuration(BAR_MS);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(a -> applyBar((float) a.getAnimatedValue()));
        barAnimator = track(animator);
        animator.start();
    }

    private void applyBar(float progress) {
        shownProgress = progress;
        binding.strengthFill.setScaleX(progress);
        paintBar();
    }

    /** Senha forte: a barra desliza para o verde; deixou de ser, volta para o dourado. */
    private void animateStrong(boolean nowStrong, boolean animate) {
        float target = nowStrong ? 1f : 0f;
        if (strongAnimator != null) strongAnimator.cancel();
        if (!animate) {
            strongMix = target;
            paintBar();
            return;
        }
        if (strongMix == target) return;
        ValueAnimator animator = ValueAnimator.ofFloat(strongMix, target);
        animator.setDuration(STRONG_MS);
        // Entra depois que a barra termina de encher: primeiro chega ao fim, depois fica verde.
        animator.setStartDelay(nowStrong ? BAR_MS / 2 : 0);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(a -> {
            strongMix = (float) a.getAnimatedValue();
            paintBar();
        });
        strongAnimator = track(animator);
        animator.start();
    }

    /**
     * Do vermelho (nada) ao dourado (quase), passando pelo laranja no meio do
     * caminho — e verde quando as duas exigências fecham.
     */
    private void paintBar() {
        int building = ColorUtils.blendARGB(danger, gold, shownProgress);
        binding.strengthFill.setBackgroundTintList(ColorStateList.valueOf(
                ColorUtils.blendARGB(building, success, strongMix)));
    }

    private void showLabel(int level, boolean animate) {
        if (level == shownLevel) return;
        shownLevel = level;
        TextView label = binding.tvStrength;
        Runnable apply = () -> {
            if (level < 0) {
                label.setText(null);
                return;
            }
            label.setText(level == 0 ? R.string.password_strength_weak
                    : level == 1 ? R.string.password_strength_medium : R.string.password_strength_strong);
            label.setTextColor(level == 0 ? danger : level == 1 ? ColorUtils.blendARGB(danger, gold, 0.5f) : success);
        };
        if (!animate) {
            apply.run();
            label.setAlpha(1f);
            label.setTranslationY(0f);
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        ObjectAnimator out = ObjectAnimator.ofFloat(label, View.ALPHA, label.getAlpha(), 0f);
        out.setDuration(LABEL_OUT_MS);
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                apply.run();
                label.setTranslationY(4 * d);
            }
        });
        AnimatorSet in = new AnimatorSet();
        in.playTogether(ObjectAnimator.ofFloat(label, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(label, View.TRANSLATION_Y, 4 * d, 0f));
        in.setDuration(LABEL_IN_MS);
        in.setInterpolator(new FastOutSlowInInterpolator());
        AnimatorSet swap = new AnimatorSet();
        swap.playSequentially(out, in);
        track(swap).start();
    }

    /** Um reflexo atravessa a barra: a senha acabou de ficar forte. */
    private void shine() {
        View shine = binding.strengthShine;
        View track = (View) shine.getParent();
        float from = -shine.getWidth();
        float to = track.getWidth();
        ObjectAnimator move = ObjectAnimator.ofFloat(shine, View.TRANSLATION_X, from, to);
        ObjectAnimator fade = ObjectAnimator.ofFloat(shine, View.ALPHA, 0f, 1f, 1f, 0f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(move, fade);
        set.setDuration(SHINE_MS);
        set.setStartDelay(BAR_MS / 2);
        set.setInterpolator(new FastOutSlowInInterpolator());
        track(set).start();
    }

    // ------------------------------------------------------------ exigências

    private void setRule(RuleViews rule, boolean met, boolean animate) {
        boolean changed = rule.met != met || !rule.initialized;
        rule.met = met;
        rule.initialized = true;
        rule.row.setContentDescription(getContext().getString(
                met ? R.string.password_rule_met : R.string.password_rule_pending, rule.label.getText()));
        if (!changed) return;
        if (rule.animator != null) rule.animator.cancel();

        int discTo = met ? gold : dim;
        int labelTo = labelColor(met);
        if (!animate) {
            rule.disc.setBackgroundTintList(ColorStateList.valueOf(discTo));
            rule.disc.setScaleX(1f);
            rule.disc.setScaleY(1f);
            rule.label.setTextColor(labelTo);
            rule.check.setScaleX(met ? 1f : 0f);
            rule.check.setScaleY(met ? 1f : 0f);
            rule.check.setRotation(0f);
            return;
        }

        int discFrom = rule.disc.getBackgroundTintList() == null ? dim
                : rule.disc.getBackgroundTintList().getDefaultColor();
        ValueAnimator disc = ValueAnimator.ofArgb(discFrom, discTo);
        disc.addUpdateListener(a -> rule.disc.setBackgroundTintList(
                ColorStateList.valueOf((int) a.getAnimatedValue())));
        disc.setDuration(TINT_MS);
        ValueAnimator label = ValueAnimator.ofArgb(rule.label.getCurrentTextColor(), labelTo);
        label.addUpdateListener(a -> rule.label.setTextColor((int) a.getAnimatedValue()));
        label.setDuration(TINT_MS);

        AnimatorSet check = new AnimatorSet();
        AnimatorSet bump = new AnimatorSet();
        if (met) {
            // O certo entra girando e passa um pouco do tamanho antes de assentar.
            check.playTogether(
                    ObjectAnimator.ofFloat(rule.check, View.SCALE_X, rule.check.getScaleX(), 1f),
                    ObjectAnimator.ofFloat(rule.check, View.SCALE_Y, rule.check.getScaleY(), 1f),
                    ObjectAnimator.ofFloat(rule.check, View.ROTATION, -70f, 0f));
            check.setDuration(CHECK_IN_MS);
            check.setInterpolator(new OvershootInterpolator(2.6f));
            // E o disco dá um pulinho junto, para o olho ir até ele.
            bump.playTogether(ObjectAnimator.ofFloat(rule.disc, View.SCALE_X, 1f, 1.25f, 1f),
                    ObjectAnimator.ofFloat(rule.disc, View.SCALE_Y, 1f, 1.25f, 1f));
            bump.setDuration(CHECK_IN_MS);
            bump.setInterpolator(new FastOutSlowInInterpolator());
        } else {
            check.playTogether(
                    ObjectAnimator.ofFloat(rule.check, View.SCALE_X, rule.check.getScaleX(), 0f),
                    ObjectAnimator.ofFloat(rule.check, View.SCALE_Y, rule.check.getScaleY(), 0f));
            check.setDuration(CHECK_OUT_MS);
            check.setInterpolator(new FastOutSlowInInterpolator());
        }

        AnimatorSet all = new AnimatorSet();
        all.playTogether(disc, label, check, bump);
        rule.animator = track(all);
        all.start();
    }

    private void showCount(int count, boolean met, boolean animate) {
        TextView counter = binding.tvLengthCount;
        counter.setText(getContext().getString(R.string.password_length_count,
                Math.min(count, PasswordRules.MIN_LENGTH), PasswordRules.MIN_LENGTH));
        float target = met ? 0f : 1f;
        if (!animate) {
            counter.setAlpha(target);
        } else if (counter.getAlpha() != target) {
            ObjectAnimator fade = ObjectAnimator.ofFloat(counter, View.ALPHA, counter.getAlpha(), target);
            fade.setDuration(TINT_MS);
            track(fade).start();
        } else if (!met && count > shownLength) {
            // Mais um caractere: o contador dá um pulinho.
            AnimatorSet bump = new AnimatorSet();
            bump.playTogether(ObjectAnimator.ofFloat(counter, View.SCALE_X, 1.25f, 1f),
                    ObjectAnimator.ofFloat(counter, View.SCALE_Y, 1.25f, 1f));
            bump.setDuration(180);
            bump.setInterpolator(new FastOutSlowInInterpolator());
            track(bump).start();
        }
        shownLength = count;
    }

    private int labelColor(boolean met) {
        return ContextCompat.getColor(getContext(), met ? R.color.white : R.color.white_70);
    }

    private <T extends Animator> T track(T animator) {
        running.add(animator);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                running.remove(animation);
            }
        });
        return animator;
    }

    /** As views de uma exigência e o estado que ela mostra. */
    private static final class RuleViews {
        final View row;
        final View disc;
        final ImageView check;
        final TextView label;
        boolean met;
        boolean initialized;
        @Nullable Animator animator;

        RuleViews(View row, View disc, ImageView check, TextView label) {
            this.row = row;
            this.disc = disc;
            this.check = check;
            this.label = label;
        }
    }
}
