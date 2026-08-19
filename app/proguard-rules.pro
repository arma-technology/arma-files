# Reguły R8 dla wydania.
#
# Biblioteki sieciowe ładują klasy po nazwie, więc R8 nie widzi ich użycia
# w kodzie i usuwa je jako martwe. Błąd nie pojawia się przy kompilacji —
# wychodzi dopiero przy próbie połączenia z serwerem, jako brak klasy
# w środku uzgadniania protokołu. Stąd te reguły.

# --- sshj (SFTP) -------------------------------------------------------------
# Wybiera implementacje szyfrów, wymiany kluczy i podpisów przez fabryki
# tworzone refleksyjnie.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.**

# --- BouncyCastle ------------------------------------------------------------
# Dostawcy JCE są rejestrowani i wyszukiwani po nazwie.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
# Kryptografia postkwantowa nie jest przez nas używana; jawnie ją odsuwamy,
# żeby R8 mógł ją usunąć razem z jej tablicami danych (kilkaset kilobajtów).
-dontnote org.bouncycastle.pqc.**

-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# --- jcifs-ng (SMB) ----------------------------------------------------------
# Konfiguracja czytana jest z nazw właściwości, a dialekty i typy komunikatów
# tworzone refleksyjnie.
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# --- Apache Commons Net (FTP) ------------------------------------------------
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**

# --- AppAuth (logowanie Google) ----------------------------------------------
# Odpowiedzi z serwera odwzorowywane są na pola po nazwie.
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# --- OkHttp ------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# --- SLF4J -------------------------------------------------------------------
# jcifs-ng loguje przez SLF4J; używamy implementacji pustej.
-dontwarn org.slf4j.**

# --- Nasze modele ------------------------------------------------------------
# Nazwy stałych wyliczeniowych trafiają do zapisanych danych (konta sieciowe,
# kategorie indeksu, dziennik kosza), więc ich zmiana zerwałaby odczyt.
-keepclassmembers enum pl.arma.files.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- XML ---------------------------------------------------------------------
# Parser PROPFIND (WebDAV) tworzy fabrykę przez javax.xml.
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
