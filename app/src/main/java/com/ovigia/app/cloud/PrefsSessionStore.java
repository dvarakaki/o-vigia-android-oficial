package com.ovigia.app.cloud;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * Sessão guardada nas preferências privadas do app. Fica fora do backup
 * ({@code backup_rules.xml}): restaurar num aparelho novo pede login de novo.
 */
public final class PrefsSessionStore implements SessionStore {

    private static final String FILE = "session";
    private static final String UID = "uid";
    private static final String EMAIL = "email";
    private static final String ACCESS = "access";
    private static final String ACCESS_EXPIRES = "accessExpires";
    private static final String REFRESH = "refresh";

    private final Context context;
    private SharedPreferences prefs;
    @Nullable private Saved cached;
    private boolean loaded;

    public PrefsSessionStore(Context context) {
        this.context = context.getApplicationContext();
    }

    @Nullable
    @Override
    public synchronized Saved load() {
        if (!loaded) {
            SharedPreferences p = prefs();
            String uid = p.getString(UID, null);
            String refresh = p.getString(REFRESH, null);
            cached = uid == null || refresh == null ? null : new Saved(uid, p.getString(EMAIL, ""),
                    p.getString(ACCESS, ""), p.getLong(ACCESS_EXPIRES, 0), refresh);
            loaded = true;
        }
        return cached;
    }

    @Override
    public synchronized void save(Saved session) {
        prefs().edit()
                .putString(UID, session.uid)
                .putString(EMAIL, session.email)
                .putString(ACCESS, session.accessToken)
                .putLong(ACCESS_EXPIRES, session.accessExpiresAt)
                .putString(REFRESH, session.refreshToken)
                .commit();
        cached = session;
        loaded = true;
    }

    @Override
    public synchronized void clear() {
        prefs().edit().clear().commit();
        cached = null;
        loaded = true;
    }

    private SharedPreferences prefs() {
        if (prefs == null) prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return prefs;
    }
}
