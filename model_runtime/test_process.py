import multiprocessing
import os
import time
import unittest
from unittest.mock import patch

from model_runtime.process import ProcessWorker


def serve(connection, config):
    os.setsid()
    connection.send({"ready": True})
    while True:
        request = connection.recv()
        if request.get("exit"):
            os._exit(9)
        if request.get("timeout"):
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
        self.assertFalse(old.is_alive())
        self.assertTrue(self.worker.ready)
        self.assertEqual(self.worker.run({}), {"ok": True})

    def test_process_exit_retains_exitcode_and_replacement_is_ready(self):
        with self.assertRaisesRegex(RuntimeError, "connection lost.*exitcode=9"):
            self.worker.run({"exit": True})
        self.assertTrue(self.worker.ready)
        self.assertEqual(self.worker.run({}), {"ok": True})

    def test_failed_restart_stays_unready_and_next_request_recovers(self):
        with patch.object(self.worker, "start", side_effect=RuntimeError("model unavailable")):
            with self.assertRaisesRegex(RuntimeError, "exitcode=9.*restart failed: model unavailable"):
                self.worker.run({"exit": True})
        self.assertFalse(self.worker.ready)
        self.assertEqual(self.worker.run({}), {"ok": True})
        self.assertTrue(self.worker.ready)

    def test_spawn_failure_closes_channels_and_next_request_recovers(self):
        self.worker.stop()
        with patch("multiprocessing.process.BaseProcess.start", side_effect=OSError("spawn failed")):
            with self.assertRaisesRegex(OSError, "spawn failed"):
                self.worker.start()
        self.assertFalse(self.worker.ready)
        self.assertTrue(self.worker.connection.closed)
        self.assertTrue(self.worker.logs.closed)
        self.assertEqual(self.worker.run({}), {"ok": True})

    def test_idle_process_death_is_recovered_before_next_request(self):
        self.worker.process.kill()
        self.worker.process.join(timeout=2)
        self.assertEqual(self.worker.run({}), {"ok": True})


if __name__ == "__main__":
    unittest.main()
