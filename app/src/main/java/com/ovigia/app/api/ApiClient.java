package com.ovigia.app.api;

import android.util.Log;

import com.ovigia.app.BuildConfig;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Cliente das fichas dos personagens, na API do O Vigia ({@code OVIGIA_API_URL}).
 * As fichas são públicas: não precisa de login nem de chave.
 */
public final class ApiClient {

    private static final String TAG = "CharacterHttp";
    private static final String USER_AGENT = "OVigiaApp/" + BuildConfig.VERSION_NAME + " (Android)";

    private ApiClient() { }

    /** Esta versão do app sabe o endereço da API. */
    public static boolean isConfigured() {
        return !BuildConfig.OVIGIA_API_URL.trim().isEmpty();
    }

    public static CharacterService create() {
        HttpLoggingInterceptor log = new HttpLoggingInterceptor(message -> Log.i(TAG, message));
        log.setLevel(BuildConfig.DEBUG ? HttpLoggingInterceptor.Level.BASIC : HttpLoggingInterceptor.Level.NONE);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> chain.proceed(
                        chain.request().newBuilder()
                                .header("User-Agent", USER_AGENT)
                                .header("Accept", "application/json")
                                .build()))
                .addInterceptor(log)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        // Sem endereço configurado o Retrofit não aceita a URL vazia: um endereço que nunca
        // é chamado, porque isConfigured() já barra as buscas antes.
        String base = isConfigured() ? BuildConfig.OVIGIA_API_URL.trim() : "http://localhost/";
        return new Retrofit.Builder()
                .baseUrl(base.endsWith("/") ? base : base + "/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(CharacterService.class);
    }
}
