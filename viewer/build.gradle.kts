plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Przeglądarki dokumentów (PDF, a docelowo CAD) niezależne od menedżera plików:
// dostają plik i rysują go. Moduł nie zna niczego z :app, więc da się go
// przenieść do innej aplikacji razem z samym katalogiem.
android {
    namespace = "pl.arma.viewer"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Test na prawdziwych rysunkach czyta pliki rzędu setek MB.
            it.maxHeapSize = "3g"
            it.environment("ARMA_CAD_SAMPLES", System.getenv("ARMA_CAD_SAMPLES") ?: "")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.16.0")
    // Geometria do przyciągania, komentarze, warstwy i skala z PDF. Sam obraz
    // strony rysuje systemowy PdfRenderer — jest szybszy i ostrzejszy.
    // BouncyCastle z PdfBox (jdk15to18) dubluje klasy z jdk18on, którego
    // używa aplikacja (sshj); PdfBox potrzebuje go tylko do PDF szyfrowanych
    // certyfikatem, a nowsza wersja ma te same klasy.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
