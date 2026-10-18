from contextlib import asynccontextmanager
import multiprocessing
import os
from pathlib import Path
import sys
import logging

from fastapi.responses import JSONResponse

from model_runtime import tracing
from model_runtime.discovery import registration
from model_runtime.pool import BoundedPool
from model_runtime.settings import load_settings


def lifespan_for(identity, worker_type):
    @asynccontextmanager
    async def lifespan(app):
        settings = getattr(app.state, "settings", None) or load_settings(identity)
        app.state.settings = settings
        agent = str(Path(__file__).resolve().parents[1] / "resources" / "skywalking-python-agent")
        if agent not in sys.path:
            sys.path.insert(0, agent)
        tracing.bootstrap(identity.name, settings.tracing)
        logging.getLogger(__name__).info(
            "Model settings source=%s port=%s concurrency=%s queueSize=%s queueTimeout=%s executionTimeout=%s "
            "recoveryAttempts=%s tracingEnabled=%s traceCollector=%s traceSamples=%s",
            settings.directory / ".env", settings.port, settings.pool.concurrency, settings.pool.queue_size,
            settings.pool.queue_timeout, settings.engine.timeout, settings.pool.recovery_attempts,
            settings.tracing.enabled, settings.tracing.collector, settings.tracing.samples)
        pool = BoundedPool(identity.kind, lambda: worker_type(multiprocessing.get_context("spawn"),
                           settings.engine, service=identity.name, tracing_settings=settings.tracing), settings.pool.concurrency,
                           settings.pool.queue_size, settings.pool.queue_timeout, settings.pool.recovery_attempts,
                           settings.pool.recovery_backoff, settings.pool.recovery_max_delay)
        app.state.pool = pool
        try:
            await pool.start()
            async with registration(identity.name, settings.port, fixed_port=True, settings=settings.discovery) as discovery:
                app.state.discovery = discovery
                yield
        finally:
            await pool.close()
            tracing.shutdown()
    return lifespan


def health_response(app):
    pool = app.state.pool
    healthy = pool.healthy_count()
    discovery = app.state.discovery
    ready = pool.accepting and healthy > 0 and discovery.ready
    return JSONResponse({"ready": ready, "pid": os.getpid(), "concurrency": pool.concurrency,
                         "healthyWorkers": healthy, "recoveringWorkers": len(pool.recoveries),
                         "failedWorkers": len(pool.failed), "queueCapacity": pool.queue_size,
                         "admitted": pool.admitted, "discovery": discovery.status()},
                        status_code=200 if ready else 503)
