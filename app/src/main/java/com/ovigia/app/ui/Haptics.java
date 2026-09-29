package com.ovigia.app.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.annotation.Nullable;

import com.ovigia.app.settings.HapticStrength;
import com.ovigia.app.settings.SettingsStore;

/**
 * A vibração do app, na força que o jogador escolheu ({@link HapticStrength}).
 *
 * Irmã do {@link Motion}: onde ele mexe na tela, esta mexe na mão. Antes o app
 * usava o toque de tecla do sistema ({@code performHapticFeedback}), que não
 * tem regulagem nenhuma e em muitos aparelhos mal se sente; aqui o pulso vai
 * direto ao motor, com duração e amplitude próprias.
 *
 * A vibração sai como vibração de mídia, não como toque de interface: o
 * Android corta as de toque quando o retorno tátil do sistema está desligado
 * (e muita gente desliga), e aí a barra das configurações vibraria para
 * ninguém. Quem liga e desliga a vibração do app é a chave do próprio app.
 *
 * Uma por app ({@link com.ovigia.app.AppContainer}). Ler a preferência é só
 * memória depois do aquecimento da abertura, então pode ser chamada da main
 * thread.
 */
public final class Haptics {

    @Nullable private final Vibrator vibrator;
    private final SettingsStore settings;

    public Haptics(Context context, SettingsStore settings) {
        this.vibrator = vibratorOf(context.getApplicationContext());
        this.settings = settings;
    }

    /** Confirmação curta (ex.: uma resposta na partida), se o jogador deixou a vibração ligada. */
    public void tap() {
        if (!settings.hapticFeedback()) return;
        pulse(settings.hapticStrength());
    }

    /**
     * Um pulso no nível pedido, ligado ou não nas configurações: é a amostra que
     * o jogador sente a cada passo da barra de intensidade.
     */
    public void preview(HapticStrength strength) {
        pulse(strength);
    }

    private void pulse(HapticStrength strength) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        VibrationEffect effect = vibrator.hasAmplitudeControl()
                ? VibrationEffect.createOneShot(strength.durationMs, strength.amplitude)
                : VibrationEffect.createOneShot(strength.flatDurationMs, VibrationEffect.DEFAULT_AMPLITUDE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA));
        } else {
            vibrator.vibrate(effect, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        }
    }

    @Nullable
    private static Vibrator vibratorOf(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            return manager != null ? manager.getDefaultVibrator() : null;
        }
        return context.getSystemService(Vibrator.class);
    }
}
