import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from service_targets import diagnose_logs


class LogDiagnosticsTests(unittest.TestCase):
    def test_missing_empty_and_present_sources_are_distinct(self):
        services = [{"name": "video", "service": "my-youtube-video-service", "kind": "java"}]
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"OBSERVABILITY_LOG_DIRECTORY": directory}):
            file = Path(directory).resolve() / "my-youtube-video-service.log"
            self.assertEqual(diagnose_logs(services, {"video"}), [("video", "MISSING", str(file))])
            file.touch()
            self.assertEqual(diagnose_logs(services, {"video"})[0][1], "EMPTY")
            file.write_text("{}\n")
            self.assertEqual(diagnose_logs(services, {"video"})[0][1], "SOURCE_PRESENT")
            self.assertEqual(diagnose_logs(services, set()), [])

    def test_custom_python_mount_and_ingest_filename(self):
        services = [{"name": "asr", "service": "asr-svc", "kind": "model"},
                    {"name": "observability", "service": "my-youtube-observability", "kind": "java"}]
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ,
                {"ASR_LOG_DIRECTORY": directory, "OBSERVABILITY_LOG_DIRECTORY": directory}):
            Path(directory, "asr-svc.log").write_text("{}\n")
            Path(directory, "ingest.log").write_text("{}\n")
            self.assertEqual([row[1] for row in diagnose_logs(services, {"asr", "observability"})],
                             ["SOURCE_PRESENT", "SOURCE_PRESENT"])
