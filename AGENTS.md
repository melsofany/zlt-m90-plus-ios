# AGENTS.md

## Repository layout

- `/` — original SwiftUI scaffold for iOS (reference only; cannot be built on Linux).
- `/android` — the deliverable Android app (Kotlin + Jetpack Compose). All active work happens here.
- `/download` — the APK plus the small HTTP server the user downloads it from. `serve.py` is built on
  `SimpleHTTPRequestHandler` but adds what a phone's download manager needs: byte ranges (`Range` →
  206, with a `BoundedFile` so the body stops at the promised length), `HEAD`, `Content-Disposition`,
  and request logging. The stdlib handler has none of these, and their absence is invisible to a
  plain `curl` — verify with `python3 download/selftest.py <base-url>` before trusting a link.

## Serving the APK

```bash
cp android/app/build/outputs/apk/release/app-release.apk download/ZLT-M90-Plus.apk
cd download && ./serve-all.sh          # one server + one keeper per exposed port
python3 selftest.py https://<host>/     # must pass before handing the link over
```

`serve.py` must not log a traceback when a client disconnects mid-download; that is normal. It also
must not print secrets — it logs only method, path, and status.

## Build & test (Android)

JDK 21 and Android SDK 34 are required. Gradle wrapper is checked in.

```bash
cd android
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (falls back to the debug key)
./gradlew testDebugUnitTest  # 153 unit + Robolectric tests
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
6. **Never infer a protocol detail from page text.** The login page and its JavaScript bundle both
   mention `base64` for unrelated reasons (a polyfill, a helper). Reading the password encoding out of
   that flipped this firmware's base64 login to clear text, and the device answers a mis-encoded
   password with its wrong-password code — so a correct password was reported as wrong. Endpoint
   *paths* may be read from the device's own files because they are unique and verifiable; field
   names, encodings, and command IDs come from `router_routes.json`, never from scraping a minified
   bundle.
7. A test double must be as strict as the device. `FakeGoformServer` rejects a clear-text password
   because the firmware does; a lenient fake that falls back to the raw value turns a real encoding
   bug into a green test. When adding a regression test, reintroduce the bug and confirm the test
   fails before trusting it.
8. **A failure may only accuse something that was actually tested.** `اسم المستخدم أو كلمة المرور غير
   صحيحة` may only be shown when the device evaluated the credentials and rejected them. Three field
   logs have now been misdiagnosed because an unrelated later failure was collapsed into a verdict
   about the password: a wrong path, an unserialisable redirect, and a 404 on the `loginfo` session
   check. A check that cannot be performed answers "unknown", never "rejected" — see `SessionCheck`
   in `ZltRouterApi`. This is rule 1 applied to errors rather than to values.
9. **An endpoint learned from the device belongs to one interface family, and is only spoken to by
   that family's code.** The 1.12.8 bundle names `/cgi-bin/http.cgi`, a JSON-RPC dispatcher that
   answers `{"success":…,"cmd":…,"message":…}` and has no `goformId` concept. A goform login posted
   there is a request in a protocol the device does not speak; it replies `ROOT IS NULL.` without
   ever seeing the password. `goform.endpointPathMarkers` in `router_routes.json` gates this.
10. **"The first path in the bundle" is a guess.** A minified bundle names several endpoints in
   arbitrary order. Select by family (`isGoformEndpoint`), never by position.

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
- `loginfo` is the honest "am I logged in" flag — but it is *corroboration*, not the verdict. It
  must be `ok` to confirm a login, and only its explicit `not_login` may reject one. A 404 or an
  unreadable body on that endpoint says nothing about the password, and treating it as a rejection
  is what produced the third misdiagnosis.
- A second dispatcher exists on this hardware at `/cgi-bin/http.cgi`, speaking
  `{"cmd":<number>,"method":"POST","language":…,"sessionId":…}` JSON instead of goform. It is
  reachable and answers, which is exactly why it must be recognised: replying is not consenting.
  This build cannot drive it; `RouterError.InterfaceNotSupported` names it and says the credentials
  were never evaluated. Supporting it means implementing that protocol from the device's own bytes,
  not translating a goform request into it.
- Uptime `realtime_time` is in **seconds**; `readUptimeMinutes` does the conversion.
- Prefer `ppp_status` over `wan_connect_status`: the latter can be present but empty, and an empty
  key must not mask a populated alias.
- **The scheme discovery infers is a preference, not a determination.** Discovery only ever sees
  `GET /`, and this firmware redirects that one path to HTTPS while serving its entire API over
  plain HTTP. Acting on that inference pointed the login at https, where every API path answers
  `404 Not Found` with a GoAhead error page. `login` therefore tries its preferred scheme and then
  the other, and `resolvedScheme` remembers whichever one answered, so later reads use it too.
- **Redirects are not followed on the API client.** OkHttp rewrites a redirected POST into a GET
  and drops the body, so chasing a redirect would turn a goform login into an anonymous fetch of
  the login page and report the wrong password. `followRedirects(false)` keeps the `Location`
  header, which is worth recording in the connection log.
- The device's GoAhead server has been seen answering a reused connection with a status line that
  repeats the request line: `ProtocolException: Unexpected status line:
  /goform/goform_set_cmd_process HTTP/1.1 301 Moved Permanently`. Requests send
  `Connection: close` to avoid the desync, and that error maps to `DeviceResponseUnreadable`
  rather than `TemporaryFailure` — retrying cannot change the answer, so offering a retry is
  misleading.
- Login reports the **most informative** failure, not the last one. A malformed reply on http
  outranks the connect timeout that https produces against the same plain-HTTP port; reporting the
  timeout would hide the fact that the device answered at all. See `Throwable.rank()`.
- A phone on a different subnet than the device has no route to it, and the timeout that produces
  is indistinguishable from a dead device. When the device did not answer *and* its address shares
  no /24 with the phone, `MainViewModel` reports `WrongNetwork`, naming both addresses.
- The connection log records a redirect's `Location` (`redirectLocation`) and the URL a probe
  actually attempted (`Attempt.attemptedUrl`). Without the latter the log paired
  `DISCOVERY_PROBE http://192.168.8.1/` with a port-443 failure, which reads as a contradiction.

## Never hard-code a firmware route

Firmware 1.12.8 serves a Vue single-page shell: `<div id="app">` plus
`js/chunk-vendors.js` and `js/app.js`. There is no form and no endpoint in the
HTML — the endpoint exists only inside the bundle. The configured goform path
answers 404 on it.

So the app discovers the endpoint at login time: it fetches the page, and if the
page names no endpoint it follows the shell to the device's own bundle (never the
framework one) and takes the paths that bundle quotes verbatim. It never
constructs a path. Discovery only runs when the configured path is rejected or
the device redirects, and a page it does not understand leaves the configured
path in place.

When adding a route, ask first whether the device publishes it. `router_routes.json`
is a fallback, not the source of truth.

## The device's real interface

Firmware 1.12.8 serves a JSON dispatcher at `POST /cgi-bin/http.cgi` — not goform. The full
protocol, derived from captured browser traffic, is in `docs/http-cgi-protocol.md`. Read it before
touching `HttpCgiClient`. The parts most easily got wrong:

- **No cookies.** The session is a `sessionId` field in the JSON body. It is the empty string before
  login, and the 64-char value `cmd 100` returns afterwards.
- **A token nonce.** `cmd 232` issues a 32-char token *before* login; `cmd 100` must echo it.
- **The password is never sent.** `passwd` carries a 64-char digest. Which inputs feed it is still
  unconfirmed, so it lives in config as `passwordDigest` (default `sha256`). A refused login raises
  `LoginRejected`, which names *both* possible causes. Do not collapse it into "wrong password".
- **Battery is inside `cmd 1005`**, not a command of its own.
- `battery_status` means "a battery is present", not "charging" — hence its position at the end of
  the `batteryCharging` aliases. RSRQ/RSSI/SINR are not aliases for RSRP; do not offer one
  measurement under another's label.

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
- `RouterProbeTest` covers discovery against real sockets, including a TLS server that serves a
  self-signed certificate. The PEM fixtures in `app/src/test/resources/tls/` are test data, not
  secrets: the certificate is self-signed and the key protects nothing. Regenerate them together
  if they are ever replaced, or the handshake test will fail on a key/cert mismatch.

## Router transport notes

- The device's admin interface is plain HTTP, but some builds redirect to HTTPS using a
  self-signed certificate. `RouterProbe` treats **any** HTTP answer — including `302`, `401`,
  `404` and a TLS handshake failure — as proof the device is present. Only a timeout or a refused
  connection rules an address out. A redirect or a TLS failure also reports `https` so the login
  continues on the scheme the device demanded.
- `ZltRouterApi.defaultClient` accepts the device's self-signed certificate. That exemption is
  confined to private-address requests: `probeInternet()` builds its own client through
  `ZltRouterApi.platformTrustClient()`, which keeps the platform trust anchors. Do not merge the
  two clients — `RouterProbeTest` fails if the internet path can inherit the exemption.
- Discovery tries the phone's real gateway first (`LocalNetworkChecker.discoveryCandidates`), so
  the common case is one probe rather than eight. The control UI may place the device on any
  private subnet, which is why the candidate list is a fallback rather than a fixed default.
