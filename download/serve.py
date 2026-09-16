#!/usr/bin/env python3
"""Serves the built APKs for download over the workspace's exposed port."""
import html
import http.server
import os
import socketserver
import sys
import time
import urllib.parse

ROOT = os.path.dirname(os.path.abspath(__file__))
PORT = int(os.environ.get("PORT", "12000"))

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
<p class="sub">نسخة واحدة تشمل كل شيء — مع سجل تشخيص مدمج</p>
<div class="note"><b>نسخة واحدة تشمل كل شيء:</b> لا يوجد تطبيق منفصل للتشخيص. عند تعذّر الاتصال
بالجهاز، افتح «عرض سجل الاتصال» داخل التطبيق لترى ما أرسله التطبيق وما ردّ به الجهاز.</div>
{cards}
<div class="card"><h2>طريقة التثبيت</h2>
<ul>
<li>نزّل الملف على الهاتف (اضغط الزر مباشرة من الهاتف).</li>
<li>عند طلب الإذن، اسمح بـ «تثبيت تطبيقات من مصادر غير معروفة».</li>
<li>ثبّت التطبيق، ثم اتصل بشبكة Wi-Fi الخاصة بالجهاز.</li>
<li>افتح التطبيق واضغط «اكتشاف الجهاز تلقائيًا»، ثم سجّل الدخول.</li>
<li>إن فشل الاتصال، اضغط «عرض سجل الاتصال» لمعرفة السبب.</li>
</ul></div>
<div class="card"><h2>بصمة الملف (SHA-256)</h2>
<p class="meta">للتأكد من سلامة الملف بعد التنزيل</p>
{hashes}</div>
</div></body></html>
"""


class Handler(http.server.SimpleHTTPRequestHandler):
    # HTTP/1.1 keeps the connection alive across a resume; HTTP/1.0, the stdlib default, closes it
    # after every response.
    protocol_version = "HTTP/1.1"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def do_GET(self):
        if urllib.parse.urlparse(self.path).path in ("/", "/index.html"):
            body = self.render_index().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
            return
        try:
            super().do_GET()
        except (ConnectionResetError, BrokenPipeError):
            # A phone that gave up on a slow download, or checked the file size and navigated away,
            # closes the socket mid-transfer. That is the client's business, not a server fault, and
            # letting it print a traceback buries the requests that did work.
            self.close_connection = True

    def do_HEAD(self):
        # Some download managers size the file with HEAD before fetching it; the stdlib handler
        # answers it by sending headers and then a body, which desynchronises the connection.
        if urllib.parse.urlparse(self.path).path in ("/", "/index.html"):
            body = self.render_index().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return
        if self.path.lower().endswith(".apk"):
            path = self.translate_path(self.path)
            if os.path.isfile(path):
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(os.path.getsize(path)))
                self.send_header("Accept-Ranges", "bytes")
                self.send_header(
                    "Content-Disposition", 'attachment; filename="ZLT-M90-Plus.apk"'
                )
                self.end_headers()
                return
            self.send_error(404, "File not found")
            return
        super().do_HEAD()

    def send_head(self):
        # A phone's download manager resumes a large file with a `Range` request, which the stdlib
        # handler does not implement: it answers 200 with the whole body, so the manager either
        # restarts from zero or treats the mismatch as a corrupt download. `Accept-Ranges: none`
        # is not a fix either — it invites exactly that restart. So `Range` is honoured here.
        if not self.path.lower().endswith(".apk"):
            return super().send_head()

        path = self.translate_path(self.path)
        if not os.path.isfile(path):
            self.send_error(404, "File not found")
            return None

        size = os.path.getsize(path)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if rng:
            parsed = self.parse_range(rng, size)
            if parsed is None:
                # An unsatisfiable range must say so rather than quietly send the whole file.
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return None
            start, end = parsed

        length = end - start + 1
        fh = open(path, "rb")
        if start:
            fh.seek(start)
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        # Without this the browser may render the file or save it under the URL's name; naming it
        # here is what makes a phone save it as an installable .apk.
        self.send_header("Content-Disposition", 'attachment; filename="ZLT-M90-Plus.apk"')
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        return BoundedFile(fh, start, end)

    @staticmethod
    def parse_range(header, size):
        """The byte range in [header], or None when it cannot be satisfied."""
        if not header.startswith("bytes="):
            # Any other unit (e.g. a multipart range) is not something this server serves.
            return None
        spec = header[len("bytes="):].split(",")[0].strip()
        first, _, last = spec.partition("-")
        try:
            if not first:
                # A suffix range: the last N bytes.
                n = int(last)
                if n <= 0:
                    return None
                return max(0, size - n), size - 1
            start = int(first)
            end = int(last) if last else size - 1
        except ValueError:
            return None
        if start > end or start >= size:
            return None
        return start, min(end, size - 1)

    def log_message(self, fmt, *args):
        # A download that never arrives is indistinguishable from one never attempted unless
        # requests are recorded, and the log used to be silenced entirely.
        sys.stderr.write(
            f"{time.strftime('%Y-%m-%d %H:%M:%S')} {self.address_string()} "
            f"{fmt % args}\n"
        )

    def render_index(self):
        entries = sorted(
            f for f in os.listdir(ROOT)
            if f.lower().endswith(".apk") and os.path.isfile(os.path.join(ROOT, f))
        )
        cards, hashes = [], []
        for name in entries:
            size = os.path.getsize(os.path.join(ROOT, name))
            cards.append(
                '<div class="card"><h2>تطبيق ZLT M90 Plus</h2>'
                '<p class="meta">{name} — {mb:.1f} ميجابايت</p>'
                '<a class="btn" href="{href}" download>تنزيل التطبيق</a></div>'.format(
                    name=html.escape(name),
                    mb=size / 1048576,
                    href=urllib.parse.quote(name),
                )
            )
            digest = sha256(os.path.join(ROOT, name))
            hashes.append("<p><code>{}…</code><br><span class=\"meta\">{}</span></p>".format(
                digest[:32], html.escape(name)))
        return INDEX.format(cards="".join(cards), hashes="".join(hashes))


def sha256(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class BoundedFile:
    """A file object that ends after the last byte of the requested range.

    `copyfile` reads until the object reports EOF, so handing it the open file would stream the
    rest of the file and send far more bytes than the `Content-Length` promised — which the client
    sees as a hung or corrupt download.
    """

    def __init__(self, fh, start, end):
        self._fh = fh
        self._remaining = end - start + 1

    def read(self, size=-1):
        if self._remaining <= 0:
            return b""
        if size is None or size < 0:
            size = self._remaining
        chunk = self._fh.read(min(size, self._remaining))
        self._remaining -= len(chunk)
        return chunk

    def close(self):
        self._fh.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with Server(("0.0.0.0", PORT), Handler) as httpd:
        print(f"serving {ROOT} on 0.0.0.0:{PORT}", flush=True)
        httpd.serve_forever()
