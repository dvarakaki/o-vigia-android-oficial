package com.ovigia.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.ImageViewCompat;

import com.ovigia.app.R;
import com.ovigia.app.data.roster.Rarity;

/** Como cada raridade aparece: cor, nome e o selo com a gema. */
public final class RarityViews {

    /** Opacidade do fundo e da borda do selo sobre a cor da raridade. */
    private static final int CHIP_FILL_ALPHA = 0x24;
    private static final int CHIP_STROKE_ALPHA = 0x99;

    @ColorRes
    public static int colorRes(Rarity rarity) {
        switch (rarity) {
            case LEGENDARY: return R.color.rarity_legendary;
            case EPIC: return R.color.rarity_epic;
            case RARE: return R.color.rarity_rare;
            case COMMON:
            default: return R.color.rarity_common;
        }
    }

    public static int color(Context context, Rarity rarity) {
        return ContextCompat.getColor(context, colorRes(rarity));
    }

    @StringRes
    public static int label(Rarity rarity) {
        switch (rarity) {
            case LEGENDARY: return R.string.rarity_legendary;
            case EPIC: return R.string.rarity_epic;
            case RARE: return R.string.rarity_rare;
            case COMMON:
            default: return R.string.rarity_common;
        }
    }

    /** Selo da raridade (estilo {@code Widget.OVigia.RarityChip}): gema e nome, na cor dela. */
    public static void bindChip(TextView chip, Rarity rarity) {
        Context context = chip.getContext();
        int color = color(context, rarity);
        chip.setText(label(rarity));
        chip.setTextColor(color);
        chip.setContentDescription(context.getString(R.string.rarity_cd, context.getString(label(rarity))));

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(context.getResources().getDisplayMetrics().density * 999);
        background.setColor(ColorUtils.setAlphaComponent(color, CHIP_FILL_ALPHA));
        background.setStroke(Math.round(context.getResources().getDisplayMetrics().density),
                ColorUtils.setAlphaComponent(color, CHIP_STROKE_ALPHA));
        chip.setBackground(background);

        int size = Math.round(chip.getTextSize() * 1.15f);
        chip.setCompoundDrawablesRelative(gem(context, color, size), null, null, null);
    }

    /** A gema na cor da raridade, mantendo o relevo das faces (multiplicado, não chapado). */
    public static void tintGem(ImageView gem, Rarity rarity) {
        ImageViewCompat.setImageTintList(gem, ColorStateList.valueOf(color(gem.getContext(), rarity)));
        ImageViewCompat.setImageTintMode(gem, PorterDuff.Mode.MULTIPLY);
    }

    /** A gema como drawable de texto, do tamanho {@code size} (px). */
    @Nullable
    public static Drawable gem(Context context, int color, int size) {
        Drawable icon = AppCompatResources.getDrawable(context, R.drawable.ic_rarity_gem);
        if (icon == null) return null;
        Drawable tinted = DrawableCompat.wrap(icon).mutate();
        DrawableCompat.setTint(tinted, color);
        DrawableCompat.setTintMode(tinted, PorterDuff.Mode.MULTIPLY);
        tinted.setBounds(0, 0, size, size);
        return tinted;
    }

    private RarityViews() { }
}
