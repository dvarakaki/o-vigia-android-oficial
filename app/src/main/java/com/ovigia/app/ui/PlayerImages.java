package com.ovigia.app.ui;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.ovigia.app.R;

import java.util.Base64;

/**
 * Foto e banner de um jogador — o próprio ou um amigo —, que vêm da conta
 * online como JPEG em Base64.
 *
 * Sem imagem — ou com uma que não abre (arquivo sumido, JPEG corrompido) — entram
 * os padrões do app: o ícone de pessoa na foto e a galáxia com a tinta
 * azul-violeta no banner.
 */
public final class PlayerImages {

    /** Troca suave da foto do próprio perfil (ela muda na edição, com a tela aberta). */
    private static final int OWN_CROSS_FADE_MS = 200;

    /** Foto do próprio perfil; {@code null} mostra o ícone padrão. */
    public static void bindAvatar(Fragment fragment, ImageView view, @Nullable String base64) {
        int padding = fragment.getResources().getDimensionPixelSize(R.dimen.avatar_icon_padding);
        byte[] bytes = decode(base64);
        avatar(fragment, view, bytes == null ? null : Glide.with(fragment).load(bytes)
                .transition(DrawableTransitionOptions.withCrossFade(OWN_CROSS_FADE_MS)), padding);
    }

    /** Banner do próprio perfil; {@code null} mostra a galáxia com a tinta ({@code tint}). */
    public static void bindBanner(Fragment fragment, ImageView view, View tint, @Nullable String base64) {
        byte[] bytes = decode(base64);
        banner(fragment, view, tint, bytes == null ? null : Glide.with(fragment).load(bytes)
                .transition(DrawableTransitionOptions.withCrossFade(OWN_CROSS_FADE_MS)));
    }

    /**
     * Foto publicada por outro jogador.
     *
     * @param iconPaddingPx respiro do ícone padrão quando não há foto
     */
    public static void bindSharedAvatar(Fragment fragment, ImageView view, @Nullable String base64, int iconPaddingPx) {
        byte[] bytes = decode(base64);
        avatar(fragment, view, bytes == null ? null : Glide.with(fragment).load(bytes), iconPaddingPx);
    }

    /** Banner publicado por outro jogador, com os mesmos padrões do próprio perfil. */
    public static void bindSharedBanner(Fragment fragment, ImageView view, View tint, @Nullable String base64) {
        byte[] bytes = decode(base64);
        banner(fragment, view, tint, bytes == null ? null : Glide.with(fragment).load(bytes));
    }

    private static void avatar(Fragment fragment, ImageView view, @Nullable RequestBuilder<Drawable> image,
                               int iconPaddingPx) {
        if (image == null) {
            Glide.with(fragment).clear(view);
            showDefaultAvatar(view, iconPaddingPx);
            return;
        }
        view.setPadding(0, 0, 0, 0);
        view.setImageTintList(null);
        image.transform(new CircleCrop())
                .listener(onFailure(() -> showDefaultAvatar(view, iconPaddingPx)))
                .into(view);
    }

    private static void banner(Fragment fragment, ImageView view, View tint,
                               @Nullable RequestBuilder<Drawable> image) {
        if (image == null) {
            Glide.with(fragment).clear(view);
            showDefaultBanner(view, tint);
            return;
        }
        tint.setVisibility(View.GONE);
        image.centerCrop()
                .placeholder(R.drawable.bg_galaxy)
                .listener(onFailure(() -> showDefaultBanner(view, tint)))
                .into(view);
    }

    private static void showDefaultAvatar(ImageView view, int iconPaddingPx) {
        view.setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx);
        view.setImageResource(R.drawable.ic_person);
        view.setImageTintList(ContextCompat.getColorStateList(view.getContext(), R.color.vigia_gold));
    }

    private static void showDefaultBanner(ImageView view, View tint) {
        view.setImageResource(R.drawable.bg_galaxy);
        tint.setVisibility(View.VISIBLE);
    }

    /** Na falha, mostra o padrão no lugar da imagem (e o Glide não põe mais nada por cima). */
    private static RequestListener<Drawable> onFailure(Runnable showDefault) {
        return new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, @Nullable Object model,
                                        @NonNull Target<Drawable> target, boolean isFirstResource) {
                showDefault.run();
                return true;
            }

            @Override
            public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model,
                                           Target<Drawable> target, @NonNull DataSource dataSource,
                                           boolean isFirstResource) {
                return false;
            }
        };
    }

    @Nullable
    private static byte[] decode(@Nullable String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private PlayerImages() { }
}
