package com.ovigia.app.data;

import com.ovigia.app.data.CharacterRepository.LoadError;
import com.ovigia.app.model.CharacterDetail;
import com.ovigia.app.model.ComicVineResponse;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Cache em disco e tratamento das respostas da Comic Vine, sem rede. */
public class ApiHeroDetailRepositoryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;
    private File dir;
    private int calls;

    @Before
    public void setUp() {
        dir = new File(tmp.getRoot(), "hero_details");
        calls = 0;
    }

    private static Response<ComicVineResponse<CharacterDetail>> ok(String name) {
        ComicVineResponse<CharacterDetail> body = new ComicVineResponse<>();
        body.statusCode = 1;
        body.results = new CharacterDetail();
        body.results.id = 1488;
        body.results.name = name;
        return Response.success(body);
    }

    private ApiHeroDetailRepository repo(ApiHeroDetailRepository.Remote remote) {
        return new ApiHeroDetailRepository(id -> {
            calls++;
            return remote.fetch(id);
        }, true, () -> dir, direct, direct);
    }

    private static final class Result {
        CharacterDetail detail;
        boolean offline;
        LoadError error;
    }

    private static Result load(ApiHeroDetailRepository repo) {
        Result r = new Result();
        repo.load(1488, false, new HeroDetailRepository.Callback() {
            @Override
            public void onSuccess(CharacterDetail detail, boolean offlineCopy) {
                r.detail = detail;
                r.offline = offlineCopy;
            }

            @Override
            public void onError(LoadError error) {
                r.error = error;
            }
        });
        return r;
    }

    @Test
    public void firstLoad_fetchesAndCaches_secondLoadUsesDisk() {
        ApiHeroDetailRepository repo = repo(id -> ok("Lizard"));
        assertEquals("Lizard", load(repo).detail.name);
        assertTrue(new File(dir, "1488.json").exists());

        Result second = load(repo);
        assertEquals("Lizard", second.detail.name);
        assertFalse(second.offline);
        assertEquals("a segunda vez não vai à rede", 1, calls);
    }

    @Test
    public void networkFailure_fallsBackToExpiredCopy() {
        load(repo(id -> ok("Lizard")));
        File cached = new File(dir, "1488.json");
        assertTrue(cached.setLastModified(0));

        Result r = load(repo(id -> {
            throw new IOException("sem rede");
        }));
        assertEquals("Lizard", r.detail.name);
        assertTrue(r.offline);
    }

    @Test
    public void errors_areMapped() {
        assertEquals(LoadError.NO_CONNECTION, load(repo(id -> {
            throw new IOException("sem rede");
        })).error);
        assertEquals(LoadError.RATE_LIMITED, load(repo(id ->
                Response.error(429, ResponseBody.create("", MediaType.get("application/json"))))).error);

        ComicVineResponse<CharacterDetail> invalidKey = new ComicVineResponse<>();
        invalidKey.statusCode = 100;
        Result r = load(repo(id -> Response.success(invalidKey)));
        assertEquals(LoadError.NOT_CONFIGURED, r.error);
        assertNull(r.detail);

        ComicVineResponse<CharacterDetail> notFound = new ComicVineResponse<>();
        notFound.statusCode = 101;
        assertEquals("herói sem ficha", LoadError.NOT_FOUND,
                load(repo(id -> Response.success(notFound))).error);
        assertEquals("404 da API", LoadError.NOT_FOUND, load(repo(id ->
                Response.error(404, ResponseBody.create("{\"status_code\":101}", MediaType.get("application/json")))))
                .error);
    }

    @Test
    public void notConfigured_doesNotCallTheApi() {
        ApiHeroDetailRepository repo = new ApiHeroDetailRepository(id -> {
            calls++;
            return ok("x");
        }, false, () -> dir, direct, direct);
        assertEquals(LoadError.NOT_CONFIGURED, load(repo).error);
        assertEquals(0, calls);
    }
}
