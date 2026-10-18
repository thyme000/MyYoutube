import asyncio
import multiprocessing
import unittest

import httpx
from fastapi import FastAPI
from model_runtime.pool import BoundedPool
from model_runtime.process import ProcessWorker
from model_runtime.test.test_process import serve


class RecoveryHttpTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_process_timeout_then_http_rejection_then_success(self):
        pool = BoundedPool("http-recovery", lambda: ProcessWorker(multiprocessing.get_context("spawn"),
                           {"timeout": 0.1, "startup_delay": 0.5}, serve), 1, 4, 1)
        await pool.start()
        app = FastAPI()

        @app.post("/ocr/batch")
        async def execute(request: dict):
            return await pool.run(request)

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://fixture") as client:
            old_pid = pool.workers[0].process.pid
            try:
                async with asyncio.timeout(0.5):
                    failed = await client.post("/ocr/batch", json={"timeout": True})
                self.assertEqual(failed.status_code, 502)
                self.assertTrue(pool.recoveries)
                rejected = await client.post("/ocr/batch", json={})
                self.assertEqual(rejected.status_code, 503)
                self.assertEqual(rejected.json(), {"detail": {"code": "MODEL_RECOVERING"}})
                self.assertEqual(rejected.headers["Retry-After"], "30")
                async with asyncio.timeout(5):
                    while pool.recoveries:
                        await asyncio.sleep(0.01)
                self.assertNotEqual(pool.workers[0].process.pid, old_pid)
                success = await client.post("/ocr/batch", json={})
                self.assertEqual(success.json(), {"ok": True})
            finally:
                await pool.close()
        self.assertFalse(pool.jobs)
        self.assertFalse(pool.recoveries)

    async def test_close_interrupts_real_process_model_loading(self):
        pool = BoundedPool("close-recovery", lambda: ProcessWorker(multiprocessing.get_context("spawn"),
                           {"timeout": 0.1}, serve), 1, 4, 1)
        await pool.start()
        worker = pool.workers[0]
        worker.config["startup_delay"] = 20
        worker.process.kill()
        worker.process.join(1)
        try:
            async with asyncio.timeout(3):
                while not pool.recoveries:
                    await asyncio.sleep(0.01)
                while worker.process.pid is None or not worker.process.is_alive():
                    await asyncio.sleep(0.01)
                await pool.close()
            self.assertFalse(worker.process.is_alive())
            self.assertFalse(pool.recoveries)
        finally:
            await pool.close()
