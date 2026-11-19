# M-Film for Android

Native Android film camera and darkroom powered by **M-Film's C engine** (`ml-dev/filmos/`), originally created by **[HNGSeries](https://github.com/HNGSeries/mfilm-canon-film-simulation)** for Canon Magic Lantern cameras.

Built with **Jetpack Compose**, **CameraX + Camera2 Interop**, and a **multi-threaded `-O3` C NDK darkroom** (`pthreads`) — zero bloated dependencies, 100% offline processing.

> **Upstream Credit:** Forked from **[HNGSeries/mfilm-canon-film-simulation](https://github.com/HNGSeries/mfilm-canon-film-simulation)**. The Film OS state model, 68 film definitions, and C RAW/DNG/JPEG developing pipeline originate from **HNGSeries**.

---

## Architecture

| Component | Location | Responsibility |
|---|---|---|
| **C Port & Mutex State** | `app/src/main/cpp/mfilm_port.c`, `mfilm_port.h` | Thread-safe (`pthread_mutex_t`) bridge for roll state, darkroom session, gallery roll import, film swapping, and custom grain/halation recipes |
| **Multi-Threaded Darkroom** | `ml-dev/filmos/fos_dev.c`, `fos_dng.c`, `fos_lab.c`, `fos_jpeg.c` | Compiled with `-O3 -DFOS_DEV_RAWIN -DFOS_MULTITHREAD` (4-thread persistent `pthreads` pool for `ST_FINISH` and `big_rows`) |
| **CR2 Generator** | `app/src/main/cpp/mkcr2_lib.c` | Encodes non-RAW / composite (2X, Half-Frame, Imported) bitmaps into 18 MP EOS-M-shaped CR2s |
| **JNI Bridge** | `app/src/main/cpp/jni_bridge.c` → `Native.kt` | Native bindings for roll management, canister rendering, darkroom stepping, and CR2 encoding |
| **Camera Controller** | `MainActivity.kt` | CameraX/Camera2 lifecycle, RAW DNG & JPEG capture, Manual Exposure (`M`), Manual Focus, WB Lock, Live Histogram, Horizon Spirit Level, Live Film Look `ColorMatrix`, Double Exposure (`2X`), Half-Frame (`½`), GPS tagging, `SoundPool` WAV audio |
| **Viewfinder & Mechanical UI** | `Ui.kt` | Pure matte-black / brass / amber Jetpack Compose HUD (`Level` & `Upright`), 7-segment counter, EV/Manual/Focus dials, framelines, 6×6 & XPan crop masks, spirit level, histogram |
| **Film Shelf & Roll Log** | `Screens.kt` | `HorizontalPager` canister shelf, ★ Favorites tab, async `LruCache` sample & canister rendering, frame exposure log & rewind |
| **Darkroom, Prints & Settings** | `LabScreens.kt` | Safelight Darkroom, Gallery Import picker, Re-develop + Film Swap, Grain/Halation recipe steppers, Roll Labels, Contact Sheet & Lightbox (`35MM` film rebate preview/share), Roll ZIP export, Settings manual |
| **Virtual Card & EXIF** | `Card.kt` | `Android/data/.../files/card/` layout, background CR2 queue, 6×6/XPan cropping, Light Leak & Dust/Scratch effects, 35mm rebate renderer, `ExifInterface` (Date/FNumber/Film/Memo/GPS), MediaStore export, RAW storage cleaner |
| **Background Lab Queue** | `DevelopService.kt` | Foreground service with `ConcurrentLinkedQueue<DevJob>`, live safelight preview, post-processing, MediaStore export, and optional Auto-Clean RAW |
| **Android System Integration** | `MFilmWidget.kt`, `MFilmTileService.kt`, `res/xml/shortcuts.xml` | Home Screen Widget, Quick Settings Tile, and App Shortcuts (`Shelf` / `Darkroom` / `Prints`) |
| **Asset Pipeline** | `scripts/build_assets.sh` → `app/src/main/assets/` | Builds `FOSFONT.BIN`, `FOSFILMS.BIN`, 68 LUT tables (`tables/`), and shelf sample photos (`samples/`) |

---

## Build & Test

### Prerequisites
- Android SDK (API 35) + NDK + CMake `3.22.1`
- JDK 17 & Gradle `8.11.1`
- Python 3 with `numpy`, `scipy`, `pillow`

### Local Build
```bash
# Generate fonts, LUT tables, and shelf sample JPEGs (once, or when films change)
bash android/scripts/build_assets.sh

# Build release APK
cd android
gradle assembleRelease
# -> android/app/build/outputs/apk/release/app-release.apk
```

### CI Pipeline (`.github/workflows/android.yml`)
On every push to `main`:
1. Generates card assets (`android/scripts/build_assets.sh`).
2. Compiles and runs the **C Smoke Test** (`android/test/smoke.c`) with `-O3 -DFOS_DEV_RAWIN -DFOS_MULTITHREAD` to verify roll loading, shooting, winding, and CR2 generation (`IMG_0101.CR2`).
3. Builds the signed release APK (`gradle --no-daemon assembleRelease`).
4. Publishes the APK to the **`android-latest`** GitHub Release.

---

## Credits & Licenses

- **Upstream Project**: **[HNGSeries/mfilm-canon-film-simulation](https://github.com/HNGSeries/mfilm-canon-film-simulation)** (`GPL-2.0`).
- **59 Film Colour Tables**: **Pat David** ([patdavid.net](https://patdavid.net)) via [Film-Luts](https://github.com/YahiaAngelo/Film-Luts) — licensed under **CC BY-SA 4.0** (see `../THIRD_PARTY.md`, also bundled in the APK).
- **Barlow Font**: SIL Open Font License 1.1.
- Not affiliated with Canon, Kodak, Fujifilm, CineStill, Ilford, Hasselblad, Olympus, or any film/camera brand.
