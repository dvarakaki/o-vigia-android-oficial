package com.ovigia.app.profile;

import android.net.Uri;

import com.ovigia.app.auth.AccountStore.ImageKind;

import java.io.File;
import java.io.IOException;

/** {@link ProfileImages} sem decodificar nada: devolve um texto que identifica a imagem. */
public final class FakeProfileImages implements ProfileImages {

    private int counter = 0;
    public boolean failNextImport = false;

    @Override
    public String encode(Uri source, ImageKind kind) throws IOException {
        if (failNextImport) {
            failNextImport = false;
            throw new IOException("imagem ilegível");
        }
        return kind.name().toLowerCase() + "-" + (++counter);
    }

    @Override
    public String encodeFile(File file, ImageKind kind) throws IOException {
        if (!file.exists()) throw new IOException("sem arquivo");
        return kind.name().toLowerCase() + "-" + file.getName();
    }
}
