# Rdzeń przeglądarek ARMA — integracja w innej aplikacji

Ten katalog to eksport z repozytorium ARMA Files (`scripts/export-viewer-core.sh`).
Źródłem prawdy jest ARMA Files — **nie poprawiaj kodu tutaj**, tylko zgłoś
zmianę i wyeksportuj ponownie. Wersja i commit źródłowy: plik `VERSION`.

## Co jest w środku

| Moduł | Zawartość | Licencja |
|---|---|---|
| `viewer/` | PDF, rysunki CAD (DXF), obrazy (w tym SVG), pliki tekstowe | kod autora ARMA (GPL-3.0-or-later w ARMA Files); zależności Apache 2.0 |
| `dwg/` | odczyt DWG przez LibreDWG (konwersja do DXF w osobnym procesie) | **GPL-3.0** — patrz „Pułapki” |
| `aar/` | te same moduły skompilowane (release) — wariant bez NDK | jw. |

Możliwości (szczegóły w `viewer/README.md`): powiększenie do 6400%
z ostrością ekranu, pomiary z przyciąganiem i skalą (z pliku, z tabelki,
z kalibracji), warstwy, wyszukiwanie tekstu, komentarze PDF z zapisem;
obrazy z powiększaniem, przewijaniem do sąsiednich zdjęć gestem w bok
i pełnym ekranem; czytnik tekstu z rozpoznawaniem kodowania (także
Windows‑1250), spisem treści i wyszukiwaniem. 21 języków interfejsu.

## Wymagania

- minSdk 26, compileSdk 36, JDK 17
- AGP 8.10.x, Kotlin 2.1.21 z wtyczką `org.jetbrains.kotlin.plugin.compose`
- Jetpack Compose z Material 3 (BOM 2025.05.01 lub nowszy)
- tylko dla `dwg/` ze źródeł: NDK 28.2.13676358 i CMake 3.31.6

## Wariant A — moduły źródłowe (zalecany)

1. Skopiuj `viewer/` (i `dwg/`, jeśli DWG ma działać) do katalogu głównego
   swojego projektu. Pliki `dwg/src/main/cpp/libs/**/*.a` są duże — w gicie
   trzymaj je w Git LFS (`*.a filter=lfs diff=lfs merge=lfs -text`).
2. `settings.gradle.kts`:
   ```kotlin
   include(":viewer")
   include(":dwg")   // opcjonalnie
   ```
3. Główny `build.gradle.kts` musi znać wtyczki (wersje jak wyżej):
   ```kotlin
   plugins {
       id("com.android.library") version "8.10.1" apply false
       id("org.jetbrains.kotlin.android") version "2.1.21" apply false
       id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
   }
   ```
4. W module aplikacji:
   ```kotlin
   implementation(project(":viewer"))
   implementation(project(":dwg"))   // opcjonalnie
   ```

## Wariant B — gotowe AAR (bez NDK)

AAR nie niesie zależności, więc trzeba je podać samemu:

```kotlin
implementation(files("libs/arma-viewer.aar"))
implementation(files("libs/arma-dwg.aar"))          // opcjonalnie
implementation(platform("androidx.compose:compose-bom:2025.05.01"))
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material:material-icons-extended")
implementation("androidx.compose.foundation:foundation")
implementation("androidx.compose.ui:ui")
implementation("androidx.core:core-ktx:1.16.0")
implementation("io.coil-kt.coil3:coil-compose:3.2.0")
implementation("io.coil-kt.coil3:coil-svg:3.2.0")
implementation("com.tom-roush:pdfbox-android:2.0.27.0") { exclude(group = "org.bouncycastle") }
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")   // dla arma-dwg
```

Reguły R8 i usługa konwersji DWG są w AAR i dołączają się same.

## Użycie

### PDF i CAD (DXF, DWG)

```kotlin
// Raz, w Application.onCreate — tylko z modułem dwg:
pl.arma.dwg.LibreDwgConverter.install()

// W UI; `file` to plik lokalny (patrz „Pułapki”: content:// trzeba skopiować).
if (SheetDocuments.canOpen(name)) {
    val state = rememberSheetViewerState(key = file.path) { ctx -> SheetDocuments.open(ctx, file, name) }
    SheetViewer(state, Modifier.fillMaxSize(), onSave = { gotowy -> gotowy.copyTo(file, overwrite = true) })
    // albo na pełnym ekranie, z akcjami w menu ⋮:
    SheetViewerDialog(state, onDismiss = { … }, title = name,
        actions = listOf(ViewerAction("Udostępnij", Icons.Default.Share) { … }))
}
```

`onSave` dostaje plik z dopisanymi komentarzami/pomiarami — gospodarz decyduje,
gdzie go zapisać. Bez `onSave` przycisk zapisu się nie pokazuje.

### Obrazy

```kotlin
if (ImageDecoding.isSvg(name)) {
    SvgImageViewer(model = file, name = name, modifier = Modifier.fillMaxSize())
} else {
    ImageViewer(file, Modifier.fillMaxSize())
    // galeria: przewijanie gestem w bok
    ImageViewer(file, Modifier.fillMaxSize(), canNavigate = true,
        onNavigate = { delta -> /* -1 poprzednie, +1 następne */ }, positionLabel = "3 / 12")
}
// Pełny ekran od razu (np. telefon w poziomie):
FullscreenImageViewer(image = bitmap, name = name, onDismiss = { … })
// Obraz spoza dysku: ImageViewer(key, name, load = { ImageDecoding.decode(plik) / własny dekoder })
```

### Tekst

```kotlin
if (TextProfile.isTextFile(name, mimeType)) {
    TextViewer(file, Modifier.fillMaxSize())
    // spoza dysku: TextViewer(name, key, load = { TextReader.read(bajty, totalBytes = rozmiar) })
}
```
Plik binarny daje komunikat „to nie jest plik tekstowy”, a nie sieczkę znaków.
Czytnik pokazuje pierwsze 2 MB (dla większych informuje, że to początek).

## Pułapki

1. **Licencja DWG.** LibreDWG (cudzy kod) jest na GPL-3.0. Aplikacja
   rozpowszechniana z modułem `dwg` musi być udostępniona na licencji zgodnej
   z GPL-3.0, razem z kodem źródłowym. Jeśli aplikacja ma być zamknięta —
   **nie dołączaj `dwg/`**: PDF, DXF, obrazy i tekst działają bez niego,
   a DWG pokaże komunikat, że nie jest obsługiwany. Sam `viewer/` to kod
   autora ARMA (publikowany na GPL-3.0-or-later); w jego własnych aplikacjach
   może być użyty na dowolnych warunkach — jego zależności (PdfBox-Android,
   Coil, AndroidX) są na Apache 2.0.
2. **Tylko pliki lokalne.** `SheetDocuments.open`, `ImageViewer(file)`
   i `TextViewer(file)` biorą `java.io.File`. Dla `content://` (SAF, inne
   aplikacje) albo plików z sieci najpierw skopiuj do `cacheDir`.
   PdfRenderer potrzebuje pliku z dostępem swobodnym, nie strumienia.
3. **Stan nad kontenerem.** `rememberSheetViewerState` trzymaj wyżej niż
   miejsce, które przełącza widok (np. arkusz w pionie ↔ pełny ekran
   w poziomie) — inaczej obrót otwiera dokument od nowa i gubi pozycję.
   Dokument zamyka się sam, gdy stan opuszcza kompozycję.
4. **DWG w osobnym procesie.** Konwersja działa w usłudze
   `pl.arma.dwg.DwgConversionService` w procesie `:dwg` (manifest modułu
   dołącza ją sam). Błąd biblioteki albo brak pamięci kończy tylko ten proces.
   Jeśli Twoja `Application.onCreate` robi coś ciężkiego, pomiń to dla procesu
   `:dwg` (`Application.getProcessName()`), bo uruchomi się też tam.
   Wynik konwersji trafia do `cacheDir/dwg_dxf` — limit ustaw przez
   `LibreDwgConverter.cacheLimitBytes` (domyślnie 1,5 GB).
5. **Architektury DWG.** `dwg` jest zbudowany tylko dla `arm64-v8a`
   i `x86_64`. Na 32-bitowych telefonach DWG nie działa (reszta tak).
6. **PDF z hasłem.** Systemowy PdfRenderer nie otwiera PDF z hasłem —
   przeglądarka pokazuje komunikat. BouncyCastle jest wyłączony z PdfBox
   (konflikt klas z innymi bibliotekami); PDF szyfrowane certyfikatem nie
   zadziałają. Jeśli Twoja aplikacja nie ma BouncyCastle i chcesz go,
   dodaj `org.bouncycastle:bcprov-jdk18on`.
7. **Pamięć.** Duże rysunki (setki MB DXF) i zdjęcia z aparatu są duże
   w pamięci. Moduł sam obniża rozdzielczość (obrazy do 4096 px, strony
   PDF według powiększenia), ale `android:largeHeap="true"` pomaga przy
   bardzo dużych arkuszach.
8. **Języki.** Teksty są w zasobach modułu (21 języków). Jeśli Twoja
   aplikacja ogranicza zasoby (`androidResources.localeFilters` /
   `resourceConfigurations`), przeglądarka pokaże się w języku z listy albo
   po angielsku.
9. **Motyw.** Komponenty biorą kolory z `MaterialTheme` — opakuj je w swój
   motyw. Okna pełnoekranowe chowają paski systemu (gest od krawędzi je
   pokazuje).
10. **Wersje Compose.** Moduł jest testowany z BOM 2025.05.01. Starszy BOM
    w aplikacji może nie mieć użytych API (np. `pluralStringResource`
    w tej postaci) — trzymaj BOM ≥ 2025.05.01.
11. **Marginesy systemowe (targetSdk 35+).** Android rysuje od krawędzi do
    krawędzi, a komponenty nie dodają marginesów same — w zwykłym widoku
    treść wchodzi pod pasek tytułu, stan i nawigację (sprawdzone na
    telefonie). Daj im `Modifier.safeDrawingPadding()` albo umieść
    w `Scaffold` i użyj jego `innerPadding`. Wyjątek: `SheetViewerDialog`
    i `FullscreenImageViewer` są pełnoekranowe i obsługują to same.
12. **Dostęp do plików.** Komponenty czytają plik bezpośrednio — aplikacja musi
    mieć do niego prawo (własny katalog, `cacheDir`, pliki z SAF skopiowane
    do siebie). Brak dostępu daje czytelny komunikat w miejscu dokumentu,
    a nie awarię.
13. **Room z KSP w aplikacji gospodarza.** Z Kotlin 2.1.21 KSP domyślnie
    używa KSP2, a Room 2.6.1 pada wtedy na „unexpected jvm signature V”.
    Pomaga `ksp.useKSP2=false` w `gradle.properties` (albo Room 2.7+).
    Sam rdzeń nie używa KSP — to pułapka zestawu wersji, zgłoszona z SUMPTEN.
14. **DWG z LibreDWG: DXF tekstowy i wersja ≥ 0.14.8612.** Binarny DXF
    z LibreDWG gubi kolory i większość kreskowań (132 z 351 i 712 z 3150
    wypełnień wg ODA). Wydania 0.14 sprzed 0.14.8612 czytają też niektóre
    napisy poza ich końcem: za tekstem lądują strzępy innych napisów, czasem
    ze znakiem nowej linii, który psuje plik. To śmieci — **nie doklejaj ich**.
    Czytnik je pomija; 0.14.8612 ich w ogóle nie tworzy (na rysunkach
    użytkownika wynik identyczny z ODA). Moduł `dwg` jest na 0.14.8612.
15. **Pierwsza kompilacja `dwg` ze źródeł** buduje część natywną przez CMake
    (około minuty). Statyczna LibreDWG jest gotowa w `libs/`; przebudowa:
    `dwg/build-libredwg.sh`.

## Aktualizacja

W repozytorium ARMA Files: `scripts/export-viewer-core.sh [katalog]` —
nadpisuje `viewer/`, `dwg/` i `aar/` w eksporcie i zapisuje nowy `VERSION`.
Po aktualizacji przejrzyj listę zmian w `VERSION` i przebuduj aplikację.
