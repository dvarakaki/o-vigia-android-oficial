package com.ovigia.app.api;

import com.ovigia.app.model.Character;
import com.ovigia.app.model.CharacterDetail;
import com.ovigia.app.model.ComicVineResponse;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * As fichas dos personagens na API do O Vigia. Os dados vieram da Comic Vine e
 * continuam no formato dela (envelope com {@code status_code} e {@code results}),
 * então os modelos são os mesmos de antes.
 */
public interface CharacterService {

    /**
     * Os campos do jogo de cada personagem: {@code id, name, gender, origin, image,
     * count_of_issue_appearances}.
     *
     * @param ids os personagens, separados por vírgula
     */
    @GET("v1/characters/summaries")
    Call<ComicVineResponse<List<Character>>> summaries(@Query("ids") String ids);

    /** Ficha completa: todos os campos, inclusive edições, equipes, aliados e inimigos. */
    @GET("v1/characters/{id}")
    Call<ComicVineResponse<CharacterDetail>> detail(@Path("id") int id);
}
