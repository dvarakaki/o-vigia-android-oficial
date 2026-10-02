package com.ovigia.app.ui.game;

import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.ovigia.app.R;
import com.ovigia.app.game.GameEvent;
import com.ovigia.app.ui.FadeNavOptions;
import com.ovigia.app.ui.home.HomeFragment;
import com.ovigia.app.ui.result.ResultFragment;

/**
 * Traduz {@link GameEvent}s em navegação. Centralizado aqui para que o fluxo
 * da partida seja legível num lugar só e cada tela não reimplemente as regras
 * de back stack.
 *
 * Regra do back stack: a tela de perguntas é sempre a base da partida; chute,
 * "continuar" e alternativas substituem umas às outras por cima dela.
 */
final class GameNavigator {

    static void handle(Fragment from, GameEvent event) {
        NavController nav = NavHostFragment.findNavController(from);
        switch (event.type) {
            case SHOW_GUESS: {
                Bundle args = new Bundle();
                args.putInt(GuessFragment.ARG_CHARACTER_ID, event.characterId);
                nav.navigate(R.id.guessFragment, args, overQuestions());
                break;
            }
            case RETURN_TO_QUESTIONS:
                nav.popBackStack(R.id.questionsFragment, false);
                break;
            case SHOW_ALTERNATIVES:
                nav.navigate(R.id.alternativesFragment, null, overQuestions());
                break;
            case SHOW_REVEAL:
                nav.navigate(R.id.revealFragment, null, overQuestions());
                break;
            case FINISHED:
                finish(from, nav, event);
                break;
            default:
                break;
        }
    }

    /** Opções para abrir uma tela de decisão logo acima da de perguntas. */
    static NavOptions overQuestions() {
        return FadeNavOptions.popUpTo(R.id.questionsFragment, false);
    }

    /**
     * Com personagem conhecido, abre a tela de resultado (que desbloqueia o herói
     * no catálogo quando o Vigia acertou); sem personagem, volta direto ao início
     * com a mensagem. Nos dois casos o grafo da partida sai do back stack e o
     * GameViewModel é destruído.
     */
    private static void finish(Fragment from, NavController nav, GameEvent event) {
        String message;
        switch (event.outcome) {
            case ENGINE_GUESSED:
                message = from.getString(R.string.victory_message);
                break;
            case PICKED_FROM_ALTERNATIVES:
                message = from.getString(R.string.alternate_correct_message, event.characterName);
                break;
            case REVEALED_AFTER_LOSS:
                message = from.getString(R.string.reveal_thanks_message, event.characterName);
                break;
            case LOST_UNREVEALED:
            default:
                nav.getBackStackEntry(R.id.homeFragment).getSavedStateHandle()
                        .set(HomeFragment.KEY_RESULT_MESSAGE, from.getString(R.string.defeat_message));
                nav.popBackStack(R.id.homeFragment, false);
                return;
        }
        Bundle args = new Bundle();
        args.putInt(ResultFragment.ARG_CHARACTER_ID, event.characterId);
        args.putString(ResultFragment.ARG_CHARACTER_NAME, event.characterName);
        args.putString(ResultFragment.ARG_IMAGE_URL, event.imageUrl);
        args.putString(ResultFragment.ARG_MESSAGE, message);
        args.putString(ResultFragment.ARG_OUTCOME, event.outcome.name());
        if (event.rarity != null) args.putString(ResultFragment.ARG_RARITY, event.rarity.name());
        nav.navigate(R.id.resultFragment, args, FadeNavOptions.popUpTo(R.id.homeFragment, false));
    }

    private GameNavigator() { }
}
