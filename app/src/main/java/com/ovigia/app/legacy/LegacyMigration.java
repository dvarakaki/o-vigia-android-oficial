package com.ovigia.app.legacy;

import android.util.Log;

import androidx.annotation.Nullable;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.auth.AccountStore.ImageKind;
import com.ovigia.app.cloud.CloudException;
import com.ovigia.app.cloud.PlayerBackend;
import com.ovigia.app.profile.ProfileImages;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Leva para a conta online o que as versões até a 1.3 guardavam fora dela.
 *
 * Se há neste aparelho uma conta antiga ligada a esta (ou com o mesmo e-mail),
 * os heróis que faltam entram, a memória do Vigia e o histórico sobem inteiros,
 * as conquistas comemoradas continuam comemoradas e foto, banner e bio
 * preenchem o que estiver vazio. Aí os arquivos dela somem.
 *
 * (O que as versões 1.x publicavam no Firebase — perfil, heróis, amigos,
 * trocas — é levado para a API de uma vez, no servidor, pela importação do
 * {@code ms-o-vigia}.)
 *
 * Nada que já está na conta online é sobrescrito. Sem rede, nada acontece: a
 * próxima abertura tenta de novo. Bloqueante: fora da main thread.
 */
public final class LegacyMigration {

    private static final String TAG = "LegacyMigration";

    private final PlayerBackend backend;
    @Nullable private final LegacyData legacy;
    @Nullable private final ProfileImages images;

    /**
     * @param legacy arquivos das versões antigas; {@code null} ignora essa fonte
     * @param images para converter as fotos antigas; {@code null} deixa as fotos de fora
     */
    public LegacyMigration(PlayerBackend backend, @Nullable LegacyData legacy, @Nullable ProfileImages images) {
        this.backend = backend;
        this.legacy = legacy;
        this.images = images;
    }

    /** Sem fontes antigas: só lê a conta. */
    public static LegacyMigration none(PlayerBackend backend) {
        return new LegacyMigration(backend, null, null);
    }

    /** A conta antiga deste aparelho com esse e-mail e essa senha (para criar a conta online dela). */
    @Nullable
    public LegacyData.Account findLocal(String email, String password) {
        return legacy == null ? null : legacy.findWithPassword(email, password);
    }

    /**
     * Os dados da conta, já com o que havia nos arquivos antigos.
     *
     * @param fallbackName nome para uma conta que não tem nenhum (o do cadastro, por exemplo)
     * @throws CloudException {@link CloudException.Reason#OFFLINE} sem rede e sem cópia no aparelho
     */
    public PlayerBackend.Account ensure(String uid, @Nullable String email, @Nullable String fallbackName)
            throws CloudException {
        PlayerBackend.Account account = backend.loadAccount(uid);
        LegacyData.Account old = legacy == null ? null : legacy.find(uid, email);
        if (account == null) {
            // A API cria a conta já com nome; isto só acontece com um servidor que não tem os dados dela.
            String name = firstNonBlank(fallbackName, old == null ? null : old.name, emailPrefix(email));
            account = backend.saveAccount(uid, new PlayerBackend.Account(clip(name), null, null, null, null, null));
        }
        if (old != null) account = mergeLocal(uid, account, old);
        return account;
    }

    // ---------------------------------------------------------------- arquivos do aparelho

    private PlayerBackend.Account mergeLocal(String uid, PlayerBackend.Account account, LegacyData.Account old)
            throws CloudException {
        List<PlayerBackend.Hero> heroes = new ArrayList<>();
        for (LegacyData.Hero h : legacy.heroes(old.id)) {
            heroes.add(new PlayerBackend.Hero(h.characterId, h.name, h.imageUrl,
                    h.savedAt > 0 ? h.savedAt : System.currentTimeMillis(), h.seen));
        }
        LegacyData.Learning learning = legacy.learning(old.id);
        List<PlayerBackend.Game> games = new ArrayList<>();
        for (LegacyData.Game g : learning.games) {
            games.add(new PlayerBackend.Game(g.timestamp, g.characterId, g.outcome, g.answers));
        }
        Set<String> celebrated = legacy.celebrated(old.id);
        PlayerBackend.LegacyImport data = new PlayerBackend.LegacyImport(heroes,
                new PlayerBackend.Learning(learning.gamesPlayed, learning.engineWins, learning.picks,
                        learning.beliefs), games, celebrated);
        // Os heróis que já estão na conta ficam com a data de lá; o servidor só põe os que faltam.
        if (!data.isEmpty()) backend.importLegacy(uid, data);

        PlayerBackend.Account merged = account;
        if (celebrated != null) {
            List<String> union = new ArrayList<>(account.celebrated == null ? new ArrayList<>() : account.celebrated);
            for (String id : celebrated) if (!union.contains(id)) union.add(id);
            merged = merged.withCelebrated(union);
        }

        boolean changed = false;
        if (merged.name.trim().isEmpty() && !old.name.trim().isEmpty()) {
            merged = merged.withName(clip(old.name), merged.bio);
            changed = true;
        }
        if (merged.bio == null && old.bio != null) {
            merged = merged.withName(merged.name, old.bio);
            changed = true;
        }
        if (merged.avatar == null) {
            String avatar = encode(legacy.image(old.avatarFile), ImageKind.AVATAR);
            if (avatar != null) {
                merged = merged.withAvatar(avatar);
                changed = true;
            }
        }
        if (merged.banner == null) {
            String banner = encode(legacy.image(old.bannerFile), ImageKind.BANNER);
            if (banner != null) {
                merged = merged.withBanner(banner);
                changed = true;
            }
        }
        if (changed) merged = backend.saveAccount(uid, merged).withCelebrated(merged.celebrated);

        legacy.remove(old);
        return merged;
    }

    @Nullable
    private String encode(@Nullable File file, ImageKind kind) {
        if (file == null || images == null) return null;
        try {
            return images.encodeFile(file, kind);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Imagem antiga ilegível", e);
            return null;
        }
    }

    // ---------------------------------------------------------------- apoio

    private static String clip(String name) {
        String clean = name == null ? "" : name.trim();
        return clean.length() <= AccountStore.MAX_NAME_LENGTH ? clean
                : clean.substring(0, AccountStore.MAX_NAME_LENGTH).trim();
    }

    private static String firstNonBlank(@Nullable String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return "";
    }

    @Nullable
    private static String emailPrefix(@Nullable String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
