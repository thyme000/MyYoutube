"""模型服务共用的 HTTP 指标，不使用高基数标签。"""
from time import perf_counter

from prometheus_client import Counter, Histogram


REQUESTS = Counter("model_http_requests", "Model HTTP requests", ["method", "status"])
DURATION = Histogram("model_http_request_duration_seconds", "Model HTTP request duration", ["method", "status"])


class HttpMetricsMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] in ("/health", "/metrics", "/metrics/"):
            return await self.app(scope, receive, send)
        started = perf_counter()
        status = 500

        async def observe_send(message):
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
            await send(message)

        try:
            return await self.app(scope, receive, observe_send)
        finally:
            labels = (scope["method"], str(status))
            REQUESTS.labels(*labels).inc()
            DURATION.labels(*labels).observe(perf_counter() - started)
