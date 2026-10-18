import unittest

from prometheus_client import REGISTRY

from model_runtime.monitoring import HttpMetricsMiddleware


class HttpMetricsTests(unittest.IsolatedAsyncioTestCase):
    async def test_records_status_duration_and_error_without_path_label(self):
        async def app(scope, receive, send):
            if scope["path"] == "/fail":
                raise RuntimeError("failed")
            await send({"type": "http.response.start", "status": 202, "headers": []})

        middleware = HttpMetricsMiddleware(app)

        async def send(_message):
            pass

        async def receive():
            return {"type": "http.request", "body": b"", "more_body": False}

        before = REGISTRY.get_sample_value("model_http_requests_total", {"method": "POST", "status": "202"}) or 0
        await middleware({"type": "http", "path": "/ocr/batch", "method": "POST"}, receive, send)
        after = REGISTRY.get_sample_value("model_http_requests_total", {"method": "POST", "status": "202"})
        self.assertEqual(after, before + 1)
        self.assertEqual(REGISTRY.get_sample_value("model_http_request_duration_seconds_count",
                                                   {"method": "POST", "status": "202"}), after)

        before_error = REGISTRY.get_sample_value("model_http_requests_total", {"method": "POST", "status": "500"}) or 0
        with self.assertRaises(RuntimeError):
            await middleware({"type": "http", "path": "/fail", "method": "POST"}, receive, send)
        self.assertEqual(REGISTRY.get_sample_value("model_http_requests_total", {"method": "POST", "status": "500"}),
                         before_error + 1)

        await middleware({"type": "http", "path": "/health", "method": "POST"}, receive, send)
        self.assertEqual(REGISTRY.get_sample_value("model_http_requests_total", {"method": "POST", "status": "202"}), after)


if __name__ == "__main__":
    unittest.main()
