import io
import logging
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from model_runtime.logs import PrivateFormatter, PipeLogHandler, configure_logging


class LogTests(unittest.TestCase):
    def test_child_pipe_removes_urls_before_forwarding_to_parent(self):
        sent = []
        connection = type("Connection", (), {"send": lambda _, item: sent.append(item)})()
        handler = PipeLogHandler(connection)
        handler.handle(logging.LogRecord("worker", logging.ERROR, "", 1,
                                         "download failed %s", ("https://storage/key?X-Amz-Signature=secret",), None))
        self.assertEqual(sent[0][0], logging.ERROR)
        self.assertIn("[redacted-url]", sent[0][1])
        self.assertNotIn("secret", sent[0][1])

    def test_rotation_and_console_share_redacted_formatter(self):
        root = logging.getLogger()
        original, level = root.handlers[:], root.level
        root.handlers = []
        try:
            with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", {"MODEL_LOG_DIRECTORY": directory}), \
                    patch("sys.stderr", new_callable=io.StringIO) as console:
                configure_logging("test")
                root.warning("source https://storage/key?signature=secret status=500")
                for handler in root.handlers:
                    handler.flush()
                text = next(Path(directory).glob("test.log")).read_text()
                self.assertIn("[redacted-url]", text)
                self.assertNotIn("secret", text)
                self.assertIn("[redacted-url]", console.getvalue())
                file = root.handlers[1]
                self.assertEqual(file.maxBytes, 10 * 1024 * 1024)
                self.assertEqual(file.backupCount, 3)
        finally:
            for handler in root.handlers:
                handler.close()
            root.handlers, root.level = original, level


if __name__ == "__main__":
    unittest.main()
