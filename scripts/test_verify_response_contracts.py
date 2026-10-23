import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from verify_response_contracts import ProbeError, probe


class ContractProbeTest(unittest.TestCase):
    def setUp(self):
        self.reply = (200, {"code": 200, "message": "ok", "data": {"items": [], "nextCursor": None}})
        self.requests = []
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                fixture.requests.append((self.command, self.headers.get("Authorization")))
                status, payload = fixture.reply
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                if status == 302:
                    self.send_header("Location", "/redirected")
                self.end_headers()
                self.wfile.write(json.dumps(payload).encode())

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_success_and_error_use_get_and_keep_tokens_out_of_output(self):
        probe(self.base, "/", "fixture-token", 200, 200, "cursor")
        self.reply = (403, {"code": 40301, "message": "forbidden", "data": None})
        probe(self.base, "/", None, 403, 40301)
        self.assertEqual([("GET", "Bearer fixture-token"), ("GET", None)], self.requests)

    def test_old_wrappers_wrong_status_and_invalid_pages_fail(self):
        for reply in [(200, {"items": []}), (200, {"code": 200, "message": "ok", "data": {"items": [], "nextCursor": 42}}),
                      (200, {"code": 200, "message": "ok", "data": {"code": 200, "data": {}}}),
                      (503, {"code": 50301, "message": "unavailable", "data": None})]:
            self.reply = reply
            with self.assertRaises(ProbeError):
                probe(self.base, "/", None, 200, 200, "cursor")

    def test_page_counts_accept_existing_long_string_serialization(self):
        self.reply = (200, {"code": 200, "message": "ok", "data": {"list": [], "total": "12", "pages": 3}})
        probe(self.base, "/", None, 200, 200, "page")
        self.reply[1]["data"]["total"] = False
        with self.assertRaises(ProbeError):
            probe(self.base, "/", None, 200, 200, "page")

    def test_redirect_is_not_followed_with_credentials(self):
        self.reply = (302, {})
        with self.assertRaises(ProbeError):
            probe(self.base, "/", "fixture-token", 200, 200)
        self.assertEqual(1, len(self.requests))


if __name__ == "__main__":
    unittest.main()
