package com.ovigia.app.ui.auth;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.ovigia.app.R;

/**
 * Medidor de força da senha em {@link #SEGMENTS} segmentos: eles enchem um a
 * um, cada um da esquerda para a direita, conforme {@link #setLevel}. Quando a
 * senha fica forte, uma onda percorre os segmentos ({@link #setWave}), que
 * crescem e voltam em sequência.
 *
 * Só desenha: quem anima é o {@link PasswordStrengthView}.
 */
public final class StrengthMeterView extends View {

    static final int SEGMENTS = 4;

    /** Espessura dos segmentos em repouso; a altura da view é a folga para a onda. */
    private static final float BAR_DP = 5f;
    private static final float GAP_DP = 5f;
    /** Quanto um segmento cresce na crista da onda. */
    private static final float WAVE_GROWTH = 0.9f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final float bar;
    private final float gap;

    /** De 0 a {@link #SEGMENTS}: quantos segmentos estão cheios (o último pode estar pela metade). */
    private float level;
    /** Posição da onda de 0 a 1; negativa = sem onda. */
    private float wave = -1f;

    public StrengthMeterView(@NonNull Context context) {
        this(context, null);
    }

    public StrengthMeterView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        bar = BAR_DP * d;
        gap = GAP_DP * d;
        track.setColor(ContextCompat.getColor(context, R.color.white_10));
        fill.setColor(ContextCompat.getColor(context, R.color.vigia_danger));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setLevel(float level) {
        this.level = Math.max(0f, Math.min(SEGMENTS, level));
        invalidate();
    }

    float getLevel() {
        return level;
    }

    void setColor(@ColorInt int color) {
        fill.setColor(color);
        invalidate();
    }

    void setWave(float wave) {
        this.wave = wave;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = Math.round(bar * (1f + WAVE_GROWTH)) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float segment = (width - gap * (SEGMENTS - 1)) / SEGMENTS;
        float centerY = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;

        for (int i = 0; i < SEGMENTS; i++) {
            // Na onda, o segmento sob a crista cresce e os vizinhos acompanham um pouco.
            float crest = wave < 0f ? 0f : Math.max(0f, 1f - Math.abs(wave * (SEGMENTS + 1) - (i + 0.5f)));
            float half = bar * (1f + WAVE_GROWTH * crest) / 2f;
            int slot = rtl ? SEGMENTS - 1 - i : i;
            float left = getPaddingLeft() + slot * (segment + gap);
            rect.set(left, centerY - half, left + segment, centerY + half);
            float radius = half;
            canvas.drawRoundRect(rect, radius, radius, track);

            float amount = Math.max(0f, Math.min(1f, level - i));
            if (amount <= 0f) continue;
            // O pedaço cheio é recortado na forma do segmento: cantos redondos mesmo pela metade.
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            float filled = segment * amount;
            if (rtl) {
                canvas.drawRect(rect.right - filled, rect.top, rect.right, rect.bottom, fill);
            } else {
                canvas.drawRect(rect.left, rect.top, rect.left + filled, rect.bottom, fill);
            }
            canvas.restore();
        }
    }
}
