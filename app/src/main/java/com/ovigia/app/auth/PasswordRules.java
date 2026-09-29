package com.ovigia.app.auth;

import java.util.EnumSet;
import java.util.Set;

/**
 * O que uma senha nova precisa ter: pelo menos {@link #MIN_LENGTH} caracteres e
 * um caractere especial (qualquer coisa que não seja letra, número ou espaço:
 * {@code ! @ # $ % - _ .} e afins).
 *
 * Vale para senha nova — cadastro e troca de senha. Entrar numa conta criada
 * antes da regra continua aceitando a senha que ela já tinha; recusar essa
 * senha trancaria o jogador fora da própria conta.
 *
 * Java puro: a mesma regra decide no {@link AccountStore} e acende a lista de
 * verificação na tela, então as duas nunca discordam.
 */
public final class PasswordRules {

    public static final int MIN_LENGTH = 8;

    /** Cada exigência, na ordem em que aparece na tela. */
    public enum Rule {
        /** Pelo menos {@link #MIN_LENGTH} caracteres. */
        LENGTH,
        /** Pelo menos um caractere especial. */
        SPECIAL
    }

    /** As exigências que a senha já cumpre. */
    public static Set<Rule> satisfied(String password) {
        Set<Rule> met = EnumSet.noneOf(Rule.class);
        if (password == null) return met;
        if (password.codePointCount(0, password.length()) >= MIN_LENGTH) met.add(Rule.LENGTH);
        if (hasSpecial(password)) met.add(Rule.SPECIAL);
        return met;
    }

    public static boolean isStrong(String password) {
        return satisfied(password).size() == Rule.values().length;
    }

    /**
     * Quanto da senha já está pronto, de 0 a 1, para a barra de força: cada
     * caractere até o mínimo empurra um pouco (a barra anda enquanto o jogador
     * digita), e o caractere especial vale a outra metade de uma vez.
     */
    public static float progress(String password) {
        if (password == null || password.isEmpty()) return 0f;
        int length = password.codePointCount(0, password.length());
        float lengthPart = Math.min(length, MIN_LENGTH) / (float) MIN_LENGTH;
        float specialPart = hasSpecial(password) ? 1f : 0f;
        return (lengthPart + specialPart) / 2f;
    }

    private static boolean hasSpecial(String password) {
        return password.codePoints().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
    }

    private PasswordRules() { }
}
