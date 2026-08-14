# ARMA Files v0.4.0

ARMA Files to nowoczesny menedżer plików dla Androida z lokalną pamięcią urządzenia, kartami SD, zasobami chmurowymi i sieciowymi oraz wbudowanymi podglądami plików.

## Najważniejsze zmiany w v0.4.0

### Ekran „Mój komputer”
Główny ekran został przebudowany na wzór klasycznego widoku „Mój komputer” z PC:

1. **Przypięte foldery** – na górze znajdują się skróty do `Pobrane` i `Zdjęcia` oraz przycisk dodania folderu przez SAF.
2. **Ten komputer** – osobne karty pamięci wbudowanej i wykrytych kart SD. Karty pokazują wolne miejsce, całkowitą pojemność i pasek zajętości.
3. **Połączone zasoby** – Google Drive, OneDrive, Dropbox, FTP/FTPS, SMB/Samba oraz opcjonalny folder SAF. Sekcja jest wyświetlana tylko wtedy, gdy istnieje aktywne i skonfigurowane źródło. Wyłączone lub nieskonfigurowane zasoby nie zajmują miejsca na ekranie.

### Pełny dostęp lokalny
Dla Androida 11+ ARMA Files może korzystać ze specjalnego uprawnienia `MANAGE_EXTERNAL_STORAGE`, ponieważ podstawową funkcją aplikacji jest zarządzanie plikami. Po jego przyznaniu aplikacja może przeglądać pamięć współdzieloną i kartę SD przez lokalny backend `file://`.

SAF nadal pozostaje dostępny jako tryb alternatywny do przypinania pojedynczych folderów i do integracji z dostawcami dokumentów.

> Publikacja aplikacji z `MANAGE_EXTERNAL_STORAGE` w Google Play podlega dodatkowym zasadom Google Play. Dla prywatnej instalacji APK uprawnienie jest przyznawane przez systemowy ekran „Dostęp do wszystkich plików”.

### Natywna obsługa obrazów
- JPEG / PNG / WebP / GIF / BMP / HEIF / HEIC / AVIF są dekodowane natywnie przez Androida.
- Podgląd obrazu ma zoom do 800%, pinch-to-zoom, przesuwanie oraz „dopasuj”.
- Obrazy z FTP, SMB i chmur są buforowane lokalnie przed dekodowaniem.
- SVG pozostaje obsługiwany przez Coil SVG.
- Miniatury nie są już kadrowane do kwadratu. `ContentScale.Fit` pokazuje cały obraz z zachowaniem proporcji.
- Miniatury rastrowe są generowane także dla źródeł FTP/SMB, a nie tylko URI `content://`.

### PDF – czytnik ciągły
- ciągłe pionowe przewijanie wszystkich stron,
- leniwe renderowanie stron – bitmapy powstają dopiero dla widocznych stron,
- pasek miniaturek stron,
- kliknięcie miniatury przewija do strony,
- licznik `bieżąca / wszystkie` jest przyciskiem skoku do numeru strony,
- przyciski poprzednia / następna,
- zoom 100–400%, przewijanie poziome powiększonego dokumentu,
- pinch-to-zoom wykonywany dwoma palcami bez blokowania zwykłego przewijania,
- PDF z Google Drive, OneDrive, Dropbox, FTP/FTPS i SMB jest obsługiwany przez ten sam viewer.

Renderowanie nadal korzysta z systemowego Android `PdfRenderer`.

### Dynamiczna liczba kolumn
W katalogu można zmieniać gęstość widoku gestem poziomym:

- przeciągnięcie **w lewo**: lista → 2 kolumny → 3 → 4 → 5,
- przeciągnięcie **w prawo**: 5 → 4 → 3 → 2 → lista.

Przycisk lista/siatka nadal pozostaje dostępny.

## Źródła plików

Opcjonalne źródła są zarządzane w **Ustawienia → Źródła plików**:

- Google Drive przez Android Storage Access Framework,
- OneDrive przez Android Storage Access Framework,
- Dropbox przez Android Storage Access Framework,
- FTP / FTPS,
- SMB / Samba.

Wyłączenie źródła zachowuje jego konfigurację, ale usuwa je z widoku „Mój komputer”, Ostatnich i Ulubionych.

## Operacje plikowe

Wspólna warstwa repozytorium obsługuje teraz SAF, `file://`, FTP i SMB. Dostępne są m.in.:

- kopiowanie i przenoszenie pomiędzy różnymi źródłami,
- tworzenie folderów i plików,
- zmiana nazwy,
- usuwanie,
- ZIP i rozpakowywanie z ochroną przed ZIP Slip,
- edycja tekstu,
- SHA-256,
- Ostatnie i Ulubione,
- udostępnianie i otwieranie w zewnętrznych aplikacjach przez FileProvider.

## Bezpieczeństwo

- hasła FTP/SMB są szyfrowane AES-GCM,
- klucz jest przechowywany w Android Keystore,
- SMB1 pozostaje wyłączony,
- FTPS używa TLS,
- ZIP zachowuje ochronę przed ścieżkami `../`,
- lokalne pliki przekazywane innym aplikacjom są kopiowane do prywatnego cache i wystawiane przez FileProvider zamiast `file://`.

## Budowanie

```bash
chmod +x gradlew
./gradlew assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Stos

- Kotlin 2.1.21
- Jetpack Compose + Material 3
- Android Gradle Plugin 8.10.1
- Gradle 8.11.1
- minSdk 26
- targetSdk / compileSdk 35
- Android PdfRenderer
- Android ImageDecoder / BitmapFactory
- Coil 3 + SVG
- Apache Commons Net 3.13.0
- jcifs-ng 2.1.9
