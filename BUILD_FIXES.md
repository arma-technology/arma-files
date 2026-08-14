# Build fixes applied locally

ARMA Files v0.1.0 as received didn't compile. Two fixes were needed:

## 1. Inconsistent JVM target between Java and Kotlin compilation

`app/build.gradle.kts` declared no `compileOptions` and no Kotlin
`jvmToolchain`, so AGP's Java compilation defaulted to target 1.8 while
Kotlin compiled against whatever JDK ran Gradle (JDK 21 in this
environment) — a hard error:

```
Inconsistent JVM-target compatibility detected for tasks
'compileDebugJavaWithJavac' (1.8) and 'compileDebugKotlin' (21).
```

Fixed by adding, inside the `android { }` block:

```kotlin
compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}
```

Same JVM 17 target used in the sibling `TextForge` project.

## 2. Missing `setContent` import

`MainActivity.kt` called `setContent { ... }` (line 54) without
`import androidx.activity.compose.setContent`. Added the import.

## Verification

- `./gradlew clean assembleDebug` — `BUILD SUCCESSFUL`, only pre-existing
  deprecation warnings remain (non-`AutoMirrored` icon variants —
  cosmetic).
- APK verified with `aapt dump badging`: `pl.arma.files`, versionName
  `0.1.0`, minSdk 26, targetSdk 35.

## Note on the custom `gradlew`

This project's `gradlew` (unlike TextForge's) self-downloads the missing
`gradle-wrapper.jar` directly from a pinned `gradle/gradle` git tag with a
SHA-256 check, then lets that jar download the full Gradle distribution as
normal. No separate bootstrap script needed — just `chmod +x gradlew &&
./gradlew assembleDebug`.
