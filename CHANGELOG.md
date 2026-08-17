# Changelog

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
