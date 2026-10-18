import multiprocessing
import os
import time
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from model_runtime.process import ProcessWorker, WorkerUnavailable


def serve(connection, config):
    time.sleep(config.get("startup_delay", 0))
    connection.send({"ready": True})
    while True:
        request = connection.recv()
        if request is None:
            return
        if request.get("exit"):
            os._exit(9)
        if request.get("timeout"):
            time.sleep(5)
        if request.get("spawn_child"):
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
            Path(request["spawn_child"]).write_text(str(child.pid))
            time.sleep(5)
        connection.send({"ok": True})


class ProcessTests(unittest.TestCase):
    def setUp(self):
        self.worker = ProcessWorker(multiprocessing.get_context("spawn"), {"timeout": 0.2}, serve)

    def tearDown(self):
        self.worker.stop()

    def test_timeout_retains_reason_and_replacement_can_serve_next_request(self):
        old = self.worker.process
        with self.assertRaisesRegex(RuntimeError, "Model execution timed out after 0.2s"):
            self.worker.run({"timeout": True})
        self.assertFalse(self.worker.ready)
        self.worker.recover()
        self.assertFalse(old.is_alive())
        self.assertTrue(self.worker.ready)
        self.assertEqual(self.worker.run({}), {"ok": True})

    def test_process_exit_retains_exitcode_and_replacement_is_ready(self):
        with self.assertRaisesRegex(RuntimeError, "connection lost.*exitcode=9"):
            self.worker.run({"exit": True})
        self.assertFalse(self.worker.ready)
        self.worker.recover()
        self.assertTrue(self.worker.ready)
        self.assertEqual(self.worker.run({}), {"ok": True})

    def test_failed_restart_stays_unready_and_requests_do_not_reload(self):
        with self.assertRaises(RuntimeError):
            self.worker.run({"exit": True})
        with patch.object(self.worker, "start", side_effect=RuntimeError("model unavailable")):
            with self.assertRaisesRegex(RuntimeError, "model unavailable"):
                self.worker.recover()
        self.assertFalse(self.worker.ready)
        with self.assertRaises(WorkerUnavailable):
            self.worker.run({})
        self.worker.recover()
        self.assertEqual(self.worker.run({}), {"ok": True})

    def test_spawn_failure_closes_channels_and_next_request_recovers(self):
        self.worker.stop()
        with patch("multiprocessing.process.BaseProcess.start", side_effect=OSError("spawn failed")):
            with self.assertRaisesRegex(OSError, "spawn failed"):
                self.worker.start()
        self.assertFalse(self.worker.ready)
        self.assertTrue(self.worker.connection.closed)
        self.assertTrue(self.worker.logs.closed)
        self.worker.recover()
        self.assertEqual(self.worker.run({}), {"ok": True})

    def test_idle_process_death_is_recovered_before_next_request(self):
        self.worker.process.kill()
        self.worker.process.join(timeout=2)
        with self.assertRaises(WorkerUnavailable):
            self.worker.run({})
        self.worker.recover()
        self.assertEqual(self.worker.run({}), {"ok": True})

    def test_timeout_cleans_descendant_process_before_replacement(self):
        import psutil
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "child.pid"
            with self.assertRaisesRegex(RuntimeError, "timed out"):
                self.worker.run({"spawn_child": str(file)})
            self.worker.recover()
            self.assertTrue(file.exists())
            pid = int(file.read_text())
            try:
                child = psutil.Process(pid)
                self.assertEqual(child.status(), psutil.STATUS_ZOMBIE)
            except psutil.NoSuchProcess:
                pass
            self.assertEqual(self.worker.run({}), {"ok": True})


if __name__ == "__main__":
    unittest.main()
