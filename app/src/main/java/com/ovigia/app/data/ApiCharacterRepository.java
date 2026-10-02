package com.ovigia.app.data;

import android.util.Log;

import com.ovigia.app.api.CharacterService;
import com.ovigia.app.data.CharacterResponses.Failure;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.engine.GameEngine;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.model.Character;
import com.ovigia.app.model.ComicVineResponse;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import retrofit2.Response;

/**
 * Implementação real de {@link CharacterRepository}: busca o elenco na API do
 * O Vigia (as fichas que vieram da Comic Vine, guardadas no nosso banco) e
 * converte para o formato do motor do jogo.
 *
 * Camadas de cache:
 * 1. Memória — perfis "puros" (sem aprendizado), enquanto o processo viver.
 * 2. Disco ({@link CharacterDiskCache}) — sobrevive ao app fechar; com ele o
 *    jogo funciona offline por 7 dias, e um
 *    cache vencido ainda serve de último recurso se a rede falhar.
 *
 * Todo I/O (assets, disco, rede) roda no {@code ioExecutor}; o callback volta
 * pelo {@code mainExecutor}.
 *
 * Os textos das perguntas são lidos de novo a cada carga: se o jogador trocar o
 * idioma, a próxima partida já vem traduzida sem refazer os perfis.
 */
public final class ApiCharacterRepository implements CharacterRepository {

    private static final String TAG = "CharacterRepository";

    /** Carrega o catálogo curado (normalmente de {@code assets/roster.json}). */
    public interface RosterSource {
        RosterCatalog load() throws IOException;
    }

    private final CharacterService service;
    private final boolean apiConfigured;
    private final RosterSource rosterSource;
    private final Supplier<Map<String, String>> questionTexts;
    private final LearningStore learningStore;
    private final Supplier<String> currentAccountId;
    private final CharacterDiskCache diskCache;
    private final Executor ioExecutor;
    private final Executor mainExecutor;

    private volatile List<CharacterProfile> cachedRawProfiles;
    /** Chaves das perguntas usadas pelo elenco, na ordem em que apareceram. */
    private volatile List<String> cachedQuestionKeys;

    /**
     * @param questionTexts textos no idioma atual; chamado no executor de I/O a cada carga.
     * @param currentAccountId id da conta ativa (ou {@code null} sem sessão), consultado a cada
     *                         carga para o aprendizado misturado ser sempre o da conta logada.
     */
    public ApiCharacterRepository(CharacterService service, boolean apiConfigured,
                                        RosterSource rosterSource, Supplier<Map<String, String>> questionTexts,
                                        LearningStore learningStore, Supplier<String> currentAccountId,
                                        Supplier<File> cacheFile,
                                        Executor ioExecutor, Executor mainExecutor) {
        this.service = service;
        this.apiConfigured = apiConfigured;
        this.rosterSource = rosterSource;
        this.questionTexts = questionTexts;
        this.learningStore = learningStore;
        this.currentAccountId = currentAccountId;
        this.diskCache = new CharacterDiskCache(cacheFile);
        this.ioExecutor = ioExecutor;
        this.mainExecutor = mainExecutor;
    }

    @Override
    public void loadCharacters(Callback callback) {
        ioExecutor.execute(() -> {
            try {
                String accountId = currentAccountId.get();
                learningStore.ensureLoaded(accountId);
                Map<String, String> texts = questionTexts.get();
                if (cachedRawProfiles == null) {
                    buildRawProfiles(texts);
                }
                Map<String, String> questions = new LinkedHashMap<>();
                for (String key : cachedQuestionKeys) {
                    String text = texts.get(key);
                    if (text != null) questions.put(key, text);
                }
                List<CharacterProfile> profiles = applyLearning(cachedRawProfiles, questions, accountId);
                mainExecutor.execute(() -> callback.onSuccess(profiles, Collections.unmodifiableMap(questions)));
            } catch (Failure e) {
                mainExecutor.execute(() -> callback.onError(e.error));
            }
        });
    }

    private synchronized void buildRawProfiles(Map<String, String> texts) throws Failure {
        if (cachedRawProfiles != null) return;

        RosterCatalog roster;
        try {
            roster = rosterSource.load();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "roster.json ilegível", e);
            throw new Failure(LoadError.EMPTY_ROSTER);
        }

        List<Character> characters = diskCache.readFresh();
        // Cache de uma versão com elenco menor: os personagens novos só viriam quando ele
        // vencesse. Vale buscar de novo agora (e o cache antigo segue de reserva sem rede).
        if (characters != null && !covers(characters, roster)) characters = null;
        if (characters == null) {
            try {
                if (!apiConfigured) throw new Failure(LoadError.NOT_CONFIGURED);
                characters = fetchAll(roster);
                diskCache.write(characters);
            } catch (Failure e) {
                characters = diskCache.readStale();
                if (characters == null) throw e;
                Log.i(TAG, "Rede indisponível (" + e.error + "); usando cache vencido");
            }
        }

        CharacterMapper mapper = new CharacterMapper(roster, texts);
        Map<String, String> questionTextByKey = new LinkedHashMap<>();
        List<CharacterProfile> profiles = new ArrayList<>();
        for (Character c : characters) {
            if (roster.get(c.id) == null) continue;
            if (c.image == null || c.image.bestForHero() == null) continue;
            profiles.add(mapper.toProfile(c, questionTextByKey));
        }
        if (profiles.isEmpty()) throw new Failure(LoadError.EMPTY_ROSTER);

        cachedQuestionKeys = Collections.unmodifiableList(new ArrayList<>(questionTextByKey.keySet()));
        cachedRawProfiles = Collections.unmodifiableList(profiles);
    }

    /** O cache tem todos os personagens do elenco atual. */
    private static boolean covers(List<Character> characters, RosterCatalog roster) {
        Set<Integer> cached = new HashSet<>();
        for (Character c : characters) cached.add(c.id);
        for (RosterCatalog.Entry e : roster.entries()) {
            if (!cached.contains(e.id)) return false;
        }
        return true;
    }

    /** Busca o elenco inteiro numa chamada. Síncrona — só no executor de I/O. */
    private List<Character> fetchAll(RosterCatalog roster) throws Failure {
        Response<ComicVineResponse<List<Character>>> response;
        try {
            response = service.summaries(roster.idFilter().replace('|', ',')).execute();
        } catch (IOException e) {
            throw new Failure(LoadError.NO_CONNECTION);
        } catch (RuntimeException e) {
            // JSON inesperado, por exemplo. Solto no executor de I/O, derrubaria o app.
            Log.w(TAG, "Resposta ilegível", e);
            throw new Failure(LoadError.SERVER_ERROR);
        }
        List<Character> results = CharacterResponses.results(CharacterResponses.body(response));
        Map<Integer, Character> byId = new LinkedHashMap<>();
        for (Character c : results) byId.put(c.id, c);
        if (byId.isEmpty()) throw new Failure(LoadError.EMPTY_ROSTER);
        return new ArrayList<>(byId.values());
    }

    /**
     * Cópia dos perfis com as crenças ajustadas pelo {@link LearningStore} da conta
     * ativa. Percorre TODAS as perguntas da partida, não só os atributos que o perfil
     * já tem: se a curadoria esqueceu um poder e o jogador responde "sim" de forma
     * consistente, o aprendizado corrige a partir da crença padrão. Sem conta,
     * devolve os perfis brutos (o motor volta a usar só o prior curado).
     */
    private List<CharacterProfile> applyLearning(List<CharacterProfile> raw, Map<String, String> questions,
                                                 String accountId) {
        if (accountId == null) return raw;
        List<CharacterProfile> adjusted = new ArrayList<>(raw.size());
        for (CharacterProfile p : raw) {
            Map<String, Double> attrs = new LinkedHashMap<>(p.attributes);
            for (String key : questions.keySet()) {
                Double original = p.attributes.get(key);
                Double blended = learningStore.blendedBelief(accountId, p.id, key,
                        original != null ? original : GameEngine.MISSING_BELIEF);
                if (blended != null) attrs.put(key, blended);
            }
            adjusted.add(p.withAttributes(attrs));
        }
        return adjusted;
    }
}
