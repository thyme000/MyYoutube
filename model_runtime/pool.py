import asyncio
import contextvars
import logging
import time

from fastapi import HTTPException
from prometheus_client import Counter, Gauge, Histogram

from model_runtime.process import WorkerUnavailable

RUNNING = Gauge("model_running", "Active model executions", ["service"])
QUEUED = Gauge("model_queue_depth", "Admitted requests waiting for a worker", ["service"])
CAPACITY = Gauge("model_queue_capacity", "Configured waiting queue capacity", ["service"])
HEALTHY = Gauge("model_healthy_workers", "Loaded and live model workers", ["service"])
RECOVERING = Gauge("model_recovering_workers", "Workers being recovered", ["service"])
FAILED = Gauge("model_failed_workers", "Workers that exhausted recovery", ["service"])
RECOVERY = Counter("model_worker_recovery", "Worker recovery results", ["service", "result"])
REJECTED = Counter("model_overload", "Capacity rejections", ["service", "reason"])
WAIT = Histogram("model_wait_seconds", "Queue wait time", ["service"])
DURATION = Histogram("model_execution_seconds", "Parent execution wrapper excluding worker recovery", ["service"])
logger = logging.getLogger(__name__)


class BoundedPool:
    def __init__(self, service, factory, concurrency, queue_size, queue_timeout,
                 recovery_attempts=3, recovery_backoff=1, recovery_max_delay=30):
        self.service, self.factory = service, factory
        self.concurrency, self.queue_size, self.queue_timeout = concurrency, queue_size, queue_timeout
        self.recovery_attempts, self.recovery_backoff = recovery_attempts, recovery_backoff
        self.recovery_max_delay = recovery_max_delay
        self.workers, self.available = [], []
        self.jobs, self.busy, self.failed = set(), set(), set()
        self.recoveries = {}
        self.changed = asyncio.Condition()
        self.closing = asyncio.Event()
        self.monitor = None
        self.admitted, self.running = 0, 0
        self.accepting = False

    @staticmethod
    def healthy(worker):
        return getattr(worker, "ready", True) and (not hasattr(worker, "process") or worker.process.is_alive())

    def healthy_count(self):
        return sum(self.healthy(worker) and worker not in self.failed
                   and worker not in self.recoveries for worker in self.workers)

    async def start(self):
        try:
            for _ in range(self.concurrency):
                worker = await asyncio.to_thread(self.factory)
                self.workers.append(worker)
                self.available.append(worker)
            self.accepting = True
            self.update_metrics()
            self.monitor = asyncio.create_task(self._monitor(), name=f"{self.service}-worker-monitor")
        except BaseException:
            await self.close()
            raise

    def update_metrics(self):
        RUNNING.labels(self.service).set(self.running)
        QUEUED.labels(self.service).set(self.admitted - self.running)
        CAPACITY.labels(self.service).set(self.queue_size)
        HEALTHY.labels(self.service).set(self.healthy_count())
        RECOVERING.labels(self.service).set(len(self.recoveries))
        FAILED.labels(self.service).set(len(self.failed))

    def reject(self, code):
        REJECTED.labels(self.service, code.lower()).inc()
        return HTTPException(503, {"code": code}, headers={"Retry-After": "30"})

    def _check_available(self):
        if not self.accepting:
            raise self.reject("MODEL_SHUTTING_DOWN")
        if not self.healthy_count():
            raise self.reject("MODEL_RECOVERY_EXHAUSTED" if len(self.failed) == len(self.workers)
                              else "MODEL_RECOVERING")

    def _schedule_recovery(self, worker):
        if not self.accepting or worker in self.recoveries or worker in self.failed:
            return
        if worker in self.available:
            self.available.remove(worker)
        self.recoveries[worker] = asyncio.create_task(self._recover(worker), context=contextvars.Context())

    async def _recover(self, worker):
        try:
            for attempt in range(self.recovery_attempts):
                if self.closing.is_set():
                    return
                try:
                    await asyncio.to_thread(worker.recover)
                    if not self.healthy(worker):
                        raise RuntimeError("Recovered worker is not ready")
                except Exception as error:
                    RECOVERY.labels(self.service, "failure").inc()
                    logger.warning("Model recovery failed service=%s attempt=%s error=%s",
                                   self.service, attempt + 1, type(error).__name__)
                    if attempt + 1 < self.recovery_attempts:
                        delay = min(self.recovery_max_delay, self.recovery_backoff * 2 ** attempt)
                        try:
                            await asyncio.wait_for(self.closing.wait(), delay)
                            return
                        except TimeoutError:
                            continue
                else:
                    RECOVERY.labels(self.service, "success").inc()
                    return
            self.failed.add(worker)
            logger.error("Model recovery exhausted service=%s", self.service)
        finally:
            async with self.changed:
                self.recoveries.pop(worker, None)
                if self.accepting and worker not in self.failed and self.healthy(worker):
                    self.available.append(worker)
                self.update_metrics()
                self.changed.notify_all()

    async def _monitor(self):
        while not self.closing.is_set():
            async with self.changed:
                for worker in self.workers:
                    if worker not in self.busy and not self.healthy(worker):
                        self._schedule_recovery(worker)
                self.update_metrics()
                self.changed.notify_all()
            try:
                await asyncio.wait_for(self.closing.wait(), 0.25)
            except TimeoutError:
                pass

    async def run(self, request):
        started = time.monotonic()
        async with self.changed:
            self._check_available()
            if self.admitted >= self.healthy_count() + self.queue_size:
                raise self.reject("MODEL_QUEUE_FULL")
            self.admitted += 1
            self.update_metrics()
            try:
                async with asyncio.timeout(self.queue_timeout):
                    while True:
                        self._check_available()
                        while self.available:
                            worker = self.available.pop(0)
                            if self.healthy(worker):
                                break
                            self._schedule_recovery(worker)
                        else:
                            await self.changed.wait()
                            continue
                        break
            except BaseException as error:
                self.admitted -= 1
                self.update_metrics()
                if isinstance(error, TimeoutError):
                    raise self.reject("MODEL_QUEUE_TIMEOUT") from error
                raise
            self.busy.add(worker)
            self.running += 1
            self.update_metrics()
        waited = time.monotonic() - started
        WAIT.labels(self.service).observe(waited)
        logger.info("Model worker acquired service=%s", self.service, extra={"queueWaitSeconds": waited})
        job = asyncio.create_task(self._execute(worker, request))
        self.jobs.add(job)

        def completed(task):
            self.jobs.discard(task)
            if not task.cancelled():
                task.exception()

        job.add_done_callback(completed)
        return await asyncio.shield(job)

    async def _execute(self, worker, request):
        started = time.monotonic()
        try:
            return await asyncio.to_thread(worker.run, request)
        except WorkerUnavailable as error:
            raise self.reject("MODEL_RECOVERING") from error
        except RuntimeError as error:
            raise HTTPException(502, str(error)) from error
        finally:
            async with self.changed:
                self.busy.discard(worker)
                self.running -= 1
                self.admitted -= 1
                if self.healthy(worker):
                    self.available.append(worker)
                else:
                    self._schedule_recovery(worker)
                self.update_metrics()
                self.changed.notify_all()
            DURATION.labels(self.service).observe(time.monotonic() - started)

    async def stop_accepting(self):
        async with self.changed:
            self.accepting = False
            self.closing.set()
            for worker in self.workers:
                if hasattr(worker, "request_stop"):
                    worker.request_stop()
            self.changed.notify_all()

    async def close(self):
        await self.stop_accepting()
        if self.monitor is not None:
            await self.monitor
        if self.jobs:
            await asyncio.gather(*self.jobs, return_exceptions=True)
        if self.recoveries:
            await asyncio.gather(*self.recoveries.values(), return_exceptions=True)
        for worker in self.workers:
            await asyncio.to_thread(worker.stop)
        self.workers.clear()
        self.available.clear()
        self.failed.clear()
        self.update_metrics()
