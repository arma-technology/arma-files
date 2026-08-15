# ARMA Files — plan rozwoju

Kolejność wynika z tego, co **blokuje** co, a nie z tego, co najładniej
wygląda na zrzucie ekranu. Punkty pochodzą z roadmapy Artura; tutaj są
tylko uszeregowane i pogrupowane w fazy, które da się dowieźć i przetestować
w całości.

## Co dziś ogranicza rozwój (ustalone przez przegląd kodu v0.4.1)

1. **Operacje żyją w `viewModelScope`** (`FileManagerViewModel.launchOperation`).
   Wyjście z aplikacji przerywa kopiowanie. Nie ma postępu, anulowania,
   kolejki ani obsługi konfliktów nazw.
2. **`paste()` z `cut` nie jest atomowe.** Najpierw `copyAll` wszystkich
   elementów, potem kasowanie. Gdy kopiowanie 3. pliku padnie, pliki 1–2
   są już skopiowane, ale nie skasowane — użytkownik zostaje z częściowym
   stanem i bez informacji, co się udało.
3. **Przenoszenie zawsze kopiuje bajt po bajcie.** Nawet w obrębie tego
   samego nośnika, gdzie wystarczyłby `rename`. Przeniesienie 4 GB na tej
   samej karcie trwa tyle, co pełna kopia.
4. **Jedno globalne konto FTP i jedno SMB.** `NetworkPrefs.ftp()`/`smb()`
   zwracają pojedynczą konfigurację, a `RemoteRepository` czyta ją wewnątrz
   każdego helpera ścieżki. Autorytet URI jest zaszyty na stałe
   (`armaftp://ftp/…`). Wiele kont, SFTP, WebDAV i autowykrywanie SMB są
   tym zablokowane.
5. **FTP zestawia nowe połączenie na każdą operację** (connect + login +
   operacja + logout). Miniatura każdego pliku w katalogu to osobne
   połączenie FTP.
6. **Zero testów** przy ~4100 liniach kodu.

## Fazy

### Faza 1 — silnik operacji (rdzeń v0.5) — ZROBIONE

Fundament pod sześć punktów roadmapy naraz: kosz, cofanie, historia,
pasek postępu, kolejka w tle, konflikty nazw.

- kolejka operacji w foreground service — przeżywa wyjście z aplikacji
- postęp w bajtach i w plikach, prędkość, czas pozostały
- anulowanie, pauza, wznowienie
- konflikty nazw: zamień / pomiń / zachowaj oba / zastosuj do wszystkich
- przenoszenie przez `rename`, gdy źródło i cel są na tym samym nośniku
- atomowy `cut`: kasowanie źródła dopiero po potwierdzonej kopii, per plik
- kosz jako `.armafiles_trash` w obrębie nośnika + dziennik metadanych
- cofanie ostatniej operacji i historia operacji

Zrobione poza planem, bo dotykało tego samego ekranu: własne przypięte
foldery z przeciąganiem kolejności oraz miejsce zajęte na kartach nośników.
Zostaje z „Mój komputer": wykres zajętości bogatszy niż pasek postępu.

Kosz dla folderów SAF został domknięty w Fazie 2.

### Faza 2 — konta i backendy — ZROBIONE

Odblokowuje cztery punkty roadmapy.

- `StorageBackend` jako interfejs; autorytet URI staje się identyfikatorem
  konta (`armasmb://<id>/…`), dzięki czemu zakładki i ulubione pozostają
  stabilne
- wiele połączeń tego samego typu (NAS Dom, NAS Firma, Proxmox…)
- SFTP i WebDAV jako nowe backendy
- pula połączeń — koniec z łączeniem od nowa przy każdej miniaturze
- autowykrywanie SMB w LAN zakładające konta jednym kliknięciem

Zrealizowane bez mDNS/NetBIOS: rozgłoszenia bywają odfiltrowane, więc
skanujemy podsieć po porcie 445. Sieci większe niż /22 pomijamy, bo
skanowanie trwałoby zbyt długo.

Domknięte przy okazji: kosz dla folderów SAF — korzeniem jest katalog
drzewa, którego identyfikator niesie samo URI dokumentu.

### Faza 3 — nawigacja i duży ekran

- prawdziwe zakładki katalogów
- tryb dwóch paneli na tablecie i w poziomie, przeciąganie między panelami
- lepszy „Mój komputer”: własne przypięte foldery, kolejność, wolne
  miejsce, wykres zajętości, USB OTG, aktywne udziały

### Faza 4 — indeks i porządki

- lokalny indeks nazw/typów/rozmiarów/dat (Room + FTS)
- globalne wyszukiwanie po całym urządzeniu i aktywnych zasobach
- kategorie obejmujące całą pamięć, sortowanie „największe pliki”
- analizator pamięci — pokazuje fakty, nie kasuje za użytkownika
- duplikaty: najpierw rozmiar, potem SHA-256

### Faza 5 — media, PDF v2, integracja z systemem

- galeria: gest lewo/prawo bez zamykania podglądu, slideshow, EXIF, obrót
- informacje o wideo/audio i prosty natywny odtwarzacz
- PDF v2: wyszukiwanie tekstu, zakładki, obrót, ostatnia strona, spis
  treści, tryb nocny/sepia
- archiwa jak katalogi: wejście do ZIP, podgląd i wypakowanie pojedynczych
  elementów
- integracja Androida: „Otwórz za pomocą”, wybór pliku dla innych aplikacji,
  odbieranie przez Share, skróty do folderów
- personalizacja UI: rozmiar ikon, wysokość wierszy, zaokrąglenia, widok
  kompaktowy/normalny/duży

### Panel inspektora

Znak rozpoznawczy aplikacji — boczny panel po długim przytrzymaniu, z
miniaturą, rozmiarem, MIME, datami, SHA-256, ścieżką, EXIF i szybkimi
akcjami. Wymaga EXIF z fazy 5 i SHA-256 liczonego w tle przez silnik z
fazy 1, więc powstaje po nich; sam panel jest pracą na jeden wieczór, gdy
dane już są.

## Zasady

- Nowy kod ma testy jednostkowe. Zaczynamy od silnika operacji, bo jego
  logika (konflikty, wznawianie, kosz) jest testowalna bez Androida.
- Każda faza kończy się działającym, zbudowanym APK.
