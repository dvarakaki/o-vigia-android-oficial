package com.ovigia.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ovigia.app.api.ApiClient;
import com.ovigia.app.api.CharacterService;
import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.cloud.ApiHttp;
import com.ovigia.app.cloud.ApiPlayerBackend;
import com.ovigia.app.cloud.CloudException;
import com.ovigia.app.cloud.PrefsSessionStore;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.collection.HeroPortraits;
import com.ovigia.app.data.CharacterRepository;
import com.ovigia.app.data.ApiCharacterRepository;
import com.ovigia.app.data.ApiHeroDetailRepository;
import com.ovigia.app.data.HeroDetailRepository;
import com.ovigia.app.data.QuestionTexts;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.data.roster.RosterSync;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.legacy.LegacyData;
import com.ovigia.app.legacy.LegacyMigration;
import com.ovigia.app.premium.Billing;
import com.ovigia.app.premium.DebugBilling;
import com.ovigia.app.premium.InfiniteWatcher;
import com.ovigia.app.premium.PlayBilling;
import com.ovigia.app.profile.AndroidProfileImages;
import com.ovigia.app.profile.ProfileImages;
import com.ovigia.app.settings.AndroidAppCache;
import com.ovigia.app.settings.AppCache;
import com.ovigia.app.settings.AppLocales;
import com.ovigia.app.settings.SettingsStore;
import com.ovigia.app.social.AchievementsStore;
import com.ovigia.app.social.AchievementsTracker;
import com.ovigia.app.social.ApiSocialBackend;
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
import java.util.Set;
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
    /** Nome e retrato de cada personagem já visto (a API guarda só o id dos heróis). */
    public final HeroPortraits heroPortraits;
    /** Se a conta logada é Vigia do Infinito (a compra que libera os lendários). */
    public final InfiniteWatcher infinite;

    private final Context appContext;
    private final RosterSync rosterSync;
    private RosterCatalog rosterCatalog;
    private boolean rosterLoaded = false;

    AppContainer(Context context) {
        Context app = context.getApplicationContext();
        appContext = app;
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainExecutor = mainHandler::post;

        // A conta e todos os dados do jogador moram na API do O Vigia; o aparelho guarda só a sessão,
        // a última cópia do que leu e a fila do que ainda não subiu.
        // getFilesDir() toca o disco: as pastas só são resolvidas fora da main thread.
        heroPortraits = new HeroPortraits(() -> new File(app.getFilesDir(), "hero_portraits.json"), ioExecutor);
        ApiHttp http = new ApiHttp(BuildConfig.OVIGIA_API_URL, ApiHttp.defaultClient(), new PrefsSessionStore(app),
                System::currentTimeMillis);
        ApiPlayerBackend player = new ApiPlayerBackend(http, heroPortraits, app::getNoBackupFilesDir,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "ovigia-sync");
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }),
                deviceName(), System::currentTimeMillis);
        ApiSocialBackend social = new ApiSocialBackend(http, player, heroPortraits, acknowledgedTrades(app),
                System::currentTimeMillis);
        rosterSync = new RosterSync(http, () -> new File(app.getFilesDir(), "roster_api.json"));
        profileImages = new AndroidProfileImages(app.getContentResolver());
        // O que as versões até a 1.3 guardavam em arquivos sobe para a conta no primeiro login e some.
        LegacyData legacy = new LegacyData(app::getFilesDir, app::getNoBackupFilesDir);
        accountStore = new AccountStore(player, new LegacyMigration(player, legacy, profileImages));
        learningStore = new LearningStore(player);
        // Lendários só entram na coleção de quem é Vigia do Infinito; os outros esperam lacrados.
        collectionStore = new CollectionStore(player, this::admitsToCollection);
        // Sem rede, a partida que acertou um lendário fica na fila: o app adivinha o lacre até ela subir.
        player.setSealsOffline((accountId, characterId) -> !admitsToCollection(accountId, characterId));
        Billing billing = BuildConfig.FAKE_BILLING ? new DebugBilling() : new PlayBilling(app, mainExecutor);
        // Quem confere a compra e diz quem é Vigia do Infinito é a API.
        infinite = new InfiniteWatcher(billing, accountStore::currentAccountId, new InfiniteWatcher.Server() {
            @Override
            public Boolean isInfinite(String accountId) throws Exception {
                return player.loadInfiniteWatcher(accountId);
            }

            @Override
            public boolean verify(String accountId, String purchaseToken) throws Exception {
                return player.verifyPurchase(accountId, Billing.PRODUCT_ID, purchaseToken);
            }

            @Override
            public boolean isInUse(Exception failure) {
                return failure instanceof CloudException
                        && ((CloudException) failure).reason == CloudException.Reason.PURCHASE_IN_USE;
            }
        }, ioExecutor, mainExecutor);
        achievementsStore = new AchievementsStore(player);
        // Trocou de conta (ou saiu): o que foi lido da anterior não vale mais.
        accountStore.addSessionListener(learningStore::invalidate);
        accountStore.addSessionListener(collectionStore::invalidate);
        accountStore.addSessionListener(achievementsStore::invalidate);
        accountStore.addSessionListener(() -> mainExecutor.execute(infinite::onSessionChanged));

        // As fichas (que vinham da Comic Vine) agora moram na API. Os caches das versões que
        // buscavam na Comic Vine são descartados: apontavam para as imagens de lá.
        ioExecutor.execute(() -> forgetComicVineCaches(app));
        CharacterService characters = ApiClient.create();
        characterRepository = new ApiCharacterRepository(
                characters,
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
                () -> new File(app.getFilesDir(), "characters_cache_v2.json"),
                ioExecutor,
                mainExecutor);

        Supplier<File> heroDetailsDirectory = () -> new File(app.getFilesDir(), "hero_details_v2");
        heroDetailRepository = new ApiHeroDetailRepository(
                ApiHeroDetailRepository.remote(characters),
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
        // Virou Vigia do Infinito (agora, ou em outro aparelho): os lendários lacrados entram na coleção.
        infinite.addInfiniteListener(accountId -> ioExecutor.execute(() -> {
            if (collectionStore.releaseSealed(accountId).isEmpty()) return;
            achievements.sync();
            socialRepository.publishQuietly();
        }));
        // A compra mora no Google Play: pergunta logo na abertura, para a primeira partida já saber.
        infinite.refresh();

        // Aquece as preferências e a conta fora da main thread antes da primeira tela que precisa delas.
        // Ler a conta primeiro garante que o que veio das versões antigas já subiu antes de qualquer
        // outra leitura (coleção, aprendizado, conquistas).
        ioExecutor.execute(settingsStore::ensureLoaded);
        ioExecutor.execute(accountStore::currentAccount);
        // O que ficou na fila (partida jogada sem rede) sobe agora e sempre que a rede voltar.
        player.uploadPendingInBackground();
        watchNetwork(app, player);
        // Elenco novo da API vale já nas conquistas e sugestões; no jogo, a partir da próxima carga.
        ioExecutor.execute(() -> {
            RosterCatalog fresh = rosterSync.refresh();
            if (fresh != null) {
                synchronized (this) {
                    rosterCatalog = fresh;
                    rosterLoaded = true;
                }
            }
        });
        // Crava o marco zero das conquistas antes da primeira partida: sem isso, quem
        // atualizou o app com meia coleção pronta veria uma enxurrada de cartões.
        achievements.sync();
        // Aquece o elenco durante a abertura: sem cache em disco (instalação nova, ou vencido),
        // essa é a chamada lenta às fichas da API — feita agora, some no tempo da splash em vez de
        // atrasar a primeira pergunta. Com cache, é só uma leitura de disco a mais, barata. O
        // resultado fica em memória no repositório; a tela de perguntas que carregar depois pega
        // o mesmo elenco na hora.
        characterRepository.loadCharacters(new CharacterRepository.Callback() {
            @Override
            public void onSuccess(List<CharacterProfile> profiles, Map<String, String> questionTextByKey) {
                // Quem precisa do elenco chama loadCharacters de novo; aqui só guarda os retratos,
                // que o catálogo e os amigos usam (a API não guarda imagem).
                heroPortraits.rememberAll(profiles);
            }

            @Override
            public void onError(CharacterRepository.LoadError error) {
                // Ignorado: a tela que realmente precisar do elenco tenta de novo e mostra o erro.
            }
        });
    }

    /** Lendários só para o Vigia do Infinito. Bloqueante (lê o roster na primeira vez): fora da main thread. */
    private boolean admitsToCollection(String accountId, int characterId) {
        RosterCatalog roster = rosterCatalog();
        return roster == null || !roster.rarityOf(characterId).requiresInfinite() || infinite.isInfinite(accountId);
    }

    /**
     * Elenco curado (jogo e conquistas), lido uma vez: o último que a API mandou ou,
     * se ela nunca respondeu, o roster.json do app. Bloqueante na primeira chamada:
     * fora da main thread. {@code null} se nenhum dos dois pôde ser lido.
     */
    public synchronized RosterCatalog rosterCatalog() {
        if (!rosterLoaded) {
            rosterLoaded = true;
            rosterCatalog = rosterSync.saved();
            if (rosterCatalog != null) return rosterCatalog;
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

    /** Apaga o elenco e as fichas que as versões anteriores baixavam da Comic Vine. */
    private static void forgetComicVineCaches(Context app) {
        File dir = app.getFilesDir();
        deleteQuietly(new File(dir, "characters_cache.json"));
        File details = new File(dir, "hero_details");
        File[] files = details.listFiles();
        if (files != null) for (File f : files) deleteQuietly(f);
        deleteQuietly(details);
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) Log.w(TAG, "Não foi possível apagar " + file);
    }

    /** O modelo do aparelho, para o jogador reconhecer a sessão. */
    private static String deviceName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String name = model.toLowerCase(java.util.Locale.ROOT).startsWith(maker.toLowerCase(java.util.Locale.ROOT))
                ? model : (maker + " " + model).trim();
        return name.isEmpty() ? "Android" : name;
    }

    /** Trocas concluídas que quem propôs já viu, por conta, nas preferências do app. */
    private static ApiSocialBackend.AcknowledgedTrades acknowledgedTrades(Context app) {
        return new ApiSocialBackend.AcknowledgedTrades() {
            private SharedPreferences prefs;

            private synchronized SharedPreferences prefs() {
                if (prefs == null) prefs = app.getSharedPreferences("acknowledged_trades", Context.MODE_PRIVATE);
                return prefs;
            }

            @Override
            public Set<String> load(String uid) {
                return ApiSocialBackend.idsFrom(prefs().getString(uid, null));
            }

            @Override
            public void save(String uid, Set<String> ids) {
                prefs().edit().putString(uid, ApiSocialBackend.idsTo(ids)).apply();
            }
        };
    }

    /** A rede voltou: o que ficou na fila sobe sem esperar a próxima jogada. */
    private static void watchNetwork(Context app, ApiPlayerBackend player) {
        ConnectivityManager connectivity = app.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        try {
            connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    player.uploadPendingInBackground();
                }
            });
        } catch (RuntimeException e) {
            // Sem acesso ao estado da rede: a fila sobe na próxima abertura ou jogada.
            Log.w(TAG, "Não foi possível acompanhar a rede", e);
        }
    }
}
