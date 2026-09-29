package com.ovigia.app.profile;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.Size;

import com.ovigia.app.auth.AccountStore.ImageKind;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;

/**
 * Implementação real de {@link ProfileImages}: decodifica com {@link ImageDecoder}
 * (que já aplica a rotação EXIF), reduz o lado maior e comprime em JPEG até
 * caber no limite de cada imagem na conta — a qualidade cai aos poucos e, se
 * ainda não couber, a imagem encolhe mais um pouco.
 */
public final class AndroidProfileImages implements ProfileImages {

    static final int AVATAR_MAX_PX = 256;
    static final int BANNER_MAX_PX = 1080;
    /** Tamanho máximo do texto em Base64 (as regras do Firestore aceitam 60 mil e 400 mil). */
    static final int AVATAR_MAX_CHARS = 55_000;
    static final int BANNER_MAX_CHARS = 380_000;
    private static final int START_QUALITY = 85;
    private static final int MIN_QUALITY = 45;
    private static final int QUALITY_STEP = 10;
    private static final float SHRINK = 0.8f;

    private final ContentResolver resolver;

    public AndroidProfileImages(ContentResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String encode(Uri source, ImageKind kind) throws IOException {
        return encode(ImageDecoder.createSource(resolver, source), kind);
    }

    @Override
    public String encodeFile(File file, ImageKind kind) throws IOException {
        return encode(ImageDecoder.createSource(file), kind);
    }

    private static String encode(ImageDecoder.Source source, ImageKind kind) throws IOException {
        boolean avatar = kind == ImageKind.AVATAR;
        int maxChars = avatar ? AVATAR_MAX_CHARS : BANNER_MAX_CHARS;
        Bitmap bitmap = decodeScaled(source, avatar ? AVATAR_MAX_PX : BANNER_MAX_PX);
        try {
            while (true) {
                for (int quality = START_QUALITY; quality >= MIN_QUALITY; quality -= QUALITY_STEP) {
                    String encoded = jpegBase64(bitmap, quality);
                    if (encoded.length() <= maxChars) return encoded;
                }
                // Nem com a qualidade mínima coube: encolhe e tenta de novo.
                int width = Math.round(bitmap.getWidth() * SHRINK);
                int height = Math.round(bitmap.getHeight() * SHRINK);
                if (width < 32 || height < 32) throw new IOException("Imagem grande demais");
                Bitmap smaller = Bitmap.createScaledBitmap(bitmap, width, height, true);
                bitmap.recycle();
                bitmap = smaller;
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static String jpegBase64(Bitmap bitmap, int quality) throws IOException {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)) {
            throw new IOException("Falha ao comprimir imagem");
        }
        return Base64.getEncoder().encodeToString(jpeg.toByteArray());
    }

    /** Decodifica reduzindo para no máximo {@code maxPx} no lado maior. */
    private static Bitmap decodeScaled(ImageDecoder.Source source, int maxPx) throws IOException {
        try {
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                Size size = info.getSize();
                int longest = Math.max(size.getWidth(), size.getHeight());
                if (longest > maxPx) {
                    float scale = (float) maxPx / longest;
                    decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * scale)),
                            Math.max(1, Math.round(size.getHeight() * scale)));
                }
                // Bitmap de hardware não pode ser comprimido.
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (RuntimeException e) {
            throw new IOException("Imagem ilegível", e);
        }
    }
}
