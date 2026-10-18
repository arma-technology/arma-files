# :dwg — odczyt DWG przez LibreDWG

Moduł zamienia rysunki DWG na binarny DXF, który czyta przeglądarka `:viewer`.
Używa biblioteki [GNU LibreDWG](https://www.gnu.org/software/libredwg/) 0.14,
dlatego **ten moduł jest na licencji GPL-3.0** (tekst w `COPYING`). Aplikacja,
która go zawiera i jest przekazywana innym, musi udostępniać swój kod źródłowy
na tej samej licencji. Bez tego modułu przeglądarka obsługuje PDF i DXF.

- konwersja w osobnym procesie (`:dwg`) — błąd biblioteki albo brak pamięci
  przy ogromnym rysunku nie zamyka aplikacji
- wyniki w pamięci podręcznej (`cacheDir/dwg_dxf`, do 1,5 GB)
- obsługiwane wersje DWG: R13–2018 (AC1012–AC1032); sprawdzone na 36
  rysunkach w wersjach 2004, 2007, 2013 i 2018

## Użycie

```kotlin
LibreDwgConverter.install() // np. w Application.onCreate
```

## Odtworzenie bibliotek `libs/<abi>/libredwg.a`

`./build-libredwg.sh` pobiera źródła 0.14 i buduje je NDK dla arm64-v8a i x86_64.
