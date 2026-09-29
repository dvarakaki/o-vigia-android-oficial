package com.ovigia.app.ui.achievements;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.ovigia.app.R;
import com.ovigia.app.databinding.ItemAchievementBinding;
import com.ovigia.app.social.AchievementProgress;
import com.ovigia.app.social.Achievements;
import com.ovigia.app.ui.Motion;

import java.util.ArrayList;
import java.util.List;

/** Lista de conquistas, igual no próprio perfil e no perfil de um amigo. */
public final class AchievementViews {

    /** Conquistas mostradas antes de "Ver todas". */
    private static final int PREVIEW = 4;

    public static void fill(LinearLayout list, Button showAll, List<AchievementProgress> progress, boolean expanded) {
        fill(list, showAll, progress, expanded, null);
    }

    /**
     * Desbloqueadas primeiro (na ordem do enum), depois as que faltam, da mais
     * perto para a mais longe. Recolhida, mostra só as primeiras e o botão
     * {@code showAll} abre o resto.
     *
     * @param motion quando a lista está trocando na tela (ex.: o jogador tocou
     *               em "Ver todas"), as linhas entram em cascata; {@code null}
     *               na montagem normal, que já entra junto com o cartão
     */
    public static void fill(LinearLayout list, Button showAll, List<AchievementProgress> progress, boolean expanded,
                            @Nullable Motion motion) {
        list.removeAllViews();
        Context context = list.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        List<AchievementProgress> ordered = new ArrayList<>(progress);
        ordered.sort((a, b) -> {
            if (a.isUnlocked() != b.isUnlocked()) return a.isUnlocked() ? -1 : 1;
            if (a.isUnlocked()) return 0;
            return Integer.compare(b.percent(), a.percent());
        });
        boolean collapsed = !expanded && ordered.size() > PREVIEW;
        showAll.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        showAll.setText(context.getString(R.string.achievements_show_all, ordered.size()));
        if (collapsed) ordered = ordered.subList(0, PREVIEW);
        int gold = ContextCompat.getColor(context, R.color.vigia_gold);
        for (AchievementProgress p : ordered) {
            ItemAchievementBinding row = ItemAchievementBinding.inflate(inflater, list, true);
            boolean unlocked = p.isUnlocked();
            String title = context.getString(AchievementArt.titleOf(p.achievement));
            String description = context.getString(AchievementArt.descriptionOf(p.achievement));
            row.imageIcon.setImageResource(AchievementArt.iconOf(p.achievement));
            row.tvTitle.setText(title);
            // Desbloqueada vira dourada: a lista se lê de longe, sem contar barras.
            row.tvTitle.setTextColor(unlocked ? gold : ContextCompat.getColor(context, R.color.white_70));
            row.tvDescription.setText(description);
            row.tvCount.setText(context.getString(R.string.achievement_count, p.current, p.achievement.target));
            row.progressBar.setProgressCompat(p.percent(), false);
            row.imageIcon.setImageTintList(ColorStateList.valueOf(unlocked
                    ? gold : ContextCompat.getColor(context, R.color.white_20)));
            row.imageCheck.setVisibility(unlocked ? View.VISIBLE : View.GONE);
            row.iconFrame.setAlpha(unlocked ? 1f : 0.6f);
            row.getRoot().setContentDescription(context.getString(unlocked
                            ? R.string.achievement_cd_unlocked : R.string.achievement_cd_locked,
                    title, description, p.current, p.achievement.target));
        }
        if (motion != null) motion.staggerIn(0, children(list));
    }

    private static View[] children(LinearLayout list) {
        View[] rows = new View[list.getChildCount()];
        for (int i = 0; i < rows.length; i++) rows[i] = list.getChildAt(i);
        return rows;
    }

    public static String countText(Context context, List<AchievementProgress> progress) {
        return context.getString(R.string.achievement_count, Achievements.unlockedCount(progress), progress.size());
    }

    private AchievementViews() { }
}
