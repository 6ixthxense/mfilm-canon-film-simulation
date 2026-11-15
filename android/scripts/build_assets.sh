#!/usr/bin/env bash
# The card's data for the app, from the camera's sources, as ml-dev/filmos/web/build.sh
# makes them for the browser: the fonts, the films, and every film's colour table.
# Needs a C compiler, python3 with numpy, and (once) the Film-Luts LUTs (films/getluts.py).
#   android/scripts/build_assets.sh            -> android/app/src/main/assets/
# MFILM_PLAIN=1: without the Film-Luts LUTs, those films get the table of their look.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SIM="$ROOT/ml-dev/filmos/sim"
OUT="$HERE/../app/src/main/assets"
make -C "$SIM" out/FOSFONT.BIN out/FOSFILMS.BIN out/devtest >/dev/null
[ -n "${MFILM_PLAIN:-}" ] || [ -d "$ROOT/ml-dev/films/luts/filmluts" ] || python3 "$ROOT/ml-dev/films/getluts.py"
rm -rf "$OUT"
mkdir -p "$OUT/tables"
cp "$SIM/out/FOSFONT.BIN" "$SIM/out/FOSFILMS.BIN" "$OUT/"
(cd "$SIM" && python3 ../tools/gentables.py "$OUT/tables" --films out/FOSFILMS.BIN --devtest out/devtest ${MFILM_PLAIN:+--plain})
cp "$ROOT/LICENSE" "$OUT/LICENSE.txt"
cp "$ROOT/THIRD_PARTY.md" "$OUT/THIRD_PARTY.txt"
ls -la "$OUT" "$OUT/tables" | tail -5
