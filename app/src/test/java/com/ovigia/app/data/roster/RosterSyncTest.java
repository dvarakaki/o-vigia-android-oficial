package com.ovigia.app.data.roster;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** O elenco da API vira o mesmo formato do roster.json. */
public class RosterSyncTest {

    @Test
    public void keepsTheRarity_andDropsWhoLeftTheGame() throws Exception {
        JsonObject response = JsonParser.parseString("{\"version\":3,\"characters\":["
                + "{\"id\":1455,\"name\":\"Iron Man\",\"teams\":[\"avengers\"],\"powers\":[\"voo\"],"
                + "\"villain\":0.08,\"mainstream\":true,\"rarity\":\"comum\",\"active\":true},"
                + "{\"id\":11932,\"name\":\"Bushwacker\",\"teams\":[],\"powers\":[\"armas\"],"
                + "\"villain\":0.92,\"mainstream\":false,\"rarity\":\"lendario\",\"active\":true},"
                + "{\"id\":7,\"name\":\"Fora\",\"powers\":[\"voo\"],\"villain\":0.1,\"mainstream\":false,"
                + "\"rarity\":\"raro\",\"active\":false}]}").getAsJsonObject();

        RosterCatalog catalog = RosterCatalog.parse(new StringReader(RosterSync.document(response).toString()));

        assertEquals(Rarity.LEGENDARY, catalog.rarityOf(11932));
        assertEquals(Rarity.COMMON, catalog.rarityOf(1455));
        assertNull("quem saiu do jogo não entra", catalog.get(7));
    }
}
