package com.ovigia.app.social;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.function.Supplier;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * {@link CredentialVault} cifrado com uma chave do Android Keystore.
 *
 * A chave AES nasce e fica dentro do Keystore (no hardware seguro, quando o
 * aparelho tem): o app pede para cifrar e decifrar, mas nunca enxerga a chave,
 * e ela não sai do aparelho. O arquivo mora em {@code no_backup}, fora do
 * backup do Android — e mesmo que saísse, não abriria em outro aparelho.
 *
 * O id da conta entra como dado autenticado do AES-GCM: um arquivo copiado para
 * outra conta, ou mexido, simplesmente não decifra (e é descartado).
 */
public final class KeystoreCredentialVault implements CredentialVault {

    private static final String TAG = "CredentialVault";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "ovigia.pending_link";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private final Supplier<File> fileSupplier;
    private final Gson gson = new Gson();

    /** @param fileSupplier resolvido só no executor social (toca o disco) */
    public KeystoreCredentialVault(Supplier<File> fileSupplier) {
        this.fileSupplier = fileSupplier;
    }

    @Override
    public synchronized void save(String accountId, String password) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(accountId.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            Sealed state = new Sealed();
            state.accountId = accountId;
            state.iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
            state.data = Base64.encodeToString(sealed, Base64.NO_WRAP);
            AtomicFiles.writeUtf8(fileSupplier.get(), gson.toJson(state, Sealed.class));
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            // Sem cofre, o pior caso é o de antes: a aba de amigos pede a senha.
            Log.w(TAG, "Não foi possível guardar a senha para reconectar", e);
            clear();
        }
    }

    @Nullable
    @Override
    public synchronized String read(String accountId) {
        File file = fileSupplier.get();
        if (!file.exists()) return null;
        try {
            Sealed state = gson.fromJson(AtomicFiles.readUtf8(file), Sealed.class);
            if (state == null || state.iv == null || state.data == null || !accountId.equals(state.accountId)) {
                // De outra conta (ou incompleto): o cofre só serve à conta logada.
                clear();
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS,
                    Base64.decode(state.iv, Base64.NO_WRAP)));
            cipher.updateAAD(accountId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.decode(state.data, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            // Arquivo mexido, chave perdida (dados do app limpos pela metade): não serve mais.
            Log.w(TAG, "Senha guardada ilegível; descartando", e);
            clear();
            return null;
        }
    }

    @Override
    public synchronized void clear() {
        File file = fileSupplier.get();
        if (file.exists() && !file.delete()) Log.w(TAG, "Não foi possível apagar a senha guardada");
    }

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.getKey(KEY_ALIAS, null) instanceof SecretKey) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    /** Formato do arquivo: a conta dona da senha e a senha cifrada (com o IV do GCM). */
    private static final class Sealed {
        String accountId;
        String iv;
        String data;
    }
}
