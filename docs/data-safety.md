# Play Data safety answers

Play asks these on the **App content → Data safety** page. FormKit itself collects nothing; every
answer below exists because of the AdMob SDK, which is why they all disappear for a Pro user.

Before submitting, check these against Google's own page for AdMob publishers
(<https://support.google.com/admob/answer/11150250>) — Google updates what its SDK collects, and the
publisher is responsible for the declaration.

## Overview answers

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | The AdMob SDK does, for ads. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | AdMob and Play Billing use HTTPS. |
| Do you provide a way for users to request that their data is deleted? | **No** | Nothing is held on a server to delete. Uninstalling removes everything on the device, and the advertising ID is reset from Android's own settings. |
| Is your app designed for children? | **No** | Target audience is 18 and over. |

## Data types to declare

### Device or other IDs — advertising ID

- **Collected: yes. Shared: yes** (with Google, as the ad network).
- **Processed ephemerally: no.**
- **Required or optional:** required for the free version. (Where consent law applies, the consent
  form comes first, and Pro removes ads entirely.)
- **Purposes:** Advertising or marketing; Analytics (ad performance); Fraud prevention, security and
  compliance (invalid-traffic checks).

### Location — approximate location

- **Collected: yes. Shared: yes.**
- AdMob derives an approximate location from the IP address to pick and cap ads. FormKit asks for no
  location permission and never reads the phone's location.
- **Required or optional:** required for the free version.
- **Purposes:** Advertising or marketing.

### App activity — app interactions

- **Collected: yes. Shared: yes.**
- Ad impressions, clicks and similar events measured by AdMob. FormKit adds no analytics of its own
  and records nothing about which tools you use.
- **Required or optional:** required for the free version.
- **Purposes:** Advertising or marketing; Analytics.

## Do NOT declare

- **Photos and videos, files and documents.** FormKit opens the files you pick, works on them on the
  device, and saves what you ask it to. Nothing is transmitted or collected, so Play's definition of
  collection isn't met. Say so plainly in the listing instead.
- **Purchase history.** FormKit Pro is bought through Google Play; the app never receives payment
  details, and Play handles the transaction itself.
- **Personal info, contacts, messages, health, calendar.** Never touched.

## Matching answers elsewhere in the Console

- **App content → Ads:** "Yes, my app contains ads."
- **App content → Target audience:** 18 and over, so the Families policy doesn't apply.
- **App content → Data safety → privacy policy URL:** the published copy of `docs/privacy-policy.md`.
- **App content → Advertising ID:** declare that the app uses it, for advertising, which the
  `com.google.android.gms.permission.AD_ID` permission (added by the ads SDK) requires.
