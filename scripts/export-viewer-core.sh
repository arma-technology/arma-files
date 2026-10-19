#!/bin/sh
# Eksport rdzenia przeglądarek (PDF, CAD, obrazy, tekst) do użycia w innych
# aplikacjach: moduły źródłowe, gotowe AAR i instrukcja integracji.
# Użycie: scripts/export-viewer-core.sh [katalog docelowy]
set -eu
cd "$(dirname "$0")/.."
OUT=${1:-$HOME/projekty/aplikacje/android/arma-viewer-core}
: "${OUT:?brak katalogu docelowego}"

./gradlew -q :viewer:assembleRelease :dwg:assembleRelease

mkdir -p "$OUT/aar"
# Moduły nadpisujemy w całości, żeby nie zostały pliki usunięte w źródle.
for module in viewer dwg; do
  rm -rf "${OUT:?}/$module"
  rsync -a --exclude build --exclude .cxx --exclude '.gradle' "$module" "$OUT/"
done
cp viewer/build/outputs/aar/viewer-release.aar "$OUT/aar/arma-viewer.aar"
cp dwg/build/outputs/aar/dwg-release.aar "$OUT/aar/arma-dwg.aar"
cp docs/viewer-core/INTEGRACJA.md "$OUT/README.md"

{
  echo "Źródło: ARMA Files $(git describe --always --dirty)"
  echo "Wyeksportowano: $(date -Iseconds)"
  echo
  echo "Ostatnie zmiany w rdzeniu (viewer/, dwg/):"
  git log --format='  %h %ad %s' --date=short -15 -- viewer dwg
} > "$OUT/VERSION"
echo "Eksport gotowy: $OUT"
