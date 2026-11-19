<div align="center">

<img src="docs/images/mfilm.png" width="112" alt="M-Film">

# M-Film (Android Native Fork)

**A real 35mm film camera & darkroom — powered by M-Film's C engine.**

> **Fork Attribution:** This repository is a fork of **[HNGSeries/mfilm-canon-film-simulation](https://github.com/HNGSeries/mfilm-canon-film-simulation)** created by **[HNGSeries](https://github.com/HNGSeries)**.
> All core Film OS architecture, the 68 film stock profiles, and the C RAW darkroom engine (`ml-dev/filmos/`) originate from the upstream M-Film project. This fork builds a full native **Android** camera & darkroom app (`android/`) directly on top of that C engine.

[![Download Android APK](https://img.shields.io/badge/Download%20Android%20APK-eca834?style=for-the-badge&logo=android&logoColor=black)](https://github.com/6ixthxense/mfilm-canon-film-simulation/releases/tag/android-latest)
[![Upstream Repo](https://img.shields.io/badge/Upstream-HNGSeries%2FM--Film-3b352f?style=for-the-badge&logo=github)](https://github.com/HNGSeries/mfilm-canon-film-simulation)

[![License GPL-2.0](https://img.shields.io/badge/license-GPL--2.0-3b352f)](LICENSE)
[![Films](https://img.shields.io/badge/films-68%20stocks-3b352f)](FILMS.md)
[![Android CI](https://github.com/6ixthxense/mfilm-canon-film-simulation/actions/workflows/android.yml/badge.svg)](https://github.com/6ixthxense/mfilm-canon-film-simulation/actions/workflows/android.yml)

**English** · [ภาษาไทย](README.th.md) · [Android Dev Guide](android/README.md) · [All 68 Films](FILMS.md) · [Credits](#credits--attribution)

</div>

---

## What is this fork?

**[M-Film](https://github.com/HNGSeries/mfilm-canon-film-simulation)** by **HNGSeries** turns Canon EOS cameras running Magic Lantern into a true film camera: load a canister from a shelf of 68 film stocks, shoot the roll without chimping, and develop RAW negatives into colour-graded film prints in-camera.

This fork compiles that **exact same C darkroom engine** (`fos_dev.c`, `fos_dng.c`, `fos_lab.c`, `fos_looks.c`) via Android NDK — enhanced with **multi-threaded `-O3` processing** — and wraps it in a pure **Jetpack Compose + CameraX / Camera2** mechanical camera interface designed around matte black, worn brass, amber readouts, and red 7-segment counters.

Every photograph is captured as sensor **RAW (DNG)** when supported (or 18 MP EOS-M-shaped **CR2** fallback), developed completely offline on-device, stamped with real **EXIF** metadata, and exported directly to `Pictures/M-Film/`.

---

## Features (M-Film for Android)

### Mechanical Viewfinder & Pro Camera Controls
- **True Dual Orientation & Ergonomics**: Dedicated **Landscape** (rangefinder grip with left/right-handed toggle) and **Portrait** (one-handed thumb layout), with tactile `SoundPool` mechanical shutter/wind/rewind/timer audio and a ~110 ms shutter blackout.
- **Rangefinder Live Readout**: Real-time shutter speed, aperture (`f/`), ISO, and `- · ● · +` light meter LEDs read directly from Camera2 capture results.
- **Manual Exposure (`M` / `AE`)**: Full shutter speed (`1/1000` to `1"`) and ISO (`100`–`3200`) control via Camera2 `CONTROL_AE_MODE_OFF`.
- **Manual Focus & 2.5× Focus Loupe**: Mechanical focus distance dial (`∞` to macro) with double-tap or drag-activated 2.5× viewfinder magnifier, plus tap-to-focus and long-press `AE/AF` lock.
- **Live Histogram & Highlight Clipping LED**: 32-bin real-time luminance histogram with a red highlight-clipping warning LED.
- **Electronic Horizon Spirit Level**: Accelerometer-driven center horizon bar that lights up amber within ±1.5° of level.
- **Live Film Look Preview**: Real-time GPU `RenderEffect` preview (`FILM LOOK` / `B&W ONLY` / `RAW VIEW`) tailored to each loaded film stock's saturation, contrast, and warmth.
- **White Balance Lock**: Cycle `AWB` / `DAY` (Daylight) / `TNG` (Tungsten) / `SHD` (Shade) via Camera2.
- **Lens Ring, Flash & Front Camera**: Multi-lens focal switcher (`0.6× · 1× · 2×`), flash (`OFF / AUTO / ON`), front/back camera toggle, self-timer (`2s / 10s`), and hardware volume/camera button half-press & shutter release.

### Analog Formats & Creative Shooting Modes
- **Frame Formats**: Shoot classic **35mm (`3:2`)**, medium-format **6×6 Square (`1:1`)**, or panoramic **Hasselblad XPan (`65:24`)** with matching viewfinder crop masks and automatic print cropping.
- **Half-Frame Mode (`½`)**: Olympus Pen F-style vertical diptychs — shoot left and right halves onto a single 35mm frame (up to 72 shots per 36-exp roll) with split viewfinder masking.
- **Full-Resolution Double Exposure (`2X`)**: Combine two full-resolution exposures onto one frame before sending to the darkroom.
- **Roll Mode vs. Single-Shot Mode**: Shoot authentic `12 / 24 / 36` exposure rolls (with optional realistic **Film Advance Wind Lever**) or enable **Single Mode** to auto-develop every frame in the background via a persistent `ConcurrentLinkedQueue` at your chosen default print size (`S / M / L`).

### Film Shelf (68 Film Stocks)
- **Horizontal Pager Shelf**: Browse 68 colour negative, slide, B&W, and cine canisters across 4 groups plus a **★ Favorites** tab (`IN CAMERA` indicator on the active roll).
- **Live Sample Previews**: Inspect real sample photos rendered through each film's LUT (tap to zoom & swipe to compare films side-by-side) alongside Grain, Punch, Colour, and Warmth spec bars.

### Experimental Darkroom & Prints
- **Multi-Threaded C Darkroom (`-O3` + `pthreads`)**: 4-thread parallelized C print pipeline with safelight-red paper developing preview and background foreground-service queue.
- **Import from Gallery**: Pick existing photos from your phone's gallery and develop them as a roll through any of the 68 film stocks.
- **Re-Develop & Swap Film Stock**: Re-develop any roll whose RAW/DNG negatives are still on card with a new print size (`S / M / L`), Push/Pull (`-1 / 0 / +1`), custom **Grain (`0%–200%`)** & **Halation (`0%–200%`)**, or swap to a completely different film stock.
- **Analog Imperfections & Data Back**: Optional orange 90s **Date Stamp**, **35mm Film Rebate Border** (black borders, sprocket holes, and amber edge codes — globally or live-toggled per photo in Lightbox), **Light Leaks** (`OFF / SUBTLE / HEAVY`), and scanner **Dust & Scratches** (`OFF / SUBTLE / VINTAGE`).
- **Contact Sheets, Lightbox, EXIF & Roll ZIP**:
  - Async `Dispatchers.IO` + `LruCache` Contact Sheet grid and pinch-to-zoom Lightbox.
  - Roll Labels / Memos (e.g., `"KYOTO 2026"`) printed on envelopes, EXIF, and high-res shareable **Contact Sheet JPEGs**.
  - One-tap **Roll ZIP Export** bundling all JPEGs, Contact Sheet, `ROLL_nnn.TXT` exposure log, and memo.
  - Real **EXIF** metadata (`DateTimeOriginal`, `FNumber`, film stock, roll memo, and optional **GPS Geotagging**) written before exporting to `Pictures/M-Film/`.
  - **RAW Storage Meter & Auto-Clean RAW** to reclaim space automatically after development.

### Android System Integration
- **Home Screen Widget**: Displays your loaded film canister and live red frame counter (`14 / 36`) — tap to jump straight into the viewfinder.
- **App Shortcuts & Quick Settings Tile**: Long-press the app icon to jump directly to **Film Shelf**, **Darkroom**, or **Prints**, or add the **M-Film** Quick Settings tile to your status bar.

---

## Controls & Gestures

| Control / Gesture | Action |
|---|---|
| **Brass Shutter / Volume Keys** | Press & hold = Half-press (meter) · Release = Shoot |
| **Tap Viewfinder** | Focus & meter at point |
| **Long-press Viewfinder** | Lock `AE / AF` |
| **Double-tap Viewfinder** | Toggle `2.5×` Focus Loupe |
| **Drag Exposure Dial** | Adjust EV compensation (or tap `M` for manual Shutter & ISO) |
| **Swipe / Tap `Wind →`** | Advance film frame (Realistic mode) |
| **Tap Film Window / Counter** | Open **Film Shelf** / **Roll Exposure Log & Rewind** |

---

## 68 Film Stocks

Colour negative, slide, black & white, and cine film across four shelves.

<table>
<tr>
<td width="33%"><img src="docs/samples/portra400.jpg" alt="Kodak Portra 400"><br><b>Kodak Portra 400</b><br><sub>Soft warm skin, gentle highlights</sub></td>
<td width="33%"><img src="docs/samples/gold200.jpg" alt="Kodak Gold 200"><br><b>Kodak Gold 200</b><br><sub>Golden hour, all day long</sub></td>
<td width="33%"><img src="docs/samples/ektar100.jpg" alt="Kodak Ektar 100"><br><b>Kodak Ektar 100</b><br><sub>Punchy colour, ultra fine grain</sub></td>
</tr>
<tr>
<td><img src="docs/samples/superia400.jpg" alt="Fujicolor Superia 400"><br><b>Fujicolor Superia 400</b><br><sub>Green shadows, everyday snaps</sub></td>
<td><img src="docs/samples/velvia50.jpg" alt="Fujichrome Velvia 50"><br><b>Fujichrome Velvia 50</b><br><sub>Vivid slide film, hard contrast</sub></td>
<td><img src="docs/samples/kodachrome64.jpg" alt="Kodak Kodachrome 64"><br><b>Kodak Kodachrome 64</b><br><sub>Magazine colour of the 70s</sub></td>
</tr>
<tr>
<td><img src="docs/samples/cinestill800t.jpg" alt="CineStill 800T"><br><b>CineStill 800T</b><br><sub>Tungsten cine film, night goes blue</sub></td>
<td><img src="docs/samples/pro400h.jpg" alt="Fujifilm Pro 400H"><br><b>Fujifilm Pro 400H</b><br><sub>Airy pastels, fourth colour layer</sub></td>
<td><img src="docs/samples/trix400.jpg" alt="Kodak Tri-X 400"><br><b>Kodak Tri-X 400</b><br><sub>Gritty contrast, street classic</sub></td>
</tr>
</table>

**[View all 68 film stocks on the same reference photo →](FILMS.md)**

---

## Download & Build

### Install Android APK
Download the latest prebuilt APK from GitHub Releases:
👉 **[Download `android-latest` APK](https://github.com/6ixthxense/mfilm-canon-film-simulation/releases/tag/android-latest)**

### Build from Source (Android)
Requires Android SDK + NDK + CMake 3.22.1, JDK 17, Gradle 8.11.1, and Python 3 (`numpy`, `scipy`, `pillow`):

```bash
# 1. Generate fonts, film tables (LUTs), and shelf sample photos
bash android/scripts/build_assets.sh

# 2. Build release APK
cd android && gradle assembleRelease
# Output: android/app/build/outputs/apk/release/app-release.apk
```

Every push to `main` runs the automated **C Smoke Test** (`android/test/smoke.c` with `-O3 -DFOS_MULTITHREAD`) and publishes a fresh APK via [`.github/workflows/android.yml`](.github/workflows/android.yml). See [android/README.md](android/README.md) for full architecture details.

### Original Canon EOS (Magic Lantern) Build
This repository retains the full upstream Magic Lantern Canon camera source under `ml-dev/` (`ml-dev/build_fork.sh <camera>`). For Canon EOS camera builds, firmware tables, and the **Darkrooms** desktop app, please visit the upstream project:
👉 **[HNGSeries/mfilm-canon-film-simulation](https://github.com/HNGSeries/mfilm-canon-film-simulation)** · [TESTING.md](TESTING.md) · [PORTING.md](PORTING.md)

---

## Credits & Attribution

- **Original Project & Creator — [HNGSeries](https://github.com/HNGSeries)**:
  This project is forked from **[HNGSeries/mfilm-canon-film-simulation](https://github.com/HNGSeries/mfilm-canon-film-simulation)**. Full credit goes to **HNGSeries** for creating M-Film, the Film OS concept and design, the 68 film models, the C darkroom/DNG/JPEG engine (`ml-dev/filmos/`), and the Canon Magic Lantern port.
  If you enjoy M-Film, consider supporting the original creator: **[Buy HNGSeries a coffee ☕](https://buy.stripe.com/7sY14p8d93QE5Ny5M58so02)**.
- **[Magic Lantern](https://www.magiclantern.fm/)** (`GPL-2.0`): The Canon firmware add-on that the camera side builds upon.
- **[Pat David](https://patdavid.net)** (`CC BY-SA 4.0`) & **[Film-Luts by Yahia Angelo](https://github.com/YahiaAngelo/Film-Luts)** (`MIT`): Colour emulation tables for 59 of the 68 film stocks (adapted from G'MIC). See [THIRD_PARTY.md](THIRD_PARTY.md) for full attribution and licensing terms.
- **Barlow Font Family** (`SIL Open Font License 1.1`): Engraved mechanical typography.

---

## License

- Repository code: **GNU General Public License v2.0** ([LICENSE](LICENSE)), inherited from M-Film and Magic Lantern.
- 59 film colour tables (`ML/DARKROOM/LOOKnn.LUT`): **CC BY-SA 4.0** by Pat David — see [THIRD_PARTY.md](THIRD_PARTY.md).
- Not affiliated with or endorsed by Canon, Kodak, Fujifilm, CineStill, Ilford, Hasselblad, Olympus, or any film/camera manufacturer.
