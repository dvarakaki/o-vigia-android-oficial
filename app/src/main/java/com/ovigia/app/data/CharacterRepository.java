package com.ovigia.app.data;

import com.ovigia.app.engine.CharacterProfile;

import java.util.List;
import java.util.Map;

/**
 * Fonte do elenco de personagens e das perguntas da partida. Abstraída como
 * interface para que o {@code GameViewModel} não dependa de rede/disco e possa
 * ser testado com um fake.
 */
public interface CharacterRepository {

    /** Por que o elenco não pôde ser carregado. A UI traduz cada caso numa mensagem. */
    enum LoadError {
        /** Sem internet ou timeout. */
        NO_CONNECTION,
        /** A Comic Vine limitou as requisições (HTTP 420/429 ou status 107). */
        RATE_LIMITED,
        /** Chave da API ausente ou recusada. */
        NOT_CONFIGURED,
        /** Resposta inesperada do servidor. */
        SERVER_ERROR,
        /** A API respondeu, mas nenhum personagem utilizável veio. */
        EMPTY_ROSTER,
        /** A Comic Vine não tem o personagem pedido (status 101, na ficha de um herói). */
        NOT_FOUND
    }

    /** Chamado sempre na main thread. */
    interface Callback {
        void onSuccess(List<CharacterProfile> profiles, Map<String, String> questionTextByKey);
        void onError(LoadError error);
    }

    /**
     * Carrega o elenco de forma assíncrona. Cada chamada devolve perfis NOVOS
     * (a probabilidade de cada um é estado da partida) com o aprendizado mais
     * recente aplicado.
     */
    void loadCharacters(Callback callback);
}
