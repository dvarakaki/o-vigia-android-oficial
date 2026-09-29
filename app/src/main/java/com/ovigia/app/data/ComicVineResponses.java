package com.ovigia.app.data;

import com.ovigia.app.data.CharacterRepository.LoadError;
import com.ovigia.app.model.ComicVineResponse;

import java.io.File;

import retrofit2.Response;

/**
 * O que as duas buscas na Comic Vine (elenco e ficha) têm em comum: traduzir o
 * HTTP e o status do corpo da resposta num {@link LoadError}, e a validade dos
 * caches em disco.
 */
final class ComicVineResponses {

    /** Status do corpo de resposta da Comic Vine (independente do HTTP). */
    static final int STATUS_OK = 1;
    static final int STATUS_INVALID_KEY = 100;
    static final int STATUS_NOT_FOUND = 101;
    static final int STATUS_RATE_LIMIT = 107;

    /**
     * O corpo da resposta, já conferido quanto a limite de requisições, chave
     * recusada e resposta vazia. O status de sucesso fica com {@link #results},
     * para cada busca tratar os status que só ela conhece antes.
     */
    static <T> ComicVineResponse<T> body(Response<ComicVineResponse<T>> response) throws Failure {
        int http = response.code();
        if (http == 420 || http == 429) throw new Failure(LoadError.RATE_LIMITED);
        if (http == 401 || http == 403) throw new Failure(LoadError.NOT_CONFIGURED);
        ComicVineResponse<T> body = response.body();
        if (!response.isSuccessful() || body == null) throw new Failure(LoadError.SERVER_ERROR);
        if (body.statusCode == STATUS_INVALID_KEY) throw new Failure(LoadError.NOT_CONFIGURED);
        if (body.statusCode == STATUS_RATE_LIMIT) throw new Failure(LoadError.RATE_LIMITED);
        return body;
    }

    /** Os resultados de uma resposta bem-sucedida. */
    static <T> T results(ComicVineResponse<T> body) throws Failure {
        if (body.statusCode != STATUS_OK || body.results == null) throw new Failure(LoadError.SERVER_ERROR);
        return body.results;
    }

    /** O arquivo foi gravado há mais de {@code ttlMillis}. */
    static boolean isExpired(File file, long ttlMillis) {
        return System.currentTimeMillis() - file.lastModified() > ttlMillis;
    }

    /** Por que a busca falhou. Sem stack trace: é fluxo normal (sem rede, limite da API…). */
    static final class Failure extends Exception {
        final LoadError error;

        Failure(LoadError error) {
            super(error.name(), null, false, false);
            this.error = error;
        }
    }

    private ComicVineResponses() { }
}
