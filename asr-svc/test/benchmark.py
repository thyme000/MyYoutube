import json
import os
import subprocess
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from fastapi.testclient import TestClient
from app import app
import psutil


def main():
    outputs = {}
    configured_source = os.getenv("ASR_BENCHMARK_SOURCE")
    if not configured_source:
        raise ValueError("ASR_BENCHMARK_SOURCE must explicitly identify an existing audio sample")
    source = Path(configured_source).expanduser().resolve()
    if not source.is_file():
        raise ValueError(f"ASR_BENCHMARK_SOURCE does not exist: {source}")
    with tempfile.TemporaryDirectory(prefix="asr-benchmark-") as directory:
        tail, silent, no_audio = Path(directory) / "tail.wav", Path(directory) / "silent.wav", Path(directory) / "no-audio.mp4"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(source), "-af", "apad=pad_dur=20", str(tail)], check=True)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "20", str(silent)], check=True)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=black:s=160x90:d=1", "-an", str(no_audio)], check=True)
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                data = (silent if self.path == "/silent" else no_audio if self.path == "/no-audio" else tail).read_bytes()
                self.send_response(200); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
            def do_PUT(self):
                outputs[self.path] = self.rfile.read(int(self.headers["Content-Length"]))
                self.send_response(200); self.end_headers()
            def log_message(self, *args): pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            with TestClient(app) as client:
                pids = [worker.process.pid for worker in app.state.pool.workers]
                def rss():
                    return sum(psutil.Process(pid).memory_info().rss for pid in pids)
                idle_rss = rss()
                def run(name, silence=False):
                    response = client.post("/asr", json={"sourceUrl": base + ("/no-audio" if name == "no-audio" else "/silent" if silence else "/source"),
                        "vttUploadUrl": base + f"/{name}.vtt", "segmentsUploadUrl": base + f"/{name}.json",
                        "title": "Ubuntu Linux 教程", "tags": "Ubuntu,Linux", "description": "简介。" * 2000 + "结尾 Kubernetes。"})
                    response.raise_for_status(); return response.json()
                started = time.monotonic(); serial = run("serial"); serial_time = time.monotonic() - started
                resident_samples = []
                stop_monitor = threading.Event()
                def monitor():
                    while not stop_monitor.is_set():
                        resident_samples.append(rss()); stop_monitor.wait(0.1)
                monitor_thread = threading.Thread(target=monitor); monitor_thread.start()
                started = time.monotonic()
                try:
                    with ThreadPoolExecutor(max_workers=2) as pool: parallel = list(pool.map(run, ["parallel-a", "parallel-b"]))
                finally:
                    stop_monitor.set(); monitor_thread.join()
                parallel_time = time.monotonic() - started
                silence = run("silence", True)
                missing_audio = run("no-audio")
                assert missing_audio["segmentCount"] == 0
                assert len({r["workerPid"] for r in parallel}) == min(2, app.state.pool.concurrency)
                assert silence["segmentCount"] == 0
                assert all(r["promptTokenCount"] <= 224 for r in [serial, *parallel, silence])
                longest, tails = 0, []
                for name in ["serial", "parallel-a", "parallel-b"]:
                    segments = json.loads(outputs[f"/{name}.json"])["segments"]
                    longest = max(longest, max((len(s["text"]) for s in segments), default=0))
                    tails.append(segments[-1] if segments else None)
                    assert "请不吝点赞" not in outputs[f"/{name}.json"].decode()
                    assert outputs[f"/{name}.vtt"].startswith(b"WEBVTT")
                print(json.dumps({"serialSeconds": serial_time, "parallelPairSeconds": parallel_time,
                    "pairThroughputSpeedup": 2 * serial_time / parallel_time, "serial": serial, "parallel": parallel,
                    "silence": silence, "noAudio": missing_audio, "workerCount": len(pids), "idleWorkerRssBytes": idle_rss,
                    "peakConcurrentWorkerRssBytes": max(resident_samples), "longestCueCharacters": longest, "lastSegments": tails}, ensure_ascii=False, indent=2))
        finally: server.shutdown(); server.server_close()


if __name__ == "__main__": main()
