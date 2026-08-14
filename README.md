# ARMA Files v0.3.0

Trzecia wersja nowoczesnego menedżera plików dla Androida. V3 zachowuje interfejs i operacje z v0.2, a warstwę plików rozszerza o konfigurowalne źródła lokalne, chmurowe i sieciowe.

## Co nowego w v0.3

### Źródła włączane w Ustawieniach
- Google Drive
- OneDrive
- Dropbox
- FTP / FTPS
- SMB / Samba
- pamięć urządzenia pozostaje źródłem podstawowym

Każde opcjonalne źródło ma osobny przełącznik. Wyłączone źródła nie pojawiają się na ekranie **Pliki**, w Ostatnich ani w Ulubionych i nie są odpytywane w tle. Konfiguracja pozostaje zapisana, więc ponowne włączenie nie wymaga wpisywania wszystkiego od początku.

### Google Drive, OneDrive i Dropbox
Chmury są podpinane przez Android Storage Access Framework (systemowy wybór katalogu). Dzięki temu ARMA Files nie przechowuje własnych tokenów OAuth dla tych usług.

1. Włącz dostawcę w **Ustawienia → Źródła plików**.
2. Wybierz **Podłącz folder**.
3. W systemowym oknie Androida wybierz właściwego dostawcę i katalog.
4. Android przekazuje aplikacji trwałe uprawnienie do wybranego drzewa dokumentów.

Dostawca musi być dostępny w systemowym selektorze dokumentów jako `DocumentsProvider`. Zakres funkcji (np. zapis, zmiana nazwy) zależy także od możliwości udostępnionych przez danego providera.

### FTP / FTPS
- host, port, użytkownik, hasło i katalog startowy
- tryb pasywny
- transfer binarny
- opcjonalny FTPS (TLS)
- w FTPS włączona jest walidacja certyfikatu oraz nazwy hosta
- przycisk **Test** zapisuje bieżące pola i od razu sprawdza połączenie

Implementacja korzysta z Apache Commons Net.

### SMB / Samba
- host/IP, port, udział, katalog startowy
- opcjonalna domena
- użytkownik i hasło
- SMB1 jest wyłączony; minimalny dialekt to SMB 2.0.2
- obsługa od SMB2 do dialektów SMB3 dostępnych w jcifs-ng
- przycisk **Test** zapisuje bieżące pola i sprawdza katalog startowy

Implementacja korzysta z jcifs-ng.

### Jedna warstwa operacji
Operacje z v0.2 działają przez wspólną warstwę repozytorium, dlatego pliki mogą być kopiowane pomiędzy różnymi źródłami, np.:
- SMB → pamięć telefonu
- FTP → Google Drive
- OneDrive → SMB
- Dropbox → ZIP w pamięci lokalnej

Dostępne pozostają m.in. kopiowanie, przenoszenie, usuwanie, zmiana nazwy, tworzenie plików i katalogów, ZIP/rozpakowywanie, odczyt i zapis tekstu oraz SHA-256. Faktyczna możliwość zapisu zależy od uprawnień serwera lub providera.

## Bezpieczeństwo
- aplikacja nie wymaga `MANAGE_EXTERNAL_STORAGE`
- dostęp lokalny/chmurowy korzysta z trwałych uprawnień SAF
- hasła FTP/SMB są szyfrowane AES-GCM
- klucz AES jest generowany i przechowywany w Android Keystore
- FTPS weryfikuje certyfikat i nazwę hosta
- SMB1 jest domyślnie i jawnie wyłączony
- rozpakowywanie ZIP zachowuje ochronę przed ZIP Slip

> Zwykły FTP nie szyfruje danych logowania ani transferu. W niezaufanej sieci używaj FTPS. SMB również powinien być używany w zaufanej sieci lokalnej lub przez VPN.

## Funkcje odziedziczone z v0.2
- atrakcyjny interfejs Material 3 w stylu ARMA
- jasny i ciemny motyw
- lista i siatka
- miniatury obrazów
- filtry typów plików
- wyszukiwanie i sortowanie
- wielokrotny wybór
- Ostatnie i Ulubione
- kopiuj / wytnij / wklej
- ZIP / rozpakuj
- edytor i przewijalny podgląd tekstu
- właściwości pliku i SHA-256
- obsługa bezpiecznych `WindowInsets`

## Budowanie na Linuxie

```bash
chmod +x gradlew
./gradlew assembleDebug
```

APK po poprawnym buildzie:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Pierwsze uruchomienie wrappera/Gradle może wymagać dostępu do Internetu do pobrania narzędzi i zależności. Projekt można też otworzyć bezpośrednio w Android Studio.

## Stos
- Kotlin 2.1.21
- Jetpack Compose + Material 3
- Android Gradle Plugin 8.10.1
- Gradle 8.11.1
- minSdk 26
- targetSdk / compileSdk 35
- Coil 3 + SVG
- Apache Commons Net 3.13.0
- jcifs-ng 2.1.9

## Kolejne sensowne kierunki
- SFTP (SSH) obok FTP/FTPS
- WebDAV
- kilka kont / kilka udziałów tego samego typu
- pasek postępu i anulowanie dużych transferów
- WorkManager dla długich kopii w tle
- cache miniatur z sieci
- MediaStore i kolekcje całego urządzenia
- EXIF oraz odtwarzacz audio/wideo
