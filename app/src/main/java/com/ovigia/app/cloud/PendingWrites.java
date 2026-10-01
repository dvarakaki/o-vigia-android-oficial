package com.ovigia.app.cloud;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Gravações que ainda não chegaram à API, em disco e na ordem em que
 * aconteceram (partidas, heróis vistos, conquistas comemoradas, aprendizado
 * esquecido). Sobrevivem a fechar o app: sobem quando houver rede, como a fila
 * do Firestore fazia. Separadas por conta. Thread-safe.
 */
final class PendingWrites {

    private static final String TAG = "PendingWrites";

    private final Supplier<File> file;
    /** Conta -> operações, na ordem. {@code null} até a primeira leitura do disco. */
    private JsonObject queues;

    PendingWrites(Supplier<File> file) {
        this.file = file;
    }

    /** Põe {@code op} no fim da fila da conta (ganha um {@code id} para ser tirada depois). */
    synchronized void add(String uid, JsonObject op) {
        JsonObject copy = op.deepCopy();
        copy.addProperty("id", UUID.randomUUID().toString());
        queue(uid).add(copy);
        save();
    }

    /** Cópia da fila da conta, da mais antiga para a mais nova. */
    synchronized List<JsonObject> of(String uid) {
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement e : queue(uid)) if (e.isJsonObject()) list.add(e.getAsJsonObject().deepCopy());
        return list;
    }

    synchronized void remove(String uid, JsonObject op) {
        String id = Json.str(op, "id");
        JsonArray queue = queue(uid);
        for (int i = 0; i < queue.size(); i++) {
            JsonElement e = queue.get(i);
            if (e.isJsonObject() && id != null && id.equals(Json.str(e.getAsJsonObject(), "id"))) {
                queue.remove(i);
                break;
            }
        }
        if (queue.isEmpty()) queues().remove(uid);
        save();
    }

    /** Esquece a fila da conta (ela foi excluída). */
    synchronized void forget(String uid) {
        if (queues().remove(uid) != null) save();
    }

    private JsonArray queue(String uid) {
        JsonObject all = queues();
        JsonElement queue = all.get(uid);
        if (queue == null || !queue.isJsonArray()) {
            queue = new JsonArray();
            all.add(uid, queue);
        }
        return queue.getAsJsonArray();
    }

    private JsonObject queues() {
        if (queues != null) return queues;
        queues = new JsonObject();
        File f = file.get();
        if (f.isFile()) {
            try {
                JsonElement read = JsonParser.parseString(AtomicFiles.readUtf8(f));
                if (read.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : read.getAsJsonObject().entrySet()) {
                        if (e.getValue().isJsonArray()) queues.add(e.getKey(), e.getValue());
                    }
                }
            } catch (IOException | JsonParseException | IllegalStateException e) {
                Log.w(TAG, "Fila de gravações ilegível: começa vazia", e);
            }
        }
        return queues;
    }

    private void save() {
        try {
            AtomicFiles.writeUtf8(file.get(), queues().toString());
        } catch (IOException e) {
            Log.w(TAG, "Não foi possível guardar a fila de gravações", e);
        }
    }
}
