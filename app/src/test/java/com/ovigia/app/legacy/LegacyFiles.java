package com.ovigia.app.legacy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Monta, numa pasta de teste, os arquivos que as versões até a 1.3 gravavam —
 * no mesmo formato, inclusive com os nomes de campo que o R8 deu nas 1.1.0 a 1.2.2.
 */
public final class LegacyFiles {

    public final File filesDir;
    public final File noBackupDir;

    public LegacyFiles(File root) {
        filesDir = new File(root, "files");
        noBackupDir = new File(root, "no_backup");
        filesDir.mkdirs();
        noBackupDir.mkdirs();
    }

    public LegacyData data() {
        return new LegacyData(() -> filesDir, () -> noBackupDir);
    }

    /** accounts.json com uma conta local (senha com hash PBKDF2, como o AccountStore antigo gravava). */
    public void account(String id, String name, String email, String password, String cloudUid, String username,
                        String avatarFile) throws IOException {
        byte[] salt = "sal-de-teste-123".getBytes(StandardCharsets.UTF_8);
        String hash = Base64.getEncoder().encodeToString(pbkdf2(password, salt, 1000));
        write("accounts.json", "{\"accounts\":[{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"email\":\"" + email
                + "\",\"salt\":\"" + Base64.getEncoder().encodeToString(salt) + "\",\"hash\":\"" + hash
                + "\",\"iterations\":1000,\"createdAt\":1,\"bio\":\"Bio antiga\""
                + (avatarFile != null ? ",\"avatarFile\":\"" + avatarFile + "\"" : "")
                + (cloudUid != null ? ",\"cloudUid\":\"" + cloudUid + "\"" : "")
                + (username != null ? ",\"username\":\"" + username + "\"" : "")
                + "}],\"currentAccountId\":\"" + id + "\"}");
    }

    public void collection(String accountId, String entriesJson) throws IOException {
        write("collection.json", "{\"byAccount\":{\"" + accountId + "\":" + entriesJson + "}}");
    }

    public void learning(String accountId, String blockJson) throws IOException {
        write("learning_store.json", "{\"byAccount\":{\"" + accountId + "\":" + blockJson + "}}");
    }

    public void achievements(String accountId, String idsJson) throws IOException {
        write("achievements.json", "{\"byAccount\":{\"" + accountId + "\":" + idsJson + "}}");
    }

    public void image(String fileName) throws IOException {
        File media = new File(filesDir, "profile_media");
        media.mkdirs();
        Files.write(new File(media, fileName).toPath(), new byte[]{1, 2, 3});
    }

    public boolean anyLeft() {
        for (String name : new String[]{"accounts.json", "collection.json", "learning_store.json",
                "achievements.json", "profile_media"}) {
            if (new File(filesDir, name).exists()) return true;
        }
        return false;
    }

    private void write(String name, String json) throws IOException {
        Files.write(new File(filesDir, name).toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }
}
