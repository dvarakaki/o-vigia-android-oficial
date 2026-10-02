package com.ovigia.app.collection;

import com.ovigia.app.cloud.CloudException;
import com.ovigia.app.cloud.FakeCloud;
import com.ovigia.app.cloud.PlayerBackend.Game;
import com.ovigia.app.collection.CollectionStore.Unlock;
import com.ovigia.app.learning.LearningStore.Outcome;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Lendários de quem ainda não é Vigia do Infinito: o servidor os guarda lacrados,
 * fora da coleção, e os libera quando a compra é conferida.
 */
public class SealedHeroesTest {

    private static final int LEGENDARY = 42;
    private static final int COMMON = 7;

    private FakeCloud cloud;
    private String ana;
    private CollectionStore store;

    @Before
    public void setUp() {
        cloud = new FakeCloud();
        cloud.legendary.add(LEGENDARY);
        cloud.rosterNames.put(LEGENDARY, "Squirrel Girl");
        cloud.rosterNames.put(COMMON, "Wolverine");
        cloud.paidTokens.add("token-pago");
        ana = cloud.createUser("ana@exemplo.com", "segredo#1");
        cloud.actAs(ana);
        store = newStore();
    }

    /** O mesmo palpite do app: lendário só para quem o servidor já disse que é Vigia do Infinito. */
    private CollectionStore newStore() {
        return new CollectionStore(cloud, (account, id) -> id != LEGENDARY || cloud.infiniteWatchers.contains(account));
    }

    /** O Vigia acertou {@code characterId}: a partida sobe e o app pergunta o que ela fez. */
    private Unlock play(int characterId, String name) {
        cloud.recordGame(ana, new Game(1L, characterId, Outcome.ENGINE_GUESSED, Collections.emptyList()), true);
        return store.unlock(ana, characterId, name, "img");
    }

    @Test
    public void legendary_withoutInfinite_isSealed_notCollected() {
        assertEquals(Unlock.SEALED, play(LEGENDARY, "Squirrel Girl"));

        assertFalse(store.contains(ana, LEGENDARY));
        assertTrue("fora da coleção", store.list(ana).isEmpty());
        assertEquals(1, store.listSealed(ana).size());
        assertEquals("Squirrel Girl", store.listSealed(ana).get(0).name);
        assertTrue(cloud.heroesOf(ana).isEmpty());
        assertEquals(1, cloud.sealedOf(ana).size());
    }

    @Test
    public void sealingTwice_keepsOneEntry() {
        play(LEGENDARY, "Squirrel Girl");
        assertEquals(Unlock.SEALED, play(LEGENDARY, "Squirrel Girl"));
        assertEquals(1, store.listSealed(ana).size());
    }

    @Test
    public void commonHeroes_areNeverSealed() {
        assertEquals(Unlock.NEW, play(COMMON, "Wolverine"));
        assertEquals(Unlock.EXISTING, play(COMMON, "Wolverine"));
        assertTrue(store.listSealed(ana).isEmpty());
    }

    @Test
    public void withoutTheServerVerdict_theGateGuesses() {
        // A partida não subiu (sem rede): vale o palpite do app.
        assertEquals(Unlock.SEALED, store.unlock(ana, LEGENDARY, "Squirrel Girl", "img"));
        assertEquals(Unlock.NEW, store.unlock(ana, COMMON, "Wolverine", "img"));
    }

    @Test
    public void verifiedPurchase_releasesTheSealed_asNewAndUnseen() throws CloudException {
        play(LEGENDARY, "Squirrel Girl");
        assertTrue("sem a compra nada sai do lacre", store.releaseSealed(ana).isEmpty());

        assertTrue(cloud.verifyPurchase(ana, "vigia_do_infinito", "token-pago"));
        List<CollectionStore.Entry> released = store.releaseSealed(ana);

        assertEquals(1, released.size());
        assertTrue(store.contains(ana, LEGENDARY));
        assertFalse("o catálogo toca a revelação", store.list(ana).get(0).seenInCatalog);
        assertTrue(store.listSealed(ana).isEmpty());
        assertTrue(cloud.sealedOf(ana).isEmpty());
        assertTrue("liberar de novo não faz nada", store.releaseSealed(ana).isEmpty());
    }

    @Test
    public void sealedFollowTheAccount_toAnotherDevice() throws CloudException {
        play(LEGENDARY, "Squirrel Girl");
        CollectionStore otherDevice = newStore();
        assertEquals(1, otherDevice.listSealed(ana).size());

        cloud.verifyPurchase(ana, "vigia_do_infinito", "token-pago");
        assertEquals("o outro aparelho vê o lacrado entrar", 1, otherDevice.releaseSealed(ana).size());
    }

    @Test
    public void infinite_unlocksLegendaryDirectly() throws CloudException {
        cloud.verifyPurchase(ana, "vigia_do_infinito", "token-pago");
        assertEquals(Unlock.NEW, play(LEGENDARY, "Squirrel Girl"));
        assertTrue(store.listSealed(ana).isEmpty());
    }

    @Test
    public void deletingTheAccount_takesTheSealedAlong() throws Exception {
        play(LEGENDARY, "Squirrel Girl");
        cloud.signIn("ana@exemplo.com", "segredo#1");
        cloud.deleteAccount("segredo#1");
        assertTrue(cloud.sealedOf(ana).isEmpty());
    }
}
