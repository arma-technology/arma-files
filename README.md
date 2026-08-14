# ARMA Files v0.2.0

Druga wersja lekkiego, nowoczesnego menedżera plików na Androida. Projekt jest napisany w Kotlinie i Jetpack Compose i korzysta z Android Storage Access Framework zamiast żądania pełnego dostępu do całej pamięci.

## Co nowego w v0.2

### Nowy wygląd
- własny nagłówek ARMA Files z podsumowaniem bieżącej lokalizacji
- odświeżona paleta jasna i ciemna zamiast przypadkowych kolorów dynamicznych systemu
- nowocześniejsze karty plików, większe promienie, lepsza hierarchia tekstu
- kolorystyczne rozróżnienie typów plików
- miniatury zdjęć bezpośrednio na liście i w siatce
- przebudowane wyszukiwanie, sortowanie i przełącznik lista/siatka
- atrakcyjniejszy ekran pierwszego uruchomienia i puste stany
- bezpieczne `WindowInsets.safeDrawing` — elementy aplikacji nie powinny chować się pod paskami systemowymi

### Organizacja
- filtry: Wszystko / Foldery / Obrazy / Wideo / Audio / Dokumenty / Archiwa
- liczba elementów i łączny rozmiar plików bieżącego widoku
- Ostatnie i Ulubione nadal działają globalnie
- zapamiętany katalog główny przez trwałe uprawnienie SAF

### Operacje na plikach
- wielokrotny wybór
- zaznacz wszystko
- kopiowanie wielu elementów
- przenoszenie wielu elementów
- usuwanie wielu elementów
- udostępnianie wielu plików
- tworzenie plików i folderów
- zmiana nazwy
- schowek z widocznym stanem i przyciskiem Wklej / Anuluj

### ZIP
- tworzenie archiwum `.zip` z jednego lub wielu plików/folderów
- rekurencyjne pakowanie folderów
- rozpakowywanie ZIP do nowego katalogu
- ochrona przed klasycznym ZIP Slip (`../` w nazwach wpisów archiwum)

### Podgląd i właściwości
- obrazy i SVG przez Coil
- przewijalny podgląd plików tekstowych
- edycja TXT/MD/JSON/YAML/XML i popularnych plików kodu
- otwieranie pozostałych formatów w zewnętrznych aplikacjach
- panel właściwości: MIME, rozmiar, data modyfikacji, URI
- automatyczne obliczanie SHA-256 dla plików

## Dostęp do pamięci

ARMA Files używa Storage Access Framework. Przy pierwszym uruchomieniu wybierz katalog, którym aplikacja ma zarządzać. Uprawnienie jest utrwalane za pomocą `takePersistableUriPermission()`, więc po restarcie aplikacja może wrócić do wybranej lokalizacji bez ponownego pytania o dostęp.

To podejście jest zgodne z nowoczesnym modelem bezpieczeństwa Androida i nie wymaga `MANAGE_EXTERNAL_STORAGE`.

## Budowanie na Linuxie

```bash
chmod +x gradlew
./gradlew assembleDebug
```

APK po poprawnym buildzie:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Możesz również otworzyć projekt w Android Studio i wykonać Gradle Sync / Build APK.

## Stos
- Kotlin 2.1.21
- Jetpack Compose + Material 3
- Android Gradle Plugin 8.10.1
- Gradle 8.11.1
- minSdk 26
- targetSdk / compileSdk 35
- Coil 3 + SVG

## Kandydaci do v0.3
- historia kilku katalogów startowych / zakładki lokalizacji
- MediaStore i szybkie kolekcje całego urządzenia
- miniatury filmów
- odtwarzacz audio/wideo
- EXIF dla zdjęć
- pasek postępu i anulowanie długich operacji
- przeniesienie dużych operacji do WorkManager
- SMB / SFTP / WebDAV
- opcjonalne szyfrowane „Sejf” i zabezpieczenie biometrią
