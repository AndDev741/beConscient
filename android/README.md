# beConscient Android collector

Kotlin app that measures phone screen time and sends it, sealed in an HPKE envelope, to your beConscient server. It records:

| Sensor | Source | Event |
|---|---|---|
| Usage | `UsageStatsManager.queryEvents` (foreground app, screen on/off, lock, shutdown) | `android.usage` |
| Notifications | `NotificationListenerService`: app, post time, flags, importance, category, and how each one was removed | `android.notification_posted`, `android.notification_removed` |
| Shorts | Accessibility service limited to YouTube, reading layout ids only | `android.shorts` |
| Launcher | The home screen: pause screens and focus periods | `android.pause`, `android.focus` |
| Time zone | Zone or clock change, boot, pairing | `android.tz_change` |
| Health | Sensor states, watermark, queue, launcher, every 15 minutes | `health` |

Notification titles, text, senders, keys and tags never leave the phone. The wire formats live in [`../contract`](../contract); this app follows `envelope.md` and `events.md`.

## Build

You need JDK 21 and the Android SDK with platform 36. `gradle/gradle-daemon-jvm.properties` asks Gradle for a JDK 21, and Gradle finds one installed through SDKMAN or the usual locations. Otherwise set `JAVA_HOME`. Point at the SDK with `ANDROID_HOME` or a `local.properties` holding `sdk.dir=...` (ignored by git).

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The unit tests run on the JVM: Robolectric for Android APIs, an in-memory Room database, and MockWebServer playing the server with the test private key from `contract/keys`.

### Test vectors

`VectorTest` opens every file in `contract/vectors` as the recipient. To rewrite this collector's own file and check it with the Go reference:

```sh
./gradlew testDebugUnitTest --tests '*VectorTest*' -Pbeconscient.writeVectors=true
cd ../tools && go run ./cmd/vectorcheck
```

## Configure

The server address and its public key are compiled in.

| Setting | Gradle property | Environment |
|---|---|---|
| Server base URL | `beconscient.serverUrl` | `BECONSCIENT_SERVER_URL` |
| Server public key file | `beconscient.publicKeyFile` | `BECONSCIENT_PUBLIC_KEY_FILE` |

The key file has the shape of `contract/keys/test-public.json` (`kid`, `public_key`, `test_only`). A relative path resolves from this folder.

The **debug** build defaults to `http://10.0.2.2:8080` (the host as seen from the emulator) and the test key. It allows plain http through `src/debug/res/xml/network_security_config.xml`; the release build allows https only.

The **release** build refuses to build when the URL is missing or not https, or when the key is missing or test-only (`kid` `test-0001` or `"test_only": true`):

```text
> beConscient release build refused:
    - the key 'test-0001' in '../contract/keys/test-public.json' is the TEST-ONLY key; production refuses it and its private half is public
```

A release build for your server:

```sh
./gradlew assembleRelease \
  -Pbeconscient.serverUrl=https://collector.example.org \
  -Pbeconscient.publicKeyFile=/secure/place/server-public.json
```

### Signing

Release signing reads its settings from `~/.gradle/gradle.properties` or the environment. Keep the keystore and its passwords outside the repository.

```properties
beconscient.signing.storeFile=/home/you/keys/beconscient.jks
beconscient.signing.storePassword=...
beconscient.signing.keyAlias=beconscient
beconscient.signing.keyPassword=...
```

The environment names are `BECONSCIENT_SIGNING_STORE_FILE`, `BECONSCIENT_SIGNING_STORE_PASSWORD`, `BECONSCIENT_SIGNING_KEY_ALIAS` and `BECONSCIENT_SIGNING_KEY_PASSWORD`. Without them `assembleRelease` produces `app-release-unsigned.apk`. To make a key once:

```sh
keytool -genkeypair -v -keystore ~/keys/beconscient.jks -alias beconscient -keyalg EC -groupname secp256r1 -validity 10000
```

## Install

Enable USB debugging on the phone, connect it, then:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

Installing with `adb install` matters on Android 13 and later. An APK opened from a browser download or a file manager counts as sideloaded, and Android then greys out the notification access and accessibility switches as "restricted settings". If that happens, open App info for beConscient, tap the three-dot menu, choose "Allow restricted settings", and confirm.

Updates keep the same signing key, so `adb install -r` keeps the queue and the pairing.

## Onboarding

The app opens on a setup list. Each step explains what it is for, shows **Done**, **Pending**, **Not granted** or **Skipped**, and is checked again every time you return to the app.

1. **Pair.** On the beConscient page, open Devices and add this phone. Type the one-time code (`XXXX-XXXX`, valid 15 minutes) and a name. The phone makes its own installation id and 32-byte secret and shows a fingerprint; compare it with the one the Devices page shows for this phone. A code that was already used, expired or mistyped shows an alert; make a new one.
2. **Usage access.** The core sensor. The first read reaches back about 7 days.
3. **Notification access.**
4. **YouTube Shorts detection** (accessibility). Optional: without it Shorts time counts as YouTube.
5. **Battery optimization.** Lets the 15-minute work run in the background.
6. **Pause app activity if unused.** Turn it off, or Android removes the permissions of an app you rarely open (Android 11 and later).

You can skip any step. The status screen keeps a warning for it, and **Finish setup** or **Review setup** brings the list back.

The status screen works without the server. It shows each sensor's state, the queue (waiting, acknowledged and kept 14 days, rejected), the last upload and the last error in plain words, recent notification listener gaps, and two actions:

- **Upload now** skips any backoff.
- **Resend since…** picks a day, shows how many acknowledged events are still on the phone from that day on, and after confirmation queues them again. Use it after restoring the server from a backup; the server answers `duplicate` for what it already has.

Optional adb shortcuts for the permissions (the accessibility service is easier to enable in Settings):

```sh
adb shell appops set org.beconscient.collector GET_USAGE_STATS allow
adb shell cmd notification allow_listener org.beconscient.collector/org.beconscient.collector.notifications.NotificationListener
adb shell dumpsys deviceidle whitelist +org.beconscient.collector
```

## How it works

| Package | Role |
|---|---|
| `usage` | `UsageReader` reads from the stored watermark minus 5 minutes, in 6-hour slices. Each slice's events and the new watermark are written in one Room transaction; a unique natural key (`kind`, `at`, `package`, `class`) drops re-read events. `TimeChangeReceiver` and `BootReceiver` write the zone markers. |
| `notifications` | `NotificationListener` hands callbacks to `NotificationCollector`, one at a time and in order. The notification key maps to a random `ref` in the local `notification_refs` table. Updates reuse the ref with `is_update: true`, at most one per minute per notification. On reconnect it compares the active notifications with that table, records what it missed, and stores the blind stretch as a gap. |
| `shorts` | `ShortsRules` is the versioned rule set (`VERSION`, candidate ids, 1.5 s stability). `ShortsDetector` is the state machine. `ShortsAccessibilityService` looks up the candidate ids with `findAccessibilityNodeInfosByViewId` and never reads text. During a focus period it widens itself to window changes from every app, reads only the package of the window in front, and sends a blocked app to the home screen; afterwards it narrows back to YouTube. |
| `focus` | `FocusMachine` holds the focus rules without clocks or Android (free, locked, deciding, break). `FocusController` persists the phase, moves it forward whenever the home screen, the accessibility service or the notification listener asks, queues one `android.focus` event per finished period and answers what is blocked: launchable apps outside the allowed list. The notification listener snoozes notifications from blocked apps until the period ends. |
| `upload` | `Uploader` freezes up to 2000 events (and at most 600 KB of event JSON) under the next sequence number, storing the ordered id-and-revision list, then sends that same batch until a receipt opens with the request's exported key and names this installation and sequence. Accepted and duplicate events are marked acknowledged and deleted 14 days later; rejected ones move to the local `rejected` table and are never resent; ids the batch did not carry are ignored. A 413 re-freezes the same sequence number with half the events. Any other failure keeps the queue as it is and retries with exponential backoff (WorkManager, from 30 s). |
| `pairing` | `PairingClient` and `CredentialStore`. The secret is wrapped with an AES-GCM key held in the Android Keystore. |
| `health` | `HealthReporter` builds the 15-minute record. |
| `setup`, `ui` | Permission checks, the onboarding model, and the Compose screens. |

Collection runs every 15 minutes through WorkManager, at boot, after an app update, and whenever the app comes to the front. There is no unlock trigger: `ACTION_USER_PRESENT` cannot reach a manifest receiver and the app has no foreground service.

Security choices: `allowBackup="false"` with data extraction rules that exclude every domain from cloud backup and device transfer; every receiver is unexported; the two exported services require `BIND_NOTIFICATION_LISTENER_SERVICE` and `BIND_ACCESSIBILITY_SERVICE`; nothing is direct-boot aware (Room's multi-instance service and WorkManager's diagnostics receiver are removed from the merged manifest). `ManifestTest` checks all of this.

## Verify on the real phone

These cannot be settled by unit tests:

- **Shorts view ids.** The ids in `ShortsRules` are candidates. With YouTube open on a Short, on the home feed and on a regular video, run `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml` and look at the `resource-id` values. Update the lists and bump `ShortsRules.VERSION` if they differ. Also check that leaving YouTube closes an open `enter` within a few seconds.
- **Usage retention and volume.** `adb shell dumpsys usagestats` shows how far back the events go and how many there are per day. The plan assumes about 7 to 10 days of retention.
- **Activity instance ids.** `UsageEvents.Event#getInstanceId` is a system API, so the app reads it by reflection on Android 12 and later and leaves `instance` out when the platform blocks it. Check whether real events carry it.
- **OEM battery behaviour.** Some vendors stop background work despite the exemption (see dontkillmyapp.com). Watch the health records for gaps longer than 45 minutes while the phone is on, including overnight Doze.
- **Restricted settings.** Confirm that an `adb install` build can enable notification access and the accessibility service without the extra step.
- **Release crypto after R8.** Pair a release build once to confirm the shrunk BouncyCastle HPKE still works.
