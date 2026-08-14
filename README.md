# ARMA Files v0.1.0

Nowoczesny menedżer plików na Androida napisany w Kotlinie i Jetpack Compose.

## Najważniejsze funkcje
- Material 3, tryb ciemny i dynamiczne kolory
- Storage Access Framework z trwałym uprawnieniem do wybranego drzewa katalogów
- lista i responsywna siatka plików
- wyszukiwanie i sortowanie po nazwie, dacie, rozmiarze i typie
- tworzenie folderów i plików
- zmiana nazwy i usuwanie
- kopiowanie, wycinanie i wklejanie, także folderów rekurencyjnie
- bezpieczne przenoszenie: źródło jest usuwane dopiero po utworzeniu kopii
- globalna lista ostatnio otwieranych plików
- globalne ulubione
- podgląd grafik, także SVG przez Coil
- podgląd i prosta edycja TXT/MD/JSON/YAML/XML oraz popularnych plików kodu
- udostępnianie i otwieranie plików w innych aplikacjach przez `content://`
- obsługa `ACTION_VIEW`, więc ARMA Files może być aplikacją otwierającą pliki
- `WindowInsets.safeDrawing`, dzięki czemu przyciski nie chowają się pod paskiem systemowym Androida
- własna adaptacyjna ikona aplikacji

## Dostęp do pamięci
Aplikacja celowo korzysta z Android Storage Access Framework zamiast `MANAGE_EXTERNAL_STORAGE`. Przy pierwszym uruchomieniu wybierasz katalog startowy, a aplikacja zachowuje przyznany dostęp za pomocą `takePersistableUriPermission()`.

To rozwiązuje problem typowy dla listy „Ostatnie”: pliki znajdujące się w zapamiętanym drzewie katalogów nadal mają dostęp po ponownym uruchomieniu aplikacji.

## Budowanie na Linuxie
Projekt ma wrapper Gradle 8.11.1. W paczce skrypt `gradlew` potrafi sam pobrać brakujący oficjalny `gradle-wrapper.jar`, zweryfikować SHA-256 i dopiero wtedy go uruchomić.

```bash
chmod +x gradlew
./gradlew assembleDebug
```

APK po poprawnym buildzie:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Możesz też po prostu otworzyć katalog projektu w Android Studio i wykonać Gradle Sync.

## Stos
- Kotlin 2.1.21
- Jetpack Compose + Material 3
- Android Gradle Plugin 8.10.1
- Gradle 8.11.1
- minSdk 26
- targetSdk / compileSdk 35
- Coil 3 + SVG

## Następne sensowne rozszerzenia
- pasek postępu i WorkManager dla dużych operacji kopiowania
- multi-select
- ZIP: kompresja i rozpakowywanie
- miniatury zdjęć i filmów bezpośrednio na liście
- zakładki do wielu katalogów startowych
- MediaStore: szybkie sekcje Obrazy / Wideo / Audio / Dokumenty
- opcjonalny tryb pełnego dostępu do pamięci dla instalacji poza Google Play
- SMB / SFTP / WebDAV
- panel właściwości pliku, checksumy i szczegóły EXIF
