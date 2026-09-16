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

## Passport photo (Milestone 4)

### Background removal: measured, not guessed

Both candidates were added to a throwaway branch, built as release bundles (R8 on) and measured
with `bundletool get-size total --dimensions=ABI`. Native libraries were checked for 16 KB
page-size support by reading the ELF `PT_LOAD` alignment.

| | arm64-v8a download | armeabi-v7a | x86_64 | Native library | 16 KB pages | Model |
|---|---|---|---|---|---|---|
| Milestone 3 baseline | ~1.64 MB | — | — | — | yes | — |
| ML Kit Selfie Segmentation 16.0.0-beta6 | 7.79 MB (+6.2) | 6.91 MB | 7.52 MB | `libxeno_native.so` 21 MB uncompressed | yes (16384) | selfie 256×256, 243 KB |
| MediaPipe tasks-vision 1.0.0 + `selfie_segmenter.tflite` | 6.92 MB (+5.3) | 6.15 MB | 7.67 MB | `libmediapipe_tasks_jni.so` 10.8 MB uncompressed | yes (16384) | selfie 256×256, 244 KB |

- **ML Kit Subject Segmentation is ruled out:** it downloads its model through Play services on
  first use, which fails offline.
- **MediaPipe's multiclass selfie model** (better hair) is 16 MB, which is too big.
- **Chosen: MediaPipe tasks-vision 1.0.0** with `selfie_segmenter.tflite` (decided 2026-09-14;
  the app goes from about 1.6 MB to about 6.9 MB on arm64). The model is stored uncompressed so
  MediaPipe can memory-map it.
- **Both options run the same small selfie model**, so accuracy is the same. The difference is
  size (MediaPipe is about 0.9 MB smaller on arm64), stability (MediaPipe is 1.0.0; ML Kit
  Selfie is still a beta) and room to grow: MediaPipe's runtime can add a 224 KB face detector
  for better auto-framing.

### Library-independent parts

- **Presets are exact at 300 DPI:** India and Schengen 35×45 mm = 413×531 px; US 2×2 in =
  600×600 px; 51×51 mm = 602×602 px. Custom sizes accept mm, inches (with a DPI) or pixels.
- **Head guides** (crown, eye line, chin) follow ICAO for 35×45 mm and the US State Department
  rules for 2×2 in. They're guides, not certification.
- **Auto-framing reads the person's silhouette:** the crown is the top of the mask. If the
  silhouette narrows into a neck near where the upper head's width says the chin should be, the
  chin is placed 12% of the crown-to-jaw height below that narrowest row. Seen from the front,
  the chin sits in front of the neck, so the narrowest row is the jaw line (measured on a
  short-haired test portrait). Long hair usually hides the neck; the head height then comes from
  the upper head's width. The photo is placed so the crown and chin sit on the guide lines, and
  the user drags and pinches from there.
- **Placement is stored in source pixels** (the point at the frame's centre and the visible
  height), so switching size presets keeps the head in place.
- **Touch-ups** are a list of brush strokes replayed on the model's mask, so undo is exact. The
  brush has a solid core and a soft edge, and dabs are spaced a quarter of the radius apart. The
  model's confidence goes through a smoothstep (0.35–0.65) to sharpen its soft edge.
- **Rendering halves the cut-out** before the final bilinear scale when shrinking a lot,
  otherwise hair edges alias.
- **Print sheet:** 4×6 in at 300 DPI (1200×1800 px). Photos keep their exact pixel size. Spacing
  is a 30 px margin and 24 px gaps when the photos fit that way; otherwise they tile edge to
  edge with shared cut lines (six 2×2 in photos fill the sheet exactly). Eight Indian photos fit
  with gaps.
- **Watermark:** "Made with FormKit", small, in the bottom margin only, never over a photo. A
  sheet tiled edge to edge has no margin and so no watermark. Pro and rewarded ads remove it in
  Milestone 6.
- **Printing at true size:** JPEGs get a 300 DPI JFIF density, edited in place so the byte count
  and any KB limit are unchanged. The sheet can also be a PDF at its physical page size, built
  with Android's own `PdfDocument` (no library).
- **PDFs are saved to Documents/FormKit** through MediaStore, the same way images are saved to
  Pictures/FormKit.

## PDF tools (Milestone 5)

### PdfBox-Android, without BouncyCastle: measured, not guessed

Built as release bundles (R8 on) on a throwaway branch, measured with
`bundletool get-size total --dimensions=ABI`, and tested on the API 37 emulator with a 3-page PDF
locked with RC4-128, AES-128 and AES-256 (made with pypdf).

| | arm64-v8a download | Largest (x86) | Added | Passwords (RC4, AES-128, AES-256) | Merge, split |
|---|---|---|---|---|---|
| Milestone 4 baseline | 6.99 MB | 8.24 MB | — | — | — |
| PdfBox-Android 2.0.27.0 | 13.03 MB | 14.29 MB | +6.04 MB | all pass | pass |
| PdfBox-Android 2.0.27.0, BouncyCastle excluded | 8.82 MB | 10.08 MB | +1.83 MB | all pass | pass |

- **Chosen: PdfBox-Android without BouncyCastle** (decided 2026-09-15). BouncyCastle costs about
  4.2 MB even after R8. Standard password encryption goes through the platform's `javax.crypto`,
  so it isn't needed. Only certificate-encrypted PDFs (rare) can't be opened; the app says so.
- **Android's own `PdfRenderer` can't take a password below API 35** (SDK extension 13 on 12–14),
  and minSdk is 24, so password support needs PdfBox. PdfBox unlocks a temporary copy, which
  `PdfRenderer` then renders.
- **R8 rules:** `-dontwarn com.gemalto.jp2.**` (optional JPEG 2000 decoder) and
  `-dontwarn org.bouncycastle.**`.

### How the tools work

- **PDFs are picked with the system file picker** (`ACTION_OPEN_DOCUMENT`), because the Photo
  Picker only offers images and videos. Neither needs a storage permission. Images → PDF still
  uses the Photo Picker (up to 50 photos).
- **Every picked file is copied into the tool's workspace**, and the session is saved, so a
  tool comes back after process death, the same as the photo tools.
- **Passwords:** PdfBox checks the password and writes an unlocked copy, which Android's renderer
  reads. Files FormKit makes never carry a password, and the screen says so; upload portals
  usually reject protected PDFs anyway. A protected PDF already under the size limit is simply
  unlocked instead of compressed.
- **Compress to a KB limit** follows §4.1 per page:
  1. A quick pass renders a small preview of each page. Its JPEG size is the page's weight, and
     a 100 px, quality-1 encode estimates the page's floor.
  2. If the floors plus the PDF's own overhead (2 KB + 0.7 KB a page) are over the limit, it
     stops at once and says the smallest size possible.
  3. Otherwise the budget is split: every page gets its floor, and the rest goes by weight, so
     photo pages get more than text pages. Each page is rendered at 200 DPI (at most 2400 px) and
     fitted with the size search.
  4. The rebuilt PDF is measured on disk. If it's over, the budget shrinks by the overshoot and
     the pages are redone (up to 4 attempts). The saved copy is checked against the limit again.
  - Rebuilt pages take their size from Android's renderer, which reports whole points, so an A4
    page (595.28 × 841.89) comes back as 595 × 841: under a millimetre smaller. Reading each page's
    exact box and rotation through PdfBox would fix that, but isn't worth the extra risk for a
    difference no form or printer will notice.
  - Checked on the emulator: a 2.8 MB two-page photo PDF compressed to 191,230 bytes for a 200 KB
    limit in about 10 seconds.
  - Before starting, the screen warns that text won't be selectable afterwards.
- **Images → PDF:** photos are capped at 3508 px on the long side (A4 at 300 DPI), put on white
  if transparent, and written as JPEG quality 88. PdfBox embeds the JPEG bytes unchanged. A4 and
  Letter pages fit the photo inside the margin, centred. Auto orientation turns a page sideways
  for a landscape photo. "Fit to photo" pages show the photo at 150 DPI.
- **PDF → Images:** 72, 150 or 300 DPI, capped at 4000 px, JPEG quality 92 or PNG. Saved to
  Pictures/FormKit/<PDF name>/, with page numbers zero-padded so galleries sort them correctly.
- **Split:** typed ranges ("1-3, 5, 8-") make one PDF per range; picked pages make one PDF in
  page order. Merge and split keep the original pages (text stays selectable).
- **Reordering** (merge, images → PDF) is a small in-house drag handle, not a library. Every row
  also has move up/down buttons for TalkBack and switch access.
- **Rendering** stays at one page in memory at a time. PdfBox buffers in temporary files, not
  RAM, so large documents don't run out of memory.

### Checked on the emulator (API 37)

Using the two public-domain NASA portraits and the pypdf-made test PDFs:

- **Images → PDF:** two photos (4.6 MB and 5.2 MB) became a 2-page A4 PDF of 2.8 MB.
- **Compress:** that PDF came out at 191,230 bytes for a 200 KB limit, in about 10 seconds.
- **Split:** ranges "1,2" made two one-page PDFs (1.59 MB and 1.28 MB).
- **Merge:** those two, reordered with the arrow, merged into a 2-page PDF whose pages carry the
  split files' image bytes unchanged, in the new order.
- **PDF → Images:** 150 DPI JPEG gave 1240 × 1752 px pages (775 KB for both), saved to
  Pictures/FormKit/Photos_2_pages/.
- **Passwords:** the AES-256 test PDF opened the prompt on its own, rejected a wrong password
  with a clear message, and opened with the right one.

## Ads and billing (Milestone 6)

### SDK size: measured, not guessed

Release bundles (R8 on) on a throwaway branch, each with the consent SDK
(`user-messaging-platform` 4.0.0) and `billing-ktx` 9.1.0, and stub code calling interstitial,
rewarded and native ads, consent and billing so R8 keeps what the real code will use. Measured
with `bundletool get-size total --dimensions=ABI`.

| | arm64-v8a download | Largest (x86) | Added |
|---|---|---|---|
| Milestone 5 baseline | 8.92 MB | 10.18 MB | — |
| `play-services-ads` 25.4.0 | 10.90 MB | 12.18 MB | +2.0 MB |
| `play-services-ads-lite` 25.0.0 | 9.24 MB | 10.51 MB | +0.3 MB |

- Both are under the 3 MB limit that needs approval.
- **Permissions both add:** `INTERNET`, `ACCESS_NETWORK_STATE`, `com.google.android.gms.permission.AD_ID`,
  `ACCESS_ADSERVICES_AD_ID`/`ATTRIBUTION`/`TOPICS`, `WAKE_LOCK`, `FOREGROUND_SERVICE` and
  `com.android.vending.BILLING`. The Data Safety answers (Milestone 8) must declare the advertising ID.
- The lite SDK borrows the ad code from Google Play services on the phone, so phones without it
  show no ads, and its latest release (25.0.0) trails the full SDK (25.4.0).
- The first measuring attempt failed for lack of memory, not because of the SDKs: the emulator
  alone held about 6 GB. Measure with the emulator shut down.

### Choices (decided 2026-09-15)

- **Full `play-services-ads` 25.4.0**, not lite: ads work on every phone and the SDK is current.
  12.2 MB at most is still well under the 25 MB budget.
- **Test purchases:** the real Play Billing code is built now, alongside a debug-only fake store,
  so unlocking, pending purchases, restoring and restarting offline can be checked on the emulator.
  A real Play test purchase needs a Play Console internal test track with `formkit_pro_lifetime`
  and a license tester, which the owner sets up (planned for Milestone 8). Uploading to Play locks
  the package name, so `app.formkit` must be final by then.
- **The rewarded ad unlocks a watermark-free print sheet for the session, and nothing else.**
  FormKit has no batch resize, and capping Images → PDF or Merge would block students mid-task.
  Pro removes the watermark for good.
- **Ad IDs:** debug builds use Google's official test IDs. Release builds read the AdMob app ID
  and ad unit IDs from local Gradle properties (`formkit.admob.appId`, `.interstitial`,
  `.rewarded`, `.native`) that never go in the repo. If any is missing, the release build
  shows no ads rather than test ads.

### How it works

- **Consent first.** Google's User Messaging Platform runs once per launch, after onboarding is
  finished, so the form never covers the welcome screens. No ad is requested until it says
  `canRequestAds()`. If it fails, there are simply no ads; every tool still works. Settings shows
  "Ad privacy choices" only where UMP says users must be able to change their choice.
- **Start-up stays fast.** The SDK starts on a background thread, with AdMob's
  `OPTIMIZE_INITIALIZATION` and `OPTIMIZE_AD_LOADING` flags and measurement delayed until
  consent. Ad content is capped at a teen rating.
- **Interstitials** follow §7 in `InterstitialPolicy`: never for Pro, never without consent, never
  on a new install's first two operations, at most one every 90 seconds. They're shown as a result
  screen first appears, and only if one is already loaded, so no one ever waits on an ad; the
  spec's 2-second timeout is therefore always met. A finished operation is counted once per
  result, even across rotation and process death.
- **Rewarded ad:** "Watch an ad" on the print sheet screen waits up to 3 seconds for an ad (the
  user asked for it and sees a spinner). Watching to the end removes the watermark from sheets until
  the app process ends. Closing early or no ad leaves the watermark, with a message saying why.
- **Native ad:** Recent files only, drawn like a file card with an "Ad" label, and placed after the
  last file so its late arrival never pushes a file the user is reading.
- **No banners anywhere.** A dismissible Pro offer sits at the bottom of every result screen, and
  the Settings card offers Get Pro and Restore purchase. Nothing is shown on launch or mid-task.
- **Pro:** `ProManager` is the single source of truth.
  - The cached answer applies at once, so Pro works offline.
  - Google Play is asked again at each app start. Pro is revoked only when Play answers
    successfully that nothing is owned (a refund); an offline or failed check keeps the cache.
  - Pending purchases unlock only once they clear.
  - Purchases are acknowledged, and a failed acknowledgement is retried on the next check,
    because Play refunds unacknowledged purchases after 3 days.
  - With no server, purchases are checked on the phone; that is the spec's zero-backend trade-off.
- **Debug test store:** debug builds use `FakeProStore` unless built with `-Pformkit.fakeStore=false`.
  Its "server" record lives apart from FormKit's own cache, and Settings has controls to go
  offline, choose the next purchase outcome (succeed, pending, cancelled, fails), clear a pending
  payment, refund, and check now. `-Pformkit.consentDebugEea=true` shows the consent form as if in
  the EEA.
- **Debug logging:** debug builds log consent, SDK start-up, every ad load and every interstitial
  decision under the `FormKitAds` tag (`adb logcat -s FormKitAds`). Release builds log nothing.

### Checked on the emulator (API 37, Google test ads, fake store)

- **Consent:** Google's consent update succeeded and recorded that GDPR doesn't apply
  (`IABTCF_gdprApplies=0`), so no form was needed; `canRequestAds=true`. Earlier, while HTTPS
  couldn't be verified, consent failed and FormKit showed no ads while every tool kept working,
  which is the designed fallback.
- **Interstitial:** AdMob's test interstitial showed as a result screen appeared, only once the
  rules allowed (for example "operation 10 … allowed=true loaded=true", more than 90 s after the
  last), and closed back to the result.
- **Native ad:** a test native ad appeared after the last file on Recent files, styled as a card
  with an "Ad" label. AdMob's native ad validator reported "No implementation issues found".
- **Rewarded ad:** "Watch an ad" on the print sheet screen played AdMob's test rewarded ad. After it,
  the screen said "No watermark on this sheet." In the resulting 1800×1200 JPEG, the 54 px bottom
  margin where the watermark goes has no watermark-grey pixels at all.
- **Pro (fake store):** the price showed in Settings. Get Pro unlocked "FormKit Pro is active" and
  removed ads. Pro stayed active after switching the store offline and restarting the app. A
  refund followed by a check brought the offer back.
- **LeakCanary:** it briefly held two closed `AdActivity` instances, then reported "All retained
  objects have been garbage collected". It asks for notification permission to report leaks;
  that prompt exists only in debug builds.

## Scan to PDF (Milestone 7)

### Finding the page: built here, not bought (decided 2026-09-16)

| | Size | Works offline | Screens | Risk |
|---|---|---|---|---|
| **Our own edge finder** (chosen) | +0 MB | always | FormKit's own | detection has to be good enough on its own |
| ML Kit document scanner | about +0.3 MB | **no on first use** | Google's | needs Play services |
| OpenCV | about +9–10 MB an ABI | always | ours | over the ~3 MB rule |

ML Kit's scanner is excellent and would have been the least work, but Play services downloads it
the first time it runs, so the first scan on a new phone fails in airplane mode. §2.6 says every
feature except ads must work offline, and "scan the form you're about to upload" is exactly what
someone does on patchy data. OpenCV would cost more than a third of the whole size budget.

### How the finder works

All of it is plain Kotlin on a 480 px greyscale copy of the photo, so it runs in unit tests without
a device (`PageEdgeFinder`).

1. Blur away paper grain and JPEG noise, find edges with a Sobel filter, and thin them to one pixel.
2. **Hough transform:** every edge pixel votes for the straight lines through it that face the way
   it does. Long straight edges win, even where a thumb or a shadow breaks them. Each winning line
   is then fitted exactly to its own pixels, which takes corner accuracy well inside a pixel or two.
3. Two roughly parallel lines crossed with two more make a candidate outline. Opposite sides may
   lean up to 40° towards each other, because a page shot at an angle is a trapezoid, not a rectangle.
4. Each candidate is scored side by side: how much of the side runs along an edge facing the right
   way, and whether the inside is lighter (or darker) than the outside all the way round. **That
   last check is what stops a box printed on the page winning**, because a printed line has the same
   paper on both sides of it. The largest well-supported outline wins.
5. If the weakest side isn't convincing, **nothing is returned**: the corners start at the photo's
   edges and the screen says the edges weren't found. §4.5 asks for exactly this rather than a bad
   auto-detect.

Straightening uses Android's own `Matrix.setPolyToPoly`, a four-point perspective transform, so
there's no matrix maths or library of ours in the output path.

### The looks (`ScanFilters`)

Enhanced and black & white first estimate how bright the bare paper is across the page: the page is
cut into a grid of about 64 cells on the long side, each takes its 90th-percentile brightness (text
rarely covers a tenth of a cell), each cell then takes the brightest of its neighbours so a cell
filled by a heading isn't mistaken for ink, and the grid is smoothed. Dividing each pixel by the
paper under it cancels shadows and uneven light, so the paper comes out white corner to corner while
ink, stamps and photos keep their colour. Greyscale is plain luminance; Original changes nothing.

### The rest of the tool

- **CameraX** (`camera-camera2`, `camera-lifecycle`, `camera-view`, +0.53 MB measured) gives a
  viewfinder inside FormKit, so a ten-page document is ten shutter taps, not ten trips to the camera app.
  `CAMERA` is asked for only when Scan is tapped, and a phone with no camera (or a refused
  permission) still scans from photos. `android.hardware.camera.any` is declared as not required.
- **Each shot goes straight to the page check**, so a page whose edges weren't found is caught while
  the document is still on the table. Corners are dragged with a magnifier following the finger,
  because the corner being placed is exactly what the finger covers. The outline turns red and Done
  waits if the shape folds over itself.
- **Screen readers** can't drag: every corner is a 48 dp target with Move left/right/up/down actions
  that nudge it 1% of the photo.
- **The KB target reuses Milestone 5's fitter.** `PdfCompressor` now takes any source of page images
  (`PageImages`), so scanned pages go through exactly the same budget split and on-disk check as
  Compress PDF. Pages are straightened once at 2400 px, and the fitting pass re-encodes from those.
  With no limit, pages are written at JPEG quality 88.
- **Page size:** A4 or Letter (auto portrait or landscape), or "Fit to page", which shows the scan at
  205 DPI so a straightened A4 page comes out A4-sized.
- **Previews:** every change redraws the page at 900 px so the list and the adjust screen show the
  finished look, not the raw photo. One render runs at a time and repeated edits collapse into the
  last one.

### Checked on the emulator (API 37)

Two generated photos of a printed form lying at an angle on a wooden desk, with a drop shadow and
light falling off across the frame (`make_scan_photos.py`):

- **Both pages were found**, and the outline sat on the corners of each page. The list showed
  straightened, cleaned pages with no desk left in them.
- **A4, 200 KB limit:** the two pages came out as a 194 KB PDF; the saved file measured 194,941
  bytes on disk.
- **Camera:** tapping Scan asked for the camera there and then. The viewfinder, shutter, page count
  and Done all worked. The emulator's virtual scene holds no document, so the finder returned
  nothing and the page opened with the corners at the photo's edges and "FormKit couldn't find this
  page's edges" — the fallback §4.5 asks for.
- **Corners:** dragging one moved it with the magnifier following the finger; Reset corners put the
  found outline back exactly.
- **Black & white** and **Rotate** both redrew the preview.
- **Process death** (`am kill` with the app in the background): the page list came back with its
  page, its look and its thumbnail.
- **Back** from the page list asked "Discard this scan?" — Keep scanning kept the pages, Discard
  emptied the tool.
- **Instrumented tests (30, all passing)** include four new ones drawing a page on a dark table with
  Android's own canvas: the outline is found within 24 px of where it was drawn, the straightened
  page matches the drawn shape's own side lengths, two scanned pages fit under a 150 KB limit, and
  three pages come out as three pages with no limit.

## Release preparation (Milestone 8)

### R8 broke the release build four times, and only running it found them

Milestones 1–7 were checked on debug builds and by instrumented tests. Neither goes through R8, and
`assembleRelease` succeeding proves nothing about a minified app at runtime. The first time the
release bundle was actually installed and opened (16 September 2026), it failed four times in a row:

1. **Crash on startup: `Failed to create an instance of androidx.work.impl.WorkDatabase`.**
   WorkManager, which the ads SDK pulls in, builds its Room database by name. Room's own rule keeps
   the generated class but says nothing about its members, so R8 removed the no-argument constructor
   Room calls. Fixed with `-keep class * extends androidx.room.RoomDatabase { <init>(); }`.
2. **Crash on the first screen: "Cannot find class with name app.formkit.feature.home.Tool".**
   Type-safe navigation identifies a route by its class's fully qualified name and looks an enum
   argument up with `Class.forName`. kotlinx.serialization's rules keep the generated serializers but
   let R8 rename the classes themselves. Fixed with `@Keep` on the enum and
   `-keepnames @kotlinx.serialization.Serializable class app.formkit.navigation.**`.
3. **Every passport photo failed with "Couldn't open this photo".** MediaPipe's tasks are wired
   through JNI and protobuf-lite, both of which find classes and fields by name, so the segmenter
   couldn't start; the tool reported it as an unreadable photo. Fixed with
   `-keep class com.google.mediapipe.** { *; }` and the same for `com.google.protobuf.**`.
4. **Then the passport tool crashed instead: `no caller found on the stack for: nb1`.** With
   MediaPipe able to start, its first log statement went through Flogger, which finds the calling
   class by walking the stack and matching its own class name — a name R8 had changed. Fixed with
   `-keep class com.google.common.flogger.** { *; }`.

Everything else survived minification untouched: resize, signature, scan, the PDF tools (PdfBox and
Android's renderer), consent, and the Pro card's offline fallback.

Those keeps cost about 0.6 MB of download, because MediaPipe, protobuf and Flogger are no longer
shrunk. They could be narrowed class by class later; a passport tool that works is worth more than
0.6 MB out of a 25 MB budget.

**From now on, a milestone isn't done until the release build has been installed and walked
through.** The check is: build the bundle, `bundletool build-apks` with the debug key,
`install-apks`, then open every tool once and watch `adb logcat -b crash`.

### Signing (decided 2026-09-16)

- The upload key's path and passwords come from the developer's own Gradle properties, exactly like
  the AdMob IDs. Nothing secret is in the repo.
- **With no key configured the release build comes out unsigned**, rather than falling back to the
  debug key, so a missing key can't quietly ship a bundle signed with a key anyone has.
- Play App Signing stays on, so the upload key can be replaced if it's ever lost.
- `RELEASE.md` has the `keytool` command and the properties to set; the owner runs it, and the
  passwords never pass through here.

### Version

1.0.0, `versionCode` 1. `RELEASE.md` says what to raise for each later upload.

### Store assets

- `docs/store-listing.md` — title (24 characters, keyword-led: "Photo Resize in KB & PDF"), short
  description, full description and what each of the eight screenshots must show.
- `docs/data-safety.md` — Play's Data safety answers. Everything declared is the AdMob SDK's doing;
  files and purchase history are deliberately *not* declared, with the reasons written down.
- `docs/privacy-policy.md` — the policy to publish through GitHub Pages.
- `docs/launch-checklist.md` — the Play Console steps in order, including the closed test with 12
  testers for 14 days that a personal developer account now needs.
- `docs/store-assets/` — the 512 px icon and the 1024 × 500 feature graphic, drawn from the app's own
  launcher artwork so they match the icon on the phone.

## Size log

Measured with `bundletool get-size total` on the R8-minified release bundle. This is the
download size Play would serve, which is what the 25 MB budget refers to.

| Milestone | AAB file | Download size (min–max across devices) | Notes |
|---|---|---|---|
| 1 – skeleton | 3.32 MB | 1.13–1.14 MiB (1,185,341–1,195,718 bytes) | Compose, Material 3, Hilt, Navigation, DataStore. No native code yet. |
| 2 – resize to KB | 4.40 MB | 1.51–1.52 MiB (1,578,216–1,591,301 bytes) | +Coil 3, ExifInterface, kotlinx-serialization-json: about +0.4 MB. |
| 3 – signature | 4.56 MB | 1.56–1.57 MiB (1,636,714–1,650,704 bytes) | Cleanup is plain Kotlin, no new libraries: about +58 KB. |
| 4 – passport photo | 26.50 MB | 5.93–7.86 MiB (6,220,787–8,243,468 bytes) | +MediaPipe tasks-vision and the 244 KB selfie model. By ABI: armeabi-v7a 6.22 MB, arm64-v8a 6.99–7.00 MB, x86_64 7.75 MB, x86 8.23 MB. The AAB holds every ABI's native library, so it's much bigger than any download. |
| 5 – PDF tools | 28.99 MB | 7.78–9.71 MiB (8,154,898–10,179,971 bytes) | +PdfBox-Android without BouncyCastle: about +1.9 MB. By ABI: armeabi-v7a 8.15 MB, arm64-v8a 8.92–8.94 MB, x86_64 9.68 MB, x86 10.16 MB. |
| 6 – ads and Pro | 33.41 MB | 9.73–11.68 MiB (10,197,992–12,246,843 bytes) | +play-services-ads (full), UMP and Play Billing: about +2.0 MB. By ABI: armeabi-v7a 10.20–10.24 MB, arm64-v8a 10.96–11.00 MB, x86_64 11.72–11.76 MB, x86 12.21–12.25 MB. |
| 7 – scan to PDF | 35.00 MB | 10.24–12.19 MiB (10,732,098–12,784,741 bytes) | +CameraX (camera2, lifecycle, view): about +0.53 MB. The page finder, the straightening and the filters add no dependency at all. By ABI: armeabi-v7a 10.73–10.77 MB, arm64-v8a 11.50–11.54 MB, x86_64 12.27–12.31 MB, x86 12.75–12.78 MB. |
| 8 – release prep | 36.04 MB | 10.83–12.80 MiB (11,354,004–13,420,782 bytes) | No new library: +0.62 MB is the keep rules that stop R8 shrinking MediaPipe, protobuf and Flogger, which is what it cost to make the minified build work at all. By ABI: armeabi-v7a 11.35–11.41 MB, arm64-v8a 12.12–12.17 MB, x86_64 12.89–12.94 MB, x86 13.37–13.42 MB. |

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
- **The same Avast HTTPS scanning breaks ads on the emulator.** The emulator doesn't trust Avast's
  certificate, so consent and ad requests fail with "Trust anchor for certification path not found".
  Pause HTTPS scanning (or exclude the emulator) while checking ads, then turn it back on.
- **Emulator DNS can go stale** (for example after the PC sleeps). Every name then fails to resolve
  while pinging an IP still works. Relaunch with `-dns-server 8.8.8.8,8.8.4.4`.
- **Memory:** the emulator holds about 6 GB. Measure release size with it shut down. With it
  running, build with `-Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.compiler.execution.strategy=in-process`,
  or the Gradle daemon can be killed.
- **UI automation gotchas on this image:**
  - Gboard's "Try out your stylus" tutorial can cover the screen after text input and swallow taps.
  - The newer photo picker selects on tap and needs "Done".
  - Android shows a one-time "Viewing full screen" hint over the first full-screen ad.
  - A fresh boot can raise "System UI isn't responding" under load; tap Wait.

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
7. **Background removal:** settled in Milestone 4 (MediaPipe, see above).
8. **PDF:** settled in Milestone 5 (PdfBox-Android without BouncyCastle, +1.83 MB, see above).
9. **Compress PDF** searches one quality level across all pages against the total size, rather
   than hitting the target page by page.
10. **Print sheet** has a 300 DPI tag and a PDF option; the watermark goes in the margin only.
11. **Interstitials** load ahead of time and only show if already loaded, so the user never waits.
12. **Target audience 18+** in Play Console, to stay out of the Families policy.

Open question: "batch processing (5+ files)" in §7 isn't defined in any feature. Is it batch
resize only, or batch across all tools?
