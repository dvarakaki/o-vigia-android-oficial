package com.ovigia.app.collection;

import com.ovigia.app.cloud.FakeCloud;

import org.junit.Before;
import org.junit.Test;

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

    @Test
    public void save_isIdempotentPerCharacter() {
        CollectionStore store = new CollectionStore(cloud);
        assertTrue(store.save(ana, 7, "Wolverine", "img"));
        assertFalse("o mesmo personagem não entra duas vezes", store.save(ana, 7, "Wolverine", "img"));

        assertTrue(store.contains(ana, 7));
        assertEquals(1, store.list(ana).size());
        assertEquals("vai para a conta", 1, cloud.heroesOf(ana).size());
    }

    @Test
    public void seenInCatalog_goesToTheAccount() {
        CollectionStore store = new CollectionStore(cloud);
        store.save(ana, 7, "Wolverine", "img");
        assertFalse(store.list(ana).get(0).seenInCatalog);

        store.markSeenInCatalog(ana, Collections.singletonList(7));

        CollectionStore otherDevice = new CollectionStore(cloud);
        assertTrue(otherDevice.list(ana).get(0).seenInCatalog);
        assertEquals("data original mantida", store.list(ana).get(0).savedAt, otherDevice.list(ana).get(0).savedAt);
    }

    @Test
    public void collections_areSeparatedByAccount() {
        CollectionStore store = new CollectionStore(cloud);
        store.save(ana, 7, "Wolverine", "img");

        cloud.actAs(bia);
        assertFalse(store.contains(bia, 7));
        assertTrue(store.list(bia).isEmpty());
        assertTrue(store.save(bia, 7, "Wolverine", "img"));
    }

    @Test
    public void list_isNewestFirst_onAnyDevice() {
        CollectionStore store = new CollectionStore(cloud);
        store.importEntry(ana, 1, "Thor", "img-1", 100);
        store.importEntry(ana, 2, "Hulk", null, 200);

        List<CollectionStore.Entry> entries = new CollectionStore(cloud).list(ana);
        assertEquals(2, entries.size());
        assertEquals("Hulk", entries.get(0).name);
        assertEquals("img-1", entries.get(1).imageUrl);
    }

    @Test
    public void invalidate_readsTheAccountAgain() {
        CollectionStore store = new CollectionStore(cloud);
        assertTrue(store.list(ana).isEmpty());
        // Outro aparelho desbloqueou um herói nesse meio-tempo.
        new CollectionStore(cloud).save(ana, 7, "Wolverine", "img");

        store.invalidate();
        assertEquals(1, store.list(ana).size());
    }
}
