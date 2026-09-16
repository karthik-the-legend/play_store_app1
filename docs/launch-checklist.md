# Pre-launch checklist

In the order the Play Console asks for things. Everything marked **you** needs an account or a
password, so it can't be done from the repo.

## 1. Publish the privacy policy (you, 5 minutes)

Play won't accept the listing without a public URL.

1. In `docs/privacy-policy.md`, replace `REPLACE_WITH_YOUR_EMAIL` with the address users should write
   to, and commit.
2. On GitHub: **Settings → Pages → Build and deployment → Source: Deploy from a branch**, branch
   `main`, folder `/docs`, then Save.
3. A few minutes later the policy is at
   `https://karthik-the-legend.github.io/play_store_app1/privacy-policy`. Open it once to check.

## 2. Set up AdMob (you)

1. Create the app in AdMob and three ad units: interstitial, rewarded, native advanced.
2. Put the app ID and the three unit IDs in your Gradle properties file (see `RELEASE.md`). Without
   all four, release builds show no ads.
3. In AdMob, link the app to the Play listing once it exists, and set up payments — ads don't earn
   anything until the account is verified.

## 3. Create the app in Play Console (you)

- **App name:** FormKit (the store title is set separately — see `docs/store-listing.md`).
- **Default language:** English (India) or English (United States).
- **App or game:** App. **Free or paid:** Free, with in-app purchases.
- The package name `app.formkit` is locked the moment you upload the first bundle. It can't be
  changed afterwards, so this is the last chance to rename.

## 4. Store listing

Paste the title, short description and full description from `docs/store-listing.md`. Upload the
icon (512 × 512), the feature graphic (1024 × 500) and at least four phone screenshots — the eight
in `docs/screenshots/` are ready to use.

## 5. App content declarations

| Section | Answer |
|---|---|
| Privacy policy | The GitHub Pages URL from step 1. |
| App access | All features work without signing in — choose "All functionality is available without special access". |
| Ads | Yes, the app contains ads. |
| Content rating | Fill in the IARC questionnaire: no violence, sexual content, profanity, drugs, gambling or user-generated content; the app **does** contain ads and digital purchases. Expect "Rated for 3+" / Everyone. |
| Target audience | 18 and over only. Don't tick any younger band, or the Families policy applies and AdMob's rules get much tighter. |
| News app | No. |
| COVID-19 apps | No. |
| Data safety | The answers in `docs/data-safety.md`. |
| Government apps | No. |
| Financial features | No. |
| Health | No. |
| Advertising ID | Yes, used for advertising. |

## 6. In-app purchase

Create a **one-time product** with product ID exactly `formkit_pro_lifetime`, priced for India (₹299
is what the code has been tested against), and activate it. The code refuses to work with any other
ID.

## 7. Testing before production

- **Internal testing:** upload the signed bundle, add your own Google account as a tester, and add it
  as a **licence tester** (Play Console → Setup → Licence testing) so purchases don't charge you.
  Then build with `-Pformkit.fakeStore=false` and check the real purchase, restore, and that Pro
  survives a restart. This is the one milestone-6 check that needs a real Play Console.
- **Closed testing:** if this is a personal (individual) developer account opened after November
  2023, Google requires a closed test with **at least 12 testers who stay opted in for 14 days**
  before you can apply for production access. Start this early — it's the longest step by far.
- **Pre-launch report:** after the first upload, Play runs the app on real devices for a few minutes.
  Read the crash and accessibility findings there.

## 8. Release

- Upload `app-release.aab` (signed — see `RELEASE.md`).
- Keep Play App Signing on, which is the default for new apps.
- Upload `mapping.txt` with the bundle so crash reports are readable.
- Release notes for 1.0.0: what the app does, in two or three lines.
- Roll out to production.

## Still open

- **The ₹299 price** is what the test store shows; set the real price when you create the product.
- **A feature graphic** (1024 × 500) still has to be made.
- **Hindi and Kannada** translations: the app already filters to `en`, `hi` and `kn`, but only
  English strings exist. Adding them later is a `values-hi/strings.xml` and `values-kn/strings.xml`
  away.
