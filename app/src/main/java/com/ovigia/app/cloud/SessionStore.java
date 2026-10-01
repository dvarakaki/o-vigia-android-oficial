package com.ovigia.app.cloud;

import androidx.annotation.Nullable;

/**
 * Onde a sessão aberta neste aparelho fica guardada: quem é a conta e os
 * tokens da API. Abstraída para os testes rodarem na JVM sem o Android.
 */
public interface SessionStore {

    /** A sessão guardada, ou {@code null} se ninguém entrou. Rápido depois da primeira leitura. */
    @Nullable
    Saved load();

    void save(Saved session);

    void clear();

    /** Sessão aberta: a conta e os tokens. Imutável. */
    final class Saved {
        public final String uid;
        public final String email;
        public final String accessToken;
        /** Quando o token de acesso vence (millis). */
        public final long accessExpiresAt;
        public final String refreshToken;

        public Saved(String uid, String email, String accessToken, long accessExpiresAt, String refreshToken) {
            this.uid = uid;
            this.email = email;
            this.accessToken = accessToken;
            this.accessExpiresAt = accessExpiresAt;
            this.refreshToken = refreshToken;
        }

        public Saved withTokens(String newAccess, long newExpiresAt, String newRefresh) {
            return new Saved(uid, email, newAccess, newExpiresAt, newRefresh);
        }

        public Saved withEmail(String newEmail) {
            return new Saved(uid, newEmail, accessToken, accessExpiresAt, refreshToken);
        }
    }

    /** Em memória, para os testes. */
    final class InMemory implements SessionStore {
        @Nullable private Saved saved;

        @Nullable
        @Override
        public synchronized Saved load() {
            return saved;
        }

        @Override
        public synchronized void save(Saved session) {
            saved = session;
        }

        @Override
        public synchronized void clear() {
            saved = null;
        }
    }
}
