plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Odczyt RAR, RAR5, ISO, CAB, LHA i XAR przez libarchive (BSD-2-Clause).
// Pozostałe formaty czyta kod w Javie (:app), ten moduł tylko uzupełnia.
android {
    namespace = "pl.arma.libarchive"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk {
            // Te same architektury co :dwg.
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
