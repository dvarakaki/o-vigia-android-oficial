package com.ovigia.app.collection;

import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.collection.CollectionStore.Unlock;

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Lendários de quem ainda não é Vigia do Infinito: esperam lacrados, fora da coleção. */
public class SealedHeroesTest {

    private static final int LEGENDARY = 42;
    private static final int COMMON = 7;

    private FakeCloud cloud;
    private String ana;
    /** Contas que já são Vigia do Infinito. */
    private final Set<String> infinite = new HashSet<>();
    private CollectionStore store;

    @Before
    public void setUp() {
        cloud = new FakeCloud();
        ana = cloud.createUser("ana@exemplo.com", "segredo#1");
        cloud.actAs(ana);
        store = newStore();
    }

    private CollectionStore newStore() {
        return new CollectionStore(cloud, (account, id) -> id != LEGENDARY || infinite.contains(account));
    }

    @Test
    public void legendary_withoutInfinite_isSealed_notCollected() {
        assertEquals(Unlock.SEALED, store.unlock(ana, LEGENDARY, "Squirrel Girl", "img"));

        assertFalse(store.contains(ana, LEGENDARY));
        assertTrue("fora da coleção", store.list(ana).isEmpty());
        assertEquals(1, store.listSealed(ana).size());
        assertEquals("Squirrel Girl", store.listSealed(ana).get(0).name);
        assertTrue("versões antigas leem heroes/: o lacrado não pode estar lá", cloud.heroesOf(ana).isEmpty());
        assertEquals(1, cloud.sealedOf(ana).size());
    }

    @Test
    public void sealingTwice_keepsOneEntry() {
        store.unlock(ana, LEGENDARY, "Squirrel Girl", "img");
        assertEquals(Unlock.SEALED, store.unlock(ana, LEGENDARY, "Squirrel Girl", "img"));
        assertEquals(1, store.listSealed(ana).size());
    }

    @Test
    public void commonHeroes_ignoreTheGate() {
        assertEquals(Unlock.NEW, store.unlock(ana, COMMON, "Wolverine", "img"));
        assertEquals(Unlock.EXISTING, store.unlock(ana, COMMON, "Wolverine", "img"));
        assertTrue(store.listSealed(ana).isEmpty());
    }

    @Test
    public void tradesAreGatedToo() {
        assertFalse("um lendário vindo de troca também fica lacrado",
                store.importEntry(ana, LEGENDARY, "Squirrel Girl", "img", 5L));
        assertEquals(1, store.listSealed(ana).size());
    }

    @Test
    public void becomingInfinite_releasesTheSealed_asNewAndUnseen() {
        store.unlock(ana, LEGENDARY, "Squirrel Girl", "img");
        assertTrue("sem a compra nada sai do lacre", store.releaseSealed(ana).isEmpty());

        infinite.add(ana);
        List<CollectionStore.Entry> released = store.releaseSealed(ana);

        assertEquals(1, released.size());
        assertTrue(store.contains(ana, LEGENDARY));
        assertFalse("o catálogo toca a revelação", store.list(ana).get(0).seenInCatalog);
        assertTrue(store.listSealed(ana).isEmpty());
        assertTrue(cloud.sealedOf(ana).isEmpty());
        assertEquals(1, cloud.heroesOf(ana).size());
        assertTrue("liberar de novo não faz nada", store.releaseSealed(ana).isEmpty());
    }

    @Test
    public void sealedFollowTheAccount_toAnotherDevice() {
        store.unlock(ana, LEGENDARY, "Squirrel Girl", "img");
        infinite.add(ana);

        List<CollectionStore.Entry> released = newStore().releaseSealed(ana);
        assertEquals("o outro aparelho lê os lacrados da conta", 1, released.size());
    }

    @Test
    public void infinite_unlocksLegendaryDirectly() {
        infinite.add(ana);
        assertEquals(Unlock.NEW, store.unlock(ana, LEGENDARY, "Squirrel Girl", "img"));
        assertTrue(store.listSealed(ana).isEmpty());
    }

    @Test
    public void deletingTheAccount_takesTheSealedAlong() throws Exception {
        store.unlock(ana, LEGENDARY, "Squirrel Girl", "img");
        cloud.signIn("ana@exemplo.com", "segredo#1");
        cloud.deleteAccount("segredo#1");
        assertTrue(cloud.sealedOf(ana).isEmpty());
    }
}
