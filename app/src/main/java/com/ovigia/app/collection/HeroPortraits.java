package com.ovigia.app.collection;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Nome e retrato de cada personagem que o app já viu, guardados no aparelho.
 *
 * A API guarda só o id dos heróis de cada conta (o retrato é da Comic Vine):
 * é daqui que o catálogo, o perfil e as trocas tiram a imagem — inclusive sem
 * rede e antes de o elenco carregar.
 *
 * Leituras tocam o disco na primeira vez: fora da main thread. Thread-safe.
 */
public final class HeroPortraits {

    private static final String TAG = "HeroPortraits";

    private final Supplier<File> file;
    private final Executor ioExecutor;
    private final AtomicBoolean saveQueued = new AtomicBoolean(false);
    /** id -> [nome, url]. {@code null} até a primeira leitura do disco. */
    private Map<Integer, String[]> byId;

    public HeroPortraits(Supplier<File> file, Executor ioExecutor) {
        this.file = file;
        this.ioExecutor = ioExecutor;
    }

    @Nullable
    public synchronized String imageUrl(int characterId) {
        String[] entry = entries().get(characterId);
        return entry == null ? null : entry[1];
    }

    @Nullable
    public synchronized String name(int characterId) {
        String[] entry = entries().get(characterId);
        return entry == null ? null : entry[0];
    }

    /** Guarda o que se sabe do personagem; valores vazios não apagam o que já estava. */
    public void remember(int characterId, @Nullable String name, @Nullable String imageUrl) {
        boolean changed;
        synchronized (this) {
            changed = put(characterId, name, imageUrl);
        }
        if (changed) scheduleSave();
    }

    /** O elenco carregou (Comic Vine ou cache): todos os retratos dele. */
    public void rememberAll(Collection<CharacterProfile> profiles) {
        boolean changed = false;
        synchronized (this) {
            for (CharacterProfile p : profiles) changed |= put(p.id, p.name, p.imageUrl);
        }
        if (changed) scheduleSave();
    }

    private boolean put(int characterId, @Nullable String name, @Nullable String imageUrl) {
        String[] old = entries().get(characterId);
        String newName = isBlank(name) ? (old == null ? null : old[0]) : name;
        String newUrl = isBlank(imageUrl) ? (old == null ? null : old[1]) : imageUrl;
        if (old != null && eq(old[0], newName) && eq(old[1], newUrl)) return false;
        if (newName == null && newUrl == null) return false;
        entries().put(characterId, new String[]{newName, newUrl});
        return true;
    }

    /** Várias mudanças seguidas (o elenco inteiro) viram uma gravação só. */
    private void scheduleSave() {
        if (!saveQueued.compareAndSet(false, true)) return;
        ioExecutor.execute(() -> {
            saveQueued.set(false);
            JsonObject json = new JsonObject();
            synchronized (this) {
                for (Map.Entry<Integer, String[]> e : entries().entrySet()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", e.getValue()[0]);
                    entry.addProperty("image", e.getValue()[1]);
                    json.add(String.valueOf(e.getKey()), entry);
                }
            }
            try {
                AtomicFiles.writeUtf8(file.get(), json.toString());
            } catch (IOException e) {
                Log.w(TAG, "Não foi possível guardar os retratos", e);
            }
        });
    }

    private Map<Integer, String[]> entries() {
        if (byId != null) return byId;
        byId = new HashMap<>();
        File f = file.get();
        if (!f.isFile()) return byId;
        try {
            JsonElement root = JsonParser.parseString(AtomicFiles.readUtf8(f));
            if (!root.isJsonObject()) return byId;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject entry = e.getValue().getAsJsonObject();
                byId.put(Integer.parseInt(e.getKey()), new String[]{text(entry, "name"), text(entry, "image")});
            }
        } catch (IOException | JsonParseException | IllegalStateException | NumberFormatException e) {
            Log.w(TAG, "Retratos ilegíveis: começam vazios", e);
        }
        return byId;
    }

    @Nullable
    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }
}
