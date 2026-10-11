"""Run against an already started OCR instance; uses real model inference and HTTP."""
import asyncio
import io
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import httpx
from PIL import Image


def main():
    image = io.BytesIO()
    Image.new("RGB", (320, 160), "white").save(image, format="PNG")
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self):
            time.sleep(2)
            content = image.getvalue()
            self.send_response(200)
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{server.server_port}/blank.png"
    base = os.getenv("OCR_CHECK_URL", "http://127.0.0.1:18088")
    async def check():
        async with httpx.AsyncClient(timeout=120) as client:
            requests = [asyncio.create_task(client.post(base + "/ocr/batch", json={"urls": [url] * 4}))]
            await asyncio.sleep(0.2)
            requests += [asyncio.create_task(client.post(base + "/ocr", params={"url": url})) for _ in range(4)]
            for _ in range(50):
                health = (await client.get(base + "/health")).json()
                if health["admitted"] == 5: break
                await asyncio.sleep(0.05)
            assert health["admitted"] == 5, health
            rejected = await client.post(base + "/ocr/batch", json={"urls": [url]})
            assert rejected.status_code == 503, rejected.text
            assert rejected.headers["Retry-After"] == "30"
            responses = await asyncio.gather(*requests)
            assert all(r.status_code == 200 for r in responses), [(r.status_code, r.text) for r in responses]
            assert all(item["text"] == "" for item in responses[0].json()["results"])
            assert all(r.json()["text"] == "" for r in responses[1:])
            metrics = (await client.get(base + "/metrics/")).text
            assert 'model_overload_total{reason="full",service="ocr"} 1.0' in metrics
            assert (await client.get(base + "/health")).json()["admitted"] == 0
            print("LIVE OCR: shared single/batch capacity=1+4, HTTP 503 + Retry-After, 8 legal empty frame results, recovered to admitted=0")
    try: asyncio.run(check())
    finally: server.shutdown(); server.server_close()


if __name__ == "__main__": main()
