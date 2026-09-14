# BUILD SPEC — "FormKit" Android App

> **How to use this file:** Put it in an empty folder as `SPEC.md`, open Claude Code in that folder, and start with:
> `Read SPEC.md. Do not write any code yet. Give me your implementation plan and flag anything ambiguous or risky. Then we'll build Milestone 1 only.`
> Then work milestone by milestone. Do **not** ask for the whole app in one go.

---

## 1. Role and objective

You are a senior Android engineer. Build a production-quality, Play Store–ready Android app called **FormKit** (working name).

**What it does:** Helps Indian students and applicants prepare files for online exam and government form uploads — resizing photos to an exact KB limit, making passport-size photos, cleaning up signatures, and converting/compressing PDFs. Everything runs **fully offline, on-device**.

**Who it's for:** A 21-year-old filling a KEA/SSC/UPSC/bank form at 11 PM the night before the deadline, on a mid-range Android phone, on mobile data. Optimize every decision for that person.

---

## 2. Non-negotiable constraints

These are hard requirements. If any instruction later in this file conflicts with these, these win.

1. **Zero backend.** No server, no database, no cloud storage, no Firebase (except AdMob, which is a separate SDK). No user accounts. No login.
2. **All processing on-device.** No image, PDF, or signature ever leaves the phone. This is both the privacy promise and the cost model.
3. **No subscriptions.** Monetization is ads + a single one-time in-app purchase.
4. **APK/AAB size target: under 25 MB** download size. Report the size after each milestone. If a library pushes past this, stop and tell me before adding it.
5. **minSdk 24, targetSdk 36** (or latest stable at build time). Kotlin only. No Java files.
6. **Offline-first means offline-tolerant.** Every feature except ads must work in airplane mode. If ads fail to load, the app continues silently — never block a user action on an ad request.

---

## 3. Tech stack

| Concern | Choice |
|---|---|
| Language | Kotlin (latest stable) |
| UI | Jetpack Compose + Material 3 |
| Build | Gradle Kotlin DSL, version catalog (`libs.versions.toml`) |
| Architecture | MVVM — Compose UI → ViewModel → Repository/UseCase |
| Async | Coroutines + Flow. All image/PDF work on `Dispatchers.Default` or `IO`, never the main thread |
| DI | Hilt |
| Navigation | Navigation Compose, type-safe routes |
| Local storage | DataStore (Preferences) for settings and entitlement. No Room unless a feature needs it |
| Image loading | Coil |
| Background removal | ML Kit Subject Segmentation **or** MediaPipe Tasks ImageSegmenter — evaluate both for APK size and accuracy, recommend one, then implement |
| PDF | Android `PdfRenderer` for reading/rasterizing; `PdfBox-Android` (Apache 2.0) for merge/split/compress. **Never iText** (AGPL licensing) |
| Ads | Google AdMob + UMP consent SDK |
| Billing | Google Play Billing Library (latest) |
| Testing | JUnit + Turbine for unit tests, Compose UI tests for critical flows |

---

## 4. Features — exact specifications

Build in the milestone order given in §9. Each feature below lists acceptance criteria that must pass before moving on.

### 4.1 Resize image to an exact KB target

The flagship feature. Everything else is secondary.

**Flow:** Pick image (Android Photo Picker) → set target size → preview before/after → save/share.

**Inputs:**
- Target file size: numeric field in KB, plus quick-pick chips: `20 KB`, `50 KB`, `100 KB`, `200 KB`, `500 KB`, `1 MB`, and `Custom`
- Optional target dimensions in px, with quick-picks for the common Indian form sizes: `200×230`, `140×160`, `160×160`, `300×300`, `413×531` (35×45mm @ 300dpi)
- Output format: JPEG (default) or PNG
- Toggle: "Keep exact dimensions" vs "Allow downscale to hit size"

**Algorithm — implement exactly this:**
1. Decode the image with `BitmapFactory.Options.inSampleSize` computed so the decoded bitmap never exceeds ~4096px on the long edge (prevents OOM on large camera photos).
2. If target dimensions were given, scale to exactly those dimensions first.
3. Binary search JPEG quality over `[1, 100]`, ~8 iterations, compressing to a `ByteArrayOutputStream` each time. Keep the largest result that is **≤ target size**.
4. If quality 1 still exceeds the target and "Allow downscale" is on: multiply both dimensions by `0.9`, repeat from step 3. Stop at a floor of 100px on the short edge or 12 attempts, whichever comes first.
5. If the target still cannot be met, show a clear message: *"Smallest achievable at these dimensions is 34 KB. Allow downscaling to go lower?"* — never fail silently, never save a file that exceeds the target the user asked for.
6. Prefer landing **just under** the target (e.g. 48 KB for a 50 KB limit), never over.

**Acceptance criteria:**
- A 4 MB camera photo resizes to ≤ 20 KB in under 2 seconds on a mid-range device
- The saved file size, checked on disk, is always ≤ the requested target
- Result screen shows: original size → new size, original dimensions → new dimensions, and % reduction
- Works with HEIC, JPEG, PNG, and WebP inputs
- Unit tests cover the binary search with at least 6 cases including the impossible-target path

### 4.2 Passport-size photo maker

**Flow:** Pick/capture photo → auto background removal → choose background colour → choose size preset → preview → export single photo or a print sheet.

**Requirements:**
- **Size presets:** Indian passport `35×45mm`, US `2×2in`, `51×51mm`, Schengen `35×45mm`, plus Custom (mm or px at a chosen DPI). Default output 300 DPI.
- **Background removal** on-device. Show a determinate or indeterminate progress indicator; it must never look frozen.
- **Background colours:** white, light blue, light grey, red, plus a custom colour picker.
- **Manual correction:** brush to erase/restore mask regions, with pinch-zoom and undo. Segmentation will be imperfect on hair and dark clothing — this tool is what makes the app usable rather than a toy.
- **Face guide overlay:** a crop frame showing head-height and eye-line guides, draggable and pinch-zoomable.
- **Print sheet export:** tile the photo into a 4×6 inch sheet at 300 DPI with faint cut lines and a count selector (typically 6 or 8 copies).
- Also apply the §4.1 KB-targeting to the output, since forms cap passport photo size too.

**Acceptance criteria:**
- Exported photo has exactly the correct pixel dimensions for the chosen preset and DPI
- Background removal completes in under 3 seconds on a mid-range device
- Manual brush edits are reflected live in the preview
- The print sheet opens correctly in Google Photos and prints at true size

### 4.3 Signature cleanup

**Flow:** Photograph a signature on paper (or pick an image) → auto-crop → clean → export.

**Requirements:**
- Auto-detect the signature's bounding box and crop with a small margin; allow manual crop adjustment
- Convert to clean black-on-white: adaptive threshold, with a live "Ink strength" slider so the user can control how bold the strokes are
- Output options: white background (JPEG) or transparent (PNG)
- Common form presets: `10 KB`, `20 KB`, and dimension presets `140×60`, `160×60`
- Also offer **Draw signature** — a finger/stylus canvas with stroke-width control, undo, and clear, exporting to the same presets

**Acceptance criteria:**
- A photo of a signature on lined/off-white paper produces a clean white background with no visible paper texture or shadow
- Transparent PNG export has genuinely transparent pixels, verified in a test

### 4.4 PDF tools

- **Images → PDF:** multi-select, reorder by drag, page size (A4/Letter/Fit-to-image), margin, orientation
- **PDF → Images:** export pages as JPEG/PNG at a selectable DPI
- **Compress PDF to a target KB:** rasterize pages via `PdfRenderer`, apply the §4.1 binary-search algorithm per page, rebuild the PDF. Warn clearly that text becomes non-selectable after this
- **Merge PDFs** with drag-to-reorder
- **Split PDF:** by page range, or extract selected pages
- Handle password-protected PDFs by prompting for the password rather than crashing

### 4.5 Scan to PDF (Milestone 5 — lowest priority)

Camera capture → document edge detection → perspective correction → filter (Original / Greyscale / B&W / Enhanced) → multi-page → export as PDF with a KB target.

If edge detection proves unreliable, ship manual corner-drag adjustment instead and tell me. Do not ship a bad auto-detect.

---

## 5. Storage, permissions, and file handling

- **Permissions: the absolute minimum.** Use the Android Photo Picker (`PickVisualMedia`) so no storage permission is needed at all. `CAMERA` only when the user taps a camera action, requested in context with an explanation.
- **Never** request `READ_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE`.
- Save outputs via `MediaStore` to `Pictures/FormKit` and `Documents/FormKit`.
- Share via `FileProvider` + `ACTION_SEND`.
- Work on temp files in `cacheDir`; clear them on app start and after successful export.
- **In-app history:** a "Recent files" screen listing exports with thumbnail, size, and date, with re-share and delete. Store metadata in DataStore or a small Room table — never upload it anywhere.

---

## 6. UI and UX specification

The competition in this category is functional but ugly. **Visual quality is a core feature, not polish.** Treat every screen as something a user would screenshot.

### Design direction
- Material 3 with **dynamic colour disabled** — use a fixed, owned brand palette so the app looks identical on every device
- Single accent colour (pick a confident deep indigo or teal), neutral greys elsewhere, generous whitespace
- Full dark theme support, switchable in settings: System / Light / Dark
- Rounded corners (16dp cards, 12dp buttons), soft elevation, no heavy borders
- Typography scale: one display, one title, one body, one label. No more.
- 8dp spacing grid throughout

### Screens
1. **Home** — a grid of tool cards with clear icons and one-line descriptions. No ad on this screen.
2. **Tool screen** (one per feature) — input at top, live preview centre, single prominent primary action at the bottom.
3. **Result screen** — before/after comparison, file size delta, and three actions: Save, Share, Do Another.
4. **Recent files**
5. **Settings** — theme, default output folder, clear cache, Pro unlock, privacy policy, rate app, share app.

### Interaction rules
- Every long operation shows progress and is cancellable
- Every error message says what went wrong **and what to do next** — never a bare "Error" toast
- Haptic feedback on primary actions
- Empty states have an illustration and a suggested first action
- Onboarding: 3 screens maximum, skippable, shown once
- Support system back and predictive back gestures correctly

### Accessibility and localisation
- All interactive elements have `contentDescription`
- Minimum touch target 48dp
- Text scales correctly up to 200% font size without clipping
- **All user-facing strings in `strings.xml`.** No hardcoded text anywhere. Ship English first; structure for Hindi and Kannada to be added later.

---

## 7. Monetization

### AdMob
- **Interstitial:** after a *completed* operation, on the way to the result screen. Frequency cap: **maximum one per 90 seconds, and never on the first two operations of a new install.**
- **Rewarded:** unlocks batch processing (5+ files at once) and watermark-free print sheets for that session.
- **Native ad:** on the Recent Files screen only, styled to match the app.
- **No banner ads anywhere in a tool or editing flow.** Do not let an ad cover, shift, or interrupt content.
- Use **AdMob test unit IDs in debug builds**, real IDs in release, wired through build config. Never hardcode a real ad unit ID in the debug path.
- Implement the **UMP consent flow** before ad initialization.
- If an ad fails or times out (2s), proceed to the result screen immediately.

### Play Billing
- One non-consumable product: `formkit_pro_lifetime`
- Pro removes all ads, enables unlimited batch processing, and removes any watermark
- Cache entitlement in DataStore so Pro works offline; re-verify on app start when online
- Handle pending purchases and restore-purchases correctly
- **Upsell rules:** one dismissible Pro card on the settings screen, plus an offer shown *after* a successful operation — never a modal on app launch, never blocking a free feature mid-flow

---

## 8. Performance and quality targets

- Cold start under 1.5s on a mid-range device
- No ANRs, no `NetworkOnMainThread`, no bitmap OOM on 50MP input images
- `LeakCanary` in debug builds
- R8/ProGuard enabled for release, with correct keep rules for PdfBox and ML Kit
- Enable App Bundle splits
- StrictMode on in debug
- Handle configuration changes and process death without losing in-progress work

---

## 9. Milestones

Build and verify one milestone at a time. After each: run the build, run the tests, report APK size, and wait for my confirmation before continuing.

| # | Scope | Definition of done |
|---|---|---|
| **1** | Project skeleton: Gradle setup, theme, navigation, Home screen, Settings screen, Recent Files (empty state) | App builds and runs, navigation works, dark mode works |
| **2** | §4.1 Resize to exact KB, end to end, with unit tests | Target size always met, tests pass |
| **3** | §4.3 Signature cleanup + draw signature | Clean output verified on a real photo |
| **4** | §4.2 Passport photo, including background removal and manual brush | Correct output dimensions, mask editing works |
| **5** | §4.4 PDF tools | All five operations work, size reported |
| **6** | AdMob + UMP + Play Billing | Test ads show, test purchase unlocks Pro, entitlement survives offline restart |
| **7** | §4.5 Scan to PDF | Only if APK size budget allows |
| **8** | Release prep | Signed AAB, ProGuard verified, store assets listed |

---

## 10. Play Store deliverables

At Milestone 8, produce:
- A signed release AAB and instructions for the upload key
- **Data Safety form answers** — this is simple because nothing is collected, but AdMob does collect advertising ID, so declare it accurately
- A privacy policy in Markdown I can host, stating: no data collection, all processing on-device, AdMob collects advertising ID for ads
- Store listing draft: title (under 30 chars, keyword-led), short description (under 80 chars), full description with the actual search terms users type — "photo resize", "resize image in kb", "passport size photo", "signature resize", "compress pdf"
- A list of 8 screenshot specs describing exactly what each should show
- A pre-launch checklist: content rating, target audience, ads declaration, testing tracks

---

## 11. Explicit non-goals — do not build these

- No login, accounts, or cloud sync
- No analytics SDK beyond what AdMob requires
- No subscription products
- No social sharing feeds, no gamification, no AI chatbot
- No feature that requires a network call to function
- No watermark on free exports **except** the multi-photo print sheet
- Do not add a library over ~3 MB without asking me first

---

## 12. How I want you to work

- Before writing code for a milestone, give me a short plan and flag ambiguities. Do not silently guess on anything that affects architecture or APK size.
- Write the KB-targeting algorithm with tests **first** — it's the core of the product and everything else depends on it.
- Commit per milestone with clear messages.
- Keep a running `DECISIONS.md` recording library choices and why, so I can defend them later.
- If something in this spec is technically wrong or a bad idea, say so and propose the alternative. Do not implement something you think is wrong just because it's written here.
