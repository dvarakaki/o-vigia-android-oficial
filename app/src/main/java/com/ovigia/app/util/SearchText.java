package com.ovigia.app.util;

import java.text.Normalizer;
import java.util.Locale;

/** Texto para comparar em buscas: "Jéan" encontra "jean", "homem-aranha" encontra "Homem-Aranha". */
public final class SearchText {

    /** Sem espaços nas pontas, sem acentos e em minúsculas; {@code null} vira vazio. */
    public static String fold(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private SearchText() { }
}
