"""复用已启动的 OCR 工作进程比较准备文件，不重复加载模型。"""
import argparse
import hashlib
import json
import re
import psutil
import threading
import time
from difflib import SequenceMatcher
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx


def swap_bytes():
    return psutil.swap_memory().used


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", required=True, type=Path)
    parser.add_argument("--prepared", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--service-url", default="http://127.0.0.1:8088")
    args = parser.parse_args()
    files, groups = {}, []
    originals = sorted(p for p in args.samples.iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png"))
    for prefix, paths in (("original", originals), ("prepared", sorted(args.prepared.glob("1600-image-*.jpg")))):
        ordered = paths if prefix == "original" else sorted(paths, key=lambda p: int(p.stem.split("-")[-1]))
        names = []
        for i, path in enumerate(ordered):
            name = f"{prefix}-{i}"
            files[name], names = path, names + [name]
        groups.append((prefix, names))
    for edge in (1920, 1600, 1280):
        names = []
        for index in range(8):
            name = f"frame-{edge}-{index}"
            files[name], names = args.prepared / f"{edge}-video-0-frame-{index}.jpg", names + [name]
        groups.append((f"frames-{edge}", names))
    if not originals or any(not path.is_file() for path in files.values()):
        raise RuntimeError("Missing samples or prepared files")

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = files.get(self.path.removeprefix("/"))
            if path is None:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(path.stat().st_size))
            self.end_headers()
            with path.open("rb") as source:
                while chunk := source.read(65536):
                    self.wfile.write(chunk)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    report = {"groups": [], "comparisons": [], "swapBefore": swap_bytes(), "failures": 0}
    texts = {}
    try:
        with httpx.Client(timeout=180, follow_redirects=False) as client:
            health = client.get(args.service_url + "/health")
            health.raise_for_status()
            report["health"] = health.json()
            if not report["health"].get("ready") or report["health"].get("admitted", 0) != 0:
                raise RuntimeError("OCR worker not idle and ready")
            for group, names in groups:
                started = time.monotonic()
                characters = 0
                for offset in range(0, len(names), 2):
                    selected = names[offset:offset+2]
                    urls = [f"http://127.0.0.1:{server.server_port}/{name}" for name in selected]
                    response = client.post(args.service_url + "/ocr/batch", json={"urls": urls})
                    if response.status_code != 200:
                        report["failures"] += 1
                        raise RuntimeError(f"OCR HTTP status {response.status_code}")
                    items = response.json()["results"]
                    if len(items) != len(selected):
                        report["failures"] += 1
                        raise RuntimeError("Incomplete OCR response")
                    for name, url, item in zip(selected, urls, items):
                        if item.get("error") or item.get("url") != url or not isinstance(item.get("text"), str):
                            report["failures"] += 1
                            raise RuntimeError("OCR image failed")
                        texts[name] = re.sub(r"\s+", "", item["text"])
                        characters += len(texts[name])
                    print(json.dumps({"group": group, "completed": offset+len(selected), "total": len(names),
                                      "elapsedSeconds": round(time.monotonic()-started, 3)}), flush=True)
                report["groups"].append({"name": group, "items": len(names), "characters": characters,
                                         "elapsedSeconds": time.monotonic()-started})
            pairs = [(f"original-{i}", f"prepared-{i}") for i in range(len(originals))]
            pairs += [(f"frame-1920-{i}", f"frame-{edge}-{i}") for edge in (1600,1280) for i in range(8)]
            for before, after in pairs:
                report["comparisons"].append({"before": before, "after": after, "beforeCharacters": len(texts[before]),
                    "afterCharacters": len(texts[after]), "similarity": SequenceMatcher(None,texts[before],texts[after],autojunk=False).ratio(),
                    "beforeHash": hashlib.sha256(texts[before].encode()).hexdigest(),
                    "afterHash": hashlib.sha256(texts[after].encode()).hexdigest()})
    except Exception as error:
        report["error"] = type(error).__name__
        raise
    finally:
        report["swapAfter"] = swap_bytes()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
