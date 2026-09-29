package com.ovigia.app.social;

import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.util.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Fica de olho nas conquistas da conta logada e avisa quando uma cai.
 *
 * Vive no escopo do app ({@link com.ovigia.app.AppContainer}) porque a festa não
 * é de uma tela só: quem escuta é a {@link com.ovigia.app.MainActivity}, que
 * mostra o cartão por cima de qualquer tela em que o jogador esteja. O evento
 * passa por um {@link Event}: se o app estiver em segundo plano, ele espera a
 * activity voltar em vez de se perder.
 *
 * {@link #sync()} é o único gatilho e pode ser chamado à vontade — ele recalcula
 * as conquistas no I/O e só publica o que o {@link AchievementsStore} ainda não
 * tinha comemorado. Os lugares que chamam são os momentos em que algo pode ter
 * mudado: a abertura do app, a entrada na conta (que também crava o marco zero,
 * ver {@link AchievementsStore}), o fim de uma partida e a volta ao perfil.
 */
public final class AchievementsTracker {

    /**
     * Quantos cartões, no máximo, uma leva mostra. Uma partida desbloqueia uma
     * ou duas conquistas; uma leva grande só acontece quando o marco zero da
     * conta já existia e o jogador voltou depois de muito tempo (ou trouxe a
     * coleção de outro aparelho). Nesses casos as mais raras entram em cena e as
     * demais ficam para o jogador encontrar na lista do perfil.
     */
    @VisibleForTesting
    static final int MAX_PER_BURST = 3;

    private final AccountStore accountStore;
    private final CollectionStore collectionStore;
    private final LearningStore learningStore;
    private final AchievementsStore achievementsStore;
    private final Supplier<RosterCatalog> roster;
    private final Executor ioExecutor;
    private final Executor mainExecutor;

    private final MutableLiveData<Event<List<Achievement>>> unlocked = new MutableLiveData<>();

    /** @param roster equipes e vilania das conquistas de equipe; pode devolver {@code null} */
    public AchievementsTracker(AccountStore accountStore, CollectionStore collectionStore,
                               LearningStore learningStore, AchievementsStore achievementsStore,
                               Supplier<RosterCatalog> roster, Executor ioExecutor, Executor mainExecutor) {
        this.accountStore = accountStore;
        this.collectionStore = collectionStore;
        this.learningStore = learningStore;
        this.achievementsStore = achievementsStore;
        this.roster = roster;
        this.ioExecutor = ioExecutor;
        this.mainExecutor = mainExecutor;
    }

    /** Conquistas recém-desbloqueadas, para comemorar uma vez. */
    public LiveData<Event<List<Achievement>>> unlocked() {
        return unlocked;
    }

    /**
     * Recalcula as conquistas da conta logada e publica em {@link #unlocked()} as
     * que ainda não foram comemoradas. Sem sessão, não faz nada. Roda tudo no
     * I/O: chamar de qualquer thread.
     */
    public void sync() {
        ioExecutor.execute(() -> {
            List<Achievement> fresh = claim();
            if (fresh.isEmpty()) return;
            mainExecutor.execute(() -> unlocked.setValue(new Event<>(fresh)));
        });
    }

    private List<Achievement> claim() {
        // A conta inteira, e não só o id: na primeira leitura ela traz o que as versões antigas
        // guardavam, e só então o marco zero é cravado — senão os heróis trazidos virariam festa.
        AccountStore.Account account = accountStore.currentAccount();
        if (account == null) return Collections.emptyList();
        List<Integer> heroIds = new ArrayList<>();
        for (CollectionStore.Entry e : collectionStore.list(account.id)) heroIds.add(e.characterId);
        LearningStore.Stats stats = learningStore.stats(account.id);
        List<AchievementProgress> progress = Achievements.evaluate(heroIds, RosterCatalog.orNull(roster), stats);
        return headline(achievementsStore.claimNewlyUnlocked(account.id, progress));
    }

    /** As mais raras primeiro (e no máximo {@link #MAX_PER_BURST}): a leva abre com o que vale mais. */
    @VisibleForTesting
    static List<Achievement> headline(List<Achievement> fresh) {
        List<Achievement> ordered = new ArrayList<>(fresh);
        // Estável: entre conquistas da mesma raridade, vale a ordem do enum.
        ordered.sort((a, b) -> b.rarity.compareTo(a.rarity));
        return ordered.size() <= MAX_PER_BURST ? ordered : new ArrayList<>(ordered.subList(0, MAX_PER_BURST));
    }
}
