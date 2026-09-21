# Every problem this app hit, and what fixed it

Written for whoever picks this up next — agent or human. Each entry states the symptom as it was
actually observed, the cause, the fix, and how to tell the fix is real. The order is roughly the
order they were found; they are not independent, and several later entries are corrections of
earlier fixes.

Two failure modes recur throughout and explain most of the confusion:

1. **A test passed for the wrong reason.** Several fakes were not faithful to the device, so a
   green suite hid a real fault. Where that happened it is called out, because the lesson is that
   the fake is part of the fix.
2. **A failure was reported as a cause.** The app repeatedly turned "I could not tell" into a
   definite verdict — "wrong password", "nothing responded" — and that is the same offence as
   showing a fabricated value, with a user acting on it.

---

## The big one: the app could not connect to the live router

**Symptom.** With the device reachable on the LAN, login failed. Earlier iterations reported a wrong
password, then "unexpected status line", then 404 on every path.

**Cause.** Firmware 1.12.8 does not serve the goform surface the client implemented. It serves a
JSON dispatcher at `POST /cgi-bin/http.cgi`, taking `{"cmd":<number>,…}` bodies. The goform client
was posting to an interface that does not speak goform, and that endpoint answered
`{"success":false,"cmd":-1,"message":"ROOT IS NULL."}` — it never examined the password.

**Fix.** `HttpCgiClient` implements the real protocol. The command numbers, field names and
handshake are in [`http-cgi-protocol.md`](http-cgi-protocol.md), derived from 152 captured requests.

Three things this protocol does that goform does not, each of which the old client got wrong by
assuming:

- **No cookies at all.** The session is a `sessionId` field in the JSON body. There is not a single
  `Set-Cookie` in the capture.
- **The session id is the empty string before login**, 64 characters after.
- **The password is never sent.** `passwd` carries a 64-character digest.

**How to know it works.** `HttpCgiClientTest` drives the real `ZltRouterApi` against
`FakeHttpCgiServer`, which models 1.12.8. Reintroducing the bug — sending the password raw — fails 7
of the 10 tests.

---

## Reporting a wrong password for a request that never tested the password

**Symptom.** The app said the password was wrong. The device's own reply said `ROOT IS NULL.`, which
has nothing to do with credentials.

**Cause.** Three faults of one shape — a failure to determine something became a definite verdict:

- The endpoint the device named was read from its bundle, but only the *first* path in it was used.
  A minified bundle lists endpoints in arbitrary order, and `http.cgi` came first, so a goform login
  went to the JSON dispatcher.
- The session check `loginfo` returned 404, and "the check could not run" was collapsed into "not
  logged in", which the caller read as bad credentials.
- The reply carried no `result` key, so it matched no case and fell through silently.

**Fix.** Endpoints are selected by *family* (`goform.endpointPathMarkers`), never by position. The
session check is tri-state: only an explicit `not_login` rejects, and CONFIRMED/UNKNOWN never
subtract from a login the device already accepted. An unrecognised reply is reported as
`InterfaceNotSupported`, naming the endpoint and saying the credentials were never evaluated.

**Rule this established.** `loginfo` is corroboration, not the verdict.

---

## Inferring the password encoding from the login page

**Symptom.** A correct password was reported wrong. This is the failure the user originally hit.

**Cause.** The SPA's shell and bundle both contain the word "base64" for unrelated reasons (a
polyfill, a helper). A build treated that as evidence about the login form's encoding and flipped
this firmware's base64 login to clear text. The device answers a mis-encoded password with its
wrong-password code, so a correct password looked rejected.

**Fix.** Only *endpoint paths* are read from the device's files, because a path is unique and
verifiable. Field names, encodings and command IDs come from `router_routes.json`.

**The trap in the test.** `FakeGoformServer` fell back to the raw password when decoding failed,
which accepted clear text and let a green suite hide the bug. The fake is strict now.

---

## HTTPS scheme inference breaking the login

**Symptom.** The device answered, and the app still could not connect.

**Cause.** Discovery probes `GET /`, and this firmware redirects that one path to HTTPS while
serving its whole API over plain HTTP. Treating that as a determination moved every request to
https, where each API path returned a GoAhead 404 page.

**Fix.** The inferred scheme is a *preference*: login tries it, then the other, and
`resolvedScheme` remembers whichever answered.

---

## Redirects being followed, turning a login into an anonymous page fetch

**Symptom.** Login failed in a way that looked like a credentials problem.

**Cause.** OkHttp rewrites a redirected POST into a GET and drops the body. Chasing a redirect
turned a login into an anonymous fetch of the login page.

**Fix.** `followRedirects(false)`. This also preserves the `Location` header, which is worth
recording in the connection log.

---

## A malformed status line hiding a redirect

**Symptom.** All four attempts (https and http, goform and luci) reported
`Unexpected status line: <path> HTTP/1.1 301 Moved Permanently`.

**Cause.** OkHttp validates the status line before it builds a `Response`, so a malformed one
discards the entire reply — `Location` included. No parsed object can recover it, which is why
"unreadable reply" was the best the app could say.

**Fix.** The reply is read a second time over a plain socket to pull the target out, and login
follows it once. The follow-up carries no credentials, and the target is checked against the same
private-host whitelist that guards the configured host, so a device cannot redirect the login off
the LAN and collect the password. Only one redirect is followed.

**The trap in the test.** The fake wrote the whole request line (`POST /path HTTP/1.1`) where the
device writes only the path, so a test passed for the wrong reason. It emits the field bytes now.

---

## Treating every way a router answers as absence

**Symptom.** The field log showed `http://192.168.8.1/` returning a TLS failure in 624 ms while nine
other addresses timed out — and the app reported that nothing responded.

**Cause.** The probe accepted only a successful status, so a redirect, an auth prompt, a 404 and a
TLS handshake failure were all discarded.

**Fix.** `RouterProbe` treats **any** HTTP answer as proof of presence. Only a timeout or a refused
connection rules an address out. A redirect or TLS failure also reports `https` so login continues
on the scheme the device demanded.

---

## "Try again" for an error that retrying cannot change

**Symptom.** The device's GoAhead server answered a reused connection with a status line repeating
the request line, and the app advised a retry.

**Fix.** Requests send `Connection: close` to avoid the desync, and that error maps to
`DeviceResponseUnreadable` rather than `TemporaryFailure`. Offering a retry was misleading advice.

---

## Reporting the last error instead of the most informative one

**Symptom.** A malformed reply on http was hidden by the connect timeout that https produced against
the same plain-HTTP port.

**Fix.** Login reports the most informative failure via `Throwable.rank()`. Reporting the timeout
would hide the fact that the device answered at all.

---

## A phone on the wrong subnet looking like a dead device

**Symptom.** A bare timeout, indistinguishable from the device being off.

**Fix.** When the device did not answer *and* its address shares no /24 with the phone,
`MainViewModel` reports `WrongNetwork`, naming both addresses.

---

## Reading the endpoint from the page — which could never work

**Symptom.** The previous fix was silently useless on this firmware: it fetched the page, found
nothing, and kept the configured path that answers 404.

**Cause.** The page is a Vue shell — a `div` and two script tags, with the title set from
`localStorage`. There is no form and no endpoint in that HTML. The endpoint exists only inside the
JavaScript bundle.

**Fix.** The shell is followed to its code. Each script is fetched and asked for the paths it quotes,
taken verbatim and never constructed. The device's own bundle is read before the framework bundle.

Two related bugs fixed at the same time:

- Relative script srcs (`js/app.js`) were concatenated onto the host, producing
  `http://192.168.8.1:443js/app.js`. They are resolved against the page now.
- Off-host script srcs are skipped: a bundle on a CDN is not the device's code.

**The trap in the test.** The fake wrote `name="user"` in HTML, which the field regex did not match —
another test passing for the wrong reason. Tests now assert the bundle was actually fetched, not
merely that login worked.

---

## `isPathRejected` looking for "404" in the user-facing message

**Symptom.** Discovery never ran.

**Cause.** The check looked for `"404"` in the message shown to the user, which is Arabic. The code
lives in `technicalDetail`, so it never matched.

**Found by** asserting the page was actually fetched rather than that login worked.

---

## A port taken from a redirect without its scheme

**Symptom.** `http://192.168.8.1:443` — visible in the field log as attempt 3.

**Fix.** The port implies its scheme, and a default port is dropped.

---

## Showing RSSI under the label RSRP

**Symptom.** The network screen showed `-48 dBm` for a signal whose RSRP is `-80`.

**Cause.** `cmd 1002` returns `RSRP`, `RSRQ`, `RSSI` and `SINR` side by side — four different
measurements. The `signalDbm` alias list named `rssi` *ahead of* `rsrp`, and keys are matched
case-insensitively, so `RSSI` was found first and rendered as `dBm`.

This is rule 1's exact prohibition: a real number the device did provide, presented under a name the
device never gave it, and differing by 32 dB — the difference between "good signal" and "marginal".

**Fix.** `rssi` was removed from `signalDbm`. Only RSRP-family keys remain.

**How to know it works.** `the signal figure is RSRP, never the RSSI the device also reports` fails
with `expected:<-80> but was:<-48>` against the old config.

**The general rule.** A value may only be shown under a label when the device used that label. Where
two metrics are merely correlated — RSRP/RSRQ/RSSI/SINR, or "battery present" vs "charging" — one
must never stand in for another.

---

## `battery_status` meaning the opposite of what was assumed

**Cause.** On this device `battery_status` means "a battery is present", not "charging".

**Fix.** It moved to the *end* of the `batteryCharging` aliases, so a genuine charging key wins.
`battery_capacity` is the percentage; `battery_charge_status`/`power_charger_status` mean charging.

---

## The download link that worked under `curl` and failed on a phone

**Symptom.** The user tapped the link on the phone and got a failed or corrupt download, while the
same URL downloaded fine with `curl`.

**Cause.** The server was the stdlib `SimpleHTTPRequestHandler`, which implements none of what a
phone's download manager does:

- A `Range` request — how a manager resumes an 11.8 MB file — got a `200` with the whole body
  instead of a `206` with the slice. The manager saw a length that did not match what it asked for,
  and either restarted from zero or called the file corrupt.
- `HEAD`, which some managers use to size the file first, was answered with a body that does not
  belong in a `HEAD` response, desynchronising the connection.

That a plain `GET` succeeds is exactly why this went unnoticed.

**Fix.** `download/serve.py` implements byte ranges (`Range` → 206, with a `BoundedFile` so the body
stops at the promised length), `HEAD`, `Content-Disposition`, and request logging.

**How to know it works.** `python3 download/selftest.py <base-url>` checks the things that actually
distinguish a working download from a broken one: 206 with a matching slice, a resumed download that
reassembles byte-for-byte, 416 for an impossible range, HEAD reporting the size. Against the old
server it fails on the first range check. Run it before handing the link to anyone.

---

## The link dying, and the log that could not say why

**Symptom.** The link stopped answering, repeatedly.

**Cause.** Two distinct things, and conflating them wastes time:

1. The server was started in the foreground, so it died with the shell that started it.
2. **The container gets recycled.** A restart replaces PID 1 and every process with it. This has
   happened at least twice and is not preventable from inside.

Separately, `log_message` was overridden to pass, so the log could never distinguish "the user never
hit the link" from "the link was hit and broke" — the question that started the investigation.

**Fix.** Requests are logged (method, path, status — never a secret). `serve-all.sh` starts one
server per exposed port plus one watcher per port, then verifies each actually answers rather than
assuming the start worked. A client that disconnects mid-download is normal and no longer prints a
traceback.

**What cannot be fixed.** `serve-keepalive.sh` cannot survive a recycle: there is no cron, no
systemd, and no supervisor in this image, and PID 1 is the agent server. The script's own header says
so, deliberately, so nobody mistakes a dead link for a broken APK.

**Recovery, in order.** The APK is never lost — it is on disk and gitignored — so recovery is always
"start the server again", never "rebuild":

```bash
cd download && ./serve-all.sh
python3 selftest.py https://<host>/
```

**Diagnosing which failure it is.** Compare PID 1's start time against the file's mtime:

```bash
ps -o pid,lstart,cmd -p 1          # was the container replaced?
ls -l download/ZLT-M90-Plus.apk    # the APK predates the recycle -> only the process died
```

If the APK's timestamp predates PID 1's start, the file survived and only the server needs
restarting.

---

## A durable link: the real fix for the recurring download failure

**Cause of the recurrence.** The sandbox URLs depend on processes inside a container that gets
recycled. No in-container mechanism can survive that.

**Fix.** Publish the APK as a GitHub Release asset. A release URL is served by GitHub and does not
depend on this container:

```
https://github.com/melsofany/zlt-m90-plus-ios/releases/latest
```

**Why it was not done earlier.** The `GITHUB_TOKEN` in the environment is a read-only GitHub App
token: it can read but `git push` and release creation both return `403`. A user-supplied classic
token with `repo` scope is required for either.

---

## Two APKs, and a diagnostic build nobody should install

**Symptom.** The user had to install the *wrong* build to reproduce a failure, then wait for a report
to be uploaded.

**Fix.** The separate diagnostic build type, its source set, the upload reporter and the collector
endpoint are gone. The transport records into `DiagnosticLog`, a bounded in-memory ring the
diagnostics screen renders, reachable from the connect screen (where failures happen) and from
settings. A report leaves the device only when the user shares it.

**Keep it that way.** The log is never written to disk and never uploaded, and
`DiagnosticRedaction` masks password/token fields before an exchange is built, so a shared report
never contains a credential.

---

## Recording a bundle whole

**Cause.** A bundle is megabytes of minified framework, so recording it would bury the one line
worth reading.

**Fix.** The log carries the paths found plus a bounded excerpt, and says plainly when a bundle
named none.

---

## Proposed LLM-driven page analysis — declined, with reason

**The proposal.** Have a model read the router's admin pages and infer the endpoint and command
numbers.

**Why not.** A model can only *guess* an endpoint or a command number, and a guessed endpoint
receives real credentials. That is the fabrication this app forbids, with a credential attached. It
would also mean shipping a cloud API key in a distributed APK and uploading the router's admin pages
off the LAN.

**What was kept from it.** The useful half already exists and is honest: discovery reads the device's
own bytes, and the diagnostic log is specific enough to name the interface that needs implementing.

---

## Still unknown: the `passwd` digest recipe

This is the one genuine open question, and it is deliberately not guessed.

**What is known.** `passwd` is 64 characters, consistent with SHA-256. It is *not* the password.

**What is unknown.** Which inputs feed it. The visible candidates are the 32-character `token` from
`cmd 232` and `domain_value`.

**Why it is not guessed.** A wrong recipe produces a value the device rejects, and an app that then
said "wrong password" would repeat the original bug.

**How to settle it** — locally, in the browser, sending the password nowhere. On the router's own
login page, open DevTools → Console:

```js
crypto.subtle.digest('SHA-256', new TextEncoder().encode('PUT_PASSWORD_HERE'))
  .then(b => console.log([...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('')))
```

Then log in with DevTools → Network open and compare the `passwd` the request sent against that
output.

- Match → the recipe is plain `sha256(password)`; login can be confirmed as implemented.
- No match → the digest includes something else, and `js/app.js` from the device is needed.

It lives in config as `httpCgi.passwordDigest` precisely so confirming it is a data change, not a
code change. A refused login raises `LoginRejected`, which names *both* possible causes.

---

## Checklist for a future agent

Before claiming a connection fix works:

1. `cd android && ./gradlew testDebugUnitTest lintDebug` — 168 tests, lint 0 errors.
2. Reintroduce the bug and confirm the new test goes red. A test that has never failed has not been
   shown to test anything.
3. Confirm the fake is faithful to the device. Most of the bugs above hid behind a fake that was
   easier than the real thing.
4. Check no `Log`/`println` reached main sources — no password, session token or data-plan figure
   may appear in Logcat.
5. Verify no value is displayed under a label the device did not use.
6. `python3 download/selftest.py <base-url>` before handing over a download link.
