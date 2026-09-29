package com.ovigia.app.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Leitura/escrita de arquivos pequenos sem risco de corromper o conteúdo: a
 * escrita vai para um arquivo temporário e só então substitui o original com
 * um rename atômico. Se o processo morrer no meio, sobra o arquivo antigo
 * intacto — nunca um JSON pela metade.
 */
public final class AtomicFiles {

    public static String readUtf8(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    public static void writeUtf8(File file, String content) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Não foi possível criar " + parent);
        }
        File tmp = new File(parent, file.getName() + ".tmp");
        moveIntoPlace(tmp, file, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Grava {@code bytes} em {@code tmp} e o põe no lugar de {@code target} com um
     * rename atômico. Se algo falhar no meio, o temporário não fica para trás.
     */
    public static void moveIntoPlace(File tmp, File target, byte[] bytes) throws IOException {
        try {
            Files.write(tmp.toPath(), bytes);
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
    }

    private AtomicFiles() { }
}
