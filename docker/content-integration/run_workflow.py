"""Run packaged services against the disposable content integration project."""
import argparse
import json
import os
import re
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ["docker", "compose", "-f", str(ROOT / "docker/compose.content-integration.yml")]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--components", action="store_true", help="Run all opt-in component tests before starting Java services")
    parser.add_argument("--speech", type=Path, required=True, help="Local speech WAV of at least ten seconds")
    parser.add_argument("--hold-seconds", type=int, default=0, help="Keep this isolated environment available for browser checks after the workflow")
    args = parser.parse_args()
    if not 0 <= args.hold_seconds <= 1800:
        parser.error("hold-seconds must be between 0 and 1800")
    if not args.speech.is_file():
        raise RuntimeError("Speech sample is missing")
    for port in range(18080, 18090):
        with socket.socket() as sock:
            if sock.connect_ex(("127.0.0.1", port)) == 0:
                raise RuntimeError(f"Test port {port} already occupied")
    marker = subprocess.check_output(COMPOSE + ["exec", "-T", "mysql", "mysql", "-uroot", "-pcontent-it-only", "-N", "-e",
            "SELECT COUNT(*) FROM content_it.isolated_test_environment WHERE id=1"], stderr=subprocess.DEVNULL, text=True).strip()
    if marker != "1":
        raise RuntimeError("Missing isolated environment marker")

    logs = Path(tempfile.mkdtemp(prefix="content-final-workflow-"))
    print(f"Workflow logs: {logs}", flush=True)
    processes = []
    handles = []

    def launch(command, name, cwd=ROOT, extra_env=None):
        handle = (logs / f"{name}.log").open("w")
        handles.append(handle)
        env = os.environ.copy()
        env.update(extra_env or {})
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process)
        return process

    def ready(url, process, timeout=180):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"Service exited before ready: {url}")
            try:
                with urllib.request.urlopen(url, timeout=3) as response:
                    if response.status == 200:
                        return
            except (OSError, ValueError):
                pass
            time.sleep(1)
        raise TimeoutError(f"Service readiness timed out: {url}")

    def terminate(*_):
        raise KeyboardInterrupt()

    signal.signal(signal.SIGTERM, terminate)
    try:
        for service, port in [("asr-svc", 18089), ("ocr-svc", 18088)]:
            env = {"NACOS_ADDR": "127.0.0.1:18848", "NACOS_NAMESPACE": "", "NACOS_GROUP": "DEFAULT_GROUP",
                   "NACOS_CLUSTER": "DEFAULT", "SERVER_PORT": str(port), "ASR_CONCURRENCY": "1",
                   "PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK": "True"}
            process = launch(["sh", "start.sh"], service, ROOT / service, env)
            ready(f"http://127.0.0.1:{port}/health", process)
            print(f"Ready: {service}", flush=True)
        if args.components:
            with (logs / "components.log").open("w") as output:
                subprocess.run(["mvn", "-o", "-f", "Backend/pom.xml", "test", "-Dcontent.integration=true",
                        "-Dpipeline.live=true", "-Dcontent.models=true", "-Dcontent.llm=true",
                        f"-Dcontent.asr.sample={args.speech.resolve()}", "-Dffmpeg.tests=true", "-Dstyle.color=never"],
                        cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, check=True)
            print("Component suite passed", flush=True)
        subprocess.run(COMPOSE + ["exec", "-T", "redis", "redis-cli", "FLUSHDB"], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(COMPOSE + ["exec", "-T", "mongodb", "mongosh", "--quiet", "--eval",
                "if (db.hello().setName !== 'content-it') throw Error('Wrong replica set'); db.getSiblingDB('content_it').dropDatabase()"],
                check=True, stdout=subprocess.DEVNULL)
        with urllib.request.urlopen("http://127.0.0.1:19200", timeout=10) as response:
            if json.load(response)["cluster_name"] != "content-it":
                raise RuntimeError("Wrong Elasticsearch cluster")
        for index in ["content", "user"]:
            try:
                urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:19200/{index}", method="DELETE"), timeout=10).close()
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
        subprocess.run(COMPOSE + ["exec", "-T", "mysql", "mysql", "-uroot", "-pcontent-it-only", "-e",
                "DROP DATABASE IF EXISTS user_db; DROP DATABASE IF EXISTS video_db; DROP DATABASE IF EXISTS chat_db; DROP DATABASE IF EXISTS transcode_db"],
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True)
        with (ROOT / "resources/schema.sql").open("rb") as source:
            subprocess.run(COMPOSE + ["exec", "-T", "mysql", "mysql", "-uroot", "-pcontent-it-only"],
                    stdin=source, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True)
        services = [("llm", 18087), ("user", 18081), ("video", 18082), ("transcode", 18086),
                    ("search", 18084), ("chat", 18083), ("admin", 18085), ("gateway", 18080)]
        running = []
        for name, port in services:
            suffix = "" if name in {"gateway", "admin"} else "-exec"
            jar = ROOT / f"Backend/my-youtube-{name}/target/my-youtube-{name}-0.0.1-SNAPSHOT{suffix}.jar"
            if not jar.is_file():
                raise RuntimeError(f"Run mvn package first: {jar}")
            home = logs / name
            home.mkdir()
            command = ["java", "-Xmx512m", "-XX:ActiveProcessorCount=2", f"-Duser.home={home}",
                       f"-Dcsp.sentinel.log.dir={home}/sentinel", "-jar", str(jar),
                       f"--server.port={port}", "--spring.config.additional-location="
                       + (ROOT / "docker/content-integration/application.yml").as_uri()]
            running.append((name, port, launch(command, name)))
        for name, port, process in running:
            wait_started(logs / f"{name}.log", process)
            print(f"Started: {name} ({port})", flush=True)
        smoke = subprocess.Popen(["python3", str(ROOT / "docker/content-integration/smoke_workflow.py"),
                        "--speech", str(args.speech.resolve()), "--output", str(logs)], cwd=ROOT)
        processes.append(smoke)
        if smoke.wait(timeout=900) != 0:
            raise RuntimeError("Workflow checks failed")
        if args.hold_seconds:
            print(f"Workflow passed; browser checks available for {args.hold_seconds} seconds", flush=True)
            time.sleep(args.hold_seconds)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
        shutdown_deadline = time.monotonic() + 30
        for process in reversed(processes):
            try:
                process.wait(timeout=max(0.1, shutdown_deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for handle in handles:
            handle.close()
        print("All workflow child processes stopped; remove the test compose project with down -v.", flush=True)


def wait_started(log, process):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Service exited: {log}")
        if re.search(r"Started \w+Application in ", log.read_text(errors="replace")):
            return
        time.sleep(1)
    raise TimeoutError(f"Startup timed out: {log}")


if __name__ == "__main__":
    main()
