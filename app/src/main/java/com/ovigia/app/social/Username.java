package com.ovigia.app.social;

import androidx.annotation.Nullable;

import com.ovigia.app.util.SearchText;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Regras do @usuario, o identificador público com que um jogador encontra o
 * outro. Único no servidor e sempre guardado em minúsculas, então "@Davi" e
 * "@davi" são o mesmo nome. As mesmas regras estão em {@code firestore.rules}.
 */
public final class Username {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 20;

    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{" + MIN_LENGTH + "," + MAX_LENGTH + "}$");

    public enum Problem {
        TOO_SHORT,
        TOO_LONG,
        /** Só letras sem acento, números e "_". */
        INVALID_CHARACTERS,
        /** Precisa ter pelo menos uma letra ou número. */
        ONLY_UNDERSCORES
    }

    /** Tira espaços e o "@" do começo e passa para minúsculas. */
    public static String normalize(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (s.startsWith("@")) s = s.substring(1);
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /** Problema do nome já normalizado, ou {@code null} se ele pode ser usado. */
    @Nullable
    public static Problem problemWith(String normalized) {
        if (normalized.length() < MIN_LENGTH) return Problem.TOO_SHORT;
        if (normalized.length() > MAX_LENGTH) return Problem.TOO_LONG;
        if (!VALID.matcher(normalized).matches()) return Problem.INVALID_CHARACTERS;
        if (normalized.replace("_", "").isEmpty()) return Problem.ONLY_UNDERSCORES;
        return null;
    }

    public static boolean isValid(String normalized) {
        return problemWith(normalized) == null;
    }

    /** Sugestão a partir do nome da conta ("Davi Souza" → "davisouza"), ou vazio se não der um nome válido. */
    public static String suggestFrom(@Nullable String name) {
        if (name == null) return "";
        String plain = SearchText.fold(name).replaceAll("[^a-z0-9_]", "");
        if (plain.length() > MAX_LENGTH) plain = plain.substring(0, MAX_LENGTH);
        return isValid(plain) ? plain : "";
    }

    private Username() { }
}
