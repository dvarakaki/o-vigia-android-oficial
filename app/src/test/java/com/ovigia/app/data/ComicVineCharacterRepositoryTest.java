package com.ovigia.app.data;

import com.ovigia.app.cloud.FakeCloud;
import com.google.gson.JsonSyntaxException;
import com.ovigia.app.api.ComicVineService;
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

/** Carga do elenco quando a Comic Vine responde algo que o Gson não entende. */
public class ComicVineCharacterRepositoryTest {

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

    /** Comic Vine cuja resposta de lista estoura no conversor (JSON inesperado). */
    private static final ComicVineService UNREADABLE = new ComicVineService() {
        @Override
        public Call<ComicVineResponse<List<Character>>> listCharacters(String apiKey, String format, int limit,
                                                                       int offset, String filter, String fieldList) {
            return new ThrowingCall<>(new JsonSyntaxException("results: esperado lista"));
        }

        @Override
        public Call<ComicVineResponse<CharacterDetail>> characterDetail(int id, String apiKey, String format) {
            throw new UnsupportedOperationException();
        }
    };

    private ComicVineCharacterRepository repository() {
        return new ComicVineCharacterRepository(UNREADABLE, "chave", true,
                () -> RosterCatalog.parse(new StringReader("{\"characters\":[{\"id\":1455,\"name\":\"Iron Man\","
                        + "\"teams\":[\"avengers\"],\"powers\":[],\"villain\":0.08,\"mainstream\":true}]}")),
                () -> Collections.singletonMap(QuestionKeys.IS_VILLAIN, "É vilão?"),
                learning, () -> null, () -> cacheFile, direct, direct);
    }

    private static final class Result {
        List<CharacterProfile> profiles;
        LoadError error;
    }

    private static Result load(ComicVineCharacterRepository repository) {
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

    /** Chamada síncrona que estoura com uma exceção não verificada, como o conversor do Gson faz. */
    private static final class ThrowingCall<T> implements Call<T> {
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
