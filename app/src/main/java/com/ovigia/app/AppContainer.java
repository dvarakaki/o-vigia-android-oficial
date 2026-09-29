package com.ovigia.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ovigia.app.api.ApiClient;
import com.ovigia.app.api.ComicVineService;
import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.cloud.FirebasePlayerBackend;
import com.ovigia.app.cloud.FirebaseServices;
import com.ovigia.app.cloud.PlayerBackend;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.CharacterRepository;
import com.ovigia.app.data.ComicVineCharacterRepository;
import com.ovigia.app.data.ComicVineHeroDetailRepository;
import com.ovigia.app.data.HeroDetailRepository;
import com.ovigia.app.data.QuestionTexts;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.legacy.LegacyData;
import com.ovigia.app.legacy.LegacyMigration;
import com.ovigia.app.profile.AndroidProfileImages;
import com.ovigia.app.profile.ProfileImages;
import com.ovigia.app.settings.AndroidAppCache;
import com.ovigia.app.settings.AppCache;
import com.ovigia.app.settings.AppLocales;
import com.ovigia.app.settings.SettingsStore;
import com.ovigia.app.social.AchievementsStore;
import com.ovigia.app.social.AchievementsTracker;
import com.ovigia.app.social.FirebaseSocialBackend;
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

    private static final String TAG = "AppContainer";

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

        // A conta e todos os dados do jogador moram no Firebase: nada disso fica em arquivo do app.
        FirebaseServices firebase = new FirebaseServices(app, BuildConfig.FIREBASE_EMULATOR_HOST);
        PlayerBackend player = new FirebasePlayerBackend(firebase);
        FirebaseSocialBackend social = new FirebaseSocialBackend(firebase);
        profileImages = new AndroidProfileImages(app.getContentResolver());
        // O que as versões até a 1.3 guardavam em arquivos sobe para a conta no primeiro login e some.
        // getFilesDir() toca o disco: as pastas só são resolvidas no executor de I/O.
        LegacyData legacy = new LegacyData(app::getFilesDir, app::getNoBackupFilesDir);
        accountStore = new AccountStore(player, new LegacyMigration(player, social, legacy, profileImages));
        learningStore = new LearningStore(player);
        collectionStore = new CollectionStore(player);
        achievementsStore = new AchievementsStore(player);
        // Trocou de conta (ou saiu): o que foi lido da anterior não vale mais.
        accountStore.addSessionListener(learningStore::invalidate);
        accountStore.addSessionListener(collectionStore::invalidate);
        accountStore.addSessionListener(achievementsStore::invalidate);

        ComicVineService comicVine = ApiClient.create();
        characterRepository = new ComicVineCharacterRepository(
                comicVine,
                ApiClient.apiKey(),
                ApiClient.isConfigured(),
                // O mesmo roster.json das conquistas, lido uma vez só.
                () -> {
                    RosterCatalog roster = rosterCatalog();
                    if (roster == null) throw new IOException("roster.json ilegível");
                    return roster;
                },
                () -> QuestionTexts.load(AppLocales.resources(app)),
                learningStore,
                accountStore::currentAccountId,
                () -> new File(app.getFilesDir(), "characters_cache.json"),
                ioExecutor,
                mainExecutor);

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
        achievements = new AchievementsTracker(accountStore, collectionStore, learningStore,
                achievementsStore, this::rosterCatalog, ioExecutor, mainExecutor);
        socialRepository = new SocialRepository(social, accountStore, collectionStore, learningStore,
                this::rosterCatalog, socialExecutor, System::currentTimeMillis);

        // Aquece as preferências e a conta fora da main thread antes da primeira tela que precisa delas.
        // Ler a conta primeiro garante que o que veio das versões antigas já subiu antes de qualquer
        // outra leitura (coleção, aprendizado, conquistas).
        ioExecutor.execute(settingsStore::ensureLoaded);
        ioExecutor.execute(accountStore::currentAccount);
        // Crava o marco zero das conquistas antes da primeira partida: sem isso, quem
        // atualizou o app com meia coleção pronta veria uma enxurrada de cartões.
        achievements.sync();
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
     * Elenco curado do roster.json (jogo e conquistas), lido uma vez. Bloqueante na
     * primeira chamada: fora da main thread. {@code null} se o arquivo não pôde ser lido.
     */
    public synchronized RosterCatalog rosterCatalog() {
        if (!rosterLoaded) {
            rosterLoaded = true;
            try (Reader reader = new InputStreamReader(appContext.getAssets().open("roster.json"),
                    StandardCharsets.UTF_8)) {
                rosterCatalog = RosterCatalog.parse(reader);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "roster.json ilegível", e);
                rosterCatalog = null;
            }
        }
        return rosterCatalog;
    }
}
