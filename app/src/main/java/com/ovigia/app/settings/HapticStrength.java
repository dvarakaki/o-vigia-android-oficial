package com.ovigia.app.settings;

/**
 * Força da vibração do app: um nível de {@link #MIN_LEVEL} a {@link #MAX_LEVEL},
 * escolhido na barra das configurações.
 *
 * Cada nível é um pulso curto — quanto dura e com que amplitude (1–255, a
 * escala do {@code VibrationEffect}). A amplitude sobe em passos iguais do
 * nível 1 ao 10, que chega a 255, o teto da API. A duração cresce devagar no
 * começo e acelera no fim: os níveis baixos ficam um toque seco, e o que
 * sobra para os altos baterem mais forte é durar mais.
 *
 * Aparelho sem controle de amplitude vibra sempre na força padrão do motor;
 * aí a única alavanca é a duração, por isso {@link #flatDurationMs} estica
 * mais que {@link #durationMs}.
 *
 * Imutável; {@link #of} devolve sempre a mesma instância para o mesmo nível.
 */
public final class HapticStrength {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 10;
    /** Nível de quem nunca escolheu: perto do meio, já mais forte que o toque de tecla do sistema. */
    public static final int DEFAULT_LEVEL = 6;

    private static final int MIN_AMPLITUDE = 60;
    private static final int MAX_AMPLITUDE = 255;
    private static final int MIN_DURATION_MS = 12;
    private static final int MAX_DURATION_MS = 60;
    private static final int MIN_FLAT_DURATION_MS = 14;
    private static final int MAX_FLAT_DURATION_MS = 75;
    /** Curvatura da duração: acima de 1, cresce devagar no começo e rápido no fim. */
    private static final double DURATION_CURVE = 1.5;

    private static final HapticStrength[] LEVELS = new HapticStrength[MAX_LEVEL + 1];

    static {
        for (int level = MIN_LEVEL; level <= MAX_LEVEL; level++) LEVELS[level] = new HapticStrength(level);
    }

    public final int level;
    /** Duração do pulso quando o motor aceita amplitude. */
    public final int durationMs;
    /** Amplitude do pulso, de 1 a 255. */
    public final int amplitude;
    /** Duração do pulso quando o motor só liga e desliga, sem amplitude. */
    public final int flatDurationMs;

    private HapticStrength(int level) {
        double t = (double) (level - MIN_LEVEL) / (MAX_LEVEL - MIN_LEVEL);
        double curved = Math.pow(t, DURATION_CURVE);
        this.level = level;
        this.amplitude = (int) Math.round(MIN_AMPLITUDE + (MAX_AMPLITUDE - MIN_AMPLITUDE) * t);
        this.durationMs = (int) Math.round(MIN_DURATION_MS + (MAX_DURATION_MS - MIN_DURATION_MS) * curved);
        this.flatDurationMs = (int) Math.round(
                MIN_FLAT_DURATION_MS + (MAX_FLAT_DURATION_MS - MIN_FLAT_DURATION_MS) * curved);
    }

    /** O nível pedido; fora da faixa (arquivo mexido, versão mais nova), o mais próximo dentro dela. */
    public static HapticStrength of(int level) {
        return LEVELS[clamp(level)];
    }

    public static int clamp(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }
}
