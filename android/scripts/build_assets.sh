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
mkdir -p "$OUT/samples"
python3 -c '
import glob, os
from PIL import Image
os.makedirs(sys_out := "'"$OUT"'/samples", exist_ok=True)
for p in glob.glob("'"$ROOT"'/docs/films/*.jpg"):
    im = Image.open(p).convert("RGB").resize((480, 320), Image.Resampling.LANCZOS)
    im.save(os.path.join(sys_out, os.path.basename(p)), "JPEG", quality=82)
'
cp "$ROOT/LICENSE" "$OUT/LICENSE.txt"
cp "$ROOT/THIRD_PARTY.md" "$OUT/THIRD_PARTY.txt"
ls -la "$OUT" "$OUT/tables" | tail -5
