import asyncio
import time
from fastapi import HTTPException
from prometheus_client import Counter, Gauge, Histogram

RUNNING = Gauge("model_running", "Active model executions", ["service"])
QUEUED = Gauge("model_queue_depth", "Admitted requests waiting for a worker", ["service"])
REJECTED = Counter("model_overload", "Capacity rejections", ["service", "reason"])
WAIT = Histogram("model_wait_seconds", "Queue wait time", ["service"])
DURATION = Histogram("model_execution_seconds", "Model execution time", ["service"])


class BoundedPool:
    def __init__(self, service, factory, concurrency, queue_size, queue_timeout):
        self.service, self.factory = service, factory
        self.concurrency, self.queue_size, self.queue_timeout = concurrency, queue_size, queue_timeout
        self.slots = asyncio.Queue()
        self.workers, self.jobs = [], set()
        self.admitted, self.running = 0, 0
        self.accepting = False

    async def start(self):
        try:
            for _ in range(self.concurrency):
                worker = await asyncio.to_thread(self.factory)
                self.workers.append(worker)
                self.slots.put_nowait(worker)
            self.accepting = True
        except BaseException:
            await self.close()
            raise

    def update_metrics(self):
        RUNNING.labels(self.service).set(self.running)
        QUEUED.labels(self.service).set(self.admitted - self.running)

    async def run(self, request):
        if not self.accepting or self.admitted >= self.concurrency + self.queue_size:
            REJECTED.labels(self.service, "full").inc()
            raise HTTPException(503, "Model queue is full", headers={"Retry-After": "30"})
        self.admitted += 1
        self.update_metrics()
        started = time.monotonic()
        try:
            worker = await asyncio.wait_for(self.slots.get(), self.queue_timeout)
        except BaseException as error:
            self.admitted -= 1
            self.update_metrics()
            if isinstance(error, TimeoutError):
                REJECTED.labels(self.service, "timeout").inc()
                raise HTTPException(503, "Model queue wait timed out", headers={"Retry-After": "30"}) from error
            raise
        if not self.accepting:
            self.admitted -= 1
            self.slots.put_nowait(worker)
            self.update_metrics()
            raise HTTPException(503, "Model pool is shutting down", headers={"Retry-After": "30"})
        WAIT.labels(self.service).observe(time.monotonic() - started)
        self.running += 1
        self.update_metrics()
        started = time.monotonic()
        job = asyncio.create_task(asyncio.to_thread(worker.run, request))
        self.jobs.add(job)

        def release(completed):
            self.jobs.discard(completed)
            self.running -= 1
            self.admitted -= 1
            self.slots.put_nowait(worker)
            self.update_metrics()
            DURATION.labels(self.service).observe(time.monotonic() - started)
            if not completed.cancelled():
                completed.exception()

        job.add_done_callback(release)
        try:
            return await asyncio.shield(job)
        except RuntimeError as error:
            raise HTTPException(502, str(error)) from error

    async def close(self):
        self.accepting = False
        if self.jobs:
            await asyncio.gather(*self.jobs, return_exceptions=True)
        for worker in self.workers:
            await asyncio.to_thread(worker.stop)
        self.workers.clear()
