package com.ovigia.app.cloud;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * A última cópia que a API mandou de cada dado da conta, em disco: sem rede, o
 * jogo segue com ela (como o cache que o Firestore guardava no aparelho). Uma
 * pasta por conta, um arquivo JSON por dado. Thread-safe.
 */
final class LocalMirror {

    private static final String TAG = "LocalMirror";

    private final Supplier<File> root;

    /** @param root pasta onde as cópias moram; resolvida só no primeiro uso (toca o disco) */
    LocalMirror(Supplier<File> root) {
        this.root = root;
    }

    @Nullable
    synchronized JsonElement read(String uid, String name) {
        File file = file(uid, name);
        if (!file.isFile()) return null;
        try {
            return JsonParser.parseString(AtomicFiles.readUtf8(file));
        } catch (IOException | JsonParseException | IllegalStateException e) {
            Log.w(TAG, "Cópia ilegível: " + name, e);
            return null;
        }
    }

    synchronized void write(String uid, String name, JsonElement value) {
        try {
            AtomicFiles.writeUtf8(file(uid, name), value.toString());
        } catch (IOException e) {
            Log.w(TAG, "Não foi possível guardar a cópia de " + name, e);
        }
    }

    /** Apaga tudo da conta (ela foi excluída). */
    synchronized void forget(String uid) {
        File dir = dir(uid);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) Log.w(TAG, "Não foi possível apagar " + f);
            }
        }
        if (dir.exists() && !dir.delete()) Log.w(TAG, "Não foi possível apagar " + dir);
    }

    private File dir(String uid) {
        // O id é um UUID: seguro como nome de pasta. Qualquer outra coisa vira só letras e números.
        return new File(root.get(), uid.replaceAll("[^A-Za-z0-9-]", "_"));
    }

    private File file(String uid, String name) {
        return new File(dir(uid), name + ".json");
    }
}
