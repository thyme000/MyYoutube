#!/usr/bin/env python3
"""Read-only gateway/admin/video/search probes; tokens come from environment variables."""
import argparse
import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class ProbeError(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        return None


def probe(base, path, token, status, code, shape=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        proxy = ProxyHandler({}) if urlsplit(base).hostname in ("localhost", "127.0.0.1", "::1") else ProxyHandler()
        response = build_opener(NoRedirect(), proxy).open(Request(base + path, headers=headers), timeout=5)
    except HTTPError as error:
        response = error
    except (URLError, TimeoutError, OSError) as error:
        raise ProbeError("connection unavailable: " + type(error).__name__) from error
    with response:
        if response.status != status:
            raise ProbeError(f"HTTP {response.status}, expected {status}")
        if "application/json" not in response.headers.get("Content-Type", ""):
            raise ProbeError("expected JSON content type")
        body = response.read(262145)
    if len(body) > 262144:
        raise ProbeError("response exceeds 256 KiB")
    try:
        result = json.loads(body)
    except (ValueError, UnicodeError) as error:
        raise ProbeError("invalid JSON") from error
    if (not isinstance(result, dict) or type(result.get("code")) is not int
            or result["code"] != code or not isinstance(result.get("message"), str) or "data" not in result):
        raise ProbeError("invalid Result envelope or unexpected code")
    data = result["data"]
    if shape == "cursor" and (not isinstance(data, dict) or not isinstance(data.get("items"), list)
            or "nextCursor" not in data or data["nextCursor"] is not None and not isinstance(data["nextCursor"], str)):
        raise ProbeError("invalid cursor page")
    if shape == "page":
        if not isinstance(data, dict) or not isinstance(data.get("list"), list):
            raise ProbeError("invalid PageResult")
        for name in ("total", "pages"):
            value = data.get(name)
            if not (type(value) is int and value >= 0 or isinstance(value, str) and value.isdecimal()):
                raise ProbeError("invalid PageResult counts")
    if status >= 400 and data is not None:
        raise ProbeError("ordinary error must have null data")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    url = urlsplit(args.gateway)
    if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.query or url.fragment:
        parser.error("gateway must be an HTTP(S) base URL without credentials, query or fragment")
    admin = os.environ.get("MY_YOUTUBE_ADMIN_TOKEN", "")
    user = os.environ.get("MY_YOUTUBE_USER_TOKEN", "")
    missing = os.environ.get("MY_YOUTUBE_MISSING_CONTENT_ID", "")
    if missing and (not missing.isdecimal() or not 0 < int(missing) <= 2**63 - 1):
        parser.error("MY_YOUTUBE_MISSING_CONTENT_ID must be a positive Java Long")
    cases = [
        ("gateway anonymous", "/admin/media-tasks", None, 401, 40101, None),
        ("admin permission", "/admin/media-tasks", user, 403, 40301, None),
        ("admin to search validation", "/admin/hot-search/dates?size=0", admin, 400, 40001, None),
        ("admin to search cursor", "/admin/hot-search/dates?size=1", admin, 200, 200, "cursor"),
        ("admin to video page", "/admin/list?pageNo=1&pageSize=1", admin, 200, 200, "page"),
        ("admin to video status validation", "/admin/list?statusCode=contract-invalid", admin, 400, 40001, None),
        ("admin to video type validation", "/admin/list?type=contract-invalid", admin, 400, 40001, None),
        ("admin binding", "/admin/content/not-a-number/media", admin, 400, 40002, None),
        ("admin to video missing content", "/admin/content/" + missing + "/media", admin if missing else "", 404, 40401, None),
        ("admin to transcode tasks cursor", "/admin/media-tasks?size=1", admin, 200, 200, "cursor"),
        ("admin to transcode history cursor", "/admin/media-retry-batches?size=1", admin, 200, 200, "cursor"),
        ("admin to transcode validation", "/admin/media-tasks?size=0", admin, 400, 40001, None),
    ]
    results = []
    for name, path, token, status, code, shape in cases:
        if token == "":
            results.append({"check": name, "result": "SKIP", "reason": "test token or missing-content fixture not provided"})
            continue
        try:
            probe(args.gateway.rstrip("/"), path, token, status, code, shape)
            results.append({"check": name, "result": "PASS"})
        except (ProbeError, OSError) as error:
            reason = str(error) if isinstance(error, ProbeError) else type(error).__name__
            results.append({"check": name, "result": "FAIL", "reason": reason})
    print(json.dumps({"scope": "read-only HTTP contracts", "checks": results}, indent=2))
    return 1 if any(row["result"] == "FAIL" for row in results) else 2 if any(row["result"] == "SKIP" for row in results) else 0


if __name__ == "__main__":
    sys.exit(main())
