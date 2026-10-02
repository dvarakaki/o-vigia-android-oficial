package com.ovigia.app.data.roster;

import androidx.annotation.Nullable;

/**
 * Raridade de um personagem: quanto menos conhecido, mais raro.
 *
 * O valor de cada um é curado no {@code roster.json} (campo {@code rarity}). O
 * ponto de partida da curadoria é a fama medida em dois eixos — o personagem é
 * conhecido do grande público ({@code mainstream}) e quantas vezes apareceu nos
 * quadrinhos (Comic Vine):
 * <ul>
 *   <li>{@link #COMMON}: conhecido do grande público com 1.500+ aparições, ou 4.000+ aparições;</li>
 *   <li>{@link #RARE}: os demais conhecidos do grande público, ou 1.500+ aparições;</li>
 *   <li>{@link #EPIC}: 600+ aparições;</li>
 *   <li>{@link #LEGENDARY}: o resto — os que quase ninguém lembra.</li>
 * </ul>
 * Ajustes à mão valem quando o cinema tornou alguém mais conhecido do que os
 * números dizem.
 *
 * Os lendários são exclusivos do Vigia do Infinito ({@link #requiresInfinite()}):
 * quem não é encontra o personagem, mas ele fica lacrado até virar.
 */
public enum Rarity {
    COMMON("comum"),
    RARE("raro"),
    EPIC("epico"),
    LEGENDARY("lendario");

    /** Valor no {@code roster.json}. Estável: mudar quebra o arquivo. */
    public final String key;

    Rarity(String key) {
        this.key = key;
    }

    /** Só o Vigia do Infinito leva o personagem para a coleção. */
    public boolean requiresInfinite() {
        return this == LEGENDARY;
    }

    /** A raridade de {@code key}, ou {@code null} se o valor não existe. */
    @Nullable
    public static Rarity fromKey(@Nullable String key) {
        if (key == null) return null;
        for (Rarity r : values()) {
            if (r.key.equals(key)) return r;
        }
        return null;
    }

    /** A raridade de {@code name()} (como vai num Bundle), ou {@link #COMMON} se o valor não existe. */
    public static Rarity fromName(@Nullable String name) {
        if (name != null) {
            for (Rarity r : values()) {
                if (r.name().equals(name)) return r;
            }
        }
        return COMMON;
    }
}
