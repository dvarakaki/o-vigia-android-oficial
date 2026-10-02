package com.ovigia.app.engine;

import com.ovigia.app.data.roster.Rarity;

import java.util.Map;

/**
 * Um personagem candidato dentro de uma partida: identidade + crença [0,1]
 * em cada atributo (chave da pergunta) + probabilidade posterior atual.
 */
public class CharacterProfile {
    public final int id;
    public final String name;
    /** Imagem grande, para o chute em destaque. */
    public final String imageUrl;
    /** Imagem pequena, para listas — evita baixar a versão grande só para uma miniatura. */
    public final String thumbnailUrl;
    public final Map<String, Double> attributes;

    /**
     * Aparições em quadrinhos (Comic Vine) — usado só para calcular o prior inicial
     * ({@link GameEngine}); não participa da atualização bayesiana em si.
     */
    public final int issueCount;

    /**
     * Se está na lista curada de reconhecimento mainstream. Ganha boost extra no
     * prior do {@link GameEngine} pra evitar que personagens obscuros com muitas
     * aparições dominem sem que ninguém tenha pensado neles.
     */
    public final boolean isMainstream;

    /** Quanto menos conhecido, mais raro; não pesa no motor, só no desbloqueio e nas telas. */
    public final Rarity rarity;

    /** Probabilidade de ser o personagem escolhido pelo jogador; atualizada a cada resposta. */
    public double probability;

    public CharacterProfile(int id, String name, String imageUrl, String thumbnailUrl,
                            Map<String, Double> attributes, int issueCount, boolean isMainstream) {
        this(id, name, imageUrl, thumbnailUrl, attributes, issueCount, isMainstream, Rarity.COMMON);
    }

    public CharacterProfile(int id, String name, String imageUrl, String thumbnailUrl,
                            Map<String, Double> attributes, int issueCount, boolean isMainstream, Rarity rarity) {
        this.id = id;
        this.name = name;
        this.imageUrl = imageUrl;
        this.thumbnailUrl = thumbnailUrl != null ? thumbnailUrl : imageUrl;
        this.attributes = attributes;
        this.issueCount = issueCount;
        this.isMainstream = isMainstream;
        this.rarity = rarity;
    }

    /** Cópia com outro conjunto de atributos (probabilidade não é copiada). */
    public CharacterProfile withAttributes(Map<String, Double> newAttributes) {
        return new CharacterProfile(id, name, imageUrl, thumbnailUrl, newAttributes, issueCount, isMainstream,
                rarity);
    }
}
