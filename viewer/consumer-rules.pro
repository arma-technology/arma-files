# Reguły R8 dołączane do aplikacji, która używa :viewer (także jako AAR).

# PdfBox-Android: czcionki, mapy znaków i filtry ładowane po nazwie z zasobów.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
# BouncyCastle jest wyłączony z zależności PdfBox (patrz build.gradle.kts).
-dontwarn org.bouncycastle.**
