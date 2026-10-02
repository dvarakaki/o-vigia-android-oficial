package com.ovigia.app.game;

import androidx.annotation.Nullable;

import com.ovigia.app.data.roster.Rarity;
import com.ovigia.app.learning.LearningStore.Outcome;

/** Algo que a tela precisa fazer uma vez: navegar ou encerrar a partida. */
public final class GameEvent {

    public enum Type {
        /** Mostrar o chute {@link #characterId}. */
        SHOW_GUESS,
        /** Voltar para a tela de perguntas (já com a nova pergunta no estado). */
        RETURN_TO_QUESTIONS,
        /** Oferecer os candidatos restantes. */
        SHOW_ALTERNATIVES,
        /** Não sobrou candidato: perguntar em quem o jogador pensou. */
        SHOW_REVEAL,
        /**
         * Partida encerrada com {@link #outcome}; {@link #characterName}, {@link #imageUrl} e
         * {@link #rarity} podem ser null.
         */
        FINISHED
    }

    public final Type type;
    public final int characterId;
    public final String characterName;
    public final String imageUrl;
    public final Outcome outcome;
    @Nullable public final Rarity rarity;

    private GameEvent(Type type, int characterId, String characterName, String imageUrl, Outcome outcome,
                      @Nullable Rarity rarity) {
        this.type = type;
        this.characterId = characterId;
        this.characterName = characterName;
        this.imageUrl = imageUrl;
        this.outcome = outcome;
        this.rarity = rarity;
    }

    static GameEvent of(Type type) {
        return new GameEvent(type, -1, null, null, null, null);
    }

    static GameEvent showGuess(int characterId) {
        return new GameEvent(Type.SHOW_GUESS, characterId, null, null, null, null);
    }

    static GameEvent finished(Outcome outcome, int characterId, String characterName, String imageUrl,
                              @Nullable Rarity rarity) {
        return new GameEvent(Type.FINISHED, characterId, characterName, imageUrl, outcome, rarity);
    }
}
