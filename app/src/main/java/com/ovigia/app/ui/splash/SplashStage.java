package com.ovigia.app.ui.splash;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.ovigia.app.databinding.ViewSplashStageBinding;
import com.ovigia.app.ui.SystemBarInsets;
import com.ovigia.app.ui.TitleShimmer;

import java.util.ArrayList;
import java.util.List;

/**
 * A abertura do app, em dois atos. O primeiro é do sistema: o selo desenha o
 * olho do Vigia ({@code splash_logo_animated}) enquanto o app carrega. O
 * segundo é este palco, que entra por baixo do splash e continua de onde ele
 * parou — o olho aberto estoura em luz, o Vigia sobe da claridade e a marca
 * assenta exatamente onde a tela inicial vai desenhá-la.
 *
 * <pre>
 *     0 →  440 ms   o clarão do olho abrindo cobre a emenda com o splash
 *    40 →  900 ms   três ondas de choque douradas se abrem do centro
 *     0 →  900 ms   a aura acende e cresce
 *     0 → 2700 ms   os raios acendem e giram devagar
 *   140 → 1040 ms   o Vigia sobe da luz, saindo de um zoom leve
 *   480 →  920 ms   o título assenta, vindo de 1.14
 *   600 →  960 ms   o filete dourado se abre do centro
 *   720 → 1100 ms   a frase aparece
 *   820 → 1480 ms   uma luz varre "O Vigia"
 *  1040 → 2700 ms   a cena respira: a aura pulsa com o Vigia já montado
 *  2540 → 3060 ms   o app entra por baixo, ainda escondido
 *  2700 → 3140 ms   o palco dissolve e sai da hierarquia
 * </pre>
 *
 * O compasso da respiração é o ponto alto da cena — é onde o Vigia fica de pé
 * no brilho, olhando de volta. Encurtar {@link #EXIT_AT} é o jeito de fazer a
 * abertura passar mais rápido; esticar é o de deixá-lo mais tempo em cena.
 *
 * Tudo é uma {@link AnimatorSet} só. Isso importa: um toque na tela (ou a
 * activity sendo destruída) chama {@link #skip()}, que dá {@code end()} no
 * conjunto — e como a entrada do app também está lá dentro, o corte deixa a
 * cena no mesmo estado final, sem caso especial para acertar depois.
 *
 * Por passar pelo sistema de animação do Android, a cena respeita a escala de
 * animação do aparelho; com as animações desligadas, {@link #inflate} nem
 * infla o palco e o app abre direto.
 */
public final class SplashStage {

    /**
     * Quanto o splash do sistema fica na tela: o tempo de o olho abrir por
     * inteiro em {@code splash_logo_animated}. Mudou o AVD, muda aqui.
     */
    public static final long SEAL_MS = 900L;

    // Linha do tempo do palco, em ms contados de play() — ver o desenho acima.
    private static final long FLASH_MS = 440;
    private static final long RING_AT = 40;
    private static final long RING_MS = 760;
    private static final long RING_STAGGER = 100;
    private static final long AURA_MS = 900;
    private static final long ART_AT = 140;
    private static final long ART_MS = 900;
    private static final long TITLE_AT = 480;
    private static final long TITLE_MS = 440;
    private static final long RULE_AT = 600;
    private static final long RULE_MS = 360;
    private static final long TAGLINE_AT = 720;
    private static final long TAGLINE_MS = 380;
    private static final long SHIMMER_AT = 820;
    private static final long SHIMMER_MS = 660;
    /**
     * A partir daqui a cena está montada e só respira: a aura pulsa devagar, os
     * raios seguem girando e o céu piscando. O Vigia e o título ficam parados —
     * é ele olhando de volta, e mexer neles tiraria a marca do lugar em que a
     * tela inicial vai desenhá-la.
     */
    private static final long HOLD_AT = 1040;
    private static final long EXIT_AT = 2700;
    private static final long EXIT_MS = 440;
    /** O app entra um pouco antes da dissolução, para a troca virar um cross-fade só. */
    private static final long REVEAL_AT = EXIT_AT - 160;
    private static final long REVEAL_MS = 520;

    /** Zoom inicial da galáxia, desfeito até 1.0 — onde a do app já está. */
    private static final float GALAXY_ZOOM = 1.12f;

    private final ViewSplashStageBinding binding;
    @Nullable private AnimatorSet scene;
    private boolean detached;

    /**
     * Infla o palco no lugar do {@code stub}, apagado e pronto para
     * {@link #play}. Devolve {@code null} — e não infla nada — quando o
     * aparelho está com as animações desligadas: aí não há cena para mostrar.
     */
    @Nullable
    public static SplashStage inflate(ViewStub stub) {
        if (!ValueAnimator.areAnimatorsEnabled()) return null;
        return new SplashStage(ViewSplashStageBinding.bind(stub.inflate()));
    }

    private SplashStage(ViewSplashStageBinding binding) {
        this.binding = binding;
        // O título encosta na barra de status igual ao da Home, senão ele
        // pularia de lugar na troca.
        SystemBarInsets.padTop(binding.getRoot());
        binding.getRoot().setOnClickListener(v -> skip());

        View title = binding.title.getRoot();
        title.setAlpha(0f);
        title.setScaleX(1.14f);
        title.setScaleY(1.14f);

        binding.galaxy.setScaleX(GALAXY_ZOOM);
        binding.galaxy.setScaleY(GALAXY_ZOOM);
        binding.starfield.setRunning(true);
    }

    /**
     * Toca a cena. O {@code content} é o conteúdo do app, que entra por baixo
     * do palco perto do fim — ele começa apagado e termina visível, sempre,
     * mesmo se a cena for cortada.
     */
    public void play(View content) {
        // A saída do splash do sistema pode chegar depois de a activity já ter
        // cortado a cena; aí não há mais palco para tocar.
        if (detached) {
            content.setAlpha(1f);
            return;
        }

        Interpolator ease = new FastOutSlowInInterpolator();
        Interpolator settle = new DecelerateInterpolator(1.6f);
        // As ondas de choque saem rápido e vão morrendo: é um pulso, não um zoom.
        Interpolator burst = new DecelerateInterpolator(2.2f);

        List<Animator> parts = new ArrayList<>();

        // Fundo: o zoom da galáxia se desfaz até encostar na do app, por baixo.
        parts.add(scale(binding.galaxy, GALAXY_ZOOM, 1f, 0, EXIT_AT + EXIT_MS, ease));

        // O olho estoura.
        ObjectAnimator flash = ObjectAnimator.ofFloat(binding.flash, View.ALPHA, 0f, 1f, 0f);
        flash.setDuration(FLASH_MS);
        flash.setInterpolator(settle);
        parts.add(flash);
        parts.add(scale(binding.flash, 0.4f, 2.2f, 0, FLASH_MS, burst));

        View[] rings = {binding.ring1, binding.ring2, binding.ring3};
        for (int i = 0; i < rings.length; i++) {
            long at = RING_AT + i * RING_STAGGER;
            parts.add(scale(rings[i], 0.12f, 2.6f + i * 0.7f, at, RING_MS, burst));
            parts.add(fade(rings[i], 0.85f - i * 0.2f, 0f, at, RING_MS, burst));
        }

        parts.add(fade(binding.aura, 0f, 1f, 0, AURA_MS, ease));
        parts.add(scale(binding.aura, 0.5f, 1f, 0, AURA_MS, ease));

        parts.add(fade(binding.rays, 0f, 0.8f, 0, AURA_MS + 200, ease));
        ObjectAnimator spin = ObjectAnimator.ofFloat(binding.rays, View.ROTATION, -22f, 20f);
        spin.setDuration(EXIT_AT);
        spin.setInterpolator(new LinearInterpolator());
        parts.add(spin);

        // O Vigia sobe da luz.
        parts.add(fade(binding.art, 0f, 1f, ART_AT, ART_MS - 200, ease));
        parts.add(rise(binding.art, dp(56f), ART_AT, ART_MS, settle));
        parts.add(scale(binding.art, 1.1f, 1f, ART_AT, ART_MS, settle));

        // A marca assenta.
        View title = binding.title.getRoot();
        parts.add(fade(title, 0f, 1f, TITLE_AT, TITLE_MS, ease));
        parts.add(scale(title, 1.14f, 1f, TITLE_AT, TITLE_MS + 120, settle));
        parts.add(scaleX(binding.rule, 0f, 1f, RULE_AT, RULE_MS, settle));
        parts.add(fade(binding.tagline, 0f, 1f, TAGLINE_AT, TAGLINE_MS, ease));
        parts.add(rise(binding.tagline, dp(12f), TAGLINE_AT, TAGLINE_MS, ease));

        Animator shimmer = TitleShimmer.sweep(SHIMMER_MS, binding.title.titleO, binding.title.titleVigia);
        shimmer.setStartDelay(SHIMMER_AT);
        parts.add(shimmer);

        // A pausa: com tudo montado, a aura respira uma vez enquanto o Vigia
        // fica em pé no brilho.
        parts.add(breathe(binding.aura, HOLD_AT, EXIT_AT - HOLD_AT));

        // O app entra por baixo, ainda coberto pelo palco.
        content.setAlpha(0f);
        content.setTranslationY(dp(14f));
        parts.add(fade(content, 0f, 1f, REVEAL_AT, REVEAL_MS, settle));
        parts.add(rise(content, dp(14f), REVEAL_AT, REVEAL_MS, settle));

        // Saída: a aura engole a cena enquanto o palco dissolve.
        parts.add(scale(binding.aura, 1f, 1.4f, EXIT_AT, EXIT_MS, ease));
        parts.add(fade(binding.aura, 1f, 0f, EXIT_AT, EXIT_MS, ease));
        parts.add(dissolve());

        AnimatorSet set = new AnimatorSet();
        set.playTogether(parts);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                detach();
            }
        });
        scene = set;
        set.start();
    }

    /** Corta para o fim da cena (toque na tela, ou a activity sendo destruída). */
    public void skip() {
        AnimatorSet set = scene;
        if (set != null && set.isStarted()) {
            set.end();
        } else {
            detach();
        }
    }

    /**
     * A dissolução do palco. Ao começar, congela o céu e manda tudo para uma
     * camada de hardware: com o palco inteiro em alpha, redesenhar as camadas a
     * cada quadro sairia caro, e nesse meio segundo já ninguém repara nas
     * estrelas paradas.
     */
    private Animator dissolve() {
        View root = binding.getRoot();
        Animator fade = fade(root, 1f, 0f, EXIT_AT, EXIT_MS, new AccelerateDecelerateInterpolator());
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                binding.starfield.setRunning(false);
                root.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                root.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
        return fade;
    }

    /** Tira o palco da hierarquia: a partir daqui o app fica com a tela inteira. */
    private void detach() {
        if (detached) return;
        detached = true;
        binding.starfield.setRunning(false);
        View root = binding.getRoot();
        ViewGroup parent = (ViewGroup) root.getParent();
        if (parent != null) parent.removeView(root);
    }

    /**
     * A respiração da aura: o brilho recua e volta uma vez só, bem devagar. É o
     * que mantém a cena viva enquanto o Vigia está parado olhando de volta.
     */
    private static Animator breathe(View view, long at, long ms) {
        AnimatorSet breath = new AnimatorSet();
        breath.playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.7f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.12f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.12f, 1f));
        return timed(breath, at, ms, new AccelerateDecelerateInterpolator());
    }

    private float dp(float value) {
        return binding.getRoot().getResources().getDisplayMetrics().density * value;
    }

    private static Animator fade(View view, float from, float to, long at, long ms, Interpolator easing) {
        return timed(ObjectAnimator.ofFloat(view, View.ALPHA, from, to), at, ms, easing);
    }

    /** Sobe {@code distance} pixels até a posição final. */
    private static Animator rise(View view, float distance, long at, long ms, Interpolator easing) {
        return timed(ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, distance, 0f), at, ms, easing);
    }

    private static Animator scaleX(View view, float from, float to, long at, long ms, Interpolator easing) {
        return timed(ObjectAnimator.ofFloat(view, View.SCALE_X, from, to), at, ms, easing);
    }

    private static Animator scale(View view, float from, float to, long at, long ms, Interpolator easing) {
        AnimatorSet both = new AnimatorSet();
        both.playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, from, to),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, from, to));
        return timed(both, at, ms, easing);
    }

    private static <T extends Animator> T timed(T animator, long at, long ms, Interpolator easing) {
        animator.setStartDelay(at);
        animator.setDuration(ms);
        animator.setInterpolator(easing);
        return animator;
    }
}
