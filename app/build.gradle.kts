import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/** Lê um arquivo .properties da raiz do projeto (vazio se não existir). */
fun rootProperties(name: String): Properties = Properties().apply {
    val file = rootProject.file(name)
    if (file.exists()) file.inputStream().use { load(it) }
}

// Configuração local de cada dev — local.properties não vai para o git.
val localProps = rootProperties("local.properties")
val comicVineApiKey: String = localProps.getProperty("COMIC_VINE_API_KEY", "")
val comicVineBaseUrl: String = localProps.getProperty(
    "COMIC_VINE_BASE_URL", "https://comicvine.gamespot.com/api/"
)
if (comicVineApiKey.isBlank() && comicVineBaseUrl.contains("comicvine.gamespot.com")) {
    logger.warn("⚠️  COMIC_VINE_API_KEY não encontrada em local.properties — o jogo só funciona com cache. Veja o README.")
}

// Amigos online (Firebase Auth + Firestore). Com app/google-services.json o plugin gera a
// configuração do projeto; sem ele o app compila igual e a aba de amigos avisa que não está
// configurada. FIREBASE_EMULATOR_HOST (só no debug) usa o Firebase Local Emulator Suite.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}
val firebaseEmulatorHost: String = localProps.getProperty("FIREBASE_EMULATOR_HOST", "")
// Loja de mentira para o Vigia do Infinito (só no debug): FAKE_BILLING=true no local.properties
// deixa testar a compra sem o app estar no Google Play. O release sempre usa o Google Play.
val fakeBilling: Boolean = localProps.getProperty("FAKE_BILLING", "false").trim().toBoolean()

// Assinatura de release: keystore.properties (fora do git) ou variáveis de ambiente no CI.
val keystoreProps = rootProperties("keystore.properties")
fun signingValue(key: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv("OVIGIA_${key.uppercase()}")

android {
    namespace = "com.ovigia.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ovigia.app"
        minSdk = 28
        targetSdk = 36
        versionCode = providers.gradleProperty("ovigia.versionCode").get().toInt()
        versionName = providers.gradleProperty("ovigia.versionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "COMIC_VINE_API_KEY", "\"$comicVineApiKey\"")
        buildConfigField("String", "COMIC_VINE_BASE_URL", "\"$comicVineBaseUrl\"")
        buildConfigField("String", "FIREBASE_EMULATOR_HOST", "\"\"")
        buildConfigField("boolean", "FAKE_BILLING", "false")
    }

    signingConfigs {
        val storeFilePath = signingValue("storeFile")
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "FIREBASE_EMULATOR_HOST", "\"$firebaseEmulatorHost\"")
            buildConfigField("boolean", "FAKE_BILLING", "$fakeBilling")
        }
        release {
            // R8: remove código/recursos não usados e ofusca. Regras em src/main/keepRules.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // O idioma é escolhido dentro do app: o AAB precisa levar todas as traduções,
    // senão a Play Store instala só a do aparelho e as outras ficariam sem texto.
    bundle {
        language {
            enableSplit = false
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        // Novas versões de bibliotecas não devem quebrar o build do CI.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
        // Botões preenchidos lado a lado são parte da identidade visual, não "button bars".
        disable += "ButtonStyle"
    }

    testOptions {
        // android.util.Log e afins viram no-op nos testes JVM.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.core.splashscreen)
    implementation(libs.fragment)
    implementation(libs.material)
    implementation(libs.navigation.fragment)
    implementation(libs.recyclerview)

    // Arquitetura (MVVM)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.lifecycle.livedata)

    // Rede (Comic Vine)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)

    // Imagens
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)

    // Amigos online
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Tradução dos textos da Comic Vine (em inglês): no aparelho, sem chave nem custo
    implementation(libs.mlkit.translate)
    implementation(libs.jsoup)

    // Vigia do Infinito: compra única pelo Google Play
    implementation(libs.billing)

    testImplementation(libs.junit)
    testImplementation(libs.arch.core.testing)

    // Só o tradutor do aparelho precisa de aparelho para ser testado.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
