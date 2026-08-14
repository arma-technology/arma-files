# Build fixes applied locally

**Status: fixes #1 and #2 below have now been needed in v0.1.0, v0.2.0,
v0.3.0, and v0.4.0 independently -- four releases in a row.** Fix #3
(`Icons.Default.InfoOutline`) has now recurred in v0.2.0, v0.3.0, and
v0.4.0 -- three releases in a row. Worth applying all three at the source
that generates these zips.

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

## 4. (v0.4.0) Path traversal via unvalidated create/rename names

New in v0.4.0: a local `file://` backend (with `MANAGE_EXTERNAL_STORAGE`)
plus FTP and SMB remote backends were added. All three build target paths
by string-concatenating a user-typed `name` onto a parent path:

- Local: `FileRepository.uniqueChild()` calls `File(parent, requestedName)`
  directly. Java's `File(File, String)` does not collapse `..`, so a name
  like `../../secret` resolves outside the intended directory.
- FTP/SMB: `RemoteRepository.childUri()` builds `"$rel/$name"` into the
  URI path, and `ftpPath()`/`smbUrl()` split that path into segments and
  rejoin them for the server -- but neither filters out `..` segments the
  way `FileRepository.sanitizeZipPath()` (used for zip extraction) already
  does. A folder/file named `..` or `../x`, or a rename to one, escapes
  the configured FTP `rootPath` or SMB share subdirectory.

`FileManagerViewModel.createFolder()` / `createFile()` / `rename()` only
called `name.trim()` before passing the name straight to the repository --
no rejection of `/`, `\`, or `..`. Since `MANAGE_EXTERNAL_STORAGE` grants
access to all of shared storage in this version, a local-backend escape is
no longer confined to one SAF-scoped tree.

Fixed by adding `FileManagerViewModel.validatedName()`, called from all
three entry points, which rejects blank names, `.` / `..`, and any name
containing `/` or `\` -- so a single path segment can never smuggle a
traversal component into any backend.

## Verification

- `./gradlew clean assembleDebug` — `BUILD SUCCESSFUL`, only pre-existing
  deprecation warnings remain (non-`AutoMirrored` icon variants —
  cosmetic).
- v0.4.0: `./gradlew assembleDebug` — `BUILD SUCCESSFUL`, same cosmetic
  warnings only. Verified `aapt dump badging` reports
  `pl.arma.files` versionName `0.4.0`, minSdk 26, targetSdk 35.
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
