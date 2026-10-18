"""ASGI 与 spawn IPC 可选的 SkyWalking 原生载体桥接。"""
from contextlib import contextmanager
from importlib.metadata import version
import os


def enabled(settings=None):
    return settings.enabled if settings is not None else os.getenv("PIPELINE_TRACING_ENABLED", "false").lower() == "true"


def bootstrap(service, settings=None):
    if not enabled(settings):
        return
    if version("apache-skywalking") != "1.3.0":
        raise RuntimeError("Python tracing requires apache-skywalking 1.3.0")
    from skywalking.agent import agent
    if agent.started():
        return
    from skywalking import config
    config.init(agent_name=service, agent_instance_name=f"{service}-{os.getpid()}",
                agent_collector_backend_services=settings.collector if settings is not None else os.getenv("SW_AGENT_COLLECTOR_BACKEND_SERVICES", "127.0.0.1:11800"),
                sample_n_per_3_secs=settings.samples if settings is not None else int(os.getenv("MODEL_TRACE_SAMPLE_PER_3_SECS", "10")),
                agent_log_reporter_active=False, agent_meter_reporter_active=False,
                agent_disable_plugins=["sw_httpx", "sw_fastapi", "sw_logging"],
                agent_trace_ignore_path="/health,/metrics,/metrics/")
    agent.start()


def shutdown():
    if enabled():
        from skywalking.agent import agent
        if agent.started():
            agent.stop()


def carrier(values):
    from skywalking.trace.carrier import Carrier
    result = Carrier()
    value = values.get("sw8", "")
    if isinstance(value, str) and len(value) <= 4096:
        try:
            result.val = value
        except (ValueError, UnicodeError):
            return Carrier()
    return result


@contextmanager
def entry(operation, values):
    if not enabled():
        yield None
        return
    from skywalking.trace.context import get_context
    span = get_context().new_entry_span(operation, carrier=carrier(values))
    span.__enter__()
    try:
        from model_runtime.context import bind, current
        trace_id = str(span.context.segment.related_traces[0])
        with bind(current() | {"traceId": trace_id}):
            yield span
    except BaseException:
        span.error_occurred = True
        raise
    finally:
        # 模型异常消息可能含带签名的素材 URL。
        span.__exit__(None, None, None)


@contextmanager
def ipc_exit():
    if not enabled():
        yield {}
        return
    from skywalking.trace.context import get_context
    span = get_context().new_exit_span("model/ipc/send", peer="model-worker")
    span.__enter__()
    try:
        yield {item.key: item.val for item in span.inject() if item.key == "sw8"}
    except BaseException:
        span.error_occurred = True
        raise
    finally:
        span.__exit__(None, None, None)


class TracingMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] in ("/health", "/metrics", "/metrics/"):
            return await self.app(scope, receive, send)
        headers = {key.decode("latin1").lower(): value.decode("latin1") for key, value in scope["headers"]}
        with entry(scope["path"], headers):
            await self.app(scope, receive, send)
