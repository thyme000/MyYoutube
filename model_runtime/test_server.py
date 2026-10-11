import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from model_runtime.server import main


class ServerTests(unittest.TestCase):
    def launch(self, directory, environment):
        with patch.dict(os.environ, environment, clear=True), \
             patch("sys.argv", ["server", "--default-port", "8088"]), \
             patch("model_runtime.server.uvicorn.run") as run:
            previous = Path.cwd()
            try:
                os.chdir(directory)
                main()
                config = dict(os.environ)
            finally:
                os.chdir(previous)
            return run.call_args.kwargs, config

    def test_env_file_configures_listener_and_discovery(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, ".env").write_text("SERVER_PORT=18088\nNACOS_INSTANCE_IP=10.0.0.8\nNACOS_ENABLED=true\n")
            options, config = self.launch(directory, {})
            self.assertEqual(options, {"host": "0.0.0.0", "port": 18088, "workers": 1})
            self.assertEqual(config["NACOS_INSTANCE_IP"], "10.0.0.8")
            self.assertEqual(config["NACOS_ENABLED"], "true")

    def test_process_environment_takes_precedence(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, ".env").write_text("SERVER_PORT=18088\nNACOS_ENABLED=true\n")
            options, config = self.launch(directory, {"SERVER_PORT": "28088", "NACOS_ENABLED": "false"})
            self.assertEqual(options["port"], 28088)
            self.assertEqual(config["NACOS_ENABLED"], "false")

    def test_without_env_file_uses_default_port(self):
        with tempfile.TemporaryDirectory() as directory:
            options, _ = self.launch(directory, {})
            self.assertEqual(options["port"], 8088)


if __name__ == "__main__":
    unittest.main()
