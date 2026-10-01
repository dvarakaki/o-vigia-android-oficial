package com.ovigia.app.cloud;

import androidx.annotation.Nullable;

import com.ovigia.app.learning.LearningStore.AnswerRecord;
import com.ovigia.app.learning.LearningStore.Outcome;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O servidor da conta do jogador: login e todos os dados dele — perfil, heróis
 * desbloqueados, a memória do Vigia, o histórico de partidas e as conquistas já
 * comemoradas. Nada disso mora em arquivo do app: a mesma conta mostra os mesmos
 * dados em qualquer aparelho e em qualquer versão.
 *
 * A implementação real é o {@link ApiPlayerBackend}, que fala com a API do
 * O Vigia; os testes usam um falso em memória.
 *
 * Tudo é bloqueante (rede): chamar fora da main thread — exceto
 * {@link #currentSession()}. Sem internet, as leituras vêm da última cópia
 * guardada no aparelho e as gravações do jogo (partidas, heróis vistos,
 * conquistas) entram numa fila que sobe quando a rede volta; por isso esses
 * métodos não lançam. O que mexe na conta em si (perfil, senha, e-mail) precisa
 * de rede.
 */
public interface PlayerBackend {

    /** Se o app tem um servidor para falar. Rápido: pode ser chamado em qualquer thread. */
    boolean isConfigured();

    // ---------------------------------------------------------------- sessão

    /** Conta com sessão aberta neste aparelho, ou {@code null}. Rápido: pode ser chamado na main thread. */
    @Nullable
    Session currentSession();

    Session signIn(String email, String password) throws CloudException;

    /** Cria a conta já com o nome que os amigos vão ver. */
    Session signUp(String email, String password, String displayName) throws CloudException;

    void signOut();

    /** Troca a senha da conta logada, conferindo a atual. */
    void changePassword(String currentPassword, String newPassword) throws CloudException;

    /**
     * Pede a troca do e-mail da conta logada: o servidor manda um link para
     * {@code newEmail}, e o e-mail só muda quando o jogador clicar nele.
     */
    void requestEmailChange(String currentPassword, String newEmail) throws CloudException;

    /**
     * Confere a senha e apaga a conta inteira: dados do jogador, perfil público,
     * @usuario, amizades, pedidos, trocas e o login.
     */
    void deleteAccount(String password) throws CloudException;

    // ---------------------------------------------------------------- perfil

    /** Dados da conta, ou {@code null} se ela ainda não tem nenhum (conta recém-criada ou de uma versão antiga). */
    @Nullable
    Account loadAccount(String uid) throws CloudException;

    /**
     * Grava nome, bio, foto e banner (o @usuario é reservado pelo {@code SocialBackend};
     * as conquistas comemoradas vão por {@link #addCelebrated}). Foto e banner novos
     * chegam em JPEG Base64 e voltam como o endereço onde ficaram guardados.
     *
     * @return a conta como ficou no servidor
     */
    Account saveAccount(String uid, Account account) throws CloudException;

    /**
     * Marca conquistas como comemoradas. Com {@code baseline}, também cria a
     * lista (o marco zero) mesmo que {@code ids} esteja vazia.
     */
    void addCelebrated(String uid, Collection<String> ids, boolean baseline);

    // ---------------------------------------------------------------- heróis

    /**
     * Heróis desbloqueados. Quem desbloqueia é o servidor: a partida que o Vigia
     * acertou ({@link #recordGame}), uma troca aceita ou a importação das versões
     * antigas ({@link #importLegacy}).
     */
    List<Hero> loadHeroes(String uid) throws CloudException;

    /**
     * Espera a partida que acabou de terminar com {@code characterId} subir (até um
     * limite de tempo; sem rede, ela continua na fila) e diz se foi ela que pôs o
     * herói na coleção.
     *
     * @return {@code true} se o herói é novo, {@code false} se a conta já tinha, ou
     *     {@code null} se não dá para saber
     */
    @Nullable
    Boolean awaitUnlock(String uid, int characterId);

    void markHeroesSeen(String uid, Collection<Integer> characterIds);

    // ---------------------------------------------------------------- memória do Vigia

    /** Números, favoritos e crenças aprendidas; vazio se a conta ainda não jogou. */
    Learning loadLearning(String uid) throws CloudException;

    /**
     * Uma partida terminou. Com personagem conhecido ({@code game}), soma um nos
     * favoritos dele, mistura as respostas nas crenças, guarda no histórico e, se o
     * Vigia acertou, desbloqueia o herói; sem ({@code null}), só conta a partida.
     * As somas são feitas no servidor: dois aparelhos jogando ao mesmo tempo não se
     * atropelam.
     */
    void recordGame(String uid, @Nullable Game game, boolean engineWin);

    /** As {@code limit} partidas mais recentes com personagem conhecido, da mais nova para a mais antiga. */
    List<Game> recentGames(String uid, int limit) throws CloudException;

    /** Esquece tudo o que o Vigia aprendeu com a conta, inclusive números e histórico. */
    void resetLearning(String uid);

    /**
     * Traz para a conta o que uma versão antiga guardava fora dela: os heróis que
     * faltam entram; favoritos e crenças são somados; os totais de partidas ficam
     * com o maior valor (o servidor antigo já podia ter contado as mesmas
     * partidas); o histórico entra inteiro; as conquistas comemoradas continuam
     * comemoradas. Precisa de rede.
     */
    void importLegacy(String uid, LegacyImport data) throws CloudException;

    // ================================================================ valores

    /** Sessão aberta: quem é a conta. */
    final class Session {
        public final String uid;
        public final String email;

        public Session(String uid, String email) {
            this.uid = uid;
            this.email = email;
        }
    }

    /**
     * Dados editáveis da conta. Foto e banner são o endereço da imagem guardada na
     * API (ou, numa troca ainda não gravada, o JPEG novo em Base64); {@code null} usa
     * o padrão.
     */
    final class Account {
        public final String name;
        @Nullable public final String bio;
        @Nullable public final String avatar;
        @Nullable public final String banner;
        /** @usuario reservado para os amigos, ou {@code null} se ainda não escolheu. */
        @Nullable public final String username;
        /** Conquistas já comemoradas; {@code null} se a conta ainda não tem marco zero. */
        @Nullable public final List<String> celebrated;

        public Account(String name, @Nullable String bio, @Nullable String avatar, @Nullable String banner,
                       @Nullable String username, @Nullable List<String> celebrated) {
            this.name = name;
            this.bio = bio;
            this.avatar = avatar;
            this.banner = banner;
            this.username = username;
            this.celebrated = celebrated == null ? null : Collections.unmodifiableList(celebrated);
        }

        public Account withName(String newName, @Nullable String newBio) {
            return new Account(newName, newBio, avatar, banner, username, celebrated);
        }

        public Account withAvatar(@Nullable String newAvatar) {
            return new Account(name, bio, newAvatar, banner, username, celebrated);
        }

        public Account withBanner(@Nullable String newBanner) {
            return new Account(name, bio, avatar, newBanner, username, celebrated);
        }

        public Account withUsername(@Nullable String newUsername) {
            return new Account(name, bio, avatar, banner, newUsername, celebrated);
        }

        public Account withCelebrated(@Nullable List<String> newCelebrated) {
            return new Account(name, bio, avatar, banner, username, newCelebrated);
        }
    }

    /** Herói desbloqueado, com nome e retrato. */
    final class Hero {
        public final int characterId;
        public final String name;
        @Nullable public final String imageUrl;
        public final long unlockedAt;
        /** A revelação animada do catálogo já tocou para ele. */
        public final boolean seen;

        public Hero(int characterId, String name, @Nullable String imageUrl, long unlockedAt, boolean seen) {
            this.characterId = characterId;
            this.name = name;
            this.imageUrl = imageUrl;
            this.unlockedAt = unlockedAt;
            this.seen = seen;
        }
    }

    /** A memória do Vigia para a conta. */
    final class Learning {
        public final int gamesPlayed;
        public final int engineWins;
        /** id do personagem -> quantas vezes o jogador pensou nele. */
        public final Map<Integer, Integer> picks;
        /** id do personagem -> (chave da pergunta -> [soma das respostas, quantidade]). */
        public final Map<Integer, Map<String, double[]>> beliefs;

        public Learning(int gamesPlayed, int engineWins, Map<Integer, Integer> picks,
                        Map<Integer, Map<String, double[]>> beliefs) {
            this.gamesPlayed = gamesPlayed;
            this.engineWins = engineWins;
            this.picks = picks;
            this.beliefs = beliefs;
        }

        public static Learning empty() {
            return new Learning(0, 0, new HashMap<>(), new HashMap<>());
        }
    }

    /** Uma partida com personagem conhecido, como fica no histórico. */
    final class Game {
        public final long timestamp;
        public final int characterId;
        public final Outcome outcome;
        /** Só respostas com evidência ("Não sei" fica de fora). */
        public final List<AnswerRecord> answers;

        public Game(long timestamp, int characterId, Outcome outcome, List<AnswerRecord> answers) {
            this.timestamp = timestamp;
            this.characterId = characterId;
            this.outcome = outcome;
            this.answers = Collections.unmodifiableList(answers);
        }
    }

    /** O que uma versão antiga guardava fora da conta, para {@link #importLegacy}. */
    final class LegacyImport {
        public final List<Hero> heroes;
        public final Learning learning;
        public final List<Game> games;
        /** Conquistas já comemoradas; {@code null} se a versão antiga não guardava. */
        @Nullable public final Collection<String> celebrated;

        public LegacyImport(List<Hero> heroes, Learning learning, List<Game> games,
                            @Nullable Collection<String> celebrated) {
            this.heroes = Collections.unmodifiableList(heroes);
            this.learning = learning;
            this.games = Collections.unmodifiableList(games);
            this.celebrated = celebrated;
        }

        public boolean isEmpty() {
            return heroes.isEmpty() && games.isEmpty() && learning.gamesPlayed == 0 && learning.picks.isEmpty()
                    && learning.beliefs.isEmpty() && celebrated == null;
        }
    }
}
