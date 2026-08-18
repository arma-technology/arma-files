import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Client ID projektu Google Cloud. Nie jest tajemnicą (identyfikuje aplikację,
// a nie użytkownika), ale nie trzymamy go w repozytorium, żeby nie przywiązywać
// kodu do jednego projektu. Kolejność: -PdriveClientId=... , potem
// local.properties (plik nieśledzony przez git).
val driveClientId: String = (project.findProperty("driveClientId") as String?)
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        val properties = Properties()
        file.inputStream().use { properties.load(it) }
        properties.getProperty("driveClientId")
    }
    ?: ""

// Google wymaga, żeby adres przekierowania był odwróconym Client ID. Wyliczamy
// go, zamiast kazać wpisywać drugą, łatwą do pomylenia wartość.
val driveRedirectScheme: String =
    if (driveClientId.endsWith(".apps.googleusercontent.com")) {
        "com.googleusercontent.apps." +
            driveClientId.removeSuffix(".apps.googleusercontent.com")
    } else {
        "pl.arma.files.oauth"
    }

android {
    namespace = "pl.arma.files"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.arma.files"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.9.0-alpha2"

        manifestPlaceholders["appAuthRedirectScheme"] = driveRedirectScheme
        buildConfigField("String", "DRIVE_CLIENT_ID", "\"$driveClientId\"")
        buildConfigField("String", "DRIVE_REDIRECT_SCHEME", "\"$driveRedirectScheme\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-svg:3.2.0")
    implementation("commons-net:commons-net:3.13.0")
    // jcifs-ng ciagnie bcprov-jdk15on, sshj bcprov-jdk18on -- te same klasy w
    // dwoch artefaktach. Zostawiamy nowszy, ktory jest nadzbiorem starego.
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.9") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    implementation("com.hierynomus:sshj:0.38.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // OAuth 2.0 + PKCE dla Google Drive. AppAuth zamiast wlasnej implementacji,
    // bo obsluguje Custom Tabs, PKCE i odswiezanie tokenow zgodnie z RFC 8252.
    implementation("net.openid:appauth:0.11.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Robolectric, bo silnik operacji posługuje się android.net.Uri, które poza
    // emulatorem rzuca "not mocked". Sama logika konfliktów i kosza jest czysta.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
