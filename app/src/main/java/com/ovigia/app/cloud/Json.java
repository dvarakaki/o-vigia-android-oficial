package com.ovigia.app.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Leitura tolerante da árvore JSON da API: campo ausente, nulo ou de outro tipo
 * vira o valor padrão em vez de exceção.
 */
public final class Json {

    @Nullable
    public static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    @Nullable
    public static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    /** Os itens de {@code array} que são objetos. */
    public static Iterable<JsonObject> objects(JsonArray array) {
        java.util.List<JsonObject> list = new java.util.ArrayList<>();
        for (JsonElement e : array) if (e.isJsonObject()) list.add(e.getAsJsonObject());
        return list;
    }

    public static JsonArray arr(@Nullable JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    public static long longOr(JsonObject o, String key, long fallback) {
        JsonElement e = o.get(key);
        try {
            return e == null || !e.isJsonPrimitive() ? fallback : e.getAsLong();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static int intOr(JsonObject o, String key, int fallback) {
        return (int) longOr(o, key, fallback);
    }

    public static double doubleOr(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);
        try {
            return e == null || !e.isJsonPrimitive() ? fallback : e.getAsDouble();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    /** Uma data da API (ISO-8601) em millis, ou 0 se ausente ou ilegível. */
    public static long millis(JsonObject o, String key) {
        String text = str(o, key);
        if (text == null) return 0;
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /** Millis no formato de data da API. */
    public static String iso(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    private Json() { }
}
