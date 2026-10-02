package com.ovigia.app.data;

import com.ovigia.app.cloud.FakeCloud;
import com.google.gson.JsonSyntaxException;
import com.ovigia.app.api.CharacterService;
import com.ovigia.app.data.CharacterRepository.LoadError;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.learning.LearningStore;
import com.ovigia.app.model.Character;
import com.ovigia.app.model.CharacterDetail;
import com.ovigia.app.model.ComicVineResponse;
import com.ovigia.app.model.ImageData;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import okhttp3.Request;
import okio.Timeout;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Carga do elenco: respostas que o Gson não entende e o cache em disco de uma versão anterior. */
public class ApiCharacterRepositoryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;
    private File cacheFile;
    private LearningStore learning;

    @Before
    public void setUp() {
        cacheFile = new File(tmp.getRoot(), "characters_cache.json");
        learning = new LearningStore(new FakeCloud());
    }

    /** API cuja resposta de lista estoura no conversor (JSON inesperado). */
    private static final CharacterService UNREADABLE = new CharacterService() {
        @Override
        public Call<ComicVineResponse<List<Character>>> summaries(String ids) {
            return new ThrowingCall<>(new JsonSyntaxException("results: esperado lista"));
        }

        @Override
        public Call<ComicVineResponse<CharacterDetail>> detail(int id) {
            throw new UnsupportedOperationException();
        }
    };

    private ApiCharacterRepository repository() {
        return repository(UNREADABLE);
    }

    private ApiCharacterRepository repository(CharacterService service) {
        return new ApiCharacterRepository(service, true,
                () -> RosterCatalog.parse(new StringReader("{\"characters\":[{\"id\":1455,\"name\":\"Iron Man\","
                        + "\"teams\":[\"avengers\"],\"powers\":[],\"villain\":0.08,\"mainstream\":true}]}")),
                () -> Collections.singletonMap(QuestionKeys.IS_VILLAIN, "É vilão?"),
                learning, () -> null, () -> cacheFile, direct, direct);
    }

    private static final class Result {
        List<CharacterProfile> profiles;
        LoadError error;
    }

    private static Result load(ApiCharacterRepository repository) {
        Result result = new Result();
        repository.loadCharacters(new CharacterRepository.Callback() {
            @Override
            public void onSuccess(List<CharacterProfile> profiles, Map<String, String> questionTextByKey) {
                result.profiles = profiles;
            }

            @Override
            public void onError(LoadError error) {
                result.error = error;
            }
        });
        return result;
    }

    @Test
    public void unreadableResponse_withoutCache_isAServerError_notACrash() {
        Result result = load(repository());
        assertEquals(LoadError.SERVER_ERROR, result.error);
        assertNull(result.profiles);
    }

    @Test
    public void unreadableResponse_fallsBackToExpiredCache() {
        Character ironMan = new Character();
        ironMan.id = 1455;
        ironMan.name = "Iron Man";
        ironMan.image = new ImageData();
        ironMan.image.superUrl = "https://exemplo.com/iron-man.jpg";
        new CharacterDiskCache(() -> cacheFile).write(Collections.singletonList(ironMan));
        // Vencido: a carga tenta a rede antes de usar a cópia.
        cacheFile.setLastModified(0);

        Result result = load(repository());
        assertNull(result.error);
        assertEquals(1, result.profiles.size());
        assertEquals("Iron Man", result.profiles.get(0).name);
    }

    @Test
    public void wholeRosterComesInOneCall_withTheRosterIds() {
        String[] asked = new String[1];
        Character ironMan = new Character();
        ironMan.id = 1455;
        ironMan.name = "Iron Man";
        ironMan.image = new ImageData();
        ironMan.image.superUrl = "https://api.exemplo.com/v1/characters/media/abc";
        ComicVineResponse<List<Character>> body = new ComicVineResponse<>();
        body.statusCode = 1;
        body.results = Collections.singletonList(ironMan);
        CharacterService api = new CharacterService() {
            @Override
            public Call<ComicVineResponse<List<Character>>> summaries(String ids) {
                asked[0] = ids;
                return new FixedCall<>(Response.success(body));
            }

            @Override
            public Call<ComicVineResponse<CharacterDetail>> detail(int id) {
                throw new UnsupportedOperationException();
            }
        };

        Result result = load(repository(api));
        assertNull(result.error);
        assertEquals("1455", asked[0]);
        assertEquals("https://api.exemplo.com/v1/characters/media/abc", result.profiles.get(0).imageUrl);
    }

    private static Character character(int id, String name) {
        Character c = new Character();
        c.id = id;
        c.name = name;
        c.image = new ImageData();
        c.image.superUrl = "https://exemplo.com/" + id + ".jpg";
        return c;
    }

    @Test
    public void freshCacheFromASmallerRoster_isFetchedAgain() {
        // Cache de ontem, de quando o elenco só tinha o Homem de Ferro.
        new CharacterDiskCache(() -> cacheFile).write(Collections.singletonList(character(1455, "Iron Man")));
        int[] fetches = {0};
        CharacterService api = new CharacterService() {
            @Override
            public Call<ComicVineResponse<List<Character>>> summaries(String ids) {
                fetches[0]++;
                ComicVineResponse<List<Character>> body = new ComicVineResponse<>();
                body.statusCode = 1;
                body.results = Arrays.asList(character(1455, "Iron Man"), character(2268, "Thor"));
                return new FixedCall<>(Response.success(body));
            }

            @Override
            public Call<ComicVineResponse<CharacterDetail>> detail(int id) {
                throw new UnsupportedOperationException();
            }
        };
        ApiCharacterRepository repository = new ApiCharacterRepository(api, true,
                () -> RosterCatalog.parse(new StringReader("{\"characters\":["
                        + "{\"id\":1455,\"powers\":[\"voo\"]},{\"id\":2268,\"powers\":[\"voo\"]}]}")),
                () -> Collections.singletonMap(QuestionKeys.IS_VILLAIN, "É vilão?"),
                learning, () -> null, () -> cacheFile, direct, direct);

        Result result = load(repository);
        assertNull(result.error);
        assertEquals("o personagem novo do elenco aparece sem esperar o cache vencer", 2, result.profiles.size());
        assertEquals(1, fetches[0]);
    }

    /** Chamada síncrona que devolve sempre a mesma resposta. */
    private static final class FixedCall<T> extends ThrowingCall<T> {
        private final Response<T> response;

        FixedCall(Response<T> response) {
            super(null);
            this.response = response;
        }

        @Override
        public Response<T> execute() {
            return response;
        }
    }

    /** Chamada síncrona que estoura com uma exceção não verificada, como o conversor do Gson faz. */
    private static class ThrowingCall<T> implements Call<T> {
        private final RuntimeException failure;

        ThrowingCall(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Response<T> execute() {
            throw failure;
        }

        @Override
        public void enqueue(Callback<T> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isExecuted() {
            return false;
        }

        @Override
        public void cancel() { }

        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public Call<T> clone() {
            return new ThrowingCall<>(failure);
        }

        @Override
        public Request request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Timeout timeout() {
            return Timeout.NONE;
        }
    }
}
