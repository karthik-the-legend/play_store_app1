# Releasing FormKit

Everything secret — your upload key and your AdMob IDs — lives in your own Gradle properties file,
never in this repository. A build with nothing configured still works: it comes out unsigned and
shows no ads.

## 1. Make your upload key (once)

Google Play signs the app you ship with its own key; the key below is the one you sign *uploads*
with. Run this yourself, somewhere outside the repo, and pick the two passwords when prompted:

```bash
"D:\android-toolchain\jdk\bin\keytool.exe" -genkeypair -v -keystore "D:\keys\formkit-upload.jks" -alias formkit -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a keystore password, then your name and organisation (any sensible answer is fine), then
a key password — pressing Enter reuses the keystore password.

**Back the file up somewhere safe.** If you lose it you can ask Google to reset the upload key, but
only because Play holds the real app-signing key; without Play App Signing a lost key ends the app.

Never commit the `.jks` file, and never put its password in this repo.

## 2. Tell Gradle where it is

This project's Gradle home is `D:\android-toolchain\gradle-home`, so the file to edit is
`D:\android-toolchain\gradle-home\gradle.properties` (create it if it isn't there). Add:

```properties
formkit.keystore.path=D:/keys/formkit-upload.jks
formkit.keystore.password=<the keystore password>
formkit.key.alias=formkit
formkit.key.password=<the key password>
```

Your real AdMob IDs go in the same file, once you've made the app and ad units in AdMob:

```properties
formkit.admob.appId=ca-app-pub-XXXXXXXXXXXXXXXX~XXXXXXXXXX
formkit.admob.interstitial=ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX
formkit.admob.rewarded=ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX
formkit.admob.native=ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX
```

If any of the four is missing, the release build shows **no ads at all** rather than Google's test
ads, which would break AdMob's rules. Debug builds always use the test IDs.

## 3. Build the bundle

```bash
cd /d/FormKit && JAVA_HOME='D:\android-toolchain\jdk' ANDROID_HOME='D:\android-toolchain\sdk' GRADLE_USER_HOME='D:\android-toolchain\gradle-home' ./gradlew :app:bundleRelease
```

The bundle lands at `app/build/outputs/bundle/release/app-release.aab`. That's the file you upload.

Check it came out signed with your key:

```bash
"D:\android-toolchain\jdk\bin\jarsigner.exe" -verify -verbose -certs "D:\FormKit\app\build\outputs\bundle\release\app-release.aab" | head -20
```

It should say `jar verified` and name your certificate. If it says the jar is unsigned, Gradle
couldn't find the keystore — check the path in step 2.

Keep `app/build/outputs/mapping/release/mapping.txt` for every version you upload: it's what turns a
crash report back into readable line numbers. Play accepts it on the App Bundle Explorer page.

## 4. Before each new version

- Raise `versionCode` by 1 in `app/build.gradle.kts`, and set `versionName` to what users should see
  (for example `1.0.1`).
- Run the tests: `./gradlew :app:testDebugUnitTest` and, with an emulator running,
  `./gradlew :app:connectedDebugAndroidTest`.
- Check the download size: `docs/` in this repo has the size log in `DECISIONS.md`. The budget is
  25 MB.

## 5. What to fill in before the first upload

- `docs/privacy-policy.md` — ready, with the contact address filled in; publish it through GitHub
  Pages (see `docs/launch-checklist.md`).
- `docs/store-listing.md` — the listing text, ready to paste.
- `docs/data-safety.md` — the answers to Play's Data safety form.
- `docs/launch-checklist.md` — everything else Play asks for, in order.
