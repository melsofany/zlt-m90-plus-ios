#!/usr/bin/env python3
"""Checks that the download server can actually deliver the APK to a phone.

Run against a live server:  python3 selftest.py [base-url]

A phone's download manager resumes a large file with a `Range` request and sizes it with `HEAD`.
The stdlib handler the server used to be built on implements neither, so it answered 200 with the
whole body and the manager treated the mismatch as corruption or restarted from zero. Those two
behaviours are what this checks, because a plain `GET` succeeding proves nothing about either.
"""
import hashlib
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:12000"
APK = "ZLT-M90-Plus.apk"
failures = []


def check(label, ok, detail=""):
    print(f"{'ok  ' if ok else 'FAIL'} {label}{(' — ' + detail) if detail else ''}")
    if not ok:
        failures.append(label)


def request(path, headers=None):
    req = urllib.request.Request(f"{BASE}/{path}", headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def main():
    status, headers, body = request(APK)
    whole = body
    size = len(whole)
    digest = hashlib.sha256(whole).hexdigest()
    check("full download is 200", status == 200, f"got {status}")
    check("download is not empty", size > 0, f"{size} bytes")
    check(
        "server advertises byte ranges",
        headers.get("Accept-Ranges") == "bytes",
        headers.get("Accept-Ranges", "missing"),
    )
    check(
        "file is offered as a download",
        "attachment" in headers.get("Content-Disposition", ""),
        headers.get("Content-Disposition", "missing"),
    )

    # A resume must return exactly the requested slice, not the whole file.
    status, headers, part = request(APK, {"Range": "bytes=1000-1099"})
    check("range request is 206", status == 206, f"got {status}")
    check("range returns only the slice", len(part) == 100, f"{len(part)} bytes")
    check(
        "range content matches the file",
        part == whole[1000:1100],
        "bytes differ" if part != whole[1000:1100] else "",
    )
    check(
        "Content-Range describes the slice",
        headers.get("Content-Range") == f"bytes 1000-1099/{size}",
        headers.get("Content-Range", "missing"),
    )

    # Splitting and rejoining must reproduce the file, which is what a resume does.
    half = size // 2
    _, _, first = request(APK, {"Range": f"bytes=0-{half - 1}"})
    _, _, second = request(APK, {"Range": f"bytes={half}-"})
    rejoined = first + second
    check(
        "a resumed download reassembles byte-for-byte",
        len(rejoined) == size and hashlib.sha256(rejoined).hexdigest() == digest,
        f"{len(first)} + {len(second)} bytes",
    )

    # An unsatisfiable range must be refused rather than silently answered with the whole file.
    status, _, _ = request(APK, {"Range": f"bytes={size + 1000}-"})
    check("an impossible range is 416", status == 416, f"got {status}")

    # Some managers size the file with HEAD first.
    req = urllib.request.Request(f"{BASE}/{APK}", method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            head_status, head_len = resp.status, resp.headers.get("Content-Length")
    except urllib.error.HTTPError as e:
        head_status, head_len = e.code, None
    check("HEAD is 200", head_status == 200, f"got {head_status}")
    check("HEAD reports the size", str(head_len) == str(size), f"{head_len} vs {size}")

    print()
    if failures:
        print(f"{len(failures)} check(s) failed: {', '.join(failures)}")
        sys.exit(1)
    print(f"all checks passed — {APK} is {size} bytes, sha256 {digest}")


if __name__ == "__main__":
    main()