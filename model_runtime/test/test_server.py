import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, patch

from model_runtime.server import main, ModelServer
from model_runtime.test.fixtures import service_env


class ServerTests(unittest.TestCase):
    def test_settings_are_loaded_before_application_import_and_actual_port_is_shared(self):
        with tempfile.TemporaryDirectory() as directory:
            service_env(directory, 'ocr', 'SERVER_PORT=18088\n')
            application = SimpleNamespace(state=SimpleNamespace())
            server = Mock()
            previous, search_path = Path.cwd(), sys.path[:]
            def imported(name):
                self.assertEqual(os.environ['SERVER_PORT'], '18088')
                return SimpleNamespace(app=application)
            try:
                with patch.dict(os.environ, {'SERVER_PORT': '8086'}, clear=True), \
                     patch('sys.argv', ['server', '--service', 'ocr', '--directory', directory]), \
                     patch('model_runtime.server.import_module', side_effect=imported), \
                     patch('model_runtime.server.uvicorn.Config') as config, \
                     patch('model_runtime.server.ModelServer', return_value=server), \
                     patch('model_runtime.lifecycle.server_record') as record:
                    main()
                    self.assertEqual(config.call_args.kwargs['port'], 18088)
                    self.assertEqual(application.state.settings.port, 18088)
                    self.assertEqual(record.call_args.args[2], 18088)
                    server.run.assert_called_once()
                    self.assertTrue(Path(directory, 'logs/ocr-svc.log').is_file())
            finally:
                os.chdir(previous)
                sys.path[:] = search_path

    def test_missing_models_fail_before_importing_application(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, '.env').write_text('SERVER_PORT=18088\n')
            with patch('sys.argv', ['server', '--service', 'asr', '--directory', directory]), \
                 patch('model_runtime.server.import_module') as imported:
                with self.assertRaisesRegex(ValueError, 'ASR_MODEL'):
                    main()
                imported.assert_not_called()


class ShutdownTests(unittest.IsolatedAsyncioTestCase):
    async def test_pool_admission_stops_before_uvicorn_drains_http_requests(self):
        pool = SimpleNamespace(stop_accepting=AsyncMock())
        application = SimpleNamespace(state=SimpleNamespace(pool=pool))
        server = ModelServer(SimpleNamespace(app=application))

        async def drain(sockets=None):
            pool.stop_accepting.assert_awaited_once()

        with patch('model_runtime.server.uvicorn.Server.shutdown', new=AsyncMock(side_effect=drain)) as shutdown:
            await server.shutdown()
            shutdown.assert_awaited_once_with(sockets=None)
