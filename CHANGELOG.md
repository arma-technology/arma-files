# Changelog

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
