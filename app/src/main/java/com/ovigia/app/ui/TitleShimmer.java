package com.ovigia.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

/**
 * A luz que atravessa um texto: uma faixa dourada passa uma vez da esquerda
 * para a direita, como um reflexo correndo sobre metal. É o que acende a marca
 * na abertura do app e o nome da conquista quando ela cai.
 *
 * Cada palavra recebe um gradiente que vai da sua própria cor até a cor da luz
 * e volta — fora da faixa o {@link Shader.TileMode#CLAMP} devolve a cor de
 * origem, então o branco continua branco e o azul continua azul; só a faixa
 * clareia. Os gradientes das duas palavras andam no mesmo eixo da linha
 * inteira, então a luz passa de uma para a outra sem emenda.
 *
 * O shader só é montado quando a animação começa, e é retirado no fim: fora
 * desse meio segundo o título é desenhado do jeito normal.
 */
public final class TitleShimmer {

    /** Cor da faixa de luz: o dourado do app, bem claro. */
    private static final int LIGHT = 0xFFF6DE9A;
    /** Quanto a faixa puxa a cor da palavra para a luz. */
    private static final float LIGHT_MIX = 0.78f;
    /** Largura da faixa, em fração da linha inteira. */
    private static final float BAND_RATIO = 0.42f;

    /**
     * Uma varredura de ponta a ponta das {@code words} (que precisam ser irmãs
     * no mesmo pai, como as duas do {@code view_title}).
     */
    public static Animator sweep(long durationMs, TextView... words) {
        final LinearGradient[] shaders = new LinearGradient[words.length];
        final Matrix matrix = new Matrix();
        // Medido só quando a animação começa: [0] é onde a faixa entra, [1] o
        // caminho que ela percorre até sair do outro lado.
        final float[] path = new float[2];

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(durationMs);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                int left = Integer.MAX_VALUE;
                int right = Integer.MIN_VALUE;
                for (TextView word : words) {
                    left = Math.min(left, word.getLeft());
                    right = Math.max(right, word.getRight());
                }
                float band = Math.max((right - left) * BAND_RATIO, 1f);
                path[0] = left - band;
                path[1] = (right - left) + 2 * band;

                for (int i = 0; i < words.length; i++) {
                    int base = words[i].getCurrentTextColor();
                    int light = ColorUtils.blendARGB(base, LIGHT, LIGHT_MIX);
                    shaders[i] = new LinearGradient(0, 0, band, 0,
                            new int[]{base, light, base},
                            new float[]{0f, 0.5f, 1f},
                            Shader.TileMode.CLAMP);
                    words[i].getPaint().setShader(shaders[i]);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                clear(words);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                clear(words);
            }
        });

        animator.addUpdateListener(a -> {
            if (shaders[0] == null) return;
            float head = path[0] + path[1] * (float) a.getAnimatedValue();
            for (int i = 0; i < words.length; i++) {
                // O shader vive nas coordenadas da palavra; o desconto do
                // getLeft() põe as duas de volta no eixo da linha.
                matrix.setTranslate(head - words[i].getLeft(), 0f);
                shaders[i].setLocalMatrix(matrix);
                words[i].invalidate();
            }
        });
        return animator;
    }

    private static void clear(TextView... words) {
        for (TextView word : words) {
            word.getPaint().setShader(null);
            word.invalidate();
        }
    }

    private TitleShimmer() { }
}
