# Play Console — odpowiedzi do formularzy

Ściąga do wypełnienia formularzy przy publikacji. Opisy sklepu są
w `fastlane/metadata/android/`, polityka prywatności w [PRIVACY.md](../PRIVACY.md).

## Dane aplikacji

- Nazwa: ARMA Files; pakiet `pl.arma.files`; aplikacja bezpłatna
- Kategoria: Narzędzia (Tools)
- Polityka prywatności: adres publicznego `PRIVACY.md` w repozytorium
- Reklamy: nie
- Grupa docelowa: 18+ (aplikacja nie jest skierowana do dzieci)
- Aplikacja informacyjna/rządowa/finansowa/zdrowotna: nie

## Bezpieczeństwo danych (Data safety)

- Czy aplikacja zbiera lub udostępnia dane użytkownika? **Nie.**
  Dane (pliki, hasła do serwerów, indeks) zostają w urządzeniu albo trafiają
  tylko na serwer wskazany przez użytkownika — według definicji Google to nie
  jest „zbieranie”, bo nie trafiają do dewelopera ani stron trzecich.
- Szyfrowanie w transmisji: zależy od protokołu wybranego przez użytkownika
  (FTP i WebDAV po HTTP są nieszyfrowane) — przy pytaniu o szyfrowanie
  zebranych danych nie dotyczy, bo nic nie jest zbierane.
- Usuwanie danych: nie dotyczy (brak kont i serwera).

## Ocena treści (IARC)

Kategoria „Narzędzia / inne”. Na wszystkie pytania o przemoc, seks, język,
hazard, substancje: nie. Interakcja użytkowników / udostępnianie treści
innym: nie. Dostęp do internetu: tak, do serwerów skonfigurowanych przez
użytkownika. Wynik spodziewany: 3+ / PEGI 3.

## Deklaracje uprawnień

### Dostęp do wszystkich plików (`MANAGE_EXTERNAL_STORAGE`)

Zastosowanie: **menedżer plików** (File manager).

> ARMA Files is a file manager. Its core function is browsing, copying,
> moving, renaming, compressing and deleting any files and folders in shared
> storage, on SD cards and USB drives, and transferring them to and from
> network servers (FTP, SFTP, SMB, WebDAV). The Storage Access Framework or
> MediaStore cannot provide this: users need to see and manage all folders,
> including Download, Documents and project folders, file types not covered by
> MediaStore (DWG, DXF, ZIP, APK, documents), and whole storage volumes. Without
> the permission the app falls back to a limited mode with folders picked
> through the system picker.

### Instalowanie pakietów (`REQUEST_INSTALL_PACKAGES`)

Zastosowanie: **zarządzanie plikami** (file management) — na liście
dozwolonych zastosowań.

> ARMA Files is a file manager. When the user taps an APK file in a folder,
> on an SD card or on a network server, the app passes it to the Android
> package installer so the user can install it, as file managers do. The app
> never downloads or installs packages on its own; every installation is
> started by the user and confirmed in the system installer dialog.

### Usługa na pierwszym planie (`FOREGROUND_SERVICE_DATA_SYNC`)

Typ: **dataSync**.

> The foreground service runs only while a file operation started by the user
> is in progress: copying or moving files between local storage and network
> servers (FTP, SFTP, SMB, WebDAV), or between storage volumes. It shows a
> notification with progress and a cancel button, and stops when the
> operation ends. Large transfers must continue when the user leaves the app.

## Wideo do deklaracji (30–60 s, jedno nagranie na wszystkie)

1. Uruchomienie aplikacji bez uprawnienia → ekran z prośbą → systemowe
   ustawienie „Dostęp do wszystkich plików” → włączenie.
2. „Mój komputer” → pamięć wbudowana → przegląd kilku folderów, w tym
   z plikami DWG/PDF/ZIP.
3. Zaznaczenie folderu → kopiowanie na serwer SMB/SFTP → powiadomienie
   z postępem, wyjście z aplikacji, operacja trwa dalej.
4. Dotknięcie pliku APK → systemowy instalator z prośbą o potwierdzenie.

Nagranie: `adb shell screenrecord /sdcard/Download/demo.mp4` (do 3 min),
wrzucić jako film „niepubliczny” na YouTube i podać link.

## Grafiki

- ikona 512×512 PNG (z `ic_launcher`, bez przezroczystych rogów — Play sam
  zaokrągla)
- grafika promocyjna 1024×500
- zrzuty ekranu z telefonu: min. 2, najlepiej 4–8 (Mój komputer, lista
  plików, PDF z pomiarem, DWG z warstwami, połączenia sieciowe, dwa panele)
