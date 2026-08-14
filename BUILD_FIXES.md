# Build fixes applied locally

**Status: fixes #1 and #2 below have now been needed in v0.1.0, v0.2.0, and
v0.3.0 independently -- three releases in a row.** Fix #3
(`Icons.Default.InfoOutline`) has now recurred too, in v0.2.0 and v0.3.0.
Worth applying all three at the source that generates these zips.

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

## 3. (v0.2.0 only) `Icons.Default.InfoOutline` doesn't exist

No such icon in `material-icons-core` or `material-icons-extended`
(confirmed by listing the actual `.aar` contents) — likely confused with
`Icons.Outlined.Info` / `Icons.Filled.Info`, the only "Info" icons that
actually exist. Fixed by using `Icons.Default.Info`, consistent with the
other `Icons.Default.*` icons already used alongside it (Star, Share).

## Verification

- `./gradlew clean assembleDebug` — `BUILD SUCCESSFUL`, only pre-existing
  deprecation warnings remain (non-`AutoMirrored` icon variants —
  cosmetic).
- v0.2.0: cross-checked every `Icons.Default.*`/`Icons.Filled.*` reference
  in `MainActivity.kt` against the actual icon classes shipped in
  `material-icons-core`/`material-icons-extended` before rebuilding — only
  `InfoOutline` was invalid.
- APK verified with `aapt dump badging`: `pl.arma.files`, versionName
  matches release, minSdk 26, targetSdk 35.

## Note on the custom `gradlew`

This project's `gradlew` (unlike TextForge's) self-downloads the missing
`gradle-wrapper.jar` directly from a pinned `gradle/gradle` git tag with a
SHA-256 check, then lets that jar download the full Gradle distribution as
normal. No separate bootstrap script needed — just `chmod +x gradlew &&
./gradlew assembleDebug`.
