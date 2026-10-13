import asyncio
import threading
import unittest
from fastapi import HTTPException
from model_runtime.pool import BoundedPool


class PoolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.release = threading.Event()
        self.started = threading.Event()
        release, started = self.release, self.started
        class Worker:
            def run(self, request):
                started.set()
                release.wait(5)
                return request
            def stop(self): pass
        self.pool = BoundedPool("test", Worker, 1, 4, 0.3)
        await self.pool.start()

    async def asyncTearDown(self):
        self.release.set()
        await self.pool.close()

    async def test_active_plus_four_waiters_then_503(self):
        jobs = [asyncio.create_task(self.pool.run(i)) for i in range(5)]
        await asyncio.to_thread(self.started.wait, 1)
        await asyncio.sleep(0.02)
        self.assertEqual(self.pool.running, 1)
        self.assertEqual(self.pool.admitted, 5)
        with self.assertRaises(HTTPException) as caught: await self.pool.run(6)
        self.assertEqual(caught.exception.status_code, 503)
        self.assertEqual(caught.exception.headers["Retry-After"], "30")
        self.release.set()
        self.assertEqual(await asyncio.gather(*jobs), list(range(5)))
        self.assertEqual(self.pool.admitted, 0)

    async def test_cancelled_client_does_not_release_busy_model(self):
        job = asyncio.create_task(self.pool.run(1))
        await asyncio.to_thread(self.started.wait, 1)
        job.cancel()
        with self.assertRaises(asyncio.CancelledError): await job
        self.assertEqual(self.pool.running, 1)
        self.assertEqual(self.pool.admitted, 1)
        self.release.set()
        await asyncio.gather(*self.pool.jobs)
        await asyncio.sleep(0)
        self.assertEqual(self.pool.admitted, 0)

    async def test_wait_timeout_returns_503_and_frees_admission(self):
        job = asyncio.create_task(self.pool.run(1))
        await asyncio.to_thread(self.started.wait, 1)
        with self.assertRaises(HTTPException) as caught: await self.pool.run(2)
        self.assertEqual(caught.exception.status_code, 503)
        self.assertEqual(self.pool.admitted, 1)
        self.release.set()
        await job


if __name__ == "__main__": unittest.main()
