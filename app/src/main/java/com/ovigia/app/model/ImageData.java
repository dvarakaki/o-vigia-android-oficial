package com.ovigia.app.model;

import com.google.gson.annotations.SerializedName;

public class ImageData {
    @SerializedName("icon_url") public String iconUrl;
    @SerializedName("thumb_url") public String thumbUrl;
    @SerializedName("tiny_url") public String tinyUrl;
    @SerializedName("medium_url") public String mediumUrl;
    @SerializedName("screen_url") public String screenUrl;
    @SerializedName("screen_large_url") public String screenLargeUrl;
    @SerializedName("small_url") public String smallUrl;
    @SerializedName("super_url") public String superUrl;
    @SerializedName("original_url") public String originalUrl;
    /** Galerias da Comic Vine a que a imagem pertence, separadas por vírgula. */
    @SerializedName("image_tags") public String imageTags;

    /** Melhor imagem para exibir o personagem em destaque (chute). */
    public String bestForHero() {
        return firstNotEmpty(superUrl, screenLargeUrl, originalUrl, screenUrl, mediumUrl, smallUrl);
    }

    /** Imagem leve para miniaturas em listas. */
    public String bestForThumbnail() {
        return firstNotEmpty(mediumUrl, screenUrl, smallUrl, superUrl, screenLargeUrl, originalUrl);
    }

    private static String firstNotEmpty(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isEmpty()) return s;
        }
        return null;
    }
}
