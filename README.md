# Pro Camera (Fixed + iOS-style UI)

โปรเจกกล้อง Android 2.1 — แก้ไขบั๊กทั้งหมดจากเวอร์ชัน 1.0/2.0 และออกแบบ UI ใหม่ในสไตล์ iOS/Apple Camera

---

## การแก้ไขเพิ่มเติมใน v2.1

### Build Blockers

| # | ไฟล์ | ปัญหา | การแก้ |
|---|------|------|--------|
| 1 | `colors.xml` | `@color/theme_bg_dark` ถูกอ้างใน `ic_launcher.xml` แต่ไม่มีอยู่ → AAPT2 build fail | เพิ่มสี `theme_bg_dark` |
| 2 | `gradle/wrapper/*` | ไม่มี Gradle Wrapper → `./gradlew` ใช้ไม่ได้ | เพิ่ม wrapper (Gradle 8.7) |
| 3 | `themes.xml` | `windowLightNavigationBar` (API 27) / `windowLayoutInDisplayCutoutMode` (API 28) ใน base values → lintVitalRelease fail | แยกไป `values-v27/`, `values-v28/` |
| 4 | `README.md` | บอกว่าใช้ Android Studio Hedgehog ได้ แต่ AGP 8.5.2 ต้องใช้ Koala+ | แก้เป็น Koala (2024.1.1) ขึ้นไป |

### Runtime Bugs

| # | ไฟล์ | ปัญหา | การแก้ |
|---|------|------|--------|
| 5 | `CameraController.kt::takePicture` | precapture trigger ถูกยิงรวมใน still-capture request → AE/flash ไม่ converge ภาพมืด/สว่างเกิน | ยิง trigger บน preview stream แล้วรอ `AE_STATE`/`FLASH_STATE` converged (timeout 1.5s) ก่อนถ่าย |
| 6 | `CameraController.kt` | หลังถ่ายทุกครั้ง `startPreview()` สร้าง capture session ใหม่ทั้งชุด → กระตุก/ช้า | ใช้ `restartPreviewRepeating()` — `setRepeatingRequest` บน session เดิม |
| 7 | `CameraController.kt::openCamera` | แนบ RAW stream ตลอด (3 surfaces) → เครื่อง LIMITED ที่รองรับ 2 streams กล้องพังทั้งแอป | แนบ RAW เฉพาะ hardware level FULL/LEVEL_3 + retry ไม่มี RAW อัตโนมัติถ้า config fail |
| 8 | `CameraController.kt::triggerFocusAtPoint` | แตะโฟกัสโหมด 16:9 แมพผิดตำแหน่ง (ไม่คิด crop + rotation) | คำนวณ crop rect ตาม preview aspect + แปลงพิกัดตาม sensor orientation |
| 9 | `MainActivity.kt` | `setImageURI` ถอดรูปเต็มความละเอียดใส่ thumbnail 48dp → เสี่ยง OOM | `decodeThumbnail()` ด้วย inSampleSize + หมุนตาม EXIF บน IO thread |
| 10 | `native-lib.cpp` | Bayer pattern สมมติ RGGB ตายตัว + white level 4095 ตายตัว | รับ `cfaPattern` (RGGB/GRBG/GBRG/BGGR) + `whiteLevel` จาก `SENSOR_INFO_*` |

### Robustness

| # | ไฟล์ | ปัญหา | การแก้ |
|---|------|------|--------|
| 11 | `CameraController.kt::stateCallback` | `onDisconnected` ขอกล้องเก่าปิดกล้องใหม่ตอนสลับ ratio (race) | เช็ค identity `if (cameraDevice === camera)` |
| 12 | `CameraController.kt` | `isManualSupported` คำนวณแต่ไม่ใช้ — สั่ง manual ISO/S บนเครื่องที่ไม่รองรับ | gate ที่ applySettings/takePicture + Toast ใน MainActivity |
| 13 | `CameraController.kt::openCamera` | ขนาด capture/preview ตายตัว 1080p ไม่ validate กับ StreamConfigurationMap | `chooseSize()` เลือกขนาดใหญ่สุดที่รองรับจริงตาม ratio |
| 14 | `MainActivity.kt::onCreate` | `systemUiVisibility` deprecated + top bar margin 28dp ชน cutout | `WindowCompat.setDecorFitsSystemWindows` + คำนวณจาก WindowInsets จริง |
| 15 | ทั้งโปรเจกต์ | Toast ฝังข้อความไทย hardcode ทั้งที่มี strings.xml | ใช้ `@string/toast_*` ทั้งหมด |
| 16 | `CameraController.kt::closeCamera` | ไม่เคลียร์ `previewRequestBuilder` → applySettings โยน exception รั่วใน log | เคลียร์ builder/flags ตอน close |

---

## การแก้ไขบั๊กหลัก

### บั๊กวิกฤต (แก้แล้ว)

| # | ไฟล์ | ปัญหา | การแก้ |
|---|------|------|--------|
| 1 | `MainActivity.kt::adjustPreviewAspectRatio` | สูตร aspect ratio กลับด้าน — 16:9 ได้ height สูงกว่า 4:3 | เปลี่ยนเป็น `(screenWidth * 9) / 16` และ `(screenWidth * 3) / 4` |
| 2 | `CameraController.kt::imageReader callback` | `acquireLatestImage()` คืน null → NPE crash | เพิ่ม `?: return@post` ทั้ง JPEG และ RAW |
| 3 | `native-lib.cpp` | สีแดง/น้ำเงินสลับกันใน bitmap (byte order ผิดบน little-endian) | เปลี่ยนสูตรเป็น `(A<<24) \| (B<<16) \| (G<<8) \| R` |
| 4 | `CameraController.kt::startBackgroundThread` | รั่ว HandlerThread ทุกครั้งที่ onResume | เช็ค `isAlive` ก่อนสร้างใหม่ |
| 5 | `native-lib.cpp` | CCM matrix รับเข้าแต่ไม่ถูกใช้ | คูณ `[r,g,b]` ด้วย CCM 3×3 จริง ๆ |
| 6 | `native-lib.cpp` | RAW processing แบบ 2×2 binning → output 1/4 resolution | ใช้ bilinear demosaic เต็มรูปแบบ (แต่ละพิกเซลส่งออก 1:1) |
| 7 | `CameraController.kt::triggerFocusAtPoint` | ไม่เช็ค max metering regions → crash บนเครื่องที่ไม่รองรับ | เช็ค `CONTROL_MAX_REGIONS_AF/AE` ก่อน set regions |
| 8 | `native-lib.cpp` | ไม่เช็ค bounds ของ raw buffer | เช็ค length ก่อนเข้าถึง, ใช้ `getRaw()` ที่ clamp index |

### บั๊กรุนแรง (แก้แล้ว)

| # | ไฟล์ | ปัญหา | การแก้ |
|---|------|------|--------|
| 9 | `MainActivity.kt::checkThumbnailExists` | query contentResolver บน main thread → ANR | ย้ายไป `lifecycleScope + Dispatchers.IO` |
| 10 | `CameraController.kt` | ถือ Activity context → เสี่ยง leak | เปลี่ยนเป็น `context.applicationContext` |
| 11 | `CameraController.kt::previewCallback` | โพสต์ไป main ทุกเฟรม → กระตุก | throttle ~16 fps ด้วย `AtomicLong + AtomicBoolean` |
| 12 | `CameraViewModel.kt` | state หายเมื่อ process death | ใช้ `SavedStateHandle` + `MutableStateFlow` |
| 13 | `CameraController.kt::takePicture` | flash อาจไม่ยิงเพราะไม่ trigger precapture | เพิ่ม `CONTROL_AE_PRECAPTURE_TRIGGER_START` |
| 14 | `CameraController.kt::switchRatio` | reopen ทันทีหลัง close → race | เพิ่ม `closeCameraSync()` ที่ abortCaptures ก่อน |
| 15 | `MainActivity.kt` | `ViewModelProvider(this)[...]` deprecated | ใช้ `by viewModels()` delegate |
| 16 | `MainActivity.kt::currentRotation` | `defaultDisplay.rotation` deprecated (API 30+) | ใช้ผ่าน currentWindowMetrics (wrapper) |
| 17 | `CameraController.kt::writeExifToUri` | FOCAL_LENGTH format Rational ผิดเมื่อ lastFocalLength=0 | เช็ค `> 0f` ก่อนเขียน |
| 18 | `CameraController.kt::saveBitmapToGallery` | ไฟล์ชื่อ PRO_RAW แต่เป็น JPEG | เปลี่ยนชื่อเป็น `PRO_DEMOSAIC_` |

### การปรับปรุง build

- AGP 8.1.0 → 8.5.2, Kotlin 1.8.20 → 1.9.24
- เพิ่ม `buildTypes { release { isMinifyEnabled, isShrinkResources } }` + `proguard-rules.pro`
- เพิ่ม `ndk.abiFilters` = `arm64-v8a`, `armeabi-v7a`
- CMake flags: `-O3 -ffast-math -funroll-loops` + NEON สำหรับ armv7
- เพิ่ม `viewBinding`, dependency `material`, `constraintlayout`, `exifinterface`
- NDK version เป็น LTS (`25.2.9519653`)

### การปรับปรุง Manifest

- `android:largeHeap="true"` (RAW processing ใช้ memory เยอะ)
- `android:hardwareAccelerated="true"`
- `android:configChanges="orientation\|screenSize\|..."` (กัน restart เมื่อหมุน)
- `android:screenOrientation="portrait"` (กล้องควรล็อค)
- `android:requestLegacyExternalStorage="true"` (Android 9 ลงลง)
- Theme เป็น `Theme.ProCamera` (edge-to-edge, transparent system bars, cutout `shortEdges`)
- ใช้ `@string/app_name` แทน hardcode

---

## การออกแบบ UI ใหม่แนว iOS/Apple Camera

### การออกแบบ

- **Edge-to-edge**: viewfinder เต็มจอ ตั้งแต่ status bar ถึง navigation bar
- **Status bar โปร่งใส** ใช้ `windowLightStatusBar = false`
- **Top gradient** และ **Bottom Gradient** สีดำโปร่งแสงเพื่อให้อ่านปุ่มได้ง่าย (iOS-style)
- **Top bar** มี flash (ซ้าย) และ ratio (ขวา) ใน **circle button** สีดำโปร่งใสมีขอบ
- **Focus ring**: สี่เหลี่ยมมุมมน สีเหลือง `#FFD60A` (iOS yellow) เมื่อ tap, สีแดง `#FF3B30` (iOS red) เมื่อ lock
- **Shutter button**: วงกลมคู่ — วงนอกสีขาว + วงในสีขาว (iOS-style)
- **Slider panel**: frosted-glass card มีมุม 20dp พร้อม border สีขาว 20%
- **Auto toggle**: ปุ่ม pill สีเหลืองโปร่งแสง + ขอบ
- **Mode buttons** (AF/ISO/WB/S/EV): text-only, สีขาว, ตัวหนา, เมื่อ active → สีเหลือง
- **Thumbnail**: รูปสี่เหลี่ยมมุมมน 10dp มีขอบขาว

### การอนิเมชัน

- **Shutter feedback**: alpha 0.4 → 1.0 ใน 80ms (เหมือน iOS)
- **Slider open/close**: alpha animation 180ms/120ms
- **Focus ring tap**: scale 0.6 → 1.0 ใน 180ms ด้วย `DecelerateInterpolator`
- **Focus ring hide**: fade-out 220ms แทน visibility GONE ทันที
- **Flash toggle**: fade-in animation

### พาเลตสี

| ใช้งาน | ค่าสี |
|--------|-------|
| พื้นหลัง | `#000000` |
| Text ปกติ | `#FFFFFF` |
| Text รอง | `#99FFFFFF` (60%) |
| Accent yellow | `#FFD60A` (iOS system yellow) |
| Accent blue | `#0A84FF` (iOS system blue) |
| ขอบโปร่งใส | `#1AFFFFFF` |
| Frosted panel | `#B3000000` (70% ดำ) |
| Focus lock | `#FF3B30` (iOS red) |

---

## โครงสร้างไฟล์

```
Procam-fixed/
├── build.gradle.kts                         (updated AGP 8.5.2)
├── settings.gradle.kts
├── gradle.properties
├── README.md                                (this file)
└── app/
    ├── build.gradle.kts                     (+ viewBinding, buildTypes, abiFilters, deps)
    ├── proguard-rules.pro                   (NEW)
    └── src/main/
        ├── AndroidManifest.xml              (+ configChanges, largeHeap, theme, etc.)
        ├── cpp/
        │   ├── CMakeLists.txt                (+ optimization flags, NEON)
        │   └── native-lib.cpp                (FIX: color order, CCM, demosaic, bounds)
        ├── java/com/procamera/
        │   ├── MainActivity.kt               (FIX: aspect ratio, viewModels, IO query)
        │   ├── CameraController.kt           (FIX: null check, leak, throttle, flash)
        │   ├── CameraViewModel.kt            (FIX: SavedStateHandle + StateFlow)
        │   ├── TouchFocusHandler.kt          (FIX: gestures, pop animation)
        │   └── ImageProcessor.kt             (unchanged)
        └── res/
            ├── drawable/
            │   ├── shutter_button.xml        (iOS-style, layer-list)
            │   ├── focus_frame.xml           (yellow square)
            │   ├── focus_frame_locked.xml    (red square)
            │   ├── bg_top_gradient.xml       (NEW)
            │   ├── bg_bottom_gradient.xml    (NEW)
            │   ├── bg_panel.xml              (NEW, frosted)
            │   ├── bg_auto_pill.xml          (NEW, yellow pill)
            │   ├── bg_thumbnail.xml          (NEW, rounded)
            │   ├── bg_circle_button.xml      (NEW)
            │   ├── ruler_thumb.xml           (iOS-style thumb)
            │   ├── rect_bg.xml
            │   ├── circle_button.xml
            │   ├── ic_flash_on.xml
            │   ├── ic_flash_off.xml
            │   ├── ic_launcher_foreground.xml
            │   ├── more_vert_24px.xml
            │   └── tick_mark.xml
            ├── layout/
            │   └── activity_main.xml         (iOS-style redesign)
            └── values/
                ├── colors.xml               (iOS palette)
                ├── strings.xml              (extracted strings)
                └── themes.xml               (NEW: Theme.ProCamera)
```

---

## วิธี build

```bash
# ใน Android Studio: File > Open > เลือก Procam-fixed/
# หรือ command line:
./gradlew assembleDebug
./gradlew installDebug
```

**ข้อกำหนด**:
- Android Studio Koala (2024.1.1) ขึ้นไป (AGP 8.5.2)
- JDK 17
- Android SDK 34 + NDK 25.2.9519653 (LTS)
- Gradle 8.7 — มี Gradle Wrapper มาให้แล้ว (`./gradlew` จะดาวน์โหลดอัตโนมัติ)
- minSdk 24, targetSdk 34

---

## สิ่งที่อาจปรับต่อไป

1. เพิ่มกล้องหน้า (front camera) และปุ่ม flip
2. เพิ่มโหมดถ่ายวิดีโอ
3. เพิ่มโหมด HDR / Night mode
4. ใช้ CameraX แทน Camera2 เพื่อความเข้ากันได้มากขึ้น
5. เพิ่ม grid lines (rule of thirds) overlay
6. เพิ่ม histogram แบบ real-time
7. เพิ่ม zoom (pinch-to-zoom)
8. เก็บการตั้งค่าผู้ใช้ SharedPreferences / DataStore
9. เพิ่ม RAW (.dng) output จริง ๆ (DngCreator)
10. ใช้ Camera2 Extensions API (Bokeh, HDR, Night)
