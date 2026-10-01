package com.ovigia.app.collection;

import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.cloud.PlayerBackend;
import com.ovigia.app.learning.LearningStore;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A coleção mora na conta online: a mesma em qualquer aparelho. */
public class CollectionStoreTest {

    private FakeCloud cloud;
    private String ana;
    private String bia;

    @Before
    public void setUp() {
        cloud = new FakeCloud();
        ana = cloud.createUser("ana@exemplo.com", "segredo#1");
        bia = cloud.createUser("bia@exemplo.com", "segredo#1");
        cloud.actAs(ana);
    }

    /** O Vigia acertou numa partida: é ela que desbloqueia o herói no servidor. */
    private void win(String uid, int characterId, String name) {
        cloud.rosterNames.put(characterId, name);
        cloud.recordGame(uid, new PlayerBackend.Game(System.currentTimeMillis(), characterId,
                LearningStore.Outcome.ENGINE_GUESSED, new ArrayList<>()), true);
    }

    @Test
    public void save_isIdempotentPerCharacter() {
        CollectionStore store = new CollectionStore(cloud);
        win(ana, 7, "Wolverine");
        assertTrue(store.save(ana, 7, "Wolverine", "img"));
        assertFalse("o mesmo personagem não entra duas vezes", store.save(ana, 7, "Wolverine", "img"));

        assertTrue(store.contains(ana, 7));
        assertEquals(1, store.list(ana).size());
        assertEquals("o servidor desbloqueou com a partida", 1, cloud.heroesOf(ana).size());
    }

    @Test
    public void save_waitsForThePendingGame_beforeShowingTheHero() {
        CollectionStore store = new CollectionStore(cloud);
        win(ana, 7, "Wolverine");
        assertTrue(store.save(ana, 7, "Wolverine", "img"));
        assertEquals("a partida sobe antes (é ela que desbloqueia)", 1, cloud.flushCount);
    }

    @Test
    public void save_saysNew_evenWhenTheCollectionWasReadAfterTheGameWentUp() {
        CollectionStore store = new CollectionStore(cloud);
        win(ana, 7, "Wolverine");
        // As conquistas leram a coleção logo depois da partida subir: o herói já está lá.
        assertTrue(store.contains(ana, 7));
        assertTrue("foi esta partida que desbloqueou", store.save(ana, 7, "Wolverine", "img"));

        win(ana, 7, "Wolverine");
        assertFalse("a segunda vitória com o mesmo herói não é novidade", store.save(ana, 7, "Wolverine", "img"));
    }

    @Test
    public void seenInCatalog_goesToTheAccount() {
        CollectionStore store = new CollectionStore(cloud);
        win(ana, 7, "Wolverine");
        store.save(ana, 7, "Wolverine", "img");
        assertFalse(store.list(ana).get(0).seenInCatalog);

        store.markSeenInCatalog(ana, Collections.singletonList(7));

        CollectionStore otherDevice = new CollectionStore(cloud);
        assertTrue(otherDevice.list(ana).get(0).seenInCatalog);
        assertEquals("Wolverine", otherDevice.list(ana).get(0).name);
    }

    @Test
    public void collections_areSeparatedByAccount() {
        CollectionStore store = new CollectionStore(cloud);
        win(ana, 7, "Wolverine");
        store.save(ana, 7, "Wolverine", "img");

        cloud.actAs(bia);
        assertFalse(store.contains(bia, 7));
        assertTrue(store.list(bia).isEmpty());
        win(bia, 7, "Wolverine");
        assertTrue(store.save(bia, 7, "Wolverine", "img"));
    }

    @Test
    public void list_isNewestFirst_onAnyDevice() {
        cloud.grantHero(ana, new PlayerBackend.Hero(1, "Thor", "img-1", 100, false));
        cloud.grantHero(ana, new PlayerBackend.Hero(2, "Hulk", null, 200, false));

        List<CollectionStore.Entry> entries = new CollectionStore(cloud).list(ana);
        assertEquals(2, entries.size());
        assertEquals("Hulk", entries.get(0).name);
        assertEquals("img-1", entries.get(1).imageUrl);
    }

    @Test
    public void importEntry_showsAHeroTheServerAlreadyGave() {
        CollectionStore store = new CollectionStore(cloud);
        assertTrue(store.list(ana).isEmpty());
        // Uma troca aceita: o servidor deu o herói; a coleção mostra sem ler a conta de novo.
        assertTrue(store.importEntry(ana, 2, "Hulk", null, 200));
        assertFalse(store.importEntry(ana, 2, "Hulk", null, 300));
        assertEquals(200, store.list(ana).get(0).savedAt);
    }

    @Test
    public void invalidate_readsTheAccountAgain() {
        CollectionStore store = new CollectionStore(cloud);
        assertTrue(store.list(ana).isEmpty());
        // Outro aparelho desbloqueou um herói nesse meio-tempo.
        win(ana, 7, "Wolverine");

        assertTrue(store.list(ana).isEmpty());
        store.invalidate();
        assertEquals(1, store.list(ana).size());
    }
}
