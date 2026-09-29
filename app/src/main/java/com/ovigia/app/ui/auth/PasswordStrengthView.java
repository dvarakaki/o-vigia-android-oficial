package com.ovigia.app.ui.auth;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
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
 *   <li><b>Medidor:</b> {@link StrengthMeterView#SEGMENTS} segmentos que enchem
 *   um a um — os dois primeiros com o tamanho da senha, os dois últimos com o
 *   caractere especial ({@link PasswordRules#progress}). A cor desliza do
 *   vermelho ao dourado junto com eles e passa ao verde quando as duas
 *   exigências fecham; nessa hora uma onda percorre os segmentos.</li>
 *   <li><b>Rótulo:</b> "Fraca", "Quase lá", "Forte" — o antigo sobe e some, o
 *   novo sobe de baixo já na cor nova; o TalkBack lê cada mudança.</li>
 *   <li><b>Exigências:</b> cada uma tem um anel que se enche de verde e desenha
 *   o certo quando ela é cumprida (o mesmo certo do e-mail); perdê-la apaga o
 *   certo. A de tamanho mostra quantos caracteres já foram ("5/8") e o número
 *   sobe a cada novo.</li>
 *   <li><b>{@link #shakeMissing()}:</b> ao tentar criar a conta com a senha
 *   fraca, as exigências que faltam tremem e ficam vermelhas por um instante — o
 *   jogador vê na hora o que falta, sem ler mensagem de erro.</li>
 * </ul>
 *
 * A regra em si é a {@link PasswordRules}, a mesma que o {@code AccountStore}
 * aplica: a tela e o cadastro nunca discordam. Tudo passa pelo sistema de
 * animação do Android, então respeita a escala de animação do aparelho.
 */
public final class PasswordStrengthView extends LinearLayout {

    private static final long METER_MS = 300;
    private static final long STRONG_MS = 320;
    private static final long WAVE_MS = 620;
    private static final long MARK_IN_MS = 460;
    private static final long MARK_OUT_MS = 180;
    private static final long TINT_MS = 220;
    private static final long LABEL_OUT_MS = 110;
    private static final long LABEL_IN_MS = 200;
    private static final long SHAKE_MS = 420;
    private static final long COUNT_MS = 160;
    private static final int MARK_DP = 18;

    private final ViewPasswordStrengthBinding binding;
    private final RuleViews length;
    private final RuleViews special;
    @ColorInt private final int gold;
    @ColorInt private final int danger;
    @ColorInt private final int success;
    @ColorInt private final int ringIdle;
    private final List<Animator> running = new ArrayList<>();

    /** Onde o medidor está (0–1), para a próxima animação sair daqui. */
    private float shownProgress = 0f;
    @Nullable private ValueAnimator meterAnimator;
    /** Quanto o medidor já virou verde (0–1): a passagem para "forte" é animada à parte do tamanho. */
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
        ringIdle = ContextCompat.getColor(context, R.color.white_20);
        binding.tvRuleLength.setText(context.getString(R.string.password_rule_length, PasswordRules.MIN_LENGTH));
        length = new RuleViews(binding.ruleLength, binding.markLength, binding.tvRuleLength, newMark(context));
        special = new RuleViews(binding.ruleSpecial, binding.markSpecial, binding.tvRuleSpecial, newMark(context));
        update("", false);
    }

    private CheckMarkDrawable newMark(Context context) {
        int size = Math.round(MARK_DP * getResources().getDisplayMetrics().density);
        return new CheckMarkDrawable(size, ringIdle, success, ContextCompat.getColor(context, R.color.vigia_space));
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
        animateMeter(PasswordRules.progress(password), animate);
        animateStrong(nowStrong, animate);
        showLabel(empty ? -1 : met.size(), animate);
        setRule(length, met.contains(Rule.LENGTH), animate);
        setRule(special, met.contains(Rule.SPECIAL), animate);
        showCount(count, met.contains(Rule.LENGTH), animate);

        if (nowStrong && !strong && animate) wave();
        strong = nowStrong;
    }

    /** As exigências que ainda faltam tremem e ficam vermelhas por um instante. */
    public void shakeMissing() {
        float d = getResources().getDisplayMetrics().density;
        for (RuleViews rule : new RuleViews[]{length, special}) {
            if (rule.met) continue;
            ObjectAnimator shake = ObjectAnimator.ofFloat(rule.row, View.TRANSLATION_X,
                    0f, -9 * d, 9 * d, -7 * d, 7 * d, -3 * d, 3 * d, 0f);
            shake.setDuration(SHAKE_MS);
            ValueAnimator ring = ValueAnimator.ofArgb(ringIdle, danger, danger, ringIdle);
            ring.setDuration(SHAKE_MS + 240);
            ring.addUpdateListener(a -> rule.mark.setRingColor((int) a.getAnimatedValue()));
            ValueAnimator text = ValueAnimator.ofArgb(rule.label.getCurrentTextColor(), danger, danger,
                    labelColor(false));
            text.setDuration(SHAKE_MS + 240);
            text.addUpdateListener(a -> rule.label.setTextColor((int) a.getAnimatedValue()));
            AnimatorSet set = new AnimatorSet();
            set.playTogether(shake, ring, text);
            track(set).start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        for (Animator a : new ArrayList<>(running)) a.cancel();
        running.clear();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------ medidor e rótulo

    private void animateMeter(float target, boolean animate) {
        if (meterAnimator != null) meterAnimator.cancel();
        if (!animate) {
            applyMeter(target);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(shownProgress, target);
        animator.setDuration(METER_MS);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(a -> applyMeter((float) a.getAnimatedValue()));
        meterAnimator = track(animator);
        animator.start();
    }

    private void applyMeter(float progress) {
        shownProgress = progress;
        binding.meter.setLevel(progress * StrengthMeterView.SEGMENTS);
        paintMeter();
    }

    /** Senha forte: o medidor desliza para o verde; deixou de ser, volta para o dourado. */
    private void animateStrong(boolean nowStrong, boolean animate) {
        float target = nowStrong ? 1f : 0f;
        if (strongAnimator != null) strongAnimator.cancel();
        if (!animate) {
            strongMix = target;
            paintMeter();
            return;
        }
        if (strongMix == target) return;
        ValueAnimator animator = ValueAnimator.ofFloat(strongMix, target);
        animator.setDuration(STRONG_MS);
        // Entra depois que o medidor termina de encher: primeiro chega ao fim, depois fica verde.
        animator.setStartDelay(nowStrong ? METER_MS / 2 : 0);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(a -> {
            strongMix = (float) a.getAnimatedValue();
            paintMeter();
        });
        strongAnimator = track(animator);
        animator.start();
    }

    /** Do vermelho (nada) ao dourado (quase), passando pelo laranja — e verde quando as duas fecham. */
    private void paintMeter() {
        int building = ColorUtils.blendARGB(danger, gold, shownProgress);
        binding.meter.setColor(ColorUtils.blendARGB(building, success, strongMix));
    }

    /** A senha acabou de ficar forte: uma onda percorre os segmentos. */
    private void wave() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(WAVE_MS);
        animator.setStartDelay(METER_MS / 2);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(a -> binding.meter.setWave((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                binding.meter.setWave(-1f);
            }
        });
        track(animator).start();
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
            label.setTextColor(level == 0 ? danger : level == 1 ? gold : success);
        };
        if (!animate) {
            apply.run();
            label.setAlpha(1f);
            label.setTranslationY(0f);
            return;
        }
        // O rótulo antigo sobe e some; o novo vem de baixo, já na cor nova.
        float rise = 8 * getResources().getDisplayMetrics().density;
        AnimatorSet out = new AnimatorSet();
        out.playTogether(ObjectAnimator.ofFloat(label, View.ALPHA, label.getAlpha(), 0f),
                ObjectAnimator.ofFloat(label, View.TRANSLATION_Y, label.getTranslationY(), -rise));
        out.setDuration(LABEL_OUT_MS);
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                apply.run();
            }
        });
        AnimatorSet in = new AnimatorSet();
        in.playTogether(ObjectAnimator.ofFloat(label, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(label, View.TRANSLATION_Y, rise, 0f));
        in.setDuration(LABEL_IN_MS);
        in.setInterpolator(new FastOutSlowInInterpolator());
        AnimatorSet swap = new AnimatorSet();
        swap.playSequentially(out, in);
        track(swap).start();
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

        int labelTo = labelColor(met);
        rule.mark.setRingColor(ringIdle);
        if (!animate) {
            rule.mark.setProgress(met ? 1f : 0f);
            rule.label.setTextColor(labelTo);
            return;
        }

        ValueAnimator mark = ValueAnimator.ofFloat(rule.mark.getProgress(), met ? 1f : 0f);
        mark.setDuration(met ? MARK_IN_MS : MARK_OUT_MS);
        mark.setInterpolator(new FastOutSlowInInterpolator());
        mark.addUpdateListener(a -> rule.mark.setProgress((float) a.getAnimatedValue()));
        ValueAnimator label = ValueAnimator.ofArgb(rule.label.getCurrentTextColor(), labelTo);
        label.addUpdateListener(a -> rule.label.setTextColor((int) a.getAnimatedValue()));
        label.setDuration(TINT_MS);

        AnimatorSet all = new AnimatorSet();
        all.playTogether(mark, label);
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
        } else if (!met && count != shownLength) {
            // Mais (ou menos) um caractere: o número entra subindo (ou descendo).
            float d = getResources().getDisplayMetrics().density * (count > shownLength ? 6 : -6);
            AnimatorSet tick = new AnimatorSet();
            tick.playTogether(ObjectAnimator.ofFloat(counter, View.TRANSLATION_Y, d, 0f),
                    ObjectAnimator.ofFloat(counter, View.ALPHA, 0.3f, 1f));
            tick.setDuration(COUNT_MS);
            tick.setInterpolator(new FastOutSlowInInterpolator());
            track(tick).start();
        }
        shownLength = count;
    }

    @ColorInt
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
        final TextView label;
        final CheckMarkDrawable mark;
        boolean met;
        boolean initialized;
        @Nullable Animator animator;

        RuleViews(View row, ImageView markView, TextView label, CheckMarkDrawable mark) {
            this.row = row;
            this.label = label;
            this.mark = mark;
            markView.setImageDrawable(mark);
        }
    }
}
