package com.ovigia.app.settings;

/** Estado da tela de configurações. Imutável. */
public final class SettingsUiState {

    /** Tamanho do cache ainda não calculado. */
    public static final long SIZE_UNKNOWN = -1;

    /** As preferências já foram lidas do disco (antes disso os interruptores ficam desabilitados). */
    public final boolean loaded;
    public final boolean hapticFeedback;
    /** Nível da barra de intensidade, de {@link HapticStrength#MIN_LEVEL} a {@link HapticStrength#MAX_LEVEL}. */
    public final int hapticLevel;
    public final boolean keepScreenOn;
    /** Em bytes, ou {@link #SIZE_UNKNOWN}. */
    public final long cacheBytes;
    public final boolean clearingCache;

    SettingsUiState(boolean loaded, boolean hapticFeedback, int hapticLevel, boolean keepScreenOn,
                    long cacheBytes, boolean clearingCache) {
        this.loaded = loaded;
        this.hapticFeedback = hapticFeedback;
        this.hapticLevel = hapticLevel;
        this.keepScreenOn = keepScreenOn;
        this.cacheBytes = cacheBytes;
        this.clearingCache = clearingCache;
    }

    static SettingsUiState loading() {
        return new SettingsUiState(false, false, HapticStrength.DEFAULT_LEVEL, false, SIZE_UNKNOWN, false);
    }

    SettingsUiState withPreferences(boolean hapticFeedback, int hapticLevel, boolean keepScreenOn) {
        return new SettingsUiState(true, hapticFeedback, hapticLevel, keepScreenOn, cacheBytes, clearingCache);
    }

    SettingsUiState withCache(long cacheBytes, boolean clearingCache) {
        return new SettingsUiState(loaded, hapticFeedback, hapticLevel, keepScreenOn, cacheBytes, clearingCache);
    }

    /** Aviso pontual mostrado depois de uma operação. */
    public enum Message {
        CACHE_CLEARED,
        LEARNING_FORGOTTEN
    }
}
