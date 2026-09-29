package com.ovigia.app.ui.auth;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

/**
 * O "certo" das verificações do login: um anel apagado que, conforme
 * {@link #setProgress} vai de 0 a 1, se enche de cor (passando um pouco do
 * tamanho antes de assentar) e tem o traço do certo desenhado por cima, da
 * ponta curta à longa — como alguém marcando à mão.
 *
 * Quem anima é quem usa (um {@code ValueAnimator} chamando {@link #setProgress}):
 * assim a animação segue a escala de animação do aparelho e pode ser cancelada.
 */
final class CheckMarkDrawable extends Drawable {

    /** O disco termina de encher aqui; o traço começa um pouco antes, para as duas coisas se emendarem. */
    private static final float FILL_END = 0.55f;
    private static final float CHECK_START = 0.35f;
    private static final Interpolator FILL_OVERSHOOT = new OvershootInterpolator(2.2f);

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path checkPath = new Path();
    private final Path checkSegment = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final int intrinsicSize;
    private final float ringWidth;

    @ColorInt private int ringColor;
    @ColorInt private int fillColor;
    private float progress;
    private float checkLength;
    private int alpha = 255;

    /**
     * @param sizePx      tamanho do disco
     * @param ringColor   anel apagado (exigência pendente)
     * @param fillColor   disco cheio (cumprida)
     * @param checkColor  traço do certo, por cima do disco
     */
    CheckMarkDrawable(int sizePx, @ColorInt int ringColor, @ColorInt int fillColor, @ColorInt int checkColor) {
        this.intrinsicSize = sizePx;
        this.ringColor = ringColor;
        this.fillColor = fillColor;
        ringWidth = Math.max(1.5f, sizePx / 11f);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ringWidth);
        fill.setStyle(Paint.Style.FILL);
        check.setStyle(Paint.Style.STROKE);
        check.setStrokeCap(Paint.Cap.ROUND);
        check.setStrokeJoin(Paint.Join.ROUND);
        check.setColor(checkColor);
    }

    /** 0 = anel apagado; 1 = disco cheio com o certo inteiro. */
    void setProgress(float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        if (clamped == this.progress) return;
        this.progress = clamped;
        invalidateSelf();
    }

    float getProgress() {
        return progress;
    }

    /** Cor do anel apagado (ex.: vermelho quando o jogador tentou seguir sem cumprir). */
    void setRingColor(@ColorInt int color) {
        if (color == ringColor) return;
        ringColor = color;
        invalidateSelf();
    }

    @ColorInt
    int getRingColor() {
        return ringColor;
    }

    void setFillColor(@ColorInt int color) {
        if (color == fillColor) return;
        fillColor = color;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        float size = Math.min(bounds.width(), bounds.height());
        float left = bounds.exactCenterX() - size / 2f;
        float top = bounds.exactCenterY() - size / 2f;
        checkPath.reset();
        checkPath.moveTo(left + size * 0.29f, top + size * 0.52f);
        checkPath.lineTo(left + size * 0.44f, top + size * 0.66f);
        checkPath.lineTo(left + size * 0.72f, top + size * 0.37f);
        measure.setPath(checkPath, false);
        checkLength = measure.getLength();
        check.setStrokeWidth(Math.max(1.5f, size / 9f));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float radius = Math.min(b.width(), b.height()) / 2f - ringWidth / 2f;

        float fillT = Math.min(1f, progress / FILL_END);
        // O anel desbota enquanto o disco cheio toma o lugar dele.
        ring.setColor(withAlpha(ColorUtils.blendARGB(ringColor, fillColor, fillT)));
        canvas.drawCircle(cx, cy, radius, ring);

        if (fillT > 0f) {
            fill.setColor(withAlpha(fillColor));
            float grown = FILL_OVERSHOOT.getInterpolation(fillT);
            canvas.drawCircle(cx, cy, (radius + ringWidth / 2f) * grown, fill);
        }

        float checkT = (progress - CHECK_START) / (1f - CHECK_START);
        if (checkT > 0f && checkLength > 0f) {
            checkSegment.reset();
            measure.getSegment(0f, checkLength * Math.min(1f, checkT), checkSegment, true);
            canvas.drawPath(checkSegment, check);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return intrinsicSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return intrinsicSize;
    }

    /** A cor com a transparência do drawable por cima da dela. */
    @ColorInt
    private int withAlpha(@ColorInt int color) {
        return ColorUtils.setAlphaComponent(color, Color.alpha(color) * alpha / 255);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        check.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // Cores próprias: o tint de quem hospeda (ex.: o ícone do TextInputLayout) não se aplica.
    }

    /** Obrigatório (abstrato) mesmo descontinuado: o sistema não consulta mais, mas a classe precisa dele. */
    @SuppressWarnings("deprecation")
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
