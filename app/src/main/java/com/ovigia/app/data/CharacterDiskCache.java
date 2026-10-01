package com.ovigia.app.data;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ovigia.app.model.Character;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Guarda o elenco (bruto, como a API devolve) em um arquivo local, pra
 * não depender de rede — nem do limite de requisições da API — toda vez que o
 * app abre. Métodos bloqueantes: chamar só a partir do executor de I/O.
 */
final class CharacterDiskCache {

    private static final String TAG = "CharacterDiskCache";
    private static final long TTL_MILLIS = TimeUnit.DAYS.toMillis(7);
    // getParameterized em vez de "new TypeToken<List<Character>>() {}": a subclasse
    // anônima depende de assinatura genérica, que o R8 remove no release.
    private static final Type LIST_TYPE = TypeToken.getParameterized(List.class, Character.class).getType();

    private final Supplier<File> fileSupplier;
    private final Gson gson = new Gson();

    CharacterDiskCache(Supplier<File> fileSupplier) {
        this.fileSupplier = fileSupplier;
    }

    /** Devolve o elenco salvo, mesmo se estiver "velho" — último recurso sem rede. */
    List<Character> readStale() {
        File file = fileSupplier.get();
        if (!file.exists()) return null;
        try {
            List<Character> characters = gson.fromJson(AtomicFiles.readUtf8(file), LIST_TYPE);
            return (characters == null || characters.isEmpty()) ? null : characters;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Falha ao ler cache em disco", e);
            return null;
        }
    }

    /** Devolve o elenco salvo só se ainda estiver dentro da validade. */
    List<Character> readFresh() {
        File file = fileSupplier.get();
        if (!file.exists() || CharacterResponses.isExpired(file, TTL_MILLIS)) return null;
        return readStale();
    }

    void write(List<Character> characters) {
        try {
            AtomicFiles.writeUtf8(fileSupplier.get(), gson.toJson(characters, LIST_TYPE));
        } catch (IOException e) {
            Log.w(TAG, "Falha ao salvar cache em disco", e);
        }
    }
}
