# Reguły R8 dołączane do aplikacji, która używa :dwg (także jako AAR).

# Nazwa klasy jest częścią symbolu JNI (Java_pl_arma_dwg_DwgNative_toDxf).
-keep class pl.arma.dwg.DwgNative { native <methods>; }
