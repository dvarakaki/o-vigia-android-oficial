package com.ovigia.app.data;

import com.ovigia.app.data.roster.Rarity;
import com.ovigia.app.data.roster.RosterCatalog;
import com.ovigia.app.engine.CharacterProfile;
import com.ovigia.app.model.Character;

import java.util.HashMap;
import java.util.Map;

/**
 * Converte um {@link Character} da Comic Vine em um {@link CharacterProfile}
 * com crenças [0,1] por atributo e registra o texto da pergunta correspondente
 * a cada atributo usado.
 *
 * Gênero e origem vêm da Comic Vine (campos confiáveis no endpoint de lista).
 * Times, poderes, vilania, reconhecimento e raridade vêm do {@link RosterCatalog}.
 */
public final class CharacterMapper {

    // Crenças "quase certas" em vez de 1/0 puros — o modelo de ruído do motor
    // já trata o erro do jogador, mas a curadoria também pode errar.
    static final double YES = 0.92;
    static final double NO = 0.08;

    private final RosterCatalog roster;
    private final Map<String, String> questionTexts;

    /**
     * @param questionTexts texto de cada chave de {@link QuestionKeys}. Chaves sem
     *                      texto são ignoradas (não viram pergunta).
     */
    public CharacterMapper(RosterCatalog roster, Map<String, String> questionTexts) {
        this.roster = roster;
        this.questionTexts = questionTexts;
    }

    public CharacterProfile toProfile(Character c, Map<String, String> questionTextByKey) {
        Map<String, Double> attrs = new HashMap<>();

        if (c.gender == 1) {
            put(attrs, questionTextByKey, QuestionKeys.GENDER_MALE, YES);
        } else if (c.gender == 2) {
            put(attrs, questionTextByKey, QuestionKeys.GENDER_MALE, NO);
        }

        // Origem é mutuamente exclusiva. Se sabemos qual é (e ela está mapeada),
        // gravamos YES pra ela e NO explícito pras outras: sem isso, as outras
        // cairiam no "não fraco" padrão do motor e as perguntas de origem —
        // das mais discriminantes do jogo — perderiam ganho de informação.
        // Origem ausente ou "Other": nada é gravado (desconhecido).
        String originSuffix = c.origin != null ? QuestionKeys.ORIGINS.get(c.origin.name) : null;
        if (originSuffix != null) {
            for (String suffix : QuestionKeys.ORIGINS.values()) {
                put(attrs, questionTextByKey, QuestionKeys.ORIGIN_PREFIX + suffix,
                        suffix.equals(originSuffix) ? YES : NO);
            }
        }

        RosterCatalog.Entry entry = roster.get(c.id);

        for (String team : QuestionKeys.TEAMS) {
            boolean member = entry != null && entry.teams.contains(team);
            put(attrs, questionTextByKey, QuestionKeys.TEAM_PREFIX + team, member ? YES : NO);
        }

        if (entry != null) {
            for (String power : entry.powers) {
                put(attrs, questionTextByKey, QuestionKeys.POWER_PREFIX + power, YES);
            }
        }

        put(attrs, questionTextByKey, QuestionKeys.IS_VILLAIN, entry != null ? entry.villain : NO);

        String heroImage = c.image != null ? c.image.bestForHero() : null;
        String thumbnail = c.image != null ? c.image.bestForThumbnail() : null;
        return new CharacterProfile(c.id, c.name, heroImage, thumbnail, attrs, c.issueCount,
                entry != null && entry.mainstream, entry != null ? entry.rarity() : Rarity.COMMON);
    }

    private void put(Map<String, Double> attrs, Map<String, String> questionTextByKey,
                     String key, double belief) {
        String text = questionTexts.get(key);
        if (text == null) return;
        attrs.put(key, belief);
        questionTextByKey.putIfAbsent(key, text);
    }
}
