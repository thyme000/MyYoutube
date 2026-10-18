import json
import os
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from model_runtime.discovery import Registration

import app
from model_runtime.pool import BoundedPool


def health_pool(worker, admitted=0):
    pool = BoundedPool("health-test", lambda: worker, 1, 4, 60)
    pool.workers = [worker]
    pool.accepting = True
    pool.admitted = admitted
    return pool


class HealthTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        with patch.dict("os.environ", {}, clear=True):
            app.app.state.discovery = Registration("asr-health-test", 8089)
        app.app.state.discovery.set_registered(True)

    async def test_restarting_or_dead_worker_is_not_ready(self):
        worker = type("Worker", (), {"ready": True, "process": SimpleNamespace(is_alive=lambda: True)})()
        app.app.state.pool = health_pool(worker, 0)
        initial = await app.health()
        self.assertEqual(initial.status_code, 200)
        self.assertEqual(json.loads(initial.body)["pid"], os.getpid())
        worker.ready = False
        response = await app.health()
        self.assertEqual(response.status_code, 503)
        self.assertFalse(json.loads(response.body)["ready"])
        worker.ready = True
        worker.process.is_alive = lambda: False
        self.assertEqual((await app.health()).status_code, 503)

    async def test_discovery_failure_and_recovery_change_readiness(self):
        worker = type("Worker", (), {"ready": True, "process": SimpleNamespace(is_alive=lambda: True)})()
        app.app.state.pool = health_pool(worker, 0)
        app.app.state.discovery.set_registered(False)
        response = await app.health()
        self.assertEqual(response.status_code, 503)
        self.assertEqual(json.loads(response.body)["discovery"], {"enabled": True, "registered": False})
        app.app.state.discovery.set_registered(True)
        self.assertEqual((await app.health()).status_code, 200)
        app.app.state.discovery.enabled = False
        app.app.state.discovery.set_registered(False)
        self.assertEqual((await app.health()).status_code, 200)


if __name__ == "__main__":
    unittest.main()
