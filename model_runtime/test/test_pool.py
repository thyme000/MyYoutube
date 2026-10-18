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


class RecoveryTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.release = threading.Event()
        self.started = threading.Event()
        self.recovering = threading.Event()
        self.loaded = threading.Event()
        owner = self
        class Worker:
            ready = True
            attempts = 0
            def run(self, request):
                owner.started.set()
                owner.release.wait(2)
                if request == "fail":
                    self.ready = False
                    raise RuntimeError("execution failed")
                return request
            def recover(self):
                self.attempts += 1
                owner.recovering.set()
                owner.loaded.wait(2)
                self.ready = True
            def request_stop(self):
                owner.loaded.set()
            def stop(self):
                self.ready = False
        self.pool = BoundedPool("recovery-test", Worker, 1, 4, 1, 2, 0.01)
        await self.pool.start()

    async def asyncTearDown(self):
        self.release.set()
        self.loaded.set()
        await self.pool.close()

    async def until(self, condition):
        async with asyncio.timeout(2):
            while not condition():
                await asyncio.sleep(0.005)

    async def test_failure_returns_while_loading_and_waiters_are_rejected_without_restarting(self):
        running = asyncio.create_task(self.pool.run("fail"))
        await asyncio.to_thread(self.started.wait, 1)
        waiting = asyncio.create_task(self.pool.run("waiting"))
        await self.until(lambda: self.pool.admitted == 2)
        self.release.set()
        with self.assertRaises(HTTPException) as error:
            await running
        self.assertEqual(error.exception.status_code, 502)
        with self.assertRaises(HTTPException) as error:
            await waiting
        self.assertEqual(error.exception.detail["code"], "MODEL_RECOVERING")
        await asyncio.to_thread(self.recovering.wait, 1)
        for _ in range(5):
            with self.assertRaises(HTTPException) as error:
                await self.pool.run("retry")
            self.assertEqual(error.exception.detail["code"], "MODEL_RECOVERING")
        self.assertEqual(self.pool.workers[0].attempts, 1)
        self.assertEqual(self.pool.admitted, 0)
        self.loaded.set()
        await self.until(lambda: not self.pool.recoveries)
        self.assertEqual(await self.pool.run("ok"), "ok")

    async def test_idle_death_is_recovered_without_request(self):
        self.pool.workers[0].ready = False
        await asyncio.to_thread(self.recovering.wait, 1)
        self.assertEqual(len(self.pool.recoveries), 1)
        self.loaded.set()
        await self.until(lambda: not self.pool.recoveries)
        self.assertEqual(self.pool.healthy_count(), 1)

    async def test_partial_failure_keeps_other_worker_available(self):
        other = self.pool.factory()
        self.pool.workers.append(other)
        self.pool.available.append(other)
        self.pool.workers[0].ready = False
        await asyncio.to_thread(self.recovering.wait, 1)
        self.release.set()
        self.assertEqual(await self.pool.run("ok"), "ok")
        self.assertEqual(self.pool.healthy_count(), 1)

    async def test_exhaustion_does_not_restart_on_each_request(self):
        def failed():
            self.pool.workers[0].attempts += 1
            raise RuntimeError("load failed")
        self.pool.workers[0].recover = failed
        self.pool.workers[0].ready = False
        await self.until(lambda: bool(self.pool.failed))
        for _ in range(3):
            with self.assertRaises(HTTPException) as error:
                await self.pool.run("retry")
            self.assertEqual(error.exception.detail["code"], "MODEL_RECOVERY_EXHAUSTED")
        self.assertEqual(self.pool.workers[0].attempts, 2)

    async def test_close_wakes_waiters_and_does_not_publish_recovered_worker(self):
        running = asyncio.create_task(self.pool.run("ok"))
        await asyncio.to_thread(self.started.wait, 1)
        waiting = asyncio.create_task(self.pool.run("waiting"))
        await self.until(lambda: self.pool.admitted == 2)
        closing = asyncio.create_task(self.pool.close())
        with self.assertRaises(HTTPException) as error:
            await waiting
        self.assertEqual(error.exception.detail["code"], "MODEL_SHUTTING_DOWN")
        self.release.set()
        self.assertEqual(await running, "ok")
        await closing
        self.assertEqual(self.pool.admitted, 0)
        self.assertFalse(self.pool.available)

    async def test_close_during_recovery_leaves_no_recovery_task(self):
        self.pool.workers[0].ready = False
        await asyncio.to_thread(self.recovering.wait, 1)
        await self.pool.close()
        self.assertFalse(self.pool.recoveries)
        self.assertFalse(self.pool.available)


if __name__ == "__main__": unittest.main()
