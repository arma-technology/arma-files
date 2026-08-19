# Google Drive — konfiguracja przed budowaniem

Aplikacja łączy się z Drive przez oficjalne API v3. Google wymaga, żeby każda
aplikacja miała własny **Client ID** — identyfikuje on aplikację, a nie
użytkownika. Jeden Client ID obsługuje wszystkie telefony i wszystkie konta;
każdy użytkownik loguje się swoim kontem i dostaje własne tokeny.

Client ID nie jest tajemnicą (i tak trafia do pliku APK), ale nie trzymamy go
w repozytorium, żeby nie przywiązywać kodu do jednego projektu.

## 1. Projekt w Google Cloud

1. <https://console.cloud.google.com/> → nowy projekt.
2. **API i usługi → Biblioteka** → włącz **Google Drive API**.

## 2. Ekran zgody

**API i usługi → Ekran zgody OAuth**:

- typ użytkownika: **Zewnętrzny**
- zakres: dodaj `https://www.googleapis.com/auth/drive`
- **Użytkownicy testowi**: dopisz każde konto Google, które ma korzystać
  z aplikacji

> **To jest ten limit, o którym trzeba wiedzieć.** Zakres `drive` jest u Google
> *zastrzeżony*. Przy statusie „Testowanie" logować się mogą wyłącznie konta
> z listy testowej (do 100). Opublikowanie projektu zdejmuje ten limit, ale
> wtedy Google wymaga weryfikacji aplikacji i corocznego audytu
> bezpieczeństwa. Do użytku rodzinnego lub firmowego lista testowa
> w zupełności wystarcza.
>
> W trybie testowym token odświeżania bywa unieważniany po około tygodniu —
> wtedy aplikacja poprosi o ponowne zalogowanie.

## 3. Identyfikator klienta OAuth

**API i usługi → Dane logowania → Utwórz → Identyfikator klienta OAuth**:

- typ aplikacji: **Android**
- nazwa pakietu: `pl.arma.files`
- odcisk SHA-1 certyfikatu podpisu

Odcisk dla kompilacji debug:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
        -alias androiddebugkey -storepass android -keypass android | grep SHA1
```

Dla wydań podpisywanych naszym kluczem odcisk jest w
`Dokumenty/ArmaFiles-klucz/PRZECZYTAJ.md`. **Aplikacja podpisana innym kluczem
nie zaloguje się** — Google wiąże Client ID z certyfikatem, i to właśnie ta
weryfikacja zastępuje sekret klienta.

> **Przy publikacji w Google Play dochodzi drugi odcisk.** Obowiązuje tam Play
> App Signing: aplikacja na telefonie użytkownika jest podpisana certyfikatem
> Google'a, a nie naszym. Jeśli zarejestrujesz tylko odcisk naszego klucza,
> logowanie zadziała w wersjach budowanych lokalnie i **zawiedzie u każdego,
> kto zainstaluje aplikację ze sklepu**. Trzeba dodać oba: nasz oraz odcisk
> z Play Console, z zakładki integralności aplikacji.
>
> Pamiętaj też, że zakres `drive` jest zastrzeżony — publiczna dystrybucja
> wymaga weryfikacji OAuth i corocznego audytu bezpieczeństwa. Alternatywa
> przy aplikacji w sklepie: zostawić projekt OAuth w trybie testowym, wtedy
> Drive działa dla kont z listy testowej, a pozostali dostają komunikat.

## 4. Budowanie

Client ID podaje się przy budowaniu:

```bash
./gradlew assembleDebug -PdriveClientId=123456789-abcdef.apps.googleusercontent.com
```

albo trwale w `local.properties` (ten plik nie trafia do repozytorium):

```properties
driveClientId=123456789-abcdef.apps.googleusercontent.com
```

Adres przekierowania (`com.googleusercontent.apps.…`) wyliczany jest
automatycznie z Client ID — nie trzeba go wpisywać drugi raz.

Bez tego parametru aplikacja buduje się normalnie, tylko sekcja Google Drive
w ustawieniach informuje, że logowanie jest niedostępne.

## 5. Użycie

Ustawienia → Dodaj połączenie → **Google Drive** → *Zaloguj się do Google*.

Logowanie otwiera się w przeglądarce systemowej, nie w oknie aplikacji —
zgodnie z RFC 8252. Dzięki temu hasło nigdy nie przechodzi przez aplikację,
a użytkownik widzi prawdziwy adres Google. Token odświeżania trafia do
`SecretStore` (AES-GCM, klucz w Android Keystore), tak samo jak hasła FTP i SMB.

Pole „Katalog startowy" pozwala zawęzić widok do podkatalogu, ale domyślnie
podpięty jest **cały dysk** — to była główna bolączka wcześniejszego podejścia
przez SAF.

## Czego Drive nie potrafi

- **Dokumenty Google** (Dokumenty, Arkusze, Prezentacje) nie mają zawartości
  do pobrania — wymagają eksportu do wybranego formatu. Są widoczne na liście,
  ale pokazują zerowy rozmiar. Eksport to osobna funkcja do dorobienia.
- **Nazwy nie są unikalne.** Drive dopuszcza dwa pliki o tej samej nazwie
  w jednym katalogu; aplikacja operuje ścieżkami, więc w takiej sytuacji
  trafi na pierwszy z nich.
