# Changelog

## 0.10.1 (w toku)

- aplikacja jest wolnym oprogramowaniem na licencji GPL-3.0-or-later; w
  Ustawieniach karta „O aplikacji” z licencją, linkiem do kodu i listą bibliotek
- 21 języków: angielski (domyślny), polski, albański, białoruski, bośniacki,
  bułgarski, chorwacki, czeski, estoński, litewski, łotewski, macedoński,
  niderlandzki, niemiecki, rosyjski, rumuński, serbski, słowacki, słoweński,
  ukraiński, węgierski; wybór w Ustawieniach → Język (oraz w ustawieniach
  systemu na Androidzie 13+), poprawna odmiana liczebników w każdym języku
- separator dziesiętny pomiarów zgodny z językiem; skala z tabelki
  rozpoznawana także jako „Maßstab”, „měřítko”, „масштаб” itd.
- analiza pamięci: „Stare pliki” pokazują datę zmiany zamiast ścieżki opisanej
  jako data
- pendrive i karta SD pojawiają się na ekranie „Mój komputer” od razu po
  podłączeniu (pendrive z ikoną USB); po wyjęciu nośnika zamykają się jego
  karty i znikają jego pliki z wyników wyszukiwania i analizy
- katalog główny pamięci podpisany nazwą nośnika zamiast „0”, katalog
  startowy serwera nazwą konta zamiast „Katalog główny”
- pole portu w edytorze połączenia daje się wyczyścić i wpisać od nowa
  (wcześniej ostatnia cyfra zostawała); puste oznacza port domyślny
- plik otwarty z innej aplikacji pokazuje rozmiar zamiast „0 B”
- **archiwa jak foldery**: dotknięcie ZIP, 7z, RAR/RAR5, tar (także
  skompresowanego), gz, bz2, xz, zst, ISO, CAB, LHA i innych otwiera je jak
  folder; pliki z wnętrza da się podglądać, kopiować i wypakowywać wybiórczo;
  „Przeglądaj zawartość” dla APK, DOCX, ODT, EPUB i innych opartych na ZIP
- archiwa z hasłem: ZIP (ZipCrypto i AES) i 7z, także z zaszyfrowanym spisem;
  polskie nazwy z archiwów ZIP utworzonych w Windows (CP852)
- rozpakowywanie w kolejce operacji z postępem i działaniem w tle; „Anuluj”
  wstrzymuje i pyta, a po potwierdzeniu usuwa wypakowane do tej pory pliki
- pakowanie do ZIP (opcjonalnie z hasłem AES-256), 7z, tar, tar.gz, tar.xz
  i tar.zst
- obraz w poziomie od razu na pełnym ekranie z narzędziami na nim; rysunek
  CAD wypełnia cały ekran i otwiera się w całości
- Ustawienia → „Miejsce zajmowane przez aplikację”: rozbicie na indeks,
  rysunki DWG, podglądy, kopie archiwów, inną pamięć podręczną, kopie offline,
  kosz, ustawienia, program i inne dane; „Wyczyść” przy każdej części
  i „Wyczyść wszystko”
- indeks plików: automatyczne budowanie (przy starcie, co kilka dni, po
  zmianie zajętości pamięci), także w tle; po każdym budowaniu usuwane są
  wpisy plików, których już nie ma (wcześniej zostawały w bazie na zawsze),
  a baza jest kompaktowana
- wyszukiwanie nie wymaga indeksu: bez niego pliki są przeszukiwane na żywo
  (wcześniej okienko nie miało pola i nie dało się nic wpisać); bez polskich
  znaków i wielkości liter; opcja „Przeszukaj również zdalne zasoby”
  (włączone serwery, folder po folderze, z informacją, gdzie trwa szukanie)
- powrót z folderu albo archiwum zostawia listę w tym samym miejscu
- **dostępne offline**: pliki i foldery z serwerów można trzymać w telefonie;
  bez zasięgu albo gdy serwer nie odpowiada w 5 s, lista i pliki otwierają się
  z kopii (z paskiem „kopia offline z …”), a zmiany zapisane bez połączenia
  trafiają do kopii; sekcja „Dostępne offline” na ekranie głównym
- synchronizacja offline w obie strony: zmiana tylko na serwerze trafia do
  telefonu, zmiana tylko w telefonie — na serwer; gdy plik zmienił się po obu
  stronach, powiadomienie „Rozbieżność wersji” pokazuje rozmiar i datę obu
  wersji i pyta, którą zachować (z serwera, z telefonu albo obie)
- ustawienia plików offline: miejsce kopii (pamięć aplikacji albo folder
  „ARMA Files offline”, z przeniesieniem istniejących kopii), synchronizacja
  w tle co 15 min / 1 h / 6 h / 24 h albo ręcznie, tylko przez Wi-Fi
- zmieniony klucz serwera SFTP albo certyfikat FTPS: okno z zapamiętanym
  i nowym odciskiem oraz decyzją „Zaufaj nowemu” / „Nie łącz” (wcześniej
  połączenie było zablokowane na stałe)
- aplikacja prosi o zgodę na powiadomienia (Android 13+) — bez niej nie było
  widać nawet postępu kopiowania
- **odświeżanie na żywo**: folder w pamięci telefonu i na SMB odświeża się sam,
  gdy coś się w nim zmieni (SMB przez powiadomienia serwera); FTP, SFTP
  i WebDAV są sprawdzane co 15 s; po powrocie do aplikacji widoczne foldery
  są sprawdzane od razu
- Ostatnie i Ulubione nie łączą się z serwerami przy starcie (nazwa, rozmiar
  i data są zapamiętane), więc niedostępny serwer nie spowalnia startu
- **limit pamięci podręcznej** (Ustawienia): rozmiar (domyślnie 500 MB) i wiek
  (domyślnie 7 dni bez użycia) dla rysunków DWG przekonwertowanych do DXF,
  kopii plików z sieci i archiwów; „Wyczyść teraz”
- poprawka: po edycji połączenia sieciowego aplikacja do restartu łączyła się
  ze starym adresem albo portem
- PDF i CAD: pionową stronę na telefonie w poziomie da się oddalić do całej
  strony (wcześniej najmniej do szerokości ekranu); „Dopasuj” przełącza
  między szerokością a całą stroną
- Android 16 (API 36) jako docelowy; poprawka karty Język przy kontekście
  z podmienionym językiem
- po zmianie języka aplikacja zostaje na bieżącym ekranie, a tytuły kart
  przechodzą na nowy język

Połączenia sieciowe (sprawdzone na serwerach testowych i na telefonie):

- SMB łączy się znowu — od wykluczenia starszego BouncyCastle brakowało
  jCIFS klasy DERApplicationSpecific i każde połączenie kończyło się błędem
  i zawieszeniem (jcifs-ng 2.1.10)
- SFTP działa na Androidzie — wbudowany w system okrojony BouncyCastle nie ma
  X25519, którym serwery SSH wymieniają klucze
- zapis na SMB i FTP szedł bajt po bajcie: 2 MB na SMB ponad minutę, teraz
  ułamek sekundy
- SMB otwiera pliki z polskimi znakami i spacją w nazwie
- FTP i SFTP bez katalogu startowego otwierają katalog domowy użytkownika, nie
  katalog główny serwera
- dowiązania do katalogów (FTP, SFTP) otwierają się jak katalogi
- SFTP: zapamiętany klucz serwera i ostrzeżenie przy jego zmianie, logowanie
  „keyboard-interactive”, limit czasu przy serwerze bez SFTP (wcześniej
  aplikacja czekała bez końca), szybszy transfer
- FTPS: zapamiętany certyfikat serwera (NAS z adresem IP i własnym
  certyfikatem wcześniej się nie łączył, a dowolny certyfikat z pasującą
  nazwą był przyjmowany), czytelny komunikat, gdy serwer wymaga wznawiania
  sesji TLS
- odmowa serwera FTP pokazuje błąd zamiast pustego katalogu
- WebDAV: zmiana nazwy na nazwę z polskimi znakami, zapis dużych plików przez
  plik tymczasowy zamiast pamięci
- usunięte logowanie do Google Drive przez API (wymagało własnego projektu
  Google i audytu przy publikacji); Google Drive działa przez aplikację Dysk
  w Ustawieniach → Źródła plików
- edytor połączenia: błąd testu na czerwono, arkusz nie opada do połowy
- ekran „Mój komputer” nazywa się teraz „Moje urządzenie” (we wszystkich
  językach), z ikoną telefonu
- Wstecz wraca tam, skąd się przyszło: z zasobu, przypiętego folderu albo
  folderu z wyników wyszukiwania do poprzedniego miejsca, także na ekran
  główny (wcześniej otwarcie zasobu kasowało historię)
- duplikaty: przy każdej kopii nośnik i folder, data i miniatura; dotknięcie
  miniatury otwiera podgląd; kopie poza najstarszą zaznaczone do kosza,
  usuwane jednym przyciskiem (z cofnięciem)
- powiększony obraz przesuwa się razem z palcem (wcześniej przy 4× cztery
  razy wolniej), powiększa się wokół palców, ma rzut z bezwładnością
  i podwójne stuknięcie; przejście do sąsiedniego zdjęcia animowane, obraz
  idzie za palcem
- lista plików: wiersze i kafelki przesuwają się płynnie po sortowaniu
  i zmianach w folderze
- pliki offline otwierają się od razu z kopii, a serwer jest sprawdzany
  w tle — nie przy każdym dotknięciu (po błędzie coraz rzadziej, do 15 min);
  zapis trafia do kopii i od razu na serwer; nowa rozbieżność wersji od razu
  pyta w aplikacji, którą wersję zachować
- „Dodaj folder” w rzędzie przypiętych przypina wybrany folder na górze,
  obok Pobranych i Zdjęć (wcześniej lądował na dole wśród podłączonych);
  folder podłączony na dole ma w menu „Przypnij do „Moje urządzenie””
- plik usunięty na serwerze (np. z Ulubionych) nie zamyka aplikacji —
  pokazuje komunikat „Tego elementu nie ma już na serwerze.”
- zmiana nazwy na nazwę istniejącego pliku nie nadpisuje go już bez śladu
  (komunikat „już istnieje”); nazwy z serwerów („..”, ukośniki) nie mogą
  wyprowadzić kopii ani plików offline poza folder docelowy
- kategoria na ekranie głównym („Zdjęcia”, „Dokumenty”…) pokazuje swoje
  pliki; wcześniej nic się nie działo, a następny otwarty folder pokazywał
  listę kategorii zamiast swojej zawartości
- wyszukiwanie globalne z folderu nie gubi wyników z polskimi znakami ani
  ze słowami w innej kolejności; szukanie w folderze też bez polskich znaków;
  wyniki da się zaznaczać; folder otwarty z wyników otwiera się jako folder
- WebDAV: pliki z „+” w nazwie otwierają się (wcześniej 404), nazwa z adresu
  zamiast etykiety serwera
- po zmianie nazwy w folderze SAF Ulubione i Ostatnie pokazują nazwę, nie adres
- pasek zajętości nośnika z kolorowymi odcinkami kategorii, osobno dla
  każdego nośnika (wcześniej karta SD pokazywała rozbicie całej pamięci)
- okno usuwania: „Do kosza” jako główny przycisk, „Usuń na zawsze” osobno
  i w kolorze błędu; kafelki mają znak offline i nazwę w dwóch liniach
- animacje: podkreślenie w dolnej nawigacji przesuwa się między zakładkami,
  pasek zaznaczenia wsuwa się z góry, miniatury wyłaniają się z pulsującego
  podkładu zamiast kółek ładowania, akcje orbity rozchodzą się po kolei,
  plik wrzucony do szuflady leci do niej, szybki podgląd wyrasta spod palca
  i znika płynnie, uchwyt arkusza Głębi zamyka poziom szybkim machnięciem;
  „Spokojny ruch” skraca albo wyłącza każdą z nich

## 0.10.0

Przeglądarka PDF i CAD (moduły `:viewer` i `:dwg`):

- PDF: powiększenie do 6400% z ostrością ekranu, wyszukiwanie tekstu (także
  napisów SHX z AutoCAD), warstwy, komentarze — oglądanie, dodawanie, zapis
- CAD: DXF natywnie, DWG przez LibreDWG (GPL-3.0) w osobnym procesie
- pomiary w PDF i CAD: przyciąganie do końców, przecięć, środków, punktów
  wstawienia i prostopadłej, tryb osi; skala z pliku, z tabelki albo kalibracja
- w poziomie pełny ekran z narzędziami na treści

Ekran plików:

- zapamiętywane sortowanie dla każdego folderu; Pobrane domyślnie po dacie
- gęstszy, płaski widok listy z kolorowymi ikonami typów plików
- obrót ekranu nie zamyka otwartego pliku

## 0.9.0-alpha3

Czytnik plików tekstowych:

- rozpoznawanie kodowania: znacznik BOM, UTF-8, a gdy plik w nim nie jest —
  CP1250; pliki `.txt`, `.srt` i `.csv` z polskiego Windowsa nie wyświetlają
  się już jako ciąg znaków zapytania
- numery wierszy, zawijanie do przełączenia, wyszukiwanie w pliku z licznikiem
  trafień i podświetleniem
- spis treści: nagłówki w Markdown, deklaracje w kodzie
- rozpoznawanie plików binarnych — zamiast krzaków komunikat
- lista typów tekstowych rozszerzona o logi, CSV, napisy, pliki konfiguracyjne
  i większość języków programowania; działa też bez typu MIME, którego FTP
  i SMB nie podają

Poprawki wydajności i stabilności:

- jedno repozytorium plików na proces; wcześniej istniały dwa, a wraz z nimi
  dwie pule połączeń i dwa konteksty SMB
- konta sieciowe trzymane w pamięci; wcześniej każda operacja na pliku
  odszyfrowywała hasła przez Android Keystore
- klucz szyfrujący ładowany raz zamiast przy każdym odczycie
- miniatury: pamięć podręczna, najwyżej trzy dekodowania naraz i pomijanie
  dużych plików zdalnych, których pobieranie kosztowało więcej niż dawało
- skaner indeksu odporny na pętle dowiązań i zbyt głębokie drzewa

Naprawione błędy:

- odczyt tekstu psuł polskie znaki na granicy porcji odczytu: każdy fragment
  był dekodowany osobno, więc znak wielobajtowy rozpadał się na dwa znaki
  zastępcze
- wpisanie frazy w wyszukiwarce na ekranie głównym nie pokazywało wyników
- odświeżanie indeksu zniknęło razem ze starą kartą indeksu
- „Kosz" był ucinany przy krawędzi; akcje w nagłówku są teraz ikonami

## 0.9.0-alpha2

- usunięty zielony nagłówek „Mój komputer" — Ustawienia są w dolnym pasku,
  więc powielał wejście i zabierał najcenniejsze miejsce na ekranie
- przypięte foldery razem z Analizą, Historią i Koszem od razu pod zakładkami
- kategorie i wyszukiwanie w jednym rzędzie; wyszukiwanie zwinięte do ikony,
  która po kliknięciu rozwija pasek na miejscu kategorii
- gdy indeksu jeszcze nie ma, rozwinięty pasek proponuje jego zbudowanie
  zamiast pustego pola

## 0.9.0-alpha1

- pasek zakładek ma zawsze tę samą budowę, także przy jednej karcie; wcześniej
  zamieniał się w inny element i ekran wyglądał jak dwa różne układy
- menu zakładki po dłuższym przytrzymaniu: duplikuj, przypnij folder,
  zamknij pozostałe, zamknij
- karta indeksu po zbudowaniu zamienia się w pole wyszukiwania globalnego —
  budowanie robi się raz, a szuka codziennie
- przypięte foldery: zamiast krzyżyka menu z akcjami (otwórz, otwórz w nowej
  karcie, odepnij), a pod nazwą liczba plików i zajętość
- pod paskiem zajętości nośnika rozbicie na kategorie
- niższy nagłówek „Mój komputer", lżejsze chipsy kategorii bez obrysu
- klikalna ścieżka nawigacji: skok do dowolnego katalogu nadrzędnego

## 0.8.0-alpha3

- zakładki niższe i węższe, mieszczą się trzy zamiast dwóch
- przycisk „+" stoi poza obszarem przewijania, więc nie znika przy krawędzi
- pasek sam przewija się do aktywnej zakładki
- uzupełniono paletę motywu: brakujące odcienie powierzchni Material zastępował
  własną, fioletową paletą bazową, przez co część elementów odcinała się
  kolorem od reszty aplikacji

## 0.8.0-alpha2

- karty katalogów w stylu przeglądarki: zaokrąglone u góry, z wklęsłymi
  narożnikami u dołu, dzięki czemu aktywna karta zlewa się z treścią
- ikona źródła na karcie (pamięć, SMB, FTP, chmura, „Mój komputer")
- płynne przejście koloru i wysokości przy przełączaniu kart
- przy jednej karcie pasek nie zabiera miejsca — zostaje sam przycisk

## 0.8.0-alpha1

- Google Drive przez oficjalne API v3 zamiast systemowego wyboru folderu:
  podpięty jest cały dysk, a nie wskazany podfolder
- logowanie kontem Google w Ustawieniach, w przeglądarce systemowej (OAuth
  z PKCE); hasło nie przechodzi przez aplikację, a token odświeżania trafia
  do zaszyfrowanego magazynu razem z hasłami FTP i SMB
- kont Google może być kilka, każde ze swoim adresem e-mail na liście
- Client ID podaje się przy budowaniu, więc aplikacja nie jest przywiązana
  do jednego projektu ani konta — patrz DRIVE_SETUP.md

## 0.7.0-alpha2

- przewijanie zdjęć gestem w podglądzie i na pełnym ekranie; gest działa
  tylko przy nieprzybliżonym obrazie, żeby nie kolidować z przesuwaniem kadru
- licznik „3 z 12" pokazujący pozycję zdjęcia
- naprawiono błąd renderowania PDF przy powiększaniu: rozmiar bitmapy tnie
  teraz budżet pikseli, a powiększanie nie przelicza stron przy każdej
  drobnej zmianie gestu

## 0.7.0-alpha1

- lokalny indeks plików: nazwy, typy, rozmiary i daty z całej pamięci
  urządzenia, budowany na żądanie z paska na ekranie „Mój komputer"
- wyszukiwanie po całym urządzeniu, a nie tylko w bieżącym katalogu —
  przełącznik obok pola wyszukiwania
- kategorie Obrazy / Filmy / Audio / Dokumenty / Archiwa / APK obejmujące
  całą pamięć, liczone przy indeksowaniu
- analiza pamięci: największe pliki, największe katalogi, pliki nietknięte
  od pół roku; sama pokazuje fakty, niczego nie kasuje
- wykrywanie duplikatów: najpierw po rozmiarze, potem porównaniem SHA-256,
  z informacją, ile miejsca dałoby się odzyskać

## 0.6.0-alpha2

- zakładki katalogów: kilka miejsc otwartych naraz, przełączanie bez cofania
  się po drzewie, „Otwórz w nowej karcie" w menu zaznaczenia
- tryb dwóch paneli na tablecie i w poziomie, z kopiowaniem i przenoszeniem
  między panelami oraz zamianą stron
- drugi panel startuje w katalogu lewego, więc od razu jest co przenosić
- pasek kart pojawia się dopiero przy drugiej karcie, żeby nie zabierać
  miejsca przy zwykłym przeglądaniu

## 0.6.0-alpha1

- połączeń każdego typu może być wiele: „NAS Dom" obok „NAS Firma" i kopii
  zapasowych, każde z własną nazwą i własnym przełącznikiem
- dodano SFTP — szyfrowany i działający przez jedno połączenie, więc nie
  wymaga trybu pasywnego ani otwierania portów jak FTP
- dodano WebDAV (Nextcloud, ownCloud, Apache mod_dav), z HTTP i HTTPS
- dodano wyszukiwanie serwerów SMB w sieci lokalnej — adresu nie trzeba już
  przepisywać ręcznie
- połączenia są utrzymywane i wykorzystywane ponownie zamiast logować się od
  nowa przy każdej operacji; katalog ze zdjęciami na FTP nie oznacza już
  jednego logowania na miniaturę
- nowy ekran połączeń: dodawanie, edycja, test połączenia i usuwanie
- kosz działa też dla folderów podpiętych przez SAF
- kosz zasobu sieciowego zostaje na właściwym serwerze
- nazwy plików i katalogów na serwerze nie mogą już przemycić ścieżki w górę
  drzewa

## 0.5.0-alpha3

- podgląd pliku otwiera się od razu rozwinięty do góry, zamiast startować w
  połowie ekranu i wymagać dociągnięcia palcem
- kliknięcie pliku APK uruchamia systemowy instalator; działa też dla plików
  z FTP, SMB i chmury, bo APK jest najpierw pobierany do prywatnego cache
- kafelek „Zdjęcia” prowadzi do zdjęć z aparatu (DCIM/Camera) zamiast do
  katalogu Pictures; istniejące podpięcie jest przestawiane jednorazowo
- karty źródeł chmurowych pokazują nazwę faktycznie podpiętego katalogu, a
  nie samo „Podłączony”, więc widać, czy podpięto cały dysk czy podfolder
- podpowiedź przy podłączaniu chmury: aby podpiąć cały dysk, trzeba zaznaczyć
  najwyższy poziom, a nie wchodzić do podfolderu

## 0.5.0-alpha2

- operacje na plikach wykonuje kolejka działająca w tle; kopiowanie nie
  przerywa się po wyjściu z aplikacji
- pasek postępu z liczbą plików, przerobionymi bajtami, prędkością i
  szacowanym czasem pozostałym
- wstrzymywanie, wznawianie i anulowanie operacji — z aplikacji i z
  powiadomienia
- ekran kolejki z podglądem wszystkich zadań i ich stanu
- konflikty nazw: zamień / pomiń / zachowaj oba, z opcją zastosowania do
  wszystkich; porównanie nazw ignoruje wielkość liter, tak jak robią to
  SMB i FAT
- kosz: usunięte elementy trafiają do `.armafiles_trash` na tym samym
  nośniku i można je przywrócić do katalogu źródłowego
- cofanie ostatniej operacji z paska w aplikacji
- przenoszenie w obrębie jednego nośnika lub jednego serwera odbywa się
  przez zmianę nazwy zamiast przepisywania bajtów — przeniesienie kilku
  gigabajtów na tej samej karcie jest teraz natychmiastowe
- przerwane przenoszenie nie kasuje już źródła bez potwierdzonej kopii
- historia operacji jako osobny ekran, z cofaniem także po zniknięciu paska
- własne przypięte foldery: dowolny katalog można przypiąć z menu zaznaczenia
  i odpiąć jednym tapnięciem
- kolejność przypiętych kafelków zmienia się przeciągnięciem po dłuższym
  przytrzymaniu
- karty nośników pokazują też miejsce zajęte, a nośniki wymienne nie są już
  wszystkie podpisywane jako karta SD — pendrive USB przedstawia się nazwą
  z systemu
- pierwsze testy jednostkowe w projekcie (38)

## 0.4.1

- poprawiono pozycjonowanie obrazu w podglądzie: obraz jest kotwiczony do góry zamiast centrowany w pionie
- dodano działający tryb pełnoekranowy dla obrazów rastrowych
- dodano pełny ekran również dla SVG
- pełny ekran działa jako osobny bezramkowy dialog i ukrywa paski systemowe
- zachowano zoom, pinch-to-zoom, przesuwanie i dopasowanie w trybie pełnoekranowym
- dodano czytelne wyjście z pełnego ekranu przez X, FullscreenExit i Back
- dodano `navigationBarsPadding()` do arkusza podglądu, aby kontrolki obrazu nie chowały się pod systemowym paskiem Androida

## 0.4.0

- przebudowano ekran główny na widok „Mój komputer”
- dodano przypięte foldery Pobrane i Zdjęcia
- dodano karty pamięci wbudowanej i kart SD z informacją o zajętości
- aktywne zasoby chmurowe/sieciowe są prezentowane jako „Połączone zasoby”; pusta sekcja jest ukrywana
- dodano lokalny backend `file://` dla pełnoprawnego przeglądania pamięci urządzenia
- dodano obsługę specjalnego uprawnienia MANAGE_EXTERNAL_STORAGE na Androidzie 11+
- SAF pozostaje dostępny jako alternatywny sposób podpinania folderów
- PDF działa w trybie ciągłego przewijania
- dodano pasek miniaturek PDF i skok do wybranego numeru strony
- strony PDF są renderowane leniwie
- zachowano zoom PDF i dodano pinch-to-zoom bez blokowania zwykłego przewijania
- dodano natywny viewer obrazów z zoomem i przesuwaniem
- miniatury obrazów pokazują cały kadr (`ContentScale.Fit`) zamiast centralnego wycinka
- miniatury rastrowe działają również dla FTP/SMB i innych źródeł zdalnych
- dodano zmianę liczby kolumn gestem prawo/lewo w zakresie lista / 2–5 kolumn
- rozszerzono typy obrazów o AVIF
- poprawiono udostępnianie lokalnych plików `file://` przez prywatny cache + FileProvider

## 0.3.1

- dodano wbudowaną przeglądarkę PDF
- PDF nie wymaga już zewnętrznej aplikacji do podstawowego podglądu
- dodano obsługę wielu stron z nawigacją poprzednia / następna
- dodano pinch-to-zoom, przesuwanie powiększonej strony i kontrolki zoomu
- dodano dopasowanie strony do ekranu
- PDF z SAF, chmur, FTP/FTPS i SMB jest lokalnie buforowany przed renderowaniem
- PDF jest rozpoznawany po MIME i rozszerzeniu `.pdf`
- renderowanie korzysta z natywnego Android PdfRenderer, bez nowej zależności

## 0.3.0

- dodano ekran Ustawienia / Źródła i połączenia
- opcjonalne źródła są całkowicie ukrywane na ekranie Pliki po wyłączeniu
- wyłączenie źródła zachowuje jego konfigurację do późniejszego ponownego użycia
- dodano Google Drive przez Android Storage Access Framework
- dodano OneDrive przez Android Storage Access Framework
- dodano Dropbox przez Android Storage Access Framework
- dodano bezpośrednią obsługę FTP
- dodano FTPS z TLS, walidacją certyfikatu i nazwy hosta
- dodano SMB / Samba przez jcifs-ng
- SMB1 jest wyłączony; minimalny dialekt SMB202
- dodano test połączenia FTP/FTPS i SMB bezpośrednio z Ustawień
- hasła FTP/SMB są szyfrowane AES-GCM z kluczem w Android Keystore
- ujednolicono operacje plikowe dla SAF, FTP i SMB
- kopiowanie/przenoszenie może działać pomiędzy różnymi źródłami
- ZIP, rozpakowywanie, edycja tekstu i SHA-256 działają również przez warstwę zdalnych źródeł
- zdalne obrazy i pliki otwierane zewnętrznie są materializowane do chronionego cache przez FileProvider
- Ostatnie i Ulubione nie odpytują wyłączonych źródeł

## 0.2.0

- całkowicie odświeżony interfejs ARMA Files
- spójna autorska paleta jasna/ciemna
- nowy hero header z metrykami bieżącego widoku
- miniatury obrazów na liście i w siatce
- filtry kategorii plików
- wielokrotny wybór i „zaznacz wszystko”
- operacje grupowe: kopiuj, wytnij, usuń, udostępnij
- widoczny schowek z Wklej / Anuluj
- tworzenie archiwów ZIP
- rozpakowywanie ZIP z ochroną przed ZIP Slip
- panel właściwości z URI i SHA-256
- przewijalny podgląd tekstu
- poprawiony ekran startowy, puste stany i komunikaty operacji

## 0.1.0

- pierwsza wersja menedżera
- SAF z trwałym dostępem
- lista/siatka, wyszukiwanie, sortowanie
- tworzenie, usuwanie, zmiana nazwy, kopiowanie i przenoszenie
- Ostatnie i Ulubione
- podgląd obrazów i plików tekstowych
