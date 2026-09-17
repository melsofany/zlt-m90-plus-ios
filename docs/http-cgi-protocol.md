# The `http.cgi` protocol on ZLT M90 PLUS 1.12.8

Derived from a HAR the user captured in a desktop browser (152 entries). Every request in that
session was `POST /cgi-bin/http.cgi` with `Content-Type: application/json`. **There were no cookies
and no `Set-Cookie` headers at all** — the session lives entirely in the JSON body.

These are observed facts, not inferences. Where something is a hypothesis it is labelled as one.

## The session handshake

| Step | cmd | method | body | response |
|---|---|---|---|---|
| 1 | `1008` | GET | `{"cmd":1008,"method":"GET","sessionId":S}` | device info incl. `domain_value`, `fake_version`, `board_type` |
| 2 | `232` | GET | | `{"success":true,"cmd":232,"buffer":"3","token":T,"netx_login_time":"16079"}` |
| 3 | `100` | POST | `{"cmd":100,"username":U,"passwd":H,"token":T,"isAutoUpgrade":"0","sessionId":S,"method":"POST"}` | `{"success":true,"cmd":100,"user_level":"3","AUTH":...,"sessionId":...}` |
| 4 | `1003` | GET | | post-login config |
| 5 | `1005` | GET | | the status blob (see below) |

So the flow is: get a server `token` from `cmd 232` (before login — it is not a credential), then
send `cmd 100` with `username`, a derived `passwd`, and that `token`.

`method` is `"GET"` for read commands and `"POST"` for the login and for writes. It is a field in
the body, not the HTTP verb — the HTTP verb is `POST` for everything.

### Field lengths, recovered from `Content-Length`

The user redacted values by replacing them with `XXXXXXX`, but the browser's `Content-Length`
header still records the true byte length. Rebuilding each body with a different-width placeholder
and subtracting isolates the real length of every redacted field:

| entry | cmd | redacted fields | recovered |
|---|---|---|---|
| 1 | 1008 | `sessionId` | `sessionId` = **0** — empty before login |
| 6 | 232 | `sessionId` | `sessionId` = **0** — empty before login |
| 12, 90 | 12 | `sessionId` | `sessionId` = **64** after login |
| 28, 81 | 269, 350 | `sessionId`+`token` | sums to 96, so `token` = **32** |
| 7 | 100 | `passwd`+`token`+`sessionId` | sums to 160, so `passwd` = **64** |

So: `sessionId` is the **empty string** for every call made before login, and the 64-character value
returned by `cmd 100` afterwards. `token` is a 32-character server nonce from `cmd 232`.

`passwd` is a 64-character value — consistent with a SHA-256 hex digest. The app must therefore
derive it, not send the password raw. Which inputs go into the derivation is **not yet known**. The
candidates visible in the handshake are the password, the 32-char `token`, and `domain_value`
(`4a93e679058284a39a7d6da21038cf5b` from `cmd 1008`).

Confirming the hash recipe requires reading `js/app.js` from the device. That file has not been
captured yet. Do not guess the recipe and ship it — a wrong recipe produces a value the device
rejects, and the app would then have to be honest about it or it repeats the original bug.

## Commands observed

All bodies carry `cmd`, `method`, `sessionId`. Only the extras are listed.

| cmd | extras | what it returns |
|---|---|---|
| `100` | `username`,`passwd`,`token`,`isAutoUpgrade` | login: `success`, `user_level`, `AUTH`, `sessionId` |
| `12` | `page_num:-1`, `subcmd:0` | SMS list, base64-encoded blobs |
| `16` | — | SMS centre settings |
| `28` | — | `{"message":""}` |
| `104` | — | uptime (seconds, float), `need_prompt_reboot` |
| `213` | `subcmd:3` | APN MTU / IP version |
| `224` | — | connected clients: `wlan24g_wifi_info[]` with `mac`,`rssi`,`ssid`,`ip`,`user` |
| `230` | — | Wi-Fi radio settings |
| `232` | — | **session token**, `buffer`, `netx_login_time` |
| `248` | — | APN list |
| `256` | — | network mode |
| `263` | — | scheduled reboot |
| `269` | `setHash`, `token` | a write; response `{"message":"success"}` |
| `337` | — | data-plan / traffic: `mon_download_flow`, `ul_mon_flow`, `dl_mon_flow`, limits |
| `350` | `subcmd:"1"`, `token` | `{"message":"0"}` — a write |
| `463` | — | `wifiType` |
| `1001` | — | hardware: `board_type`, `hwversion`, `module_imei`, `ICCID`, `IMSI`, `device_sn`, `lan_ip`, memory, temperature |
| `1002` | — | radio/signal: `network_type_str` (`4G+`), `RSRP`, `RSRQ`, `RSSI`, `SINR`, `signal_lvl`, `PLMN`, `network_operator`, `currentband`, `CELL_ID`, `ENODEBID`, `flow_dl`, `flow_ul` |
| `1003` | — | post-login config |
| `1005` | — | **the status blob**, including battery (below) |
| `1008` | — | device/branding info, `domain_value`, `fake_version` |
| `1014` | — | capacity + temperature history graph |
| `1016` | — | temperature history graph |
| `1017` | — | Wi-Fi capability options (labels + values) |

## Battery lives in `cmd 1005`

Not a separate command. Fields observed, with real values:

```
battery_status: "1"
battery_capacity: "37"          # percent; observed climbing 37->40 while charging
battery_charge_status: "1"      # 1 = charging
power_charger_status: "1"       # 1 = charger connected
battery_abnormal_charging_detect: ""
is_battery_temperature_abnormal: ""
forbid_upgrade_without_battery: ""
```

`cmd 1001` also carries `device_temperature` (`"53"`) — that is the device temperature, a different
thing from battery temperature.

## Which of the app's six screens have real data

- **Battery** — `battery_capacity`, `battery_charge_status`, `power_charger_status` from `cmd 1005`. Real.
- **Network** — `cmd 1002` (signal, network type, band) and `cmd 1001` (device/IMEI/LAN). Real.
- **Data plan** — `cmd 337` (`mon_download_flow`, `ul_mon_flow`, `dl_mon_flow`, `limitSwitch`,
  `limitSize`). Real. Note the units are not yet confirmed; do not assume bytes.
- **Connected devices** — `cmd 224`, with per-client `mac`, `ip`, `ssid`, `user`, `rssi`. Real.
- **Device info** — `cmd 1001` + `cmd 1008`. Real.
- **Router connection** — the handshake above. Real, and the blocker is the `passwd` hash recipe.

## How this relates to the goform code already in the app

The app currently implements goform (`/goform/goform_get_cmd_process` etc.). This device does not
serve that interface — every request is `http.cgi`. The commit `1d4422f` made the app stop sending
goform requests to `http.cgi` and correctly report the interface as unsupported. That remains
correct behaviour until this interface is actually implemented.

## How to confirm the digest recipe (local, no network)

The recipe can be settled without reading minified JavaScript, and without sending the password
anywhere. In the browser, on the router's own login page, open DevTools -> Console and run this —
it computes SHA-256 **inside the page**, locally:

```js
crypto.subtle.digest('SHA-256', new TextEncoder().encode('PUT_PASSWORD_HERE'))
  .then(b => console.log([...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('')))
```

Then log in with DevTools -> Network open, click the `http.cgi` request, and compare the `passwd`
value it sent against the console's output.

- If they match, the recipe is plain `sha256(password)` and login can be implemented immediately.
- If they do not match, the digest includes something else (the 32-char `token` and
  `domain_value` are the visible candidates), and `js/app.js` from the device is needed to read the
  real recipe.

Do not skip this check by assuming the simpler recipe. A wrong recipe yields a value the device
rejects, and an app that then says "wrong password" would be repeating the exact bug that
`1d4422f` fixed.

## What must not happen

- Do not guess a `cmd` number. Every number above was observed in the user's own traffic.
- Do not guess the `passwd` hash recipe. Read `js/app.js` from the device.
- Do not treat `battery_capacity` as available unless `cmd 1005` actually answered — the project's
  first rule still applies.