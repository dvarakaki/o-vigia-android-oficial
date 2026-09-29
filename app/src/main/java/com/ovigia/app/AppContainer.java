package com.ovigia.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.ovigia.app.api.ApiClient;
import com.ovigia.app.api.ComicVineService;
import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.CharacterRepository;
import com.ovigia.app.data.ComicVineCharacterRepository;
import com.ovigia.app.data.ComicVineHeroDetailRepository;
import com.ovigia.app.data.HeroDetailRepository;
import com.ovigia.app.data.QuestionTexts;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.profile.AndroidProfileImages;
import com.ovigia.app.profile.ProfileImages;
import com.ovigia.app.settings.AndroidAppCache;
import com.ovigia.app.settings.AppCache;
import com.ovigia.app.settings.AppLocales;
import com.ovigia.app.settings.SettingsStore;
import com.ovigia.app.social.AchievementsStore;
import com.ovigia.app.social.AchievementsTracker;
import com.ovigia.app.social.FirebaseSocialBackend;
import com.ovigia.app.social.KeystoreCredentialVault;
import com.ovigia.app.social.SocialRepository;
import com.ovigia.app.translation.CachedHeroTranslationRepository;
import com.ovigia.app.translation.HeroTranslationRepository;
import com.ovigia.app.translation.MlKitTextTranslator;
import com.ovigia.app.ui.Haptics;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Injeção de dependências manual: cria uma vez, no escopo do app, tudo que as
 * telas precisam. Mantém as classes de domínio livres de singletons e fáceis
 * de testar com fakes.
 */
public final class AppContainer {

    /** Uma thread só: serializa as gravações em disco e evita corridas entre elas. */
    public final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ovigia-io");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    /** Rede dos amigos online: fora do I/O de disco, para uma rede lenta não travar gravações. */
    public final ExecutorService socialExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ovigia-social");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    /** Tradução das fichas: pesada e demorada, com a menor prioridade de todas. */
    public final ExecutorService translationExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ovigia-translate");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    public final Executor mainExecutor;
    public final LearningStore learningStore;
    public final CharacterRepository characterRepository;
    public final AccountStore accountStore;
    public final CollectionStore collectionStore;
    public final ProfileImages profileImages;
    public final HeroDetailRepository heroDetailRepository;
    public final HeroTranslationRepository heroTranslationRepository;
    public final SettingsStore settingsStore;
    /** A vibração do app, na força escolhida nas configurações. */
    public final Haptics haptics;
    public final AppCache appCache;
    public final SocialRepository socialRepository;
    /** Conquistas já comemoradas, por conta. */
    public final AchievementsStore achievementsStore;
    /** Avisa quando uma conquista cai, para a festa aparecer por cima de qualquer tela. */
    public final AchievementsTracker achievements;

    private final Context appContext;
    private RosterCatalog rosterCatalog;
    private boolean rosterLoaded = false;

    AppContainer(Context context) {
        Context app = context.getApplicationContext();
        appContext = app;
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainExecutor = mainHandler::post;

        // getFilesDir() toca o disco: os caminhos só são resolvidos no executor de I/O.
        learningStore = new LearningStore(() -> new File(app.getFilesDir(), "learning_store.json"), ioExecutor);
        accountStore = new AccountStore(() -> new File(app.getFilesDir(), "accounts.json"),
                AccountStore.DEFAULT_ITERATIONS);
        ComicVineService comicVine = ApiClient.create();
        characterRepository = new ComicVineCharacterRepository(
                comicVine,
                ApiClient.apiKey(),
                ApiClient.isConfigured(),
                () -> {
                    try (Reader reader = new InputStreamReader(app.getAssets().open("roster.json"),
                            StandardCharsets.UTF_8)) {
                        return RosterCatalog.parse(reader);
                    }
                },
                () -> QuestionTexts.load(AppLocales.resources(app)),
                learningStore,
                () -> {
                    AccountStore.Account current = accountStore.currentAccount();
                    return current == null ? null : current.id;
                },
                () -> new File(app.getFilesDir(), "characters_cache.json"),
                ioExecutor,
                mainExecutor);

        collectionStore = new CollectionStore(() -> new File(app.getFilesDir(), "collection.json"));
        profileImages = new AndroidProfileImages(app.getContentResolver(),
                () -> new File(app.getFilesDir(), "profile_media"));
        Supplier<File> heroDetailsDirectory = () -> new File(app.getFilesDir(), "hero_details");
        heroDetailRepository = new ComicVineHeroDetailRepository(
                ComicVineHeroDetailRepository.remote(comicVine, ApiClient.apiKey()),
                ApiClient.isConfigured(),
                heroDetailsDirectory,
                ioExecutor,
                mainExecutor);
        Supplier<File> heroTranslationsDirectory = () -> new File(app.getFilesDir(), "hero_translations");
        heroTranslationRepository = new CachedHeroTranslationRepository(
                new MlKitTextTranslator(app),
                heroTranslationsDirectory,
                ioExecutor,
                translationExecutor,
                mainExecutor,
                System::currentTimeMillis);
        settingsStore = new SettingsStore(() -> new File(app.getFilesDir(), "settings.json"), ioExecutor);
        haptics = new Haptics(app, settingsStore);
        appCache = new AndroidAppCache(app, heroDetailsDirectory, heroTranslationsDirectory);
        achievementsStore = new AchievementsStore(() -> new File(app.getFilesDir(), "achievements.json"));
        achievements = new AchievementsTracker(accountStore, collectionStore, learningStore,
                achievementsStore, this::rosterCatalog, ioExecutor, mainExecutor);
        socialRepository = new SocialRepository(
                new FirebaseSocialBackend(app, BuildConfig.FIREBASE_EMULATOR_HOST),
                accountStore, collectionStore, learningStore, profileImages, this::rosterCatalog,
                socialExecutor, System::currentTimeMillis,
                // Fora do backup: a senha cifrada só abre neste aparelho, com a chave do Keystore dele.
                new KeystoreCredentialVault(() -> new File(app.getNoBackupFilesDir(), "pending_link.json")));

        // Aquece o aprendizado, a sessão e as preferências fora da main thread antes da primeira tela que precisa deles.
        ioExecutor.execute(learningStore::ensureLoaded);
        ioExecutor.execute(accountStore::ensureLoaded);
        ioExecutor.execute(settingsStore::ensureLoaded);
        // Crava o marco zero das conquistas antes da primeira partida: sem isso, quem
        // atualizou o app com meia coleção pronta veria uma enxurrada de cartões.
        achievements.sync();
        // Um login sem rede deixou a conexão com os amigos pela metade: termina assim que der.
        socialRepository.resumePendingQuietly();
        // Aquece o elenco durante a abertura: sem cache em disco (instalação nova, ou vencido),
        // essa é a chamada lenta à Comic Vine — feita agora, some no tempo da splash em vez de
        // atrasar a primeira pergunta. Com cache, é só uma leitura de disco a mais, barata. O
        // resultado fica em memória no repositório; a tela de perguntas que carregar depois pega
        // o mesmo elenco na hora.
        characterRepository.loadCharacters(new CharacterRepository.Callback() {
            @Override
            public void onSuccess(List<CharacterProfile> profiles, Map<String, String> questionTextByKey) {
                // Só aquecer o cache em memória — quem precisa do elenco chama loadCharacters de novo.
            }

            @Override
            public void onError(CharacterRepository.LoadError error) {
                // Ignorado: a tela que realmente precisar do elenco tenta de novo e mostra o erro.
            }
        });
    }

    /**
     * Equipes e vilania do roster.json, lidas uma vez (conquistas). Bloqueante na
     * primeira chamada: fora da main thread. {@code null} se o arquivo não pôde ser lido.
     */
    public synchronized RosterCatalog rosterCatalog() {
        if (!rosterLoaded) {
            rosterLoaded = true;
            try (Reader reader = new InputStreamReader(appContext.getAssets().open("roster.json"),
                    StandardCharsets.UTF_8)) {
                rosterCatalog = RosterCatalog.parse(reader);
            } catch (IOException | RuntimeException e) {
                rosterCatalog = null;
            }
        }
        return rosterCatalog;
    }
}
