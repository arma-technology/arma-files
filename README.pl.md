# ARMA Files

[English](README.md) | **Polski**

Menedżer plików dla Androida z przeglądarką PDF i rysunków CAD. Pamięć
urządzenia, karty SD i pendrive'y, Google Drive, OneDrive, Dropbox, FTP/FTPS,
SFTP, SMB i WebDAV w jednym miejscu, z podglądem większości plików bez
wychodzenia z aplikacji.

**[Pobierz najnowszą wersję (APK)](https://github.com/arma-technology/arma-files/releases/latest)** · wymagany Android 8.0 lub nowszy

Historia zmian: [CHANGELOG.md](CHANGELOG.md) · plan: [ROADMAP.md](ROADMAP.md) · [prywatność](PRIVACY.md)

## Co potrafi

**Przeglądanie**
- **Głębia**: folder otwiera się jako warstwa nad poprzednim, karty pokazują
  żywą zawartość, przytrzymanie pliku otwiera szybki podgląd, koło akcji
  kopiuje, udostępnia i usuwa bez menu, a szuflada zbiera pliki z różnych
  folderów i wrzuca je tam, gdzie trzeba
- motywy Noc i Papier z kolorem akcentu, rozmyciem warstw i „Spokojnym
  ruchem”, który skraca albo wyłącza animacje
- ekran „Moje urządzenie”: przypięte foldery, pamięć wbudowana, karty SD,
  podłączone zasoby sieciowe i chmurowe
- Wstecz wraca tam, skąd się przyszło; karty i historia przetrwają ponowne
  uruchomienie

**Pliki**
- kopiowanie i przenoszenie między dowolnymi źródłami w tle, kosz,
  zmiana nazwy, SHA-256, Ostatnie i Ulubione
- **archiwa** otwierane jak foldery i rozpakowywane w tle: ZIP (także
  z hasłem ZipCrypto/AES), 7z (także z hasłem), RAR i RAR5, tar oraz
  tar.gz/bz2/xz/zst/lz4/br, gz, bz2, xz, zst, lzma, ISO, CAB, LHA, XAR, ar/deb,
  cpio, arj; na żądanie także APK, DOCX, ODT, EPUB i inne oparte na ZIP;
  tworzenie ZIP (z hasłem AES-256), 7z, tar, tar.gz, tar.xz, tar.zst
- **mapa pamięci**: nośnik jako prostokąty o polu proporcjonalnym do zajętego
  miejsca, z przybliżaniem w głąb folderów
- **duplikaty**: gdzie leży każda kopia, miniatury, usuwanie do kosza
- pliki offline otwierają się od razu z kopii, serwer sprawdzany w tle
- indeks plików z wyszukiwaniem i statystykami folderów; sortowanie
  zapamiętywane osobno dla każdego folderu

**Podglądy**
- obrazy (także SVG) z przesuwaniem i powiększaniem pod palcem, pliki tekstowe
  z rozpoznawaniem kodowania, Markdown, arkusze (xlsx, CSV) jako tabela
- odtwarzacz audio i wideo (Media3) z okładkami, kolejką i wieloma formatami;
  pliki z sieci pobierane z paskiem postępu
- **PDF**: powiększenie do 6400%, wyszukiwanie tekstu, warstwy, komentarze
  (oglądanie, dodawanie, zapis), pomiary
- **CAD**: DXF natywnie, DWG przez [LibreDWG](https://www.gnu.org/software/libredwg/);
  warstwy, rodzaje linii, kreskowania, wymiary, wyszukiwanie tekstu
- **pomiary w PDF i CAD**: przyciąganie do końców, przecięć, środków
  i prostopadłej, tryb osi do mierzenia odległości od ściany; skala odczytana
  z pliku, z tabelki rysunkowej albo z kalibracji

**Języki:** angielski, polski, albański, białoruski, bośniacki, bułgarski,
chorwacki, czeski, estoński, litewski, łotewski, macedoński, niderlandzki,
niemiecki, rosyjski, rumuński, serbski, słowacki, słoweński, ukraiński,
węgierski. Poprawki tłumaczeń są mile widziane — pliki `res/values-*/strings.xml`.

Przeglądarka PDF i CAD to osobne moduły (`viewer/`, `dwg/`), które można
przenieść do innej aplikacji — opis w [viewer/README.md](viewer/README.md).

## Budowanie

Wymagania: JDK 17, Android SDK 36, NDK 28.2 i CMake 3.31 (dla modułów `dwg`
i `libarchive`), [Git LFS](https://git-lfs.com) — skompilowane biblioteki
LibreDWG i libarchive są w LFS.

```bash
git lfs install
git clone https://github.com/arma-technology/arma-files.git
cd arma-files
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
scripts/test.sh                # testy jednostkowe
```

Biblioteki można zbudować samodzielnie: `dwg/build-libredwg.sh`,
`libarchive/build-libarchive.sh`.

Opcjonalnie w `local.properties` (plik poza repozytorium):

| Klucz | Do czego |
|---|---|
| `storeFile`, `storePassword`, `keyAlias`, `keyPassword` | podpis wydania |
| `sourceUrl` | adres kodu źródłowego pokazywany w aplikacji (dla forków) |

## Uprawnienia

Na Androidzie 11+ aplikacja prosi o „Dostęp do wszystkich plików”
(`MANAGE_EXTERNAL_STORAGE`), bo zarządzanie plikami to jej podstawowa funkcja.
Bez niego działa w trybie ograniczonym przez systemowy wybór folderów (SAF).
Hasła do FTP/SFTP/SMB są szyfrowane AES-GCM kluczem z Android Keystore.

## Zgłaszanie błędów

Błędy i pomysły zgłaszaj w [Issues](https://github.com/arma-technology/arma-files/issues),
po polsku albo po angielsku. Przy błędzie podglądu najbardziej pomaga plik,
na którym występuje — jeśli nie może być publiczny, wystarczy opis, wersja
aplikacji i model telefonu.

## Licencja

Copyright © ARMA

Program jest wolnym oprogramowaniem: możesz go rozpowszechniać i modyfikować
na warunkach [GNU General Public License](LICENSE) w wersji 3 lub (według
Twojego wyboru) dowolnej późniejszej. Rozpowszechniany jest bez jakiejkolwiek
gwarancji.

Dołączone biblioteki mają licencje zgodne z GPL-3.0: LibreDWG (GPL-3.0+),
PdfBox-Android, AndroidX, Coil, OkHttp, Commons Net, SSHJ, Commons Compress,
zip4j (Apache 2.0), jcifs-ng (LGPL-2.1), SLF4J, Brotli (MIT), libarchive,
zstd-jni (BSD-2-Clause), XZ for Java (0BSD).
