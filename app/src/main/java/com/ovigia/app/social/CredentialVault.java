package com.ovigia.app.social;

import androidx.annotation.Nullable;

/**
 * Onde fica, por pouco tempo, a senha de um login que ainda não conseguiu
 * abrir a sessão online.
 *
 * O login local não precisa de rede, mas a sessão dos amigos precisa — e a
 * conta online só abre com a senha. Se não havia rede na hora (ou o app foi
 * fechado antes de terminar), a senha digitada se perderia e a aba de amigos
 * teria que pedi-la de novo. Com o cofre, o app termina a conexão sozinho
 * quando a rede voltar ({@link SocialRepository#resumePending()}).
 *
 * Guarda uma senha só, presa à conta que entrou, e só até a conexão dar certo:
 * depois disso quem mantém o jogador online é a sessão do próprio Firebase, e o
 * cofre é esvaziado. Tudo é bloqueante (disco): chamar no executor social.
 */
public interface CredentialVault {

    /** Cofre que não guarda nada: sem ele, a aba de amigos pede a senha quando a conexão do login falha. */
    CredentialVault NONE = new CredentialVault() {
        @Override public void save(String accountId, String password) { }
        @Nullable @Override public String read(String accountId) { return null; }
        @Override public void clear() { }
    };

    /** Guarda a senha da conta, no lugar do que houver. Falhas são ignoradas (sem cofre, só volta a pedir). */
    void save(String accountId, String password);

    /**
     * A senha guardada para essa conta, ou {@code null} se não há. Se o que está
     * guardado é de outra conta, o cofre é esvaziado: ele só serve à conta logada.
     */
    @Nullable
    String read(String accountId);

    /** Esvazia o cofre. */
    void clear();
}
