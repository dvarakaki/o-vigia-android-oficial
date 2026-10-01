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

// Conta, dados do jogador e amigos moram na API do O Vigia (repositório ms-o-vigia).
// OVIGIA_API_URL vem do local.properties ou, no CI, da variável de ambiente. Sem ela o app
// compila igual e as telas de conta e amigos avisam que não estão disponíveis.
// OVIGIA_API_URL_DEBUG (opcional) aponta o build de debug para outro servidor — por exemplo
// http://10.0.2.2:8080/ para a API rodando no computador, vista do emulador.
val ovigiaApiUrl: String = localProps.getProperty("OVIGIA_API_URL") ?: System.getenv("OVIGIA_API_URL") ?: ""
val ovigiaApiUrlDebug: String = localProps.getProperty("OVIGIA_API_URL_DEBUG", ovigiaApiUrl)

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
        buildConfigField("String", "OVIGIA_API_URL", "\"$ovigiaApiUrl\"")
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
            buildConfigField("String", "OVIGIA_API_URL", "\"$ovigiaApiUrlDebug\"")
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

    // Rede (Comic Vine e a API do O Vigia)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)

    // Imagens
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)

    // Tradução dos textos da Comic Vine (em inglês): no aparelho, sem chave nem custo
    implementation(libs.mlkit.translate)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.okhttp.mockwebserver)

    // Só o tradutor do aparelho precisa de aparelho para ser testado.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
