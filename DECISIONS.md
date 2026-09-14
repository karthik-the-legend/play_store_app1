# Decisions

A running record of choices that affect architecture, size, privacy or the Play listing, and why.
Newest entries go at the bottom of each section. "Default" means proposed in planning and
adopted without an explicit answer yet; flip it if you disagree.

## Toolchain (Milestone 1, 2026-09-14)

| Choice | Version | Why |
|---|---|---|
| Android Gradle Plugin | 9.4.0 | Latest stable. Uses AGP 9's built-in Kotlin, so there's no separate `kotlin-android` plugin. |
| Gradle | 9.7.1 | Latest stable; AGP 9.4 needs 9.6+. Wrapper pins the SHA-256 of the distribution. |
| Kotlin | 2.4.20 | Latest stable. KSP 2.3.12 (KSP2 is versioned separately from Kotlin). |
| compileSdk / targetSdk | 37 | "36 or latest stable" in the spec; 37 is the latest stable and what AGP 9.4 supports. |
| minSdk | 24 | Per spec. |
| JDK | 17 | What AGP 9.4 requires. |

## Libraries

| Library | Why | Rejected alternative |
|---|---|---|
| Jetpack Compose (BOM 2026.09.00) + Material 3 | Spec. | — |
| Hilt 2.60.1 via KSP | Spec. KSP instead of kapt: faster builds, kapt is in maintenance. | Manual DI (fine at this size, but the spec asks for Hilt). |
| Navigation Compose 2.10 with `@Serializable` routes | Type-safe routes; supports predictive back. | Navigation 3 (still settling; no reason to be early). |
| DataStore Preferences 1.2 | Settings and, later, the Pro entitlement. | SharedPreferences (main-thread I/O). |
| core-splashscreen | One splash implementation for API 24 through 37; holds until settings are read so the theme and onboarding decision never flash. | — |
| LeakCanary 2.14 (debug only) | Spec. Never ships in release. | — |
| Turbine + kotlinx-coroutines-test | Flow tests. | — |

Not added yet (arrive with the milestone that needs them): Coil, PdfBox-Android, ML Kit or
MediaPipe, AdMob, UMP, Play Billing.

## App identity

- **applicationId `app.formkit`** is a placeholder. It can never change after the first Play
  upload, so confirm the final ID before Milestone 8.
- The app name "FormKit" is also the name of a well-known web forms library. It's fine as a
  working name; the store title should lead with keywords anyway.

## Design system

- **Fixed brand palette, no dynamic colour.** Accent is deep indigo `#3F3CBB` (8:1 contrast on
  white). Everything else is near-neutral grey. Light and dark palettes live in
  `core/ui/theme/Color.kt`; `res/values*/colors.xml` mirrors the window colour so launch has no
  colour jump.
- **Four text styles:** Display 28sp, Title 18sp, Body 16sp, Label 14sp. All 15 Material slots
  map onto these four, so Material components that choose their own slot stay on-scale.
- **System font, no bundled fonts.** Saves size and already covers Devanagari and Kannada.
- **Shapes:** cards 16dp, buttons 12dp (buttons set explicitly; Material's default is a pill).
- **Spacing:** everything comes from `Spacing` (8/16/24/32/48dp).
- **Icons:** our own stroke icons as vector drawables, plus three standard Material glyphs
  (back, history, settings). The `material-icons-extended` artifact is deprecated and heavy.
- **Illustrations are drawn in Compose**, not shipped as images: a few KB each, and they follow
  light and dark themes automatically.
- **Large text:** the Home grid drops to one column above 1.3× font scale; empty states and
  onboarding pages scroll rather than clip.
- **Theme switching** is handled in Compose with `configChanges="uiMode"`, so changing it
  doesn't recreate the activity. On Android 12+ we also tell the system the app's night mode so
  the next launch splash matches.

## Privacy and storage

- **Android backup and device-to-device transfer are disabled** (`allowBackup=false` plus
  data-extraction rules). Otherwise settings and the future history would be copied to Google
  Drive, which breaks "nothing leaves the phone".
- **No permissions in the manifest.**
- **Cache cleanup only deletes stale files.** On app start, only cache entries untouched for 24 h
  are removed, instead of wiping the whole cache as the spec says. A full wipe would destroy the
  working files of a tool screen that Android is restoring after process death (spec §8).
  Settings → "Clear" still wipes everything on demand.
- **Save location is fixed and shown, not chosen** (default). Photos go to `Pictures/FormKit`,
  PDFs to `Documents/FormKit`. A custom folder would need the system folder picker (Storage
  Access Framework), which adds friction. We can revisit if users ask.

## Resize to exact KB (Milestone 2)

- **The algorithm differs from the spec in "allow downscale" mode.** The spec's fixed ×0.9 steps,
  at most 12 of them, can only reduce each side to 28%. A 12 MP photo at quality 1 is still far
  above 20 KB at that size, so the spec's own acceptance test could never pass. It also needed
  up to 12 × 8 full-resolution encodes. `SizeTargeter` instead:
  1. encodes a 640px probe at quality 75 to estimate how many pixels fit, and starts there;
  2. at each size, binary-searches quality 50–100 (quality 1–100 at the smallest size or on the
     last attempt), keeping the largest result under the limit;
  3. if nothing fits, shrinks by a step estimated from how far over it was (between ×0.3 and
     ×0.9), never below a 100px short edge, at most 12 attempts;
  4. if the first fit is under 80% of the limit, grows once to land closer to it.
  "Keep dimensions" and "exact dimensions" follow the spec's search (quality 1–100, 8 steps).
- **Every quality search starts by encoding the lowest allowed quality.** If that's already over
  the limit, the answer is immediate. Found on the emulator: a 4000×3000 photo at 20 KB with
  dimensions kept took ~15 s of full-resolution encodes just to say "can't reach 20 KB". Now it
  takes one encode. It costs at most one extra encode when the target is reachable.
- **Quality floor of 50 while downscaling.** Given the choice, a slightly smaller photo at
  decent quality beats a full-size photo at quality 5. That matters when it's your face on an
  admit card.
- **Exact dimensions center-crop** to the target shape before scaling, instead of stretching.
  The result screen says so. A manual crop could come later.
- **JPEG from a transparent image** is flattened onto white first. Otherwise transparent areas
  turn black.
- **Minimum size:** if the best result under the maximum is still below the minimum, the user
  is told the largest achievable size. We don't pad files with junk bytes to fake a size.
- **Decoding** uses `BitmapFactory` with `inSampleSize` (long edge ≤ 4096) plus `ExifInterface`
  for rotation on every API level. One code path; HEIC works from Android 9, the version where
  the platform decoder added it.
- **Exported files** go to MediaStore `Pictures/FormKit`. The file size is read back from disk
  after writing, and the file is deleted if it's over the limit. On Android 9 and older this
  needs `WRITE_EXTERNAL_STORAGE` (declared with `maxSdkVersion=28`), requested only when saving.
- **Export history** is a small JSON-backed DataStore (no Room). Entries whose file was deleted
  outside the app are dropped when Recent files opens.
- **Coil 3** loads thumbnails and previews (local files and content URIs only, no network module).
- **Export file names** are "<original name>_<size>KB.jpg", e.g. `IMG_2031_48KB.jpg`. The Android
  Photo Picker hides real file names and reports media IDs like `19.jpg`; names that are only
  digits become `FormKit_48KB.jpg`. MediaStore adds " (1)" etc. when a name is already taken.
- **Temporary files:** each resize session works in its own `cache/resize-<time>/` folder. It's
  deleted when the user leaves the tool or taps Do another, and `CacheJanitor` removes any left
  over after a crash once they're a day old. The spec says to clear temp files right after
  export, but that would break Back to settings and Share after saving, so cleanup happens when
  the session ends instead.

## Signature cleanup (Milestone 3)

- **No OpenCV.** It would add 10+ MB per ABI. Cleanup is about 250 lines of Kotlin working on
  plain pixel arrays, so it's unit-tested on the JVM.
- **Adaptive threshold (Bradley–Roth with an integral image):** each pixel is compared with the
  mean brightness of a window around it (radius = long edge / 24). Shadows and off-white paper
  scale paper and ink together, so they cancel out. Edges get a soft ramp (coverage 0–255)
  instead of a hard cut, so exported strokes are anti-aliased rather than jagged.
- **Brightness is the average of luminance and the brightest channel.** Light-blue and red
  ruled lines are bright in at least one channel, so they fade into the paper, while blue and
  black pen stays dark. Red pen still reads as ink, just less strongly.
- **After thresholding, connected pieces that aren't handwriting are removed:**
  - specks smaller than max(6 px, image area / 100,000);
  - ruled lines at least 80% of the page long and at most 3% thick;
  - bands running edge to edge that are no wider than three windows. This is what a hard shadow
    or the paper's edge leaves behind. If a signature touches the band it becomes one wide
    piece and is kept; better an edge left for the user to crop out than a lost signature;
  - large, sparse shapes touching the border.
- **Ink strength** moves the threshold (35% darker than the surroundings at 0, 8% at 1). From
  0.75 up it also thickens strokes by one pixel, so "bolder" is visibly bolder.
- **Photos are processed at 1600 px on the long edge.** That's plenty for a signature crop, and
  fast enough that the preview follows the slider (with a 40 ms debounce).
- **Output ink is pure black** whatever the pen colour. Forms ask for black-on-white.
- **Exact dimensions (140×60 etc.) pad instead of crop.** The signature is scaled to fit and
  centred, and the rest is filled with white or transparency, so no part of the name is lost.
- **The camera is the system camera app** (`ACTION_IMAGE_CAPTURE` into a FileProvider file), so
  there's still no `CAMERA` permission. The pending capture path is saved in case the camera
  app's memory use kills FormKit.
- **The drawing pad is 2.5:1** and always white with black ink, in both themes. Strokes are
  stored as rounded fractions of the pad, with points closer than 0.3% of its width dropped,
  so a signature is a few KB of saved state. Export renders them at 1500 px wide, then uses
  the same crop, fit and size search as photos.
- **Back from the photo or drawing screen returns to the choice screen** and discards that
  photo or drawing; output settings are kept.
- **Shared with Resize now:** saving and sharing (`ImageExports`), the result action bar, the
  Android 9 storage-permission gate, `Section` and `NumberField`, the progress dialog and
  `TargetInput` (KB and dimension parsing).
- **Limitation:** a hard-edged shadow crossing the signature itself can't be separated from
  the ink. The user sees the band in the preview and can crop, raise or lower ink strength, or
  retake. The tips on the start screen say to avoid shadows across the page.

## Size log

Measured with `bundletool get-size total` on the R8-minified release bundle. This is the
download size Play would serve, which is what the 25 MB budget refers to.

| Milestone | AAB file | Download size (min–max across devices) | Notes |
|---|---|---|---|
| 1 – skeleton | 3.32 MB | 1.13–1.14 MiB (1,185,341–1,195,718 bytes) | Compose, Material 3, Hilt, Navigation, DataStore. No native code yet. |
| 2 – resize to KB | 4.40 MB | 1.51–1.52 MiB (1,578,216–1,591,301 bytes) | +Coil 3, ExifInterface, kotlinx-serialization-json: about +0.4 MB. |
| 3 – signature | 4.56 MB | 1.56–1.57 MiB (1,636,714–1,650,704 bytes) | Cleanup is plain Kotlin, no new libraries: about +58 KB. |

## Test environment notes

- `SettingsRepositoryTest` uses an in-memory `DataStore`. On this Windows development machine,
  file-backed DataStores fail in JVM tests when replacing the file ("Unable to rename …"),
  because the antivirus is still scanning the previous write. Real storage gets exercised on
  the device and emulator instead.
- Instrumented tests pin Espresso 3.7.0 and AndroidX Test 1.7.0. The older Espresso that
  Compose's test rule pulls in calls `InputManager.getInstance` through reflection, which no
  longer exists on Android 17, so every Compose UI test failed before its first line.
- Java-based tools on this machine need `-Djavax.net.ssl.trustStoreType=Windows-ROOT`, because
  Avast re-signs HTTPS. That setting is machine-local (Gradle user home, `GRADLE_OPTS`) and
  never goes in the repo.

## Defaults adopted from planning, not yet confirmed

These affect later milestones. They're recorded here so they don't get silently re-decided.

1. **KB means 1000 bytes.** A file under N×1000 bytes also passes portals that count 1 KB as
   1024 bytes.
2. **Explicit pixel dimensions override "allow downscale."** A form that asks for 200×230 must
   get exactly 200×230.
3. **Optional minimum KB** alongside the maximum, because many forms set a range (for example
   20–50 KB).
4. **Resize speed:** estimate a starting resolution from the target size before running the
   binary search, instead of starting every search at 4096px.
5. **PNG can only reach a KB target by downscaling**, because `Bitmap.compress` ignores quality
   for PNG. The UI will say so.
6. **Camera** uses the system camera app (`ACTION_IMAGE_CAPTURE`, no permission) until Scan to
   PDF needs CameraX.
7. **Background removal:** ML Kit *Subject* Segmentation is ruled out because it downloads its
   model on first use, which fails offline. ML Kit Selfie Segmentation and MediaPipe will be
   measured in Milestone 4.
8. **PDF:** Images→PDF and Compress use a small in-house writer that embeds JPEGs directly.
   PdfBox is only for merge, split and password-protected files, and needs approval first
   because it's likely over 3 MB.
9. **Compress PDF** searches one quality level across all pages against the total size, rather
   than hitting the target page by page.
10. **Print sheet** has a 300 DPI tag and a PDF option; the watermark goes in the margin only.
11. **Interstitials** load ahead of time and only show if already loaded, so the user never waits.
12. **Target audience 18+** in Play Console, to stay out of the Families policy.

Open question: "batch processing (5+ files)" in §7 isn't defined in any feature. Is it batch
resize only, or batch across all tools?
