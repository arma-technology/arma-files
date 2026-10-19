# :viewer — przeglądarka PDF, rysunków CAD, obrazów i tekstu

Moduł niezależny od ARMA Files: dostaje plik lokalny i go wyświetla. Nie zna
niczego z `:app`, więc przenosi się go do innej aplikacji razem z katalogiem.
Licencja zależności: PdfBox-Android (Apache 2.0). Odczyt DWG jest w osobnym
module `:dwg` (GPL-3.0) — bez niego działają PDF i DXF.

## Wspólne dla PDF i CAD (`sheet/`)

- powiększenie do 6400% z ostrością ekranu — nad bitmapą całej strony rysowany
  jest wycinek widocznej części w pełnej rozdzielczości
- szczypanie w punkcie palców, przesuwanie w obu osiach z bezwładnością,
  podwójne tapnięcie, obrót bez utraty miejsca
- wyszukiwanie tekstu z licznikiem i przeskokiem do kolejnego trafienia
- **pomiar**: celownik pośrodku ekranu, przyciąganie do końców, przecięć,
  środków odcinków i okręgów, punktów wstawienia bloków, prostopadłej i do
  linii; tryb osi (poziomo/pionowo od pierwszego punktu, z przecięciem
  z linią rysunku — „odległość od ściany”); Δx/Δy; cofanie
- **skala**: z pliku (rzutnia AutoCAD `/VP /Measure`, jednostki `$INSUNITS`),
  z tabelki rysunkowej („SKALA 1:50”), wybór z listy albo kalibracja z
  odcinka o znanej długości
- **warstwy**: lista z filtrem, włączanie i wyłączanie

## PDF (`pdf/`)

Obraz rysuje systemowy PdfRenderer; PdfBox dokłada:
- komentarze i adnotacje — lista, dymek, wtapianie w obraz (PdfRenderer ich
  nie rysuje), dodawanie notatek i pomiarów jako adnotacji, usuwanie, zapis
- warstwy PDF (OCG)
- linie wektorowe strony do przyciągania (także na stronach obróconych)
- napisy SHX z AutoCAD (adnotacje „AutoCAD SHX Text”) w wyszukiwaniu
- wyszukiwanie zapasowe dla Androida < 15 bez modułu PDF

## CAD (`cad/`)

Własny czytnik DXF (tekstowy i binarny, R12–2018, strony kodowe sprzed 2007):
LINE, ARC, CIRCLE, ELLIPSE, LWPOLYLINE/POLYLINE (łuki, szerokość, 3D), SPLINE
(NURBS i punkty dopasowania), TEXT/ATTRIB, MTEXT (formatowanie, ułamki,
Unicode), INSERT z tablicami MINSERT i atrybutami, DIMENSION (bloki *D),
LEADER, MLEADER, HATCH (pełne z otworami i wzorem z definicji w pliku),
SOLID/TRACE/3DFACE, WIPEOUT, ramki IMAGE; OCS, BYLAYER/BYBLOCK, kolory ACI
i RGB, przezroczystość, rodzaje linii z $LTSCALE, grubości linii; tła jasne,
ciemne i czarno-białe. Bryły 3D (ACIS) i obiekty OLE są pomijane z komunikatem.

```kotlin
val state = rememberSheetViewerState(key = uri) { ctx -> SheetDocuments.open(ctx, plik, nazwa) }

SheetViewer(state, Modifier.fillMaxSize(), onSave = { gotowy -> /* nadpisz oryginał */ })
// albo na pełnym ekranie, z akcjami gospodarza w menu ⋮
SheetViewerDialog(state, onDismiss = { … }, title = nazwa, actions = listOf(…), onSave = …)
```

## Obrazy (`image/`)

`ImageViewer` — powiększanie (szczypanie, przyciski do 800%), przesuwanie,
przewijanie do sąsiednich zdjęć gestem w bok, przycisk pełnego ekranu;
`FullscreenImageViewer` — obraz na całym ekranie z narzędziami na nim;
`SvgImageViewer` / `FullscreenSvgViewer` — SVG przez Coil;
`ImageDecoding.decode` — dekodowanie z ograniczeniem rozdzielczości.

## Tekst (`text/`)

`TextViewer` — czytnik z numerami linii, zawijaniem, wyszukiwaniem i spisem
treści (Markdown, kod); `TextReader` rozpoznaje kodowanie (UTF-8/16,
Windows-1250, ISO-8859-2…) i odrzuca pliki binarne; `TextProfile` mówi,
czy plik jest tekstowy i jakiego rodzaju.

## Przeniesienie do innej aplikacji

`scripts/export-viewer-core.sh` tworzy eksport (moduły, AAR, instrukcja)
w `../arma-viewer-core`. Instrukcja integracji z pułapkami:
[docs/viewer-core/INTEGRACJA.md](../docs/viewer-core/INTEGRACJA.md).
Reguły R8 jadą z modułami (`consumer-rules.pro`).

## Testy na prawdziwych rysunkach

`ARMA_CAD_SAMPLES=<katalog z .dxf/.dxb> ./gradlew :viewer:testDebugUnitTest
--tests '*RealDrawingsTest'` — czyta wszystkie rysunki i wypisuje statystyki;
`ARMA_CAD_RENDER=<pliki rozdzielone :> ARMA_CAD_OUT=<katalog>` w
`RenderPreviewTest` zapisuje podglądy PNG.
