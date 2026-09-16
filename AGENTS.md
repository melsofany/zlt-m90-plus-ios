# AGENTS.md

## Repository layout

- `/` — original SwiftUI scaffold for iOS (reference only; cannot be built on Linux).
- `/android` — the deliverable Android app (Kotlin + Jetpack Compose). All active work happens here.

## Build & test (Android)

JDK 21 and Android SDK 34 are required. Gradle wrapper is checked in.

```bash
cd android
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (falls back to the debug key)
./gradlew testDebugUnitTest  # 110 unit + Robolectric tests
./gradlew lintDebug          # must stay at 0 errors
```

APKs land in `android/app/build/outputs/apk/{debug,release}/`.
If `ANDROID_HOME` is unset, create `android/local.properties` with `sdk.dir=/path/to/android-sdk`.

Release signing is picked up automatically from `android/keystore.properties` (gitignored; see
`keystore.properties.example`). No properties file means the debug key is used.

## Connection log

There is one app, and the troubleshooting it needs is inside it. When a connection fails there is
nothing left to inspect — one Arabic sentence and the request that produced it is gone — so the
transport records every exchange into `DiagnosticLog`, a bounded in-memory ring the diagnostics
screen renders. It is reachable from the connect screen (where failures happen) and from settings,
and the user shares it deliberately through the system share sheet.

- `data/remote/ZltRouterApi.kt` records every exchange through `Diagnostics`, and
  `ui/MainViewModel` records discovery probes; both are no-ops without a sink.
- `DiagnosticRedaction` masks password/token fields before an exchange is built, so what the log
  holds — and therefore what a shared report contains — never includes a credential.
- The log is never written to disk and never uploaded. Keep it that way; a report leaves the device
  only because the user chose to send it.
- `DiagnosticLog` is bounded to 200 exchanges, which covers a discovery sweep plus a login and a
  full dashboard load.

## Architecture

Strict layering: `ui/screens` (Compose) → `ui/MainViewModel` (state) → `data/repository` → `data/remote` (RouterAPI) → device over LAN.

- `data/model/Models.kt` — domain models. Every model carries a `source: DataSource` field.
- `data/remote/` — `RouterApiProtocol` + `ZltRouterApi` + `MockRouterApi`. Routes/fields live in `app/src/main/assets/router_routes.json`, not in Kotlin. Supports JSON and XML.
- `data/session/SessionStore.kt` — session token encrypted with an AES-256 key in the Android Keystore. Never store the router password.
- `domain/` — pure logic (`BatteryEstimator`, `DataPlanCalculator`), highly unit-testable.

## Project rules (do not break these)

1. **Never display a fabricated value as real.** If firmware does not return it, show `غير متاحة من الجهاز` plus a reason. Values derived by the app are labelled `تقديري`; values from the router are labelled `حسب الجهاز`.
2. Demo mode is opt-in via an explicit tap and shows a persistent "sample data" banner. It is never persisted across launches.
3. No `Log`/`println` anywhere in main sources — no passwords, session tokens, or data-plan figures in Logcat.
4. Everything is Arabic and RTL (`android:supportsRtl="true"`); never rely on colour alone — pair it with an icon and text.
5. Impactful actions (Wi-Fi change, reboot) require a confirmation dialog and are disabled in demo mode.

## Router protocol notes

- The M90 Plus speaks GoAhead goform, not the LuCI REST API the scaffold assumed. Reads are
  `goform_get_cmd_process?cmd=a,b,c` with a multi-field JSON reply; writes are
  `goform_set_cmd_process` with a `goformId`.
- **The goform login password is Base64-encoded.** Posting it in clear text is answered with the
  same `{"result":"3"}` the firmware uses for a genuinely wrong password, so a correct password
  looks rejected. `goform.loginPasswordEncoding` in `router_routes.json` controls this and
  defaults to `base64`.
- A login body needs `isTest=false` and `goformId=LOGIN`, and the `Referer` header must be set or
  the firmware ignores the request.
- `loginfo` is the honest "am I logged in" flag. Confirm it after logging in.
- Uptime `realtime_time` is in **seconds**; `readUptimeMinutes` does the conversion.
- Prefer `ppp_status` over `wan_connect_status`: the latter can be present but empty, and an empty
  key must not mask a populated alias.

## Testing notes

- Robolectric Compose tests render a small viewport, so call `performScrollTo()` before `assertIsDisplayed()` on anything below the fold.
- Text like `البطارية` appears both as a card title and as a bottom-bar tab; select tabs with `onNode(hasText(...) and isSelectable())`.
- `org.json` is a real test dependency (not the Android stub) so parser tests run on the JVM.
- `VisualSnapshotTest` runs with `GraphicsMode.NATIVE` and captures pixels. Robolectric has no real
  window, so it uses `createAndroidComposeRule<ComponentActivity>()` and draws `android.R.id.content`
  into a bitmap by hand; `captureToImage()` would hang here. Variants (theme, direction, font scale)
  are driven by `mutableStateOf` because `setContent` may only be called once per test.
- There is no emulator in this environment (no `/dev/kvm`), so instrumented tests are not run; the
  Robolectric screenshots are the visual regression net.
- Diagnostic tests install real sockets (`FakeGoformServer`) so capture is asserted on the bytes
  that cross the wire rather than on a mock.
