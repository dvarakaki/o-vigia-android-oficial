package com.ovigia.app.catalog;

import com.ovigia.app.cloud.FakeCloud;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.catalog.CatalogUiState.Filter;
import com.ovigia.app.catalog.CatalogUiState.Item;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.CharacterRepository;
import com.ovigia.app.data.roster.Rarity;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.learning.LearningStore.Outcome;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CatalogViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;
    private final FakeCloud cloud = new FakeCloud();
    private AccountStore accounts;
    private CollectionStore collection;
    private FakeRoster roster;

    @Before
    public void setUp() {
        accounts = new AccountStore(cloud);
        collection = new CollectionStore(cloud);
        roster = new FakeRoster();
        accounts.signUp("Ana", "ana@b.com", "segredo#1");
    }

    private CatalogViewModel started() {
        CatalogViewModel vm = new CatalogViewModel(accounts, collection, roster, direct, direct);
        vm.start();
        return vm;
    }

    private void unlock(int id, String name) {
        collection.save(accounts.currentAccount().id, id, name, "img-" + id);
    }

    @Test
    public void unlockRules_onlyWhenTheWatcherGetsItRight() {
        assertTrue(UnlockRules.unlocks(Outcome.ENGINE_GUESSED));
        assertTrue(UnlockRules.unlocks(Outcome.PICKED_FROM_ALTERNATIVES));
        assertFalse(UnlockRules.unlocks(Outcome.REVEALED_AFTER_LOSS));
        assertFalse(UnlockRules.unlocks(Outcome.LOST_UNREVEALED));
        assertFalse(UnlockRules.unlocks(null));
    }

    @Test
    public void newAccount_hasNoHeroes_butSeesTheWholeRosterLocked() {
        CatalogViewModel vm = started();
        CatalogUiState state = vm.state().getValue();
        assertEquals(CatalogUiState.Status.READY, state.status);
        assertEquals(0, state.unlockedCount);
        assertEquals(4, state.totalCount);
        assertTrue("filtro padrão mostra só os desbloqueados", state.items.isEmpty());

        vm.setFilter(Filter.ALL);
        List<Item> all = vm.state().getValue().items;
        assertEquals(4, all.size());
        for (Item item : all) {
            assertFalse(item.unlocked);
            assertNull("bloqueado não revela nome", item.name);
            assertNull(item.imageUrl);
        }
    }

    @Test
    public void unlockedHeroes_areRevealedAndCounted() {
        unlock(3, "Thor");
        unlock(1, "Hulk");
        CatalogViewModel vm = started();

        CatalogUiState state = vm.state().getValue();
        assertEquals(2, state.unlockedCount);
        assertEquals(50, state.progressPercent());
        assertEquals("ordem alfabética", "Hulk", state.items.get(0).name);
        assertEquals("thumb-3", state.items.get(1).imageUrl);
    }

    @Test
    public void search_findsOnlyUnlockedNames_ignoringAccents() {
        unlock(2, "Homem-Aranha");
        CatalogViewModel vm = started();
        vm.setFilter(Filter.ALL);

        vm.setQuery("aranha");
        assertEquals(1, vm.state().getValue().items.size());

        vm.setQuery("thor");
        assertTrue("Thor está bloqueado: a busca não o revela", vm.state().getValue().items.isEmpty());
    }

    @Test
    public void newlyUnlocked_revealsOnceThenCountsAsSeen() {
        unlock(1, "Hulk");
        CatalogUiState first = started().state().getValue();
        assertTrue("primeira visita depois do desbloqueio: carta revela", first.items.get(0).revealNow);

        CatalogUiState second = started().state().getValue();
        assertFalse("segunda visita: sem revelação", second.items.get(0).revealNow);

        unlock(3, "Thor");
        CatalogUiState third = started().state().getValue();
        assertFalse(third.items.get(0).revealNow);
        assertTrue("só o recém-desbloqueado revela", third.items.get(1).revealNow);
    }

    @Test
    public void withoutRoster_showsUnlockedFromWhatWasSaved() {
        unlock(9, "Fora do elenco");
        roster.fail = true;
        CatalogUiState state = started().state().getValue();

        assertFalse(state.hasRoster());
        assertEquals(1, state.items.size());
        assertEquals("img-9", state.items.get(0).imageUrl);
    }

    @Test
    public void sealedLegendaries_showTheirName_butNotTheImage_besideMyHeroes() {
        CollectionStore gated = new CollectionStore(cloud, (account, id) -> id != 4);
        collection = gated;
        String account = accounts.currentAccount().id;
        assertEquals(CollectionStore.Unlock.SEALED, gated.unlock(account, 4, "Visão", "img-4"));
        unlock(1, "Hulk");

        CatalogUiState state = started().state().getValue();
        assertEquals("lacrado não conta como desbloqueado", 1, state.unlockedCount);
        assertEquals("meus heróis + o lacrado", 2, state.items.size());
        Item sealed = state.items.get(1);
        assertEquals("Visão", sealed.name);
        assertTrue(sealed.sealed);
        assertFalse(sealed.unlocked);
        assertNull("a imagem é do Vigia do Infinito", sealed.imageUrl);
        assertEquals(Rarity.LEGENDARY, sealed.rarity);
    }

    @Test
    public void reload_showsReleasedHeroes() {
        boolean[] infinite = {false};
        collection = new CollectionStore(cloud, (account, id) -> id != 4 || infinite[0]);
        String account = accounts.currentAccount().id;
        collection.unlock(account, 4, "Visão", "img-4");
        CatalogViewModel vm = started();
        assertEquals(0, vm.state().getValue().unlockedCount);

        infinite[0] = true;
        collection.releaseSealed(account);
        vm.reload();
        CatalogUiState state = vm.state().getValue();
        assertEquals(1, state.unlockedCount);
        assertTrue(state.items.get(0).unlocked);
        assertTrue("entra com a revelação", state.items.get(0).revealNow);
    }

    @Test
    public void progress_isCountedPerRarity() {
        unlock(1, "Hulk");
        CatalogUiState state = started().state().getValue();

        assertEquals(Rarity.values().length, state.rarities.size());
        for (CatalogUiState.RarityProgress p : state.rarities) {
            if (p.rarity == Rarity.COMMON) {
                assertEquals(1, p.unlocked);
                assertEquals(2, p.total);
            } else if (p.rarity == Rarity.LEGENDARY) {
                assertEquals(0, p.unlocked);
                assertEquals(1, p.total);
            }
        }
    }

    @Test
    public void withoutSession_asksForLogin() {
        accounts.signOut();
        assertEquals(CatalogUiState.Status.SIGNED_OUT, started().state().getValue().status);
    }

    @Test
    public void unlocksBelongToTheAccount() {
        unlock(1, "Hulk");
        accounts.signOut();
        accounts.signUp("Bia", "bia@b.com", "segredo#2");
        assertEquals(0, started().state().getValue().unlockedCount);
    }

    private static final class FakeRoster implements CharacterRepository {
        boolean fail = false;

        @Override
        public void loadCharacters(Callback callback) {
            if (fail) {
                callback.onError(LoadError.NO_CONNECTION);
                return;
            }
            List<CharacterProfile> cast = new ArrayList<>();
            String[] names = {"Hulk", "Homem-Aranha", "Thor", "Visão"};
            for (int i = 0; i < names.length; i++) {
                int id = i + 1;
                // Visão é o lendário do elenco de teste; Thor, épico; os outros, comuns.
                Rarity rarity = id == 4 ? Rarity.LEGENDARY : id == 3 ? Rarity.EPIC : Rarity.COMMON;
                cast.add(new CharacterProfile(id, names[i], "img-" + id, "thumb-" + id, new HashMap<>(), 0, false,
                        rarity));
            }
            callback.onSuccess(cast, new HashMap<>());
        }
    }
}
