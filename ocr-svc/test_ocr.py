import io
import json
from types import SimpleNamespace
import unittest
import weakref
from unittest.mock import patch

from model_runtime.discovery import Registration

import httpx
from PIL import Image

import app
from engine import recognize_image


class ImageTests(unittest.TestCase):
    def test_ten_4k_images_release_pixels_between_inferences(self):
        data = io.BytesIO()
        with Image.new("RGB", (3840, 2160), "white") as image:
            image.save(data, format="PNG")
        references = []

        class Model:
            def predict_iter(self, pixels):
                self_test.assertTrue(all(ref() is None for ref in references))
                self_test.assertEqual(pixels.shape, (2160, 3840, 3))
                references.append(weakref.ref(pixels))
                yield {"rec_texts": ["第一行", "第二行"]}

        self_test = self
        transport = httpx.MockTransport(lambda request: httpx.Response(200, content=data.getvalue()))
        with httpx.Client(transport=transport) as client:
            for index in range(10):
                self.assertEqual(recognize_image(Model(), client, f"http://test/{index}"), "第一行\n第二行")
                self.assertIsNone(references[-1]())

    def test_empty_text_is_success_but_download_and_decode_errors_propagate(self):
        data = io.BytesIO()
        with Image.new("RGB", (16, 16)) as image:
            image.save(data, format="PNG")
        model = SimpleNamespace(predict_iter=lambda pixels: iter([{"rec_texts": []}]))
        responses = [httpx.Response(200, content=data.getvalue()), httpx.Response(500),
                     httpx.Response(200, content=b"invalid image")]
        with httpx.Client(transport=httpx.MockTransport(lambda request: responses.pop(0))) as client:
            self.assertEqual(recognize_image(model, client, "http://test/empty"), "")
            with self.assertRaises(httpx.HTTPStatusError):
                recognize_image(model, client, "http://test/failed")
            with self.assertRaises(OSError):
                recognize_image(model, client, "http://test/corrupt")


class BudgetTests(unittest.TestCase):
    def test_download_and_dimensions_are_bounded_before_inference(self):
        data = io.BytesIO()
        with Image.new("RGB", (20, 20)) as image:
            image.save(data, format="PNG")
        model = SimpleNamespace(predict_iter=lambda pixels: self.fail("inference must not run"))
        transport = httpx.MockTransport(lambda request: httpx.Response(200, content=data.getvalue()))
        with httpx.Client(transport=transport) as client:
            with patch.dict("os.environ", {"OCR_MAX_IMAGE_BYTES": "5"}):
                with self.assertRaisesRegex(ValueError, "byte budget"):
                    recognize_image(model, client, "http://test/large")
            with patch.dict("os.environ", {"OCR_MAX_IMAGE_PIXELS": "399"}):
                with self.assertRaisesRegex(ValueError, "dimension budget"):
                    recognize_image(model, client, "http://test/pixels")
            with patch.dict("os.environ", {"OCR_MAX_IMAGE_EDGE": "19"}):
                with self.assertRaisesRegex(ValueError, "dimension budget"):
                    recognize_image(model, client, "http://test/edge")


class HealthTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        with patch.dict("os.environ", {}, clear=True):
            app.app.state.discovery = Registration("ocr-health-test", 8088)
        app.app.state.discovery.set_registered(True)

    async def test_restarting_or_dead_worker_is_not_ready(self):
        worker = SimpleNamespace(ready=True, process=SimpleNamespace(is_alive=lambda: True))
        app.app.state.pool = SimpleNamespace(accepting=True, workers=[worker], admitted=1)
        self.assertEqual((await app.health()).status_code, 200)
        worker.ready = False
        response = await app.health()
        self.assertEqual(response.status_code, 503)
        self.assertFalse(json.loads(response.body)["ready"])
        worker.ready = True
        worker.process.is_alive = lambda: False
        self.assertEqual((await app.health()).status_code, 503)
        worker.process.is_alive = lambda: True
        self.assertEqual((await app.health()).status_code, 200)

    async def test_discovery_failure_and_recovery_change_readiness(self):
        worker = SimpleNamespace(ready=True, process=SimpleNamespace(is_alive=lambda: True))
        app.app.state.pool = SimpleNamespace(accepting=True, workers=[worker], admitted=0)
        app.app.state.discovery.set_registered(False)
        response = await app.health()
        self.assertEqual(response.status_code, 503)
        self.assertEqual(json.loads(response.body)["discovery"], {"enabled": True, "registered": False})
        app.app.state.discovery.set_registered(True)
        self.assertEqual((await app.health()).status_code, 200)
        app.app.state.discovery.enabled = False
        app.app.state.discovery.set_registered(False)
        self.assertEqual((await app.health()).status_code, 200)


if __name__ == "__main__":
    unittest.main()
