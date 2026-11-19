<div align="center">

<img src="docs/images/mfilm.png" width="112" alt="M-Film">

# M-Film (Android Native Fork)

**กล้องฟิล์ม 35mm และห้องมืดล้างฟิล์มบน Android — ขับเคลื่อนด้วยเอนจิน C แท้จาก M-Film**

> **เครดิตต้นฉบับ (Fork Attribution):** โปรเจกต์นี้ Fork มาจาก **[HNGSeries/mfilm-canon-film-simulation](https://github.com/HNGSeries/mfilm-canon-film-simulation)** โดย **[HNGSeries](https://github.com/HNGSeries)**
> แนวคิด Film OS, สูตรฟิล์มทั้ง 68 ตัว และเอนจินห้องมืดภาษา C (`ml-dev/filmos/`) ทั้งหมดเป็นผลงานของต้นฉบับ Fork นี้นำเอนจิน C ชุดเดียวกันมาพัฒนาต่อยอดเป็นแอปกล้องฟิล์ม **Android แบบ Native** (`android/`) เต็มรูปแบบ

[![ดาวน์โหลด APK (Android)](https://img.shields.io/badge/%E0%B8%94%E0%B8%B2%E0%B8%A7%E0%B8%99%E0%B9%8C%E0%B9%82%E0%B8%AB%E0%B8%A5%E0%B8%94%20APK%20(Android)-eca834?style=for-the-badge&logo=android&logoColor=black)](https://github.com/6ixthxense/mfilm-canon-film-simulation/releases/tag/android-latest)
[![ต้นฉบับ HNGSeries/M-Film](https://img.shields.io/badge/Upstream-HNGSeries%2FM--Film-3b352f?style=for-the-badge&logo=github)](https://github.com/HNGSeries/mfilm-canon-film-simulation)

[![License GPL-2.0](https://img.shields.io/badge/license-GPL--2.0-3b352f)](LICENSE)
[![Films](https://img.shields.io/badge/films-68%20stocks-3b352f)](FILMS.md)
[![Android CI](https://github.com/6ixthxense/mfilm-canon-film-simulation/actions/workflows/android.yml/badge.svg)](https://github.com/6ixthxense/mfilm-canon-film-simulation/actions/workflows/android.yml)

[English](README.md) · **ภาษาไทย** · [คู่มือพัฒนา Android](android/README.md) · [ดูฟิล์มทั้ง 68 ตัว](FILMS.md) · [เครดิตต้นฉบับ](#เครดิตและผู้สร้างต้นฉบับ)

</div>

---

## เกี่ยวกับ Fork นี้

**[M-Film](https://github.com/HNGSeries/mfilm-canon-film-simulation)** ของ **HNGSeries** คือระบบกล้องฟิล์มสำหรับกล้อง Canon ที่ลง Magic Lantern ให้คุณเลือกม้วนจากชั้นวาง 68 ฟิล์ม ถ่ายโดยไม่ดูรูปหลังจอ แล้วนำมาล้างในห้องมืดของกล้อง

Fork นี้คอมไพล์ **เอนจินห้องมืดภาษา C ชุดเดียวกัน** (`fos_dev.c`, `fos_dng.c`, `fos_lab.c`, `fos_looks.c`) ผ่าน Android NDK พร้อมเร่งความเร็วแบบ **Multi-thread (`pthreads` + `-O3`)** และออกแบบอินเทอร์เฟซใหม่ทั้งหมดด้วย **Jetpack Compose + CameraX / Camera2** ในสไตล์กล้องฟิล์มกลไก (ดำด้าน, ทองเหลือง, แสงอำพัน และเลขนับเฟรม LED 7-segment สีแดง)

ทุกภาพบันทึกเป็นไฟล์ **RAW (DNG)** จากเซนเซอร์มือถือ (หรือแปลงเป็น **CR2** 18 MP เมื่อเครื่องไม่รองรับ RAW) ล้างภาพแบบออฟไลน์ 100% บนเครื่อง ประทับข้อมูล **EXIF** จริง และบันทึกลง `Pictures/M-Film/` อัตโนมัติ

---

## ฟีเจอร์หลัก (M-Film for Android)

### ช่องมองภาพและการควบคุมระดับโปร
- **รองรับแนวนอน & แนวตั้ง + ถนัดซ้าย/ขวา**: เลย์เอาต์สไตล์กล้อง Rangefinder พร้อมเสียงกลไกผ่าน `SoundPool` (ชัตเตอร์, ขึ้นฟิล์ม, กรอฟิล์ม, นับถอยหลัง) และจอดำวูบตอนลั่นชัตเตอร์ (~110 ms)
- **มาตรวัดแสงสดในช่องมอง**: แสดงสปีดชัตเตอร์, รูรับแสง (`f/`), ISO และไฟวัดแสง `- · ● · +` จาก Camera2 แบบเรียลไทม์
- **โหมด Manual เต็มรูปแบบ (`M` / `AE`)**: ปรับสปีดชัตเตอร์ (`1/1000` – `1"`) และ ISO (`100`–`3200`) เองได้ผ่าน Camera2 `CONTROL_AE_MODE_OFF`
- **Manual Focus & Focus Loupe 2.5×**: แหวนหมุนปรับโฟกัสด้วยมือพร้อมกรอบขยาย 2.5× (แตะ 2 ครั้งหรือลากแหวนโฟกัส), แตะโฟกัส และแตะค้างเพื่อล็อก `AE/AF`
- **Live Histogram & ไฟเตือน Highlight Clipping**: แถบ Histogram 32 ช่องมุมขวาบนช่องมอง พร้อมไฟ LED สีแดงเตือนเมื่อส่วนสว่างหลุด
- **เส้นระดับน้ำอิเล็กทรอนิกส์ (Horizon Spirit Level)**: เส้นวัดระดับเอียงกลางช่องมอง เปลี่ยนเป็นสีอำพันเมื่อได้ระนาบ ±1.5°
- **พรีวิวโทนฟิล์มสด (Live Film Look Preview)**: สลับได้ 3 โหมด (`FILM LOOK` / `B&W ONLY` / `RAW VIEW`) ประมวลผลบน GPU ลื่นๆ ตามโทนของฟิล์มที่ใส่อยู่
- **White Balance Lock**: ล็อกสมดุลแสงขาว `AWB` / `DAY` / `TNG` (Tungsten) / `SHD` (Shade)
- **สลับเลนส์, แฟลช & กล้องหน้า-หลัง**: แหวนเลือกเลนส์ (`0.6× · 1× · 2×`), แฟลช (`OFF / AUTO / ON`), สลับกล้องหน้า-หลัง, ตั้งเวลาถ่าย (`2s / 10s`) และใช้ปุ่มเพิ่ม-ลดเสียงแทนปุ่มชัตเตอร์ได้

### ฟอร์แมตกล้องฟิล์ม & โหมดถ่ายสร้างสรรค์
- **สัดส่วนเฟรมในตำนาน**: สลับระหว่าง **35mm (`3:2`)**, **6×6 Square (`1:1`)** และ **Hasselblad XPan (`65:24`)** พร้อมกรอบช่องมองและครอปภาพตรงสัดส่วนตอนล้าง
- **โหมดครึ่งเฟรม Half-Frame (`½`)**: ถ่าย 2 ช็อตเรียงซ้าย-ขวาลงในเฟรมเดียวแบบ Olympus Pen F (ม้วน 36 รูปถ่ายได้ 72 ช็อต)
- **Double Exposure ความละเอียดเต็ม (`2X`)**: ถ่ายซ้อน 2 ภาพความละเอียดเต็มก่อนส่งเข้าห้องมืด
- **โหมด ROLL (12/24/36) หรือ SINGLE**: ถ่ายเป็นม้วนพร้อมก้านขึ้นฟิล์มแบบสมจริง หรือเปิดโหมด **SINGLE** ให้ล้างอัตโนมัติทีละรูปผ่านระบบคิว (`ConcurrentLinkedQueue`) ตามขนาดภาพมาตรฐาน (`S / M / L`) ที่ตั้งไว้

### ชั้นวางฟิล์ม 68 ตัว
- **HorizontalPager + แท็บ ★ Favorites**: ปัดเลือกกระป๋องฟิล์มลื่นๆ แยก 4 กลุ่ม (Colour, Slide, B&W, Cine) พร้อมปักหมุดฟิล์มโปรดและป้าย `IN CAMERA`
- **ภาพตัวอย่างโทนสีจริง**: ดูโทนสีตัวอย่างของแต่ละฟิล์มได้ตั้งแต่ตอนเลือก (แตะซูมเต็มจอและปัดซ้าย-ขวาเทียบระหว่างฟิล์มได้)

### ห้องมืดสายทดลอง & หน้า Prints
- **เอนจิน C Multi-thread (`-O3` + `pthreads`)**: ล้างภาพเร็วขึ้น 2–3 เท่าบน 4 เธรด พร้อมพรีวิวไฟแดง Safelight
- **Import รูปจาก Gallery มาล้างฟิล์ม**: เลือกรูปเก่าในเครื่องมาสร้างเป็นม้วนแล้วล้างผ่านฟิล์มทั้ง 68 ตัวได้ทันที
- **ล้างซ้ำ & สลับฟิล์ม (Re-develop & Swap Film Stock)**: ม้วนที่ยังมีไฟล์ RAW/DNG สามารถกด `Re-dev` เปลี่ยนขนาด (`S/M/L`), Push/Pull (`-1/0/+1`), ปรับ **Grain (`0%–200%`)** และ **Halation (`0%–200%`)** หรือเปลี่ยนไปล้างด้วยฟิล์มตัวอื่นได้
- **ลูกเล่นอนาล็อกครบชุด**: ประทับวันที่ยุค 90 (`Date Stamp`), **ขอบฟิล์ม 35mm พร้อมรูหนามเตย** (เปิดอัตโนมัติใน Settings หรือกดปุ่ม `35MM` แชร์เฉพาะรูปจาก Lightbox), **Light Leak** (`OFF / SUBTLE / HEAVY`) และ **Dust & Scratches** (`OFF / SUBTLE / VINTAGE`)
- **Contact Sheet, Lightbox, EXIF & Export ZIP**:
  - โหลดรูป Contact Sheet และ Lightbox บน `Dispatchers.IO` + `LruCache` ลื่นไม่กระตุก
  - ตั้งชื่อซองฟิล์ม (`Label` เช่น `"KYOTO 2026"`) แสดงบนซอง, EXIF และหัวแผ่น Contact Sheet
  - ส่งออก **Contact Sheet ความละเอียดสูง** หรือกด **`ZIP`** แชร์ทั้งม้วน (รูปทั้งหมด + Contact Sheet + Exposure Log) ในไฟล์เดียว
  - เขียน **EXIF** แท้ (`DateTimeOriginal`, `FNumber`, ชื่อฟิล์ม, ชื่อม้วน และ **GPS Geotag** แบบเลือกเปิดได้)
  - แสดงพื้นที่ไฟล์ RAW และมีสวิตช์ **Auto-Clean RAW** ลบไฟล์ RAW หลังล้างเสร็จอัตโนมัติ

### เชื่อมต่อกับระบบ Android
- **Home Screen Widget**: โชว์กระป๋องฟิล์มที่ใส่อยู่และเลขนับเฟรมบนหน้าจอโฮม แตะเปิดกล้องได้ทันที
- **App Shortcuts & Quick Settings Tile**: กดค้างไอคอนแอปเข้า **Film Shelf**, **Darkroom**, **Prints** และเพิ่มปุ่มลัดบน Quick Settings ของเครื่องได้

---

## ฟิล์มทั้งหมด 68 ตัว

<table>
<tr>
<td width="33%"><img src="docs/samples/portra400.jpg" alt="Kodak Portra 400"><br><b>Kodak Portra 400</b><br><sub>ผิวอุ่นนุ่ม ไฮไลต์ละมุน</sub></td>
<td width="33%"><img src="docs/samples/gold200.jpg" alt="Kodak Gold 200"><br><b>Kodak Gold 200</b><br><sub>แสงเย็นสีทองตลอดวัน</sub></td>
<td width="33%"><img src="docs/samples/ektar100.jpg" alt="Kodak Ektar 100"><br><b>Kodak Ektar 100</b><br><sub>สีจัดจ้าน เกรนละเอียด</sub></td>
</tr>
<tr>
<td><img src="docs/samples/superia400.jpg" alt="Fujicolor Superia 400"><br><b>Fujicolor Superia 400</b><br><sub>เงาอมเขียว สไตล์สแน็ป</sub></td>
<td><img src="docs/samples/velvia50.jpg" alt="Fujichrome Velvia 50"><br><b>Fujichrome Velvia 50</b><br><sub>ฟิล์มสไลด์สีสด คอนทราสต์จัด</sub></td>
<td><img src="docs/samples/kodachrome64.jpg" alt="Kodak Kodachrome 64"><br><b>Kodak Kodachrome 64</b><br><sub>โทนสีนิตยสารยุค 70</sub></td>
</tr>
<tr>
<td><img src="docs/samples/cinestill800t.jpg" alt="CineStill 800T"><br><b>CineStill 800T</b><br><sub>ฟิล์มหนังทังสเตน กลางคืนอมฟ้า แสงฟุ้งแดง</sub></td>
<td><img src="docs/samples/pro400h.jpg" alt="Fujifilm Pro 400H"><br><b>Fujifilm Pro 400H</b><br><sub>พาสเทลโปร่งนุ่มนวล</sub></td>
<td><img src="docs/samples/trix400.jpg" alt="Kodak Tri-X 400"><br><b>Kodak Tri-X 400</b><br><sub>ขาวดำเกรนดิบ คลาสสิกสายสตรีท</sub></td>
</tr>
</table>

**[ดูตัวอย่างฟิล์มครบทั้ง 68 ตัวบนภาพเดียวกัน →](FILMS.md)**

---

## ดาวน์โหลดและวิธี Build

### ติดตั้งบน Android
ดาวน์โหลดไฟล์ APK เวอร์ชันล่าสุดได้จาก GitHub Releases:
👉 **[ดาวน์โหลด `android-latest` APK](https://github.com/6ixthxense/mfilm-canon-film-simulation/releases/tag/android-latest)**

### Build เองจากซอร์สโค้ด
```bash
# 1. สร้างฟอนต์ ตารางสี LUT และภาพตัวอย่างฟิล์ม
bash android/scripts/build_assets.sh

# 2. คอมไพล์ APK Release
cd android && gradle assembleRelease
# ไฟล์ที่ได้: android/app/build/outputs/apk/release/app-release.apk
```

สำหรับรายละเอียดเชิงลึกดูได้ที่ [android/README.md](android/README.md) ส่วนฝั่งกล้อง Canon EOS (Magic Lantern) และโปรแกรม **Darkrooms** บนคอมพิวเตอร์ สามารถติดตามต้นฉบับได้ที่ **[HNGSeries/mfilm-canon-film-simulation](https://github.com/HNGSeries/mfilm-canon-film-simulation)** และ [docs/MANUAL.th.md](docs/MANUAL.th.md)

---

## เครดิตและผู้สร้างต้นฉบับ

- **ผู้สร้างต้นฉบับ — [HNGSeries](https://github.com/HNGSeries)**:
  โปรเจกต์นี้ Fork มาจาก **[HNGSeries/mfilm-canon-film-simulation](https://github.com/HNGSeries/mfilm-canon-film-simulation)** ขอมอบเครดิตหลักให้ **HNGSeries** สำหรับการสร้างสรรค์ M-Film, แนวคิด Film OS, สูตรจำลองฟิล์ม 68 ตัว, เอนจินห้องมืดภาษา C (`ml-dev/filmos/`) และเวอร์ชันสำหรับกล้อง Canon Magic Lantern
  สามารถสนับสนุนผู้พัฒนาต้นฉบับได้ที่: **[Buy HNGSeries a coffee ☕](https://buy.stripe.com/7sY14p8d93QE5Ny5M58so02)**
- **[Magic Lantern](https://www.magiclantern.fm/)** (`GPL-2.0`): เฟิร์มแวร์เสริมที่เป็นรากฐานของฝั่งกล้อง Canon
- **[Pat David](https://patdavid.net)** (`CC BY-SA 4.0`) และ **[Film-Luts โดย Yahia Angelo](https://github.com/YahiaAngelo/Film-Luts)** (`MIT`): ตารางสีจำลองฟิล์มสำหรับ 59 จาก 68 ฟิล์ม (รายละเอียดใน [THIRD_PARTY.md](THIRD_PARTY.md))
- **ฟอนต์ Barlow** (`SIL Open Font License 1.1`)

---

## สัญญาอนุญาต (License)

- โค้ดในคลังนี้ใช้สัญญาอนุญาต **GNU General Public License v2.0** ([LICENSE](LICENSE)) ตามต้นฉบับ M-Film และ Magic Lantern
- ตารางสีฟิล์ม 59 ตัว (`ML/DARKROOM/LOOKnn.LUT`) อยู่ภายใต้ **CC BY-SA 4.0** โดย Pat David ([THIRD_PARTY.md](THIRD_PARTY.md))
- โปรเจกต์นี้ไม่มีส่วนเกี่ยวข้องหรือได้รับการรับรองจาก Canon, Kodak, Fujifilm, CineStill, Ilford, Hasselblad, Olympus หรือผู้ผลิตกล้อง/ฟิล์มรายใด
