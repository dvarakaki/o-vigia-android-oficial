package com.ovigia.app.catalog;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.ovigia.app.auth.AccountStore;
import com.ovigia.app.catalog.HeroDetailUiState.Status;
import com.ovigia.app.catalog.HeroDetailUiState.TranslationStatus;
import com.ovigia.app.collection.CollectionStore;
import com.ovigia.app.data.CharacterRepository.LoadError;
import com.ovigia.app.data.HeroDetailRepository;
import com.ovigia.app.model.CharacterDetail;
import com.ovigia.app.translation.FakeHeroTranslations;
import com.ovigia.app.translation.TranslationException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HeroDetailViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantLiveData = new InstantTaskExecutorRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Executor direct = Runnable::run;
    private AccountStore accounts;
    private CollectionStore collection;
    private FakeDetails details;
    private FakeHeroTranslations translations;

    @Before
    public void setUp() {
        accounts = new AccountStore(() -> new File(tmp.getRoot(), "accounts.json"), 1_000);
        collection = new CollectionStore(() -> new File(tmp.getRoot(), "collection.json"));
        details = new FakeDetails();
        translations = new FakeHeroTranslations();
        accounts.signUp("Ana", "ana@b.com", "segredo#1");
    }

    private HeroDetailViewModel started(int id) {
        return started(id, "pt");
    }

    private HeroDetailViewModel started(int id, String language) {
        HeroDetailViewModel vm = new HeroDetailViewModel(id, accounts, collection, details, translations,
                language, direct, direct);
        vm.start();
        return vm;
    }

    private void unlock(int id, String name) {
        collection.save(accounts.currentAccount().id, id, name, "img");
    }

    @Test
    public void lockedHero_doesNotLoadTheSheet() {
        HeroDetailUiState state = started(1488).state().getValue();
        assertEquals(Status.LOCKED, state.status);
        assertEquals(0, details.loads);
    }

    @Test
    public void unlockedHero_loadsFullSheet_withUnlockedAlliesMarked() {
        unlock(1488, "Lizard");
        unlock(1455, "Iron Man");

        HeroDetailViewModel vm = started(1488, "en");
        HeroDetailUiState state = vm.state().getValue();

        assertEquals(Status.READY, state.status);
        assertEquals("Lizard", state.detail.name);
        assertEquals("Lizard", state.previewName);
        assertTrue(state.unlockedIds.contains(1455));
    }

    @Test
    public void error_canBeRetried() {
        unlock(1488, "Lizard");
        details.failWith = LoadError.NO_CONNECTION;
        HeroDetailViewModel vm = started(1488, "en");
        assertEquals(Status.ERROR, vm.state().getValue().status);
        assertEquals(LoadError.NO_CONNECTION, vm.state().getValue().error);

        details.failWith = null;
        vm.retry();
        assertEquals(Status.READY, vm.state().getValue().status);
        assertEquals(2, details.loads);
    }

    @Test
    public void withoutSession_signsOut() {
        accounts.signOut();
        assertEquals(Status.SIGNED_OUT, started(1488).state().getValue().status);
    }

    // ------------------------------------------------------------ tradução

    @Test
    public void noIdiomaDaApiNaoHaOQueTraduzir() {
        unlock(1488, "Lizard");

        HeroDetailUiState state = started(1488, "en").state().getValue();

        assertEquals(TranslationStatus.NONE, state.translationStatus);
        assertEquals(0, translations.jobs);
    }

    @Test
    public void aFichaSoApareceComOQueATraducaoJaTem() {
        unlock(1488, "Lizard");

        HeroDetailViewModel vm = started(1488);

        // Sem esperar a tradução, o resumo em inglês piscaria antes dela em toda abertura.
        assertEquals(Status.LOADING, vm.state().getValue().status);
        assertEquals(1, translations.jobs);

        translations.publish("A scientist.", "Um cientista.", "<p>Origem.</p>", true);

        HeroDetailUiState state = vm.state().getValue();
        assertEquals(Status.READY, state.status);
        assertEquals(TranslationStatus.DONE, state.translationStatus);
        assertEquals("Um cientista.", state.translation.text("A scientist."));
    }

    @Test
    public void aBiografiaChegaAosPoucos() {
        unlock(1488, "Lizard");
        HeroDetailViewModel vm = started(1488);

        translations.publish("A scientist.", "Um cientista.", null, false);
        assertEquals(TranslationStatus.TRANSLATING, vm.state().getValue().translationStatus);
        assertNull(vm.state().getValue().translation.description);

        translations.publish("A scientist.", "Um cientista.", "<p>Origem.</p>", true);
        assertEquals(TranslationStatus.DONE, vm.state().getValue().translationStatus);
        assertEquals("<p>Origem.</p>", vm.state().getValue().translation.description);
    }

    @Test
    public void oJogadorPodeVoltarAoTextoOriginal() {
        unlock(1488, "Lizard");
        HeroDetailViewModel vm = started(1488);
        translations.publish("A scientist.", "Um cientista.", "<p>Origem.</p>", true);

        vm.setShowOriginal(true);
        assertTrue(vm.state().getValue().showOriginal);
        assertEquals("a tradução continua guardada", "Um cientista.",
                vm.state().getValue().translation.text("A scientist."));

        vm.setShowOriginal(false);
        assertFalse(vm.state().getValue().showOriginal);
    }

    @Test
    public void semPacoteDoIdiomaEsperaOJogadorAutorizarODownload() {
        unlock(1488, "Lizard");
        HeroDetailViewModel vm = started(1488);
        translations.fail(TranslationException.Reason.NEEDS_DOWNLOAD);

        HeroDetailUiState state = vm.state().getValue();
        assertEquals("a ficha aparece mesmo sem tradução", Status.READY, state.status);
        assertEquals(TranslationStatus.NEEDS_DOWNLOAD, state.translationStatus);
        assertFalse(translations.lastAllowMeteredDownload);

        vm.downloadTranslator();
        assertEquals(2, translations.jobs);
        assertTrue(translations.lastAllowMeteredDownload);
        assertEquals(TranslationStatus.TRANSLATING, vm.state().getValue().translationStatus);
    }

    @Test
    public void sairDaTelaCancelaATraducaoEVoltarContinua() {
        unlock(1488, "Lizard");
        HeroDetailViewModel vm = started(1488);
        translations.publish("A scientist.", "Um cientista.", null, false);

        vm.onHidden();
        assertEquals(1, translations.cancels);

        vm.onVisible();
        assertEquals("a tradução recomeça de onde o cache parou", 2, translations.jobs);
    }

    @Test
    public void comATraducaoProntaVoltarParaATelaNaoTraduzDeNovo() {
        unlock(1488, "Lizard");
        HeroDetailViewModel vm = started(1488);
        translations.publish("A scientist.", "Um cientista.", "<p>Origem.</p>", true);

        vm.onHidden();
        vm.onVisible();

        assertEquals(1, translations.jobs);
    }

    private static final class FakeDetails implements HeroDetailRepository {
        int loads = 0;
        LoadError failWith = null;

        @Override
        public void load(int characterId, boolean forceRefresh, Callback callback) {
            loads++;
            if (failWith != null) {
                callback.onError(failWith);
                return;
            }
            CharacterDetail detail = new CharacterDetail();
            detail.id = characterId;
            detail.name = "Lizard";
            detail.deck = "A scientist.";
            callback.onSuccess(detail, false);
        }
    }
}
