package com.ovigia.app.cloud;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.PersistentCacheSettings;

/**
 * O Firebase do app, ligado uma vez e dividido entre a conta do jogador
 * ({@link FirebasePlayerBackend}) e os amigos ({@code FirebaseSocialBackend}).
 *
 * O Firestore guarda uma cópia dos documentos no aparelho: sem internet, o
 * jogo segue com os dados da conta e o que for gravado sobe sozinho quando a
 * rede voltar. É o cache do Firebase — o app em si não guarda dado nenhum do
 * jogador em arquivo.
 */
public final class FirebaseServices {

    /** Projeto "demo-*": o Emulator Suite aceita sem projeto real no console. */
    private static final String EMULATOR_PROJECT_ID = "demo-ovigia";

    private final Context context;
    private final String emulatorHost;
    private FirebaseAuth auth;
    private FirebaseFirestore db;

    /** @param emulatorHost host do Firebase Local Emulator Suite, ou vazio para o projeto real */
    public FirebaseServices(Context context, String emulatorHost) {
        this.context = context.getApplicationContext();
        this.emulatorHost = emulatorHost == null ? "" : emulatorHost.trim();
    }

    /** Se o app foi compilado com um projeto do Firebase (ou aponta para o emulador). Rápido. */
    public boolean isConfigured() {
        return !emulatorHost.isEmpty() || !FirebaseApp.getApps(context).isEmpty();
    }

    public synchronized FirebaseAuth auth() throws CloudException {
        init();
        return auth;
    }

    public synchronized FirebaseFirestore db() throws CloudException {
        init();
        return db;
    }

    /** Liga Auth e Firestore na primeira operação (ler a sessão salva toca o disco). */
    private void init() throws CloudException {
        if (auth != null) return;
        if (!isConfigured()) throw new CloudException(CloudException.Reason.NOT_CONFIGURED);
        FirebaseApp app;
        if (!FirebaseApp.getApps(context).isEmpty()) {
            app = FirebaseApp.getInstance();
        } else {
            app = FirebaseApp.initializeApp(context, new FirebaseOptions.Builder()
                    .setProjectId(EMULATOR_PROJECT_ID)
                    .setApplicationId("1:000000000000:android:0000000000000000")
                    .setApiKey("emulator")
                    .build());
        }
        FirebaseAuth newAuth = FirebaseAuth.getInstance(app);
        FirebaseFirestore newDb = FirebaseFirestore.getInstance(app);
        if (!emulatorHost.isEmpty()) {
            newAuth.useEmulator(emulatorHost, 9099);
            newDb.useEmulator(emulatorHost, 8080);
        }
        newDb.setFirestoreSettings(new FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build());
        auth = newAuth;
        db = newDb;
    }
}
