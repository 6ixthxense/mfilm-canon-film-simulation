# M-Film for Android

M-Film's film OS on a phone: the same C that runs on the Canon (`ml-dev/filmos/`), built with the
NDK, with the phone's camera as LiveView. Load a roll from the shelf of 68 films, shoot it without
seeing the pictures, then develop it in the darkroom; the prints go to the gallery
(`Pictures/M-Film/<day> <film>/`).

> ภาษาไทยสั้นๆ: พอร์ต Film OS ของ M-Film (โค้ด C ชุดเดียวกับที่รันบนกล้อง) มาเป็นแอป Android แบบ native
> ใช้กล้องมือถือแทน LiveView ถ่ายเป็น RAW (CR2 แบบ EOS M) แล้วล้างในห้องมืดด้วยตารางสีของฟิล์ม
> รูปที่ล้างแล้วไปอยู่ใน Pictures/M-Film ปุ่มเสียงคือชัตเตอร์ ลากนิ้วบนจอ = หมุน dial

## How it works

| Part | File |
|---|---|
| The port: every `fos_port_*` the film OS needs, its loop on a thread of its own, the roll kept on the card | `app/src/main/cpp/mfilm_port.c` (from `ml-dev/filmos/web/web.c`) |
| The shutter: the sensor's RAW as a DNG; without RAW, the photo into an 18 MP CR2 shaped like the EOS M's | `MainActivity.kt`; `app/src/main/cpp/mkcr2_lib.c` (includes `ml-dev/filmos/sim/tools/mkcr2.c`) |
| Developing a DNG | `ml-dev/filmos/fos_dng.c`, `fos_dev_start_raw` in `fos_dev.c` (`FOS_DEV_RAWIN`) |
| JNI | `app/src/main/cpp/jni_bridge.c` → `Native.kt` |
| Camera (CameraX), keys, the screen over LiveView | `MainActivity.kt`, `Views.kt` |
| The card, the CR2 queue, the gallery | `Card.kt` |
| The card's data (fonts, films, colour tables) | `scripts/build_assets.sh` → `app/src/main/assets/` |

The card is `Android/data/io.github.sixthxense.mfilm/files/card/`, laid out as a camera's
(`DCIM/100CANON/IMG_nnnn.CR2`, `ROLL_nnn.TXT`, `ML/DARKROOM/LOOKnn.LUT`), so its RAWs also open in
Darkrooms or any raw converter. A frame's CR2 is written in the background (a few seconds each); the
darkroom waits until every one is on the card.

The shutter takes the sensor's own RAW where the phone gives it (CameraX `OUTPUT_FORMAT_RAW`): a DNG,
`DCIM/100CANON/IMG_nnnn.DNG`, which the darkroom develops with the phone's colour matrix and with the
lens's fall-off taken out (`ml-dev/filmos/fos_dng.c`). A phone without RAW gets a CR2 made from its photo,
as before.

Controls: the round button or a volume key is the shutter (press = half-press, release = shoot);
MENU, INFO, PLAY, the arrows and SET as on the camera. And the screen is a touch screen:

| | Film OS up | LiveView |
|---|---|---|
| tap | SET | focus and meter there |
| drag across | ◀ ▶ | the dial (aperture) |
| drag up / down | ▲ ▼ | exposure compensation |
| two fingers | MENU (back) | Home |

Back closes the film OS's screens.

Held upright, the app turns with the phone (`res/layout-port/`): the keys go under the screen, and on
LiveView the frame is 2:3, the portrait picture, with a viewfinder of its own (the film, the frame
count); the film OS's screens stay 3:2 above the keys. The gestures follow the screen as it is held.
Each frame's RAW is marked with how the phone was held, so the darkroom turns its print upright.

## Build

GitHub Actions (`.github/workflows/android.yml`) builds the APK on every push that touches it, and on
`main` publishes it as the `android-latest` release.

On a computer: Android Studio (or the SDK with NDK and CMake 3.22.1), Gradle 8.11, Python 3 with numpy:

```bash
android/scripts/build_assets.sh       # once, and when the films change (fetches the Film-Luts LUTs once)
cd android && gradle assembleRelease  # app/build/outputs/apk/release/app-release.apk
```

## Licences

GPL-2.0 like the rest of M-Film. The colour tables of 59 films are Pat David's, CC BY-SA 4.0
(`THIRD_PARTY.md`); they are in the APK and on the card as `ML/THIRD_PARTY.txt`. Not affiliated with
Canon, Kodak, Fujifilm or others. Darkrooms, the desktop program, is closed source and not part of this.
