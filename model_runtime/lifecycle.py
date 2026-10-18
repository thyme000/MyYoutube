from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import secrets
import threading
import time

from filelock import FileLock


class ControlServer(HTTPServer):
    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(2)
        return connection, address


@contextmanager
def server_record(identity, directory, port, shutdown):
    runtime = Path(directory) / ".runtime"
    runtime.mkdir(exist_ok=True)
    record = runtime / "server.json"
    token = secrets.token_hex(32)

    class ControlHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            authorized = secrets.compare_digest(self.headers.get("Authorization", ""), f"Bearer {token}")
            if self.path != "/shutdown" or not authorized:
                self.send_error(403)
                return
            shutdown()
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, *args):
            pass

    with FileLock(str(runtime / "server.lock"), timeout=0):
        control = ControlServer(("127.0.0.1", 0), ControlHandler)
        thread = threading.Thread(target=control.serve_forever, daemon=True, name="model-control")
        temporary = runtime / f"server-{os.getpid()}.tmp"
        try:
            temporary.unlink(missing_ok=True)
            payload = {"pid": os.getpid(), "service": identity.name, "directory": str(Path(directory).resolve()),
                       "port": port, "recordedAt": time.time_ns() // 1_000_000,
                       "controlPort": control.server_port, "controlToken": token}
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                json.dump(payload, output)
            temporary.replace(record)
            thread.start()
            yield
        finally:
            if thread.is_alive():
                control.shutdown()
                thread.join(timeout=2)
            control.server_close()
            record.unlink(missing_ok=True)
            temporary.unlink(missing_ok=True)
