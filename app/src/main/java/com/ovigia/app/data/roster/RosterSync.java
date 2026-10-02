package com.ovigia.app.data.roster;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.ovigia.app.cloud.ApiHttp;
import com.ovigia.app.cloud.Json;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.function.Supplier;

/**
 * O elenco curado vem da API ({@code GET /v1/characters}): um personagem novo
 * (ou uma curadoria corrigida) chega sem atualizar o app. A última versão fica
 * em disco no mesmo formato do {@code assets/roster.json}, que continua valendo
 * enquanto a API nunca respondeu.
 *
 * Bloqueante (disco e rede): fora da main thread.
 */
public final class RosterSync {

    private static final String TAG = "RosterSync";

    private final ApiHttp http;
    private final Supplier<File> rosterFile;
    private final Supplier<File> etagFile;

    /** @param rosterFile onde guardar o elenco da API; o ETag fica num arquivo ao lado */
    public RosterSync(ApiHttp http, Supplier<File> rosterFile) {
        this.http = http;
        this.rosterFile = rosterFile;
        this.etagFile = () -> new File(rosterFile.get().getPath() + ".etag");
    }

    /** O elenco guardado da última vez que a API respondeu, ou {@code null}. */
    @Nullable
    public RosterCatalog saved() {
        File file = rosterFile.get();
        if (!file.isFile()) return null;
        try {
            return RosterCatalog.parse(new StringReader(AtomicFiles.readUtf8(file)));
        } catch (IOException | JsonParseException | IllegalStateException e) {
            Log.w(TAG, "Elenco guardado ilegível", e);
            return null;
        }
    }

    /**
     * Pergunta à API se o elenco mudou e guarda o novo.
     *
     * @return o elenco novo, ou {@code null} se nada mudou, a API não respondeu ou mandou algo ilegível
     */
    @Nullable
    public RosterCatalog refresh() {
        if (!http.isConfigured()) return null;
        try {
            File file = rosterFile.get();
            String etag = file.isFile() ? readEtag() : null;
            ApiHttp.Conditional response = http.getPublic("v1/characters", etag);
            if (response == null || !response.body.isJsonObject()) return null;
            String document = document(response.body.getAsJsonObject()).toString();
            RosterCatalog catalog = RosterCatalog.parse(new StringReader(document));
            AtomicFiles.writeUtf8(file, document);
            if (response.etag != null) AtomicFiles.writeUtf8(etagFile.get(), response.etag);
            return catalog;
        } catch (ApiHttp.Failure e) {
            if (!e.isOffline()) Log.w(TAG, "A API não mandou o elenco", e);
            return null;
        } catch (IOException | JsonParseException | IllegalStateException e) {
            Log.w(TAG, "Elenco da API ilegível", e);
            return null;
        }
    }

    /** Só os personagens ativos, no formato do {@code roster.json}. */
    static JsonObject document(JsonObject response) {
        JsonArray characters = new JsonArray();
        for (JsonObject c : Json.objects(Json.arr(response, "characters"))) {
            JsonElement active = c.get("active");
            if (active != null && active.isJsonPrimitive() && !active.getAsBoolean()) continue;
            JsonObject entry = new JsonObject();
            entry.add("id", c.get("id"));
            entry.add("name", c.get("name"));
            entry.add("teams", c.has("teams") ? c.get("teams") : new JsonArray());
            entry.add("powers", c.has("powers") ? c.get("powers") : new JsonArray());
            entry.add("villain", c.get("villain"));
            entry.add("mainstream", c.get("mainstream"));
            if (c.has("rarity")) entry.add("rarity", c.get("rarity"));
            characters.add(entry);
        }
        JsonObject doc = new JsonObject();
        doc.addProperty("version", Json.intOr(response, "version", 1));
        doc.add("characters", characters);
        return doc;
    }

    @Nullable
    private String readEtag() {
        File file = etagFile.get();
        try {
            return file.isFile() ? AtomicFiles.readUtf8(file).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
