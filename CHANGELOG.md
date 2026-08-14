# Changelog

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
