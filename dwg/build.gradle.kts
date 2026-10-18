plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Odczyt DWG przez LibreDWG (GPL-3.0). Osobny moduł, żeby przeglądarka
// (:viewer) nie zależała od kodu na licencji GPL.
android {
    namespace = "pl.arma.dwg"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk {
            // Telefony arm64 i emulator x86_64; 32-bitowe ARM pominięte —
            // duży rysunek i tak nie zmieściłby się w ich przestrzeni adresowej.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":viewer"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
