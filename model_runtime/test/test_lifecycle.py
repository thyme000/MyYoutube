import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import Mock, patch

from filelock import Timeout
from model_runtime.lifecycle import server_record
from model_runtime.settings import SERVICES
from model_runtime.test.fixtures import service_env


def request_shutdown(record, token=None):
    request = urllib.request.Request(f"http://127.0.0.1:{record['controlPort']}/shutdown", data=b'',
                                     headers={'Authorization': 'Bearer ' + (token or record['controlToken'])})
    return urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=3)


class LifecycleTests(unittest.TestCase):
    def test_record_is_locked_authenticated_and_removed_after_failure(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch('socket.getfqdn', side_effect=AssertionError('control listener must not resolve its address')):
            stopped = Mock()
            record = Path(directory, '.runtime/server.json')
            with self.assertRaisesRegex(ValueError, 'startup failed'):
                with server_record(SERVICES['ocr'], directory, 8088, stopped):
                    data = json.loads(record.read_text())
                    self.assertEqual(data['service'], 'ocr-svc')
                    self.assertEqual(data['port'], 8088)
                    with self.assertRaises(Timeout):
                        with server_record(SERVICES['ocr'], directory, 8088, stopped):
                            self.fail('duplicate admitted')
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        request_shutdown(data, 'wrong')
                    self.assertEqual(error.exception.code, 403)
                    stopped.assert_not_called()
                    with request_shutdown(data) as response:
                        self.assertEqual(response.status, 202)
                    stopped.assert_called_once()
                    raise ValueError('startup failed')
            self.assertFalse(record.exists())

    def test_real_uvicorn_cooperative_shutdown_drains_lifespan_and_removes_record(self):
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            with socket.socket() as socket_probe:
                socket_probe.bind(('127.0.0.1', 0))
                port = socket_probe.getsockname()[1]
            service_env(work, 'ocr', f'SERVER_PORT={port}\n')
            work.joinpath('app.py').write_text(
                "from contextlib import asynccontextmanager\n"
                "from pathlib import Path\n"
                "from fastapi import FastAPI\n"
                "@asynccontextmanager\n"
                "async def lifespan(app):\n"
                "    yield\n"
                "    Path('stopped').touch()\n"
                "app = FastAPI(lifespan=lifespan)\n"
                "@app.get('/health')\n"
                "async def health():\n"
                "    return {'ready': True}\n")
            env = {**os.environ, 'PYTHONPATH': str(root)}
            console = work / 'console.log'
            with console.open('wb') as output:
                process = subprocess.Popen([sys.executable, '-m', 'model_runtime.server', '--service', 'ocr',
                                            '--directory', str(work)], cwd=work, env=env,
                                           stdout=output, stderr=subprocess.STDOUT)
            try:
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                health = f'http://127.0.0.1:{port}/health'
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline and process.poll() is None:
                    try:
                        with opener.open(health, timeout=0.5) as response:
                            self.assertEqual(response.status, 200)
                        break
                    except (urllib.error.URLError, TimeoutError):
                        time.sleep(0.05)
                else:
                    logs = console.read_text(encoding='utf-8', errors='replace')
                    self.fail(f'test server did not become ready (exit code {process.poll()})\n{logs}')

                with request_shutdown(json.loads(work.joinpath('.runtime/server.json').read_text())) as response:
                    self.assertEqual(response.status, 202)
                self.assertEqual(process.wait(timeout=10), 0)
                self.assertTrue(work.joinpath('stopped').exists())
                self.assertFalse(work.joinpath('.runtime/server.json').exists())
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
