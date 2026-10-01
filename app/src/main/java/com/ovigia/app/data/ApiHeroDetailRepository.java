package com.ovigia.app.data;

import android.util.Log;

import com.google.gson.Gson;
import com.ovigia.app.api.CharacterService;
import com.ovigia.app.data.CharacterRepository.LoadError;
import com.ovigia.app.data.CharacterResponses.Failure;
import com.ovigia.app.model.CharacterDetail;
import com.ovigia.app.model.ComicVineResponse;
import com.ovigia.app.util.AtomicFiles;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import retrofit2.Response;

/**
 * Busca a ficha completa na API do O Vigia e guarda cada herói em
 * {@code files/hero_details_v2/{id}.json}: a ficha abre na hora nas próximas vezes e
 * funciona offline. Um cache vencido ainda serve de último recurso se a rede falhar.
 */
public final class ApiHeroDetailRepository implements HeroDetailRepository {

    private static final String TAG = "HeroDetailRepository";
    private static final long TTL_MILLIS = TimeUnit.DAYS.toMillis(30);

    /** Chamada à API — separada para testar o tratamento de respostas sem rede. */
    public interface Remote {
        Response<ComicVineResponse<CharacterDetail>> fetch(int characterId) throws IOException;
    }

    private final Remote remote;
    private final boolean apiConfigured;
    private final Supplier<File> directory;
    private final Executor ioExecutor;
    private final Executor mainExecutor;
    private final Gson gson = new Gson();

    public ApiHeroDetailRepository(Remote remote, boolean apiConfigured, Supplier<File> directory,
                                         Executor ioExecutor, Executor mainExecutor) {
        this.remote = remote;
        this.apiConfigured = apiConfigured;
        this.directory = directory;
        this.ioExecutor = ioExecutor;
        this.mainExecutor = mainExecutor;
    }

    /** Remote real, sobre o serviço Retrofit. */
    public static Remote remote(CharacterService service) {
        return id -> service.detail(id).execute();
    }

    @Override
    public void load(int characterId, boolean forceRefresh, Callback callback) {
        ioExecutor.execute(() -> {
            File file = new File(directory.get(), characterId + ".json");
            if (!forceRefresh && file.exists() && !CharacterResponses.isExpired(file, TTL_MILLIS)) {
                CharacterDetail cached = read(file);
                if (cached != null) {
                    mainExecutor.execute(() -> callback.onSuccess(cached, false));
                    return;
                }
            }
            try {
                CharacterDetail fresh = fetch(characterId);
                write(file, fresh);
                mainExecutor.execute(() -> callback.onSuccess(fresh, false));
            } catch (Failure failure) {
                CharacterDetail stale = file.exists() ? read(file) : null;
                if (stale != null) {
                    Log.i(TAG, "Rede indisponível (" + failure.error + "); usando ficha salva");
                    mainExecutor.execute(() -> callback.onSuccess(stale, true));
                } else {
                    mainExecutor.execute(() -> callback.onError(failure.error));
                }
            }
        });
    }

    private CharacterDetail fetch(int characterId) throws Failure {
        if (!apiConfigured) throw new Failure(LoadError.NOT_CONFIGURED);
        Response<ComicVineResponse<CharacterDetail>> response;
        try {
            response = remote.fetch(characterId);
        } catch (IOException e) {
            throw new Failure(LoadError.NO_CONNECTION);
        } catch (RuntimeException e) {
            // JSON inesperado, por exemplo.
            Log.w(TAG, "Resposta ilegível", e);
            throw new Failure(LoadError.SERVER_ERROR);
        }
        if (response.code() == 404) throw new Failure(LoadError.NOT_FOUND);
        ComicVineResponse<CharacterDetail> body = CharacterResponses.body(response);
        if (body.statusCode == CharacterResponses.STATUS_NOT_FOUND) throw new Failure(LoadError.NOT_FOUND);
        return CharacterResponses.results(body);
    }

    private CharacterDetail read(File file) {
        try {
            return gson.fromJson(AtomicFiles.readUtf8(file), CharacterDetail.class);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Ficha salva ilegível", e);
            return null;
        }
    }

    private void write(File file, CharacterDetail detail) {
        try {
            AtomicFiles.writeUtf8(file, gson.toJson(detail, CharacterDetail.class));
        } catch (IOException e) {
            Log.w(TAG, "Falha ao salvar ficha", e);
        }
    }
}
