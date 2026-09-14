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

## Size log

Measured with `bundletool get-size total` on the R8-minified release bundle. This is the
download size Play would serve, which is what the 25 MB budget refers to.

| Milestone | AAB file | Download size (min–max across devices) | Notes |
|---|---|---|---|
| 1 – skeleton | 3.32 MB | 1.13–1.14 MiB (1,185,341–1,195,718 bytes) | Compose, Material 3, Hilt, Navigation, DataStore. No native code yet. |

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
