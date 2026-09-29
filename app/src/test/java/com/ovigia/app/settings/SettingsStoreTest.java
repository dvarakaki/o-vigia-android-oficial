package com.ovigia.app.settings;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettingsStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;

    private File file() {
        return new File(tmp.getRoot(), "settings.json");
    }

    @Test
    public void defaults_areOnWithoutFile() {
        SettingsStore store = new SettingsStore(this::file, direct);

        assertTrue(store.hapticFeedback());
        assertEquals(HapticStrength.DEFAULT_LEVEL, store.hapticStrength().level);
        assertTrue(store.keepScreenOn());
        assertFalse("ler o padrão não cria arquivo", file().exists());
    }

    @Test
    public void changes_survive_aNewInstance() {
        SettingsStore store = new SettingsStore(this::file, direct);
        store.setHapticFeedback(false);
        store.setHapticLevel(9);
        store.setKeepScreenOn(false);

        SettingsStore reopened = new SettingsStore(this::file, direct);
        assertFalse(reopened.hapticFeedback());
        assertEquals(9, reopened.hapticStrength().level);
        assertFalse(reopened.keepScreenOn());
    }

    @Test
    public void missingKey_keepsItsDefault() throws IOException {
        Files.write(file().toPath(), "{\"hapticFeedback\":false}".getBytes(StandardCharsets.UTF_8));

        SettingsStore store = new SettingsStore(this::file, direct);
        assertFalse(store.hapticFeedback());
        assertEquals("arquivo de antes da barra ganha o nível padrão", HapticStrength.DEFAULT_LEVEL,
                store.hapticStrength().level);
        assertTrue(store.keepScreenOn());
    }

    @Test
    public void levelOutsideTheBar_snapsToTheNearestEnd() throws IOException {
        Files.write(file().toPath(), "{\"hapticLevel\":42,\"keepScreenOn\":false}"
                .getBytes(StandardCharsets.UTF_8));

        SettingsStore store = new SettingsStore(this::file, direct);
        assertEquals(HapticStrength.MAX_LEVEL, store.hapticStrength().level);
        assertFalse("o resto do arquivo continua valendo", store.keepScreenOn());
    }

    @Test
    public void corruptFile_fallsBackToDefaults() throws IOException {
        Files.write(file().toPath(), "{não é json".getBytes(StandardCharsets.UTF_8));

        SettingsStore store = new SettingsStore(this::file, direct);
        assertTrue(store.hapticFeedback());
        assertTrue(store.keepScreenOn());
    }
}
