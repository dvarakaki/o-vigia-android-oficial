package com.ovigia.app.ui.achievements;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.ViewStub;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.OneShotPreDrawListener;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.ovigia.app.R;
import com.ovigia.app.databinding.ViewAchievementStageBinding;
import com.ovigia.app.social.Achievement;
import com.ovigia.app.ui.SystemBarInsets;
import com.ovigia.app.ui.TitleShimmer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A festa de uma conquista: um cartão desce do topo, o medalhão estoura em luz
 * e o nome da conquista é varrido por um brilho dourado.
 *
 * Fica acima de todas as telas, na {@link com.ovigia.app.MainActivity}, porque
 * a conquista não é de uma tela só — ela pode cair no fim de uma partida, ao
 * abrir o perfil ou ao entrar na conta. Só o cartão recebe toque: o resto do
 * palco deixa o toque passar, então o jogador continua usando a tela de baixo
 * enquanto a comemoração acontece; tocar o cartão corta para o fim dela.
 *
 * <pre>
 *     0 →  420 ms   o cartão desce do topo e acende
 *    90 →  520 ms   o estouro dourado abre atrás do medalhão
 *   130 →  990 ms   a onda de choque se abre e some
 *   150 →  790 ms   o medalhão assenta, girando e crescendo
 *   120 → HOLD      os raios giram devagar por trás
 *   320 →  680 ms   a chamada sobe ("Conquista lendária desbloqueada")
 *   400 →  760 ms   o nome assenta
 *   480 →  840 ms   a descrição aparece
 *   720 → 1400 ms   uma luz varre o nome
 *  HOLD → +360 ms   o cartão sobe e dissolve
 * </pre>
 *
 * O compasso {@code HOLD} é o que separa uma conquista comum de uma lendária:
 * quanto mais rara, mais tempo o cartão fica em cena e mais forte é a luz
 * ({@link Achievement.Rarity}).
 *
 * Cada cartão é uma {@link AnimatorSet} só — é isso que faz o toque para pular
 * (e o {@link #cancel()} da activity) deixarem a cena no estado final sem caso
 * especial. Uma leva com mais de uma conquista vira uma fila: um cartão só por
 * vez, na ordem em que chegaram.
 *
 * Com as animações desligadas no aparelho, o cartão aparece parado e sai
 * sozinho depois de {@link #STILL_MS} — a conquista continua sendo anunciada.
 */
public final class AchievementStage {

    // Linha do tempo de um cartão, em ms contados do início dele.
    private static final long ENTER_MS = 420;
    private static final long BURST_AT = 90;
    private static final long BURST_MS = 430;
    private static final long WAVE_AT = 130;
    private static final long WAVE_MS = 860;
    private static final long MEDAL_AT = 150;
    private static final long MEDAL_MS = 640;
    private static final long RAYS_AT = 120;
    private static final long TEXT_AT = 320;
    private static final long TEXT_MS = 360;
    private static final long TEXT_STAGGER = 80;
    private static final long SHIMMER_AT = 720;
    private static final long SHIMMER_MS = 680;
    private static final long EXIT_MS = 360;
    /** Respiro entre um cartão e o próximo da fila. */
    private static final long GAP_MS = 180;
    /** Quanto o cartão fica parado quando o aparelho está sem animações. */
    private static final long STILL_MS = 2400;

    private final ViewAchievementStageBinding binding;
    private final Deque<Achievement> queue = new ArrayDeque<>();
    @Nullable private AnimatorSet scene;
    @Nullable private Runnable pending;
    /** Um cartão em cena (ou a caminho): a fila espera a vez dela. */
    private boolean busy;
    private boolean released;

    /** Infla o palco no lugar do {@code stub}, escondido e pronto para {@link #show}. */
    public static AchievementStage inflate(ViewStub stub) {
        return new AchievementStage(ViewAchievementStageBinding.bind(stub.inflate()));
    }

    private AchievementStage(ViewAchievementStageBinding binding) {
        this.binding = binding;
        // O cartão encosta na barra de status, não por baixo dela.
        SystemBarInsets.marginTop(binding.card);
        binding.getRoot().setVisibility(View.GONE);
        binding.card.setOnClickListener(v -> skip());
    }

    /**
     * Entra na fila e começa a comemorar. Uma leva já chega na ordem em que
     * deve aparecer — ver {@code AchievementsTracker.headline}.
     */
    public void show(List<Achievement> achievements) {
        if (released) return;
        queue.addAll(achievements);
        if (!busy) next();
    }

    /** Corta tudo e esvazia a fila (a activity sendo destruída). */
    public void cancel() {
        released = true;
        busy = false;
        queue.clear();
        cancelPending();
        AnimatorSet set = scene;
        scene = null;
        if (set != null) set.cancel();
        binding.getRoot().setVisibility(View.GONE);
    }

    /** Toque no cartão: corta para o fim dele e passa para o próximo. */
    private void skip() {
        AnimatorSet set = scene;
        if (set != null && set.isStarted()) {
            set.end();
        } else if (pending != null) {
            // Cartão parado (sem animações no aparelho): o toque adianta a saída.
            cancelPending();
            next();
        }
    }

    private void next() {
        scene = null;
        cancelPending();
        Achievement achievement = queue.poll();
        if (achievement == null || released) {
            busy = false;
            binding.getRoot().setVisibility(View.GONE);
            return;
        }
        busy = true;
        bind(achievement);
        // Apagado antes de aparecer: o quadro entre virar visível e a cena
        // começar mostraria o cartão pronto, e a entrada perderia a graça.
        prime();
        binding.getRoot().setVisibility(View.VISIBLE);
        // Antes de desenhar: o cartão já está medido, então dá para saber de que
        // altura ele cai — e a cena começa sem nenhum quadro do cartão pronto.
        OneShotPreDrawListener.add(binding.card, () -> {
            if (released) return;
            if (ValueAnimator.areAnimatorsEnabled()) {
                play(achievement);
            } else {
                rest();
                pending = this::next;
                binding.getRoot().postDelayed(pending, STILL_MS);
            }
        });
    }

    private void bind(Achievement achievement) {
        Context context = binding.getRoot().getContext();
        String kicker = context.getString(AchievementArt.kickerOf(achievement.rarity));
        String title = context.getString(AchievementArt.titleOf(achievement));
        String description = context.getString(AchievementArt.descriptionOf(achievement));
        binding.imageIcon.setImageResource(AchievementArt.iconOf(achievement));
        binding.tvKicker.setText(kicker);
        binding.tvTitle.setText(title);
        binding.tvDescription.setText(description);
        // O cartão é decorativo para o TalkBack: quem avisa é o anúncio, de uma vez só.
        announce(context.getString(R.string.achievement_unlocked_cd, kicker, title, description));
    }

    /**
     * Anúncio avulso ao TalkBack. Descontinuado no Android 16 em favor de regiões
     * "ao vivo", mas o cartão passa por cima de qualquer tela e some sozinho: não
     * há região estável para marcar, e o anúncio continua funcionando.
     */
    @SuppressWarnings("deprecation")
    private void announce(String text) {
        binding.getRoot().announceForAccessibility(text);
    }

    /** Tudo apagado, à espera da cena. */
    private void prime() {
        binding.card.setAlpha(0f);
        binding.medal.setAlpha(0f);
        for (View text : new View[]{binding.tvKicker, binding.tvTitle, binding.tvDescription}) {
            text.setAlpha(0f);
        }
        binding.rays.setAlpha(0f);
        binding.burst.setAlpha(0f);
        binding.shockwave.setAlpha(0f);
    }

    /** Estado final do cartão, sem animação nenhuma. */
    private void rest() {
        binding.card.setAlpha(1f);
        binding.card.setTranslationY(0f);
        binding.medal.setAlpha(1f);
        binding.medal.setScaleX(1f);
        binding.medal.setScaleY(1f);
        binding.medal.setRotation(0f);
        for (View text : new View[]{binding.tvKicker, binding.tvTitle, binding.tvDescription}) {
            text.setAlpha(1f);
            text.setTranslationY(0f);
        }
        binding.rays.setAlpha(0f);
        binding.burst.setAlpha(0f);
        binding.shockwave.setAlpha(0f);
    }

    private void play(Achievement achievement) {
        Interpolator ease = new FastOutSlowInInterpolator();
        Interpolator settle = new DecelerateInterpolator(1.6f);
        // A onda de choque sai rápido e vai morrendo: é um pulso, não um zoom.
        Interpolator burstOut = new DecelerateInterpolator(2.2f);

        long hold = holdOf(achievement.rarity);
        float glow = glowOf(achievement.rarity);
        float drop = -Math.max(binding.card.getBottom(), dp(96));

        List<Animator> parts = new ArrayList<>();

        // O cartão desce do topo.
        parts.add(timed(ObjectAnimator.ofFloat(binding.card, View.TRANSLATION_Y, drop, 0f),
                0, ENTER_MS, settle));
        parts.add(timed(ObjectAnimator.ofFloat(binding.card, View.ALPHA, 0f, 1f), 0, ENTER_MS / 2, ease));

        // A luz estoura atrás do medalhão.
        parts.add(timed(ObjectAnimator.ofFloat(binding.burst, View.ALPHA, 0f, glow, 0f),
                BURST_AT, BURST_MS, settle));
        parts.add(scale(binding.burst, 0.5f, 1.6f, BURST_AT, BURST_MS, burstOut));
        parts.add(timed(ObjectAnimator.ofFloat(binding.shockwave, View.ALPHA, 0.9f, 0f),
                WAVE_AT, WAVE_MS, burstOut));
        parts.add(scale(binding.shockwave, 0.3f, 2.1f, WAVE_AT, WAVE_MS, burstOut));

        // Os raios acendem e giram devagar enquanto o cartão está em cena.
        parts.add(timed(ObjectAnimator.ofFloat(binding.rays, View.ALPHA, 0f, glow * 0.7f, 0f),
                RAYS_AT, hold - RAYS_AT, ease));
        parts.add(timed(ObjectAnimator.ofFloat(binding.rays, View.ROTATION, -20f, spinOf(achievement.rarity)),
                RAYS_AT, hold - RAYS_AT, new LinearInterpolator()));

        // O medalhão assenta.
        parts.add(timed(ObjectAnimator.ofFloat(binding.medal, View.ALPHA, 0f, 1f),
                MEDAL_AT, MEDAL_MS / 3, ease));
        parts.add(scale(binding.medal, 0.3f, 1f, MEDAL_AT, MEDAL_MS, new OvershootInterpolator(2.2f)));
        parts.add(timed(ObjectAnimator.ofFloat(binding.medal, View.ROTATION, -40f, 0f),
                MEDAL_AT, MEDAL_MS, new OvershootInterpolator(1.6f)));

        // Os textos sobem, um atrás do outro.
        View[] lines = {binding.tvKicker, binding.tvTitle, binding.tvDescription};
        for (int i = 0; i < lines.length; i++) {
            long at = TEXT_AT + i * TEXT_STAGGER;
            parts.add(timed(ObjectAnimator.ofFloat(lines[i], View.ALPHA, 0f, 1f), at, TEXT_MS, ease));
            parts.add(timed(ObjectAnimator.ofFloat(lines[i], View.TRANSLATION_Y, dp(10), 0f), at, TEXT_MS, settle));
        }

        // A luz atravessa o nome da conquista.
        Animator shimmer = TitleShimmer.sweep(SHIMMER_MS, binding.tvTitle);
        shimmer.setStartDelay(SHIMMER_AT);
        parts.add(shimmer);

        // Saída: o cartão sobe e dissolve.
        parts.add(timed(ObjectAnimator.ofFloat(binding.card, View.TRANSLATION_Y, 0f, drop), hold, EXIT_MS, ease));
        parts.add(timed(ObjectAnimator.ofFloat(binding.card, View.ALPHA, 1f, 0f), hold, EXIT_MS, ease));

        AnimatorSet set = new AnimatorSet();
        set.playTogether(parts);
        set.addListener(new AnimatorListenerAdapter() {
            private boolean finished;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (finished) return;
                finished = true;
                if (released) return;
                // Um respiro antes do próximo: dois cartões colados viram um borrão.
                pending = AchievementStage.this::next;
                binding.getRoot().postDelayed(pending, GAP_MS);
            }
        });
        scene = set;
        set.start();
    }

    /** Quanto o cartão fica em cena antes de sair. */
    private static long holdOf(Achievement.Rarity rarity) {
        switch (rarity) {
            case LEGENDARY: return 3400;
            case RARE: return 2900;
            case COMMON:
            default: return 2600;
        }
    }

    /** Força da luz por trás do medalhão. */
    private static float glowOf(Achievement.Rarity rarity) {
        switch (rarity) {
            case LEGENDARY: return 1f;
            case RARE: return 0.8f;
            case COMMON:
            default: return 0.6f;
        }
    }

    /** Quanto os raios giram: a lendária dá quase uma volta inteira. */
    private static float spinOf(Achievement.Rarity rarity) {
        switch (rarity) {
            case LEGENDARY: return 300f;
            case RARE: return 90f;
            case COMMON:
            default: return 40f;
        }
    }

    private void cancelPending() {
        if (pending == null) return;
        binding.getRoot().removeCallbacks(pending);
        pending = null;
    }

    private float dp(float value) {
        return binding.getRoot().getResources().getDisplayMetrics().density * value;
    }

    private static Animator scale(View view, float from, float to, long at, long ms, Interpolator easing) {
        AnimatorSet both = new AnimatorSet();
        both.playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, from, to),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, from, to));
        return timed(both, at, ms, easing);
    }

    private static <T extends Animator> T timed(@NonNull T animator, long at, long ms, Interpolator easing) {
        animator.setStartDelay(at);
        animator.setDuration(ms);
        animator.setInterpolator(easing);
        return animator;
    }
}
