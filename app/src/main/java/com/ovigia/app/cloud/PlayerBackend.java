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
 * A implementação real é o {@link FirebasePlayerBackend}; os testes usam um
 * falso em memória.
 *
 * Tudo é bloqueante (rede): chamar fora da main thread — exceto
 * {@link #currentSession()}. Sem internet, as leituras vêm do cache do próprio
 * Firebase no aparelho e as gravações entram numa fila que sobe sozinha quando
 * a rede volta; por isso os métodos que gravam não lançam.
 */
public interface PlayerBackend {

    /** Se o app tem um servidor para falar. Rápido: pode ser chamado em qualquer thread. */
    boolean isConfigured();

    // ---------------------------------------------------------------- sessão

    /** Conta com sessão aberta neste aparelho, ou {@code null}. Rápido: pode ser chamado na main thread. */
    @Nullable
    Session currentSession();

    Session signIn(String email, String password) throws CloudException;

    Session signUp(String email, String password) throws CloudException;

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

    /** Grava nome, bio, foto, banner e @usuario (as conquistas comemoradas vão por {@link #addCelebrated}). */
    void saveAccount(String uid, Account account);

    /**
     * Marca conquistas como comemoradas. Com {@code baseline}, também cria a
     * lista (o marco zero) mesmo que {@code ids} esteja vazia.
     */
    void addCelebrated(String uid, Collection<String> ids, boolean baseline);

    // ---------------------------------------------------------------- heróis

    List<Hero> loadHeroes(String uid) throws CloudException;

    /** Grava o herói (quem chama confere antes se ele já estava lá). */
    void saveHero(String uid, Hero hero);

    void markHeroesSeen(String uid, Collection<Integer> characterIds);

    // ---------------------------------------------------------------- memória do Vigia

    /** Números, favoritos e crenças aprendidas; vazio se a conta ainda não jogou. */
    Learning loadLearning(String uid) throws CloudException;

    /**
     * Uma partida terminou. Com personagem conhecido ({@code game}), soma um nos
     * favoritos dele, mistura as respostas nas crenças e guarda no histórico;
     * sem ({@code null}), só conta a partida. As somas são feitas no servidor:
     * dois aparelhos jogando ao mesmo tempo não se atropelam.
     */
    void recordGame(String uid, @Nullable Game game, boolean engineWin);

    /** As {@code limit} partidas mais recentes com personagem conhecido, da mais nova para a mais antiga. */
    List<Game> recentGames(String uid, int limit) throws CloudException;

    /** Esquece tudo o que o Vigia aprendeu com a conta, inclusive números e histórico. */
    void resetLearning(String uid);

    /**
     * Traz para a conta o que uma versão antiga guardava fora dela: favoritos e
     * crenças são somados; os totais de partidas ficam com o maior valor (o
     * servidor antigo já podia ter contado as mesmas partidas); o histórico entra
     * inteiro.
     */
    void importLearning(String uid, Learning learning, List<Game> games);

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

    /** Dados editáveis da conta. Imagens em JPEG codificado em Base64 ({@code null} usa o padrão). */
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

    /** Herói desbloqueado, com nome e imagem do momento do desbloqueio. */
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
}
