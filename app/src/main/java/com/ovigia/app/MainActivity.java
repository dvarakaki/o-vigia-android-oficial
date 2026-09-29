package com.ovigia.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ovigia.app.databinding.ActivityMainBinding;
import com.ovigia.app.social.Achievement;
import com.ovigia.app.ui.achievements.AchievementStage;
import com.ovigia.app.ui.splash.SplashStage;

import java.util.List;

/**
 * Activity única: hospeda o NavHostFragment com todas as telas. O fundo espacial
 * vai de ponta a ponta; aqui o conteúdo recua das laterais, da barra de navegação
 * e do teclado. O topo (barra de status) fica com cada tela — ver
 * {@link com.ovigia.app.ui.SystemBarInsets} — para permitir cabeçalhos imersivos.
 *
 * A abertura é dos dois lados da emenda: o splash do sistema desenha o selo do
 * olho e, ao sair, entrega a cena para a {@link SplashStage}, que já está
 * montada por baixo. Só na primeira criação da activity — girar a tela cai
 * direto no app.
 *
 * As conquistas também são daqui: elas podem cair em qualquer tela, então quem
 * escuta o {@code AchievementsTracker} é a activity, e o cartão da comemoração
 * ({@link AchievementStage}) aparece por cima do que estiver na tela.
 */
public class MainActivity extends AppCompatActivity {

    /** Saída do selo: ele abre num estouro enquanto o clarão do palco entra por baixo. */
    private static final long SPLASH_EXIT_ICON_MS = 300L;
    private static final long SPLASH_EXIT_VIEW_MS = 340L;

    /** Guardado só para cortar a cena se a activity morrer no meio dela. */
    @Nullable private SplashStage splash;
    /** Inflado na primeira conquista que cai; depois disso, reaproveitado. */
    @Nullable private AchievementStage achievements;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.navHost, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, 0, bars.right, bars.bottom);
            // Não consome: as telas ainda precisam do topo.
            return insets;
        });

        AppContainer container = ((OVigiaApplication) getApplication()).container();
        container.achievements.unlocked().observe(this, event -> {
            List<Achievement> unlocked = event.consume();
            if (unlocked == null || unlocked.isEmpty()) return;
            if (achievements == null) achievements = AchievementStage.inflate(binding.achievementStage);
            achievements.show(unlocked);
        });

        // A cena de abertura é só da primeira criação: depois de girar a tela o
        // app já está aberto, e repeti-la seria um atraso sem motivo.
        if (savedInstanceState == null) {
            splash = SplashStage.inflate(binding.splashStage);
            if (splash != null) installSplashAnimation(splashScreen, splash, binding.navHost);
        }
    }

    @Override
    protected void onDestroy() {
        // As cenas estão presas a estas views: sem isso, elas continuariam
        // animando uma hierarquia que não existe mais.
        if (splash != null) splash.skip();
        if (achievements != null) achievements.cancel();
        super.onDestroy();
    }

    /**
     * Segura o splash do sistema até o olho terminar de abrir e, na saída,
     * entrega a cena para o palco: o selo abre num estouro e some, e o clarão
     * do palco entra por baixo no mesmo instante — a emenda fica dentro da luz.
     */
    private void installSplashAnimation(SplashScreen splashScreen, @NonNull SplashStage stage, View content) {
        final long releaseAt = SystemClock.elapsedRealtime() + SplashStage.SEAL_MS;
        splashScreen.setKeepOnScreenCondition(
                () -> SystemClock.elapsedRealtime() < releaseAt);

        splashScreen.setOnExitAnimationListener(provider -> {
            stage.play(content);

            View splashView = provider.getView();
            View icon = provider.getIconView();
            DecelerateInterpolator open = new DecelerateInterpolator(1.4f);

            ObjectAnimator fade = ObjectAnimator.ofFloat(splashView, View.ALPHA, 1f, 0f);
            fade.setDuration(SPLASH_EXIT_VIEW_MS);
            fade.setInterpolator(open);

            AnimatorSet exit = new AnimatorSet();
            if (icon == null) {
                exit.play(fade);
            } else {
                icon.setPivotX(icon.getWidth() / 2f);
                icon.setPivotY(icon.getHeight() / 2f);
                AnimatorSet burst = new AnimatorSet();
                burst.playTogether(
                        ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.6f),
                        ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.6f),
                        ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f));
                burst.setDuration(SPLASH_EXIT_ICON_MS);
                burst.setInterpolator(open);
                exit.playTogether(burst, fade);
            }

            exit.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    provider.remove();
                }
            });
            exit.start();
        });
    }
}
