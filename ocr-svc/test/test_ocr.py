import io
import json
import os
from types import SimpleNamespace
import unittest
import weakref
from unittest.mock import patch
from pathlib import Path
from model_runtime.settings import OcrSettings

from model_runtime.discovery import Registration

import httpx
from PIL import Image

import app
from model_runtime.pool import BoundedPool


def health_pool(worker, admitted=0):
    pool = BoundedPool("health-test", lambda: worker, 1, 4, 60)
    pool.workers = [worker]
    pool.accepting = True
    pool.admitted = admitted
    return pool
from engine import recognize_image
from engine import model_options
from model_runtime.test.fixtures import service_env
from model_runtime.settings import SERVICES, load_settings
import tempfile


class ImageTests(unittest.TestCase):
    def setUp(self):
        self.config = OcrSettings(detection=Path('.'), recognition=Path('.'))

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
                self.assertEqual(recognize_image(Model(), client, f"http://test/{index}", self.config), "第一行\n第二行")
                self.assertIsNone(references[-1]())

    def test_empty_text_is_success_but_download_and_decode_errors_propagate(self):
        data = io.BytesIO()
        with Image.new("RGB", (16, 16)) as image:
            image.save(data, format="PNG")
        model = SimpleNamespace(predict_iter=lambda pixels: iter([{"rec_texts": []}]))
        responses = [httpx.Response(200, content=data.getvalue()), httpx.Response(500),
                     httpx.Response(200, content=b"invalid image")]
        with httpx.Client(transport=httpx.MockTransport(lambda request: responses.pop(0))) as client:
            self.assertEqual(recognize_image(model, client, "http://test/empty", self.config), "")
            with self.assertRaises(httpx.HTTPStatusError):
                recognize_image(model, client, "http://test/failed", self.config)
            with self.assertRaises(OSError):
                recognize_image(model, client, "http://test/corrupt", self.config)


class ModelConfigurationTests(unittest.TestCase):
    def test_all_enabled_modules_receive_explicit_local_models(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            service_env(directory, "ocr")
            settings = load_settings(SERVICES["ocr"], directory)
            options = model_options(settings.engine)
            self.assertEqual(options["text_detection_model_name"], "test_det")
            self.assertEqual(options["text_recognition_model_name"], "test_rec")
            self.assertEqual(Path(options["text_detection_model_dir"]), Path(directory).resolve() / "det")
            self.assertTrue(options["use_textline_orientation"])
            self.assertFalse(options["use_doc_orientation_classify"])
            self.assertFalse(options["use_doc_unwarping"])
            self.assertNotIn("lang", options)


class BudgetTests(unittest.TestCase):
    def setUp(self):
        self.config = OcrSettings(detection=Path('.'), recognition=Path('.'))

    def test_download_and_dimensions_are_bounded_before_inference(self):
        data = io.BytesIO()
        with Image.new("RGB", (20, 20)) as image:
            image.save(data, format="PNG")
        model = SimpleNamespace(predict_iter=lambda pixels: self.fail("inference must not run"))
        transport = httpx.MockTransport(lambda request: httpx.Response(200, content=data.getvalue()))
        with httpx.Client(transport=transport) as client:
            with patch.object(self, "config", self.config.model_copy(update={"max_image_bytes": 5})):
                with self.assertRaisesRegex(ValueError, "byte budget"):
                    recognize_image(model, client, "http://test/large", self.config)
            with patch.object(self, "config", self.config.model_copy(update={"max_image_pixels": 399})):
                with self.assertRaisesRegex(ValueError, "dimension budget"):
                    recognize_image(model, client, "http://test/pixels", self.config)
            with patch.object(self, "config", self.config.model_copy(update={"max_image_edge": 19})):
                with self.assertRaisesRegex(ValueError, "dimension budget"):
                    recognize_image(model, client, "http://test/edge", self.config)


class HealthTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        with patch.dict("os.environ", {}, clear=True):
            app.app.state.discovery = Registration("ocr-health-test", 8088)
        app.app.state.discovery.set_registered(True)

    async def test_restarting_or_dead_worker_is_not_ready(self):
        worker = type("Worker", (), {"ready": True, "process": SimpleNamespace(is_alive=lambda: True)})()
        app.app.state.pool = health_pool(worker, 1)
        initial = await app.health()
        self.assertEqual(initial.status_code, 200)
        self.assertEqual(json.loads(initial.body)["pid"], os.getpid())
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
        worker = type("Worker", (), {"ready": True, "process": SimpleNamespace(is_alive=lambda: True)})()
        app.app.state.pool = health_pool(worker, 0)
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
