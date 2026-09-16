#!/usr/bin/env python3
"""Serves the built APKs for download over the workspace's exposed port."""
import html
import http.server
import json
import os
import socketserver
import time
import urllib.parse
import uuid

ROOT = os.path.dirname(os.path.abspath(__file__))
REPORTS = os.path.join(ROOT, "reports")
PORT = int(os.environ.get("PORT", "12000"))
MAX_REPORT_BYTES = 4 * 1024 * 1024

INDEX = """<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ZLT M90 Plus — تنزيل التطبيق</title>
<style>
 body{{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#0f1115;color:#e8eaed;
      margin:0;padding:28px 18px;line-height:1.7}}
 .wrap{{max-width:680px;margin:0 auto}}
 h1{{font-size:1.5rem;margin:0 0 4px}}
 p.sub{{color:#9aa0a6;margin:0 0 26px}}
 .card{{background:#1a1d23;border:1px solid #2a2f38;border-radius:14px;padding:18px;margin-bottom:16px}}
 .card h2{{font-size:1.05rem;margin:0 0 6px}}
 .card .meta{{color:#9aa0a6;font-size:.85rem;margin-bottom:14px}}
 a.btn{{display:inline-block;background:#2f6fed;color:#fff;text-decoration:none;
        padding:11px 22px;border-radius:9px;font-weight:600}}
 a.btn:hover{{background:#3d7bf7}}
 a.alt{{background:#333941}}
 a.alt:hover{{background:#3f4652}}
 .note{{background:#2a2214;border:1px solid #5c4a1e;border-radius:11px;padding:13px 16px;
        font-size:.9rem;color:#f0d9a8;margin-bottom:18px}}
 code{{background:#22262e;padding:2px 6px;border-radius:5px;font-size:.82rem;
       word-break:break-all;display:inline-block}}
 ul{{padding-inline-start:20px;margin:8px 0}}
 li{{margin-bottom:5px;font-size:.92rem}}
</style></head><body><div class="wrap">
<h1>ZLT M90 Plus</h1>
<p class="sub">إصدار مُصلَّح — يحل مشكلة عدم الاتصال بالجهاز</p>
<div class="note"><b>ما الذي أُصلح:</b> كان التطبيق يرسل كلمة المرور كنص صريح، وهذا الجهاز
يتطلب إرسالها مُرمَّزة بـ Base64. لذلك كان يرفض كلمة المرور الصحيحة ويظهر
«بيانات الدخول غير صحيحة».</div>
{cards}
<div class="card"><h2>طريقة التثبيت</h2>
<ul>
<li>نزّل الملف على الهاتف (اضغط الزر مباشرة من الهاتف).</li>
<li>عند طلب الإذن، اسمح بـ «تثبيت تطبيقات من مصادر غير معروفة».</li>
<li>ثبّت التطبيق، ثم اتصل بشبكة Wi-Fi الخاصة بالجهاز.</li>
<li>افتح التطبيق واضغط «اكتشاف الجهاز تلقائيًا»، ثم سجّل الدخول.</li>
</ul></div>
<div class="card"><h2>بصمة الملف (SHA-256)</h2>
<p class="meta">للتأكد من سلامة الملف بعد التنزيل</p>
{hashes}</div>
</div></body></html>
"""


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def do_GET(self):
        if urllib.parse.urlparse(self.path).path in ("/", "/index.html"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(self.render_index().encode("utf-8"))
            return
        super().do_GET()

    def do_POST(self):
        """Receives one diagnostic report from the instrumented APK."""
        if urllib.parse.urlparse(self.path).path != "/report":
            self.send_error(404, "not found")
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_REPORT_BYTES:
            self.send_error(413, "bad length")
            return

        payload = self.rfile.read(length)
        os.makedirs(REPORTS, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
        path = os.path.join(REPORTS, f"report-{stamp}-{uuid.uuid4().hex[:6]}.json")
        with open(path, "wb") as handle:
            handle.write(payload)
        summary = self.summarize(payload)
        print(f"[report] {os.path.basename(path)} {len(payload)}B {summary}", flush=True)

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    @staticmethod
    def summarize(payload):
        """Prints the interesting parts to the log so a failure is visible without opening files."""
        try:
            data = json.loads(payload.decode("utf-8"))
        except Exception as error:  # noqa: BLE001 - a diagnostic server must not die on bad input
            return f"unparseable: {error}"
        exchanges = data.get("exchanges") or []
        parts = []
        for entry in exchanges:
            if entry.get("kind") == "session":
                network = entry.get("network") or {}
                parts.append(
                    "env wifi={} model={} gw={} local={}".format(
                        network.get("onWifi"), entry.get("deviceModel"),
                        network.get("gateway"), network.get("localIpv4"),
                    )
                )
                continue
            status = entry.get("status")
            error = entry.get("error")
            outcome = f"HTTP {status}" if status is not None else (error or "?")
            body = (entry.get("responseBody") or "")[:180].replace("\n", " ")
            parts.append(f"{entry.get('method')} {entry.get('url')} -> {outcome} | {body}")
        return " || ".join(parts)

    def render_index(self):
        entries = sorted(
            f for f in os.listdir(ROOT)
            if f.lower().endswith(".apk") and os.path.isfile(os.path.join(ROOT, f))
        )
        cards, hashes = [], []
        for name in entries:
            size = os.path.getsize(os.path.join(ROOT, name))
            primary = "release" in name
            label = "النسخة الموصى بها (release)" if primary else "نسخة التشخيص (debug)"
            cards.append(
                '<div class="card"><h2>{label}</h2>'
                '<p class="meta">{name} — {mb:.1f} ميجابايت</p>'
                '<a class="btn{cls}" href="{href}" download>{label}</a></div>'.format(
                    label=html.escape(label),
                    name=html.escape(name),
                    mb=size / 1048576,
                    cls="" if primary else " alt",
                    href=urllib.parse.quote(name),
                )
            )
            digest = sha256(os.path.join(ROOT, name))
            hashes.append("<p><code>{}…</code><br><span class=\"meta\">{}</span></p>".format(
                digest[:32], html.escape(name)))
        return INDEX.format(cards="".join(cards), hashes="".join(hashes))

    def log_message(self, fmt, *args):
        pass


def sha256(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with Server(("0.0.0.0", PORT), Handler) as httpd:
        print(f"serving {ROOT} on 0.0.0.0:{PORT}", flush=True)
        httpd.serve_forever()
