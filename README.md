# FormKit

An Android app for getting documents ready for online forms — exam applications, job portals,
government sites — entirely on the phone. No account, no server, no uploads.

- **Resize to an exact KB size**, with exact pixel dimensions when a form demands them
- **Passport photos** with the background removed on-device, and 4×6 print sheets
- **Signature cleanup** from a photo of paper, or signed with a finger
- **Scan to PDF**: find the page's edges, straighten it, clean it up, fit a KB limit
- **PDF tools**: compress to a size, images to PDF, merge, split, PDF to images

Free with ads (AdMob), plus a one-time FormKit Pro purchase that removes them.

## Building

Kotlin, Jetpack Compose, Hilt, minSdk 24. Open in Android Studio, or:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest   # needs a device or emulator
```

Debug builds use Google's AdMob test ad units and a fake billing store, so nothing here needs an
AdMob or Play Console account.

## Where things are

| File | What's in it |
|---|---|
| [SPEC.md](SPEC.md) | The brief the app was built to. |
| [DECISIONS.md](DECISIONS.md) | Every library choice, the measurements behind it, the algorithms, and what was checked on a device. |
| [RELEASE.md](RELEASE.md) | Signing, AdMob IDs and how to build the bundle to upload. |
| [docs/](docs) | Privacy policy, store listing, Data safety answers, launch checklist, screenshots and store artwork. |

## Privacy

Files are opened, processed and saved on the device. The app has no server, no analytics and no
account. The free version's ads come from Google AdMob, which uses the advertising ID; Pro removes
them. See [docs/privacy-policy.md](docs/privacy-policy.md).
