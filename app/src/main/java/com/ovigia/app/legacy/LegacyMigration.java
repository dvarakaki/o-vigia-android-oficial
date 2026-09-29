package com.ovigia.app.legacy;

import android.util.Log;

import androidx.annotation.Nullable;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.auth.AccountStore.ImageKind;
import com.ovigia.app.cloud.CloudException;
import com.ovigia.app.cloud.PlayerBackend;
import com.ovigia.app.profile.ProfileImages;
import com.ovigia.app.social.PublicProfile;
import com.ovigia.app.social.SocialBackend;
import com.ovigia.app.social.SocialException;
import com.ovigia.app.social.UserCard;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Leva para a conta online o que as versões até a 1.3 guardavam fora dela.
 *
 * <ol>
 *   <li><b>Conta sem dados no servidor:</b> nasce do que as versões antigas
 *   publicavam para os amigos ({@code users/{uid}} e {@code profiles/{uid}}) —
 *   nome, @usuario, foto, banner, bio, heróis e os totais de partidas.</li>
 *   <li><b>Arquivos deste aparelho:</b> se há uma conta antiga ligada a esta
 *   (ou com o mesmo e-mail), os heróis que faltam entram, a memória do Vigia e o
 *   histórico sobem inteiros, as conquistas comemoradas continuam comemoradas e
 *   foto, banner e bio preenchem o que estiver vazio. Aí os arquivos dela somem.</li>
 * </ol>
 *
 * Nada que já está na conta online é sobrescrito. Sem rede, nada acontece: a
 * próxima abertura tenta de novo. Bloqueante: fora da main thread.
 */
public final class LegacyMigration {

    private static final String TAG = "LegacyMigration";

    private final PlayerBackend backend;
    @Nullable private final SocialBackend social;
    @Nullable private final LegacyData legacy;
    @Nullable private final ProfileImages images;

    /**
     * @param social perfil publicado pelas versões antigas; {@code null} ignora essa fonte
     * @param legacy arquivos das versões antigas; {@code null} ignora essa fonte
     * @param images para converter as fotos antigas; {@code null} deixa as fotos de fora
     */
    public LegacyMigration(PlayerBackend backend, @Nullable SocialBackend social, @Nullable LegacyData legacy,
                           @Nullable ProfileImages images) {
        this.backend = backend;
        this.social = social;
        this.legacy = legacy;
        this.images = images;
    }

    /** Sem fontes antigas: só cria o perfil da conta nova. */
    public static LegacyMigration none(PlayerBackend backend) {
        return new LegacyMigration(backend, null, null, null);
    }

    /** A conta antiga deste aparelho com esse e-mail e essa senha (para criar a conta online dela). */
    @Nullable
    public LegacyData.Account findLocal(String email, String password) {
        return legacy == null ? null : legacy.findWithPassword(email, password);
    }

    /**
     * Os dados da conta, já com o que havia nas fontes antigas.
     *
     * @param fallbackName nome para uma conta que não tem nenhum (o do cadastro, por exemplo)
     * @throws CloudException {@link CloudException.Reason#OFFLINE} sem rede e sem cópia no aparelho
     */
    public PlayerBackend.Account ensure(String uid, @Nullable String email, @Nullable String fallbackName)
            throws CloudException {
        PlayerBackend.Account account = backend.loadAccount(uid);
        LegacyData.Account old = legacy == null ? null : legacy.find(uid, email);
        if (account == null) {
            account = bootstrap(uid, email, firstNonBlank(fallbackName, old == null ? null : old.name));
        }
        if (old != null) account = mergeLocal(uid, account, old);
        return account;
    }

    // ---------------------------------------------------------------- servidor antigo

    private PlayerBackend.Account bootstrap(String uid, @Nullable String email, @Nullable String fallbackName)
            throws CloudException {
        UserCard card = null;
        PublicProfile published = null;
        if (social != null) {
            try {
                card = social.loadCard(uid);
                if (card != null) published = social.loadProfile(uid);
            } catch (SocialException e) {
                // Sem rede agora: melhor não criar a conta pela metade — a próxima abertura tenta de novo.
                if (e.error == SocialException.Error.OFFLINE) throw new CloudException(CloudException.Reason.OFFLINE, e);
            } catch (RuntimeException e) {
                Log.w(TAG, "Perfil antigo ilegível", e);
            }
        }
        String name = firstNonBlank(card == null ? null : card.name, fallbackName, emailPrefix(email));
        PlayerBackend.Account created = new PlayerBackend.Account(clip(name), published == null ? null : published.bio,
                card == null ? null : card.avatar, published == null ? null : published.banner,
                card == null ? null : card.username, null);
        backend.saveAccount(uid, created);
        if (published != null) {
            for (PublicProfile.Hero hero : published.heroes) {
                // Já estavam no catálogo antes: a revelação animada não toca de novo.
                backend.saveHero(uid, new PlayerBackend.Hero(hero.characterId, hero.name, hero.imageUrl,
                        hero.unlockedAt, true));
            }
            if (published.gamesPlayed > 0) {
                backend.importLearning(uid, new PlayerBackend.Learning(published.gamesPlayed, published.engineWins,
                        new HashMap<>(), new HashMap<>()), new ArrayList<>());
            }
        }
        return created;
    }

    // ---------------------------------------------------------------- arquivos do aparelho

    private PlayerBackend.Account mergeLocal(String uid, PlayerBackend.Account account, LegacyData.Account old)
            throws CloudException {
        Set<Integer> have = new HashSet<>();
        for (PlayerBackend.Hero h : backend.loadHeroes(uid)) have.add(h.characterId);
        for (LegacyData.Hero h : legacy.heroes(old.id)) {
            if (have.add(h.characterId)) {
                backend.saveHero(uid, new PlayerBackend.Hero(h.characterId, h.name, h.imageUrl,
                        h.savedAt > 0 ? h.savedAt : System.currentTimeMillis(), h.seen));
            }
        }

        LegacyData.Learning learning = legacy.learning(old.id);
        if (!learning.isEmpty()) {
            List<PlayerBackend.Game> games = new ArrayList<>();
            for (LegacyData.Game g : learning.games) {
                games.add(new PlayerBackend.Game(g.timestamp, g.characterId, g.outcome, g.answers));
            }
            backend.importLearning(uid, new PlayerBackend.Learning(learning.gamesPlayed, learning.engineWins,
                    learning.picks, learning.beliefs), games);
        }

        PlayerBackend.Account merged = account;
        Set<String> celebrated = legacy.celebrated(old.id);
        if (celebrated != null) {
            backend.addCelebrated(uid, celebrated, true);
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
        if (merged.username == null && old.username != null && uid.equals(old.cloudUid)) {
            merged = merged.withUsername(old.username);
            changed = true;
        }
        if (changed) backend.saveAccount(uid, merged);

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
