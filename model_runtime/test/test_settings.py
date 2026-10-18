import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from model_runtime.settings import SERVICES, load_settings
from model_runtime.test.fixtures import service_env


class SettingsTests(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, {}, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()

    def test_own_file_overrides_parent_and_paths_are_service_relative(self):
        service_env(self.directory, extra="ASR_CONCURRENCY=2\nSERVER_PORT=18089\n")
        os.environ.update(ASR_CONCURRENCY="8", SERVER_PORT="8086", NACOS_ENABLED="true", ASR_LANGUAGE="en")
        settings = load_settings(SERVICES["asr"], self.directory)
        self.assertEqual(settings.pool.concurrency, 2)
        self.assertEqual(settings.port, 18089)
        self.assertEqual(settings.engine.language, "zh")
        self.assertEqual(settings.engine.model, self.directory / "speech.bin")
        self.assertEqual(settings.log_directory, self.directory / "logs")
        self.assertEqual(os.environ["NACOS_ENABLED"], "false")
        self.assertNotIn("ASR_LANGUAGE", os.environ)

    def test_parent_model_cannot_fill_missing_required_file_configuration(self):
        service_env(self.directory)
        (self.directory / ".env").write_text("ASR_VAD_MODEL=vad.bin\n")
        os.environ["ASR_MODEL"] = str(self.directory / "speech.bin")
        with self.assertRaisesRegex(ValueError, "ASR_MODEL must be explicitly set"):
            load_settings(SERVICES["asr"], self.directory)

    def test_missing_empty_model_and_invalid_budgets_fail_before_model_load(self):
        with self.assertRaisesRegex(ValueError, "Missing service configuration"):
            load_settings(SERVICES["asr"], self.directory)
        for value in ("0", "-1", "nan"):
            service_env(self.directory, extra=f"ASR_CONCURRENCY={value}\n")
            with self.assertRaises(ValueError):
                load_settings(SERVICES["asr"], self.directory)
        service_env(self.directory)
        (self.directory / "speech.bin").write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "ASR_MODEL.*nonempty"):
            load_settings(SERVICES["asr"], self.directory)

    def test_ocr_requires_each_enabled_model_and_never_selects_a_default(self):
        service_env(self.directory, "ocr")
        settings = load_settings(SERVICES["ocr"], self.directory)
        self.assertEqual(settings.engine.detection, self.directory / "det")
        (self.directory / "orientation/inference.json").unlink()
        with self.assertRaisesRegex(ValueError, "OCR_TEXTLINE_ORIENTATION_MODEL_DIR"):
            load_settings(SERVICES["ocr"], self.directory)
        with (self.directory / ".env").open("a") as file:
            file.write("OCR_USE_TEXTLINE_ORIENTATION=false\n")
        self.assertIsNone(load_settings(SERVICES["ocr"], self.directory).engine.textline_orientation)

    def test_configuration_is_immutable_and_not_reread_per_request(self):
        service_env(self.directory)
        settings = load_settings(SERVICES["asr"], self.directory)
        service_env(self.directory, extra="ASR_LANGUAGE=en\n")
        self.assertEqual(settings.engine.language, "zh")
        with self.assertRaises(ValueError):
            settings.engine.language = "en"

    def test_discovery_and_tracing_budgets_are_validated_before_startup(self):
        for line in ("NACOS_OPERATION_TIMEOUT=nan", "NACOS_CHECK_INTERVAL=0", "MODEL_TRACE_SAMPLE_PER_3_SECS=-1"):
            service_env(self.directory, extra=line + "\n")
            with self.assertRaises(ValueError):
                load_settings(SERVICES["asr"], self.directory)

    def test_missing_ocr_metadata_is_a_configuration_error(self):
        service_env(self.directory, "ocr")
        (self.directory / "det/inference.yml").unlink()
        with self.assertRaisesRegex(ValueError, "OCR_DETECTION_MODEL_DIR"):
            load_settings(SERVICES["ocr"], self.directory)

    def test_ignored_parent_tracing_configuration_warns_without_exposing_values(self):
        service_env(self.directory)
        os.environ.update(PIPELINE_TRACING_ENABLED="true", NACOS_PASSWORD="private-fixture-value")
        with self.assertLogs("model_runtime.settings", level="WARNING") as captured:
            settings = load_settings(SERVICES["asr"], self.directory)
        self.assertFalse(settings.tracing.enabled)
        message = " ".join(captured.output)
        self.assertIn("PIPELINE_TRACING_ENABLED", message)
        self.assertIn(str(self.directory / ".env"), message)
        self.assertNotIn("private-fixture-value", message)
