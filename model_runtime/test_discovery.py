import asyncio
import logging
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from model_runtime.discovery import Registration, registration, REGISTERED


class DiscoveryTests(unittest.IsolatedAsyncioTestCase):
    def client(self, port=8089):
        host = SimpleNamespace(ip="127.0.0.1", port=port, clusterName="DEFAULT",
                               healthy=True, enabled=True, weight=1)
        client = Mock()
        client.logger = logging.getLogger("naming")
        client.register_instance = AsyncMock(return_value=True)
        client.deregister_instance = AsyncMock(return_value=True)
        client.grpc_client_proxy.start = AsyncMock()
        client.grpc_client_proxy.close_client = AsyncMock()
        client.grpc_client_proxy.query_instance_of_service = AsyncMock(return_value=SimpleNamespace(hosts=[host]))
        client.grpc_client_proxy.redo_service._listen_task = asyncio.create_task(asyncio.Event().wait())
        return client, host

    async def until(self, condition):
        async with asyncio.timeout(1):
            while not condition():
                await asyncio.sleep(0.005)

    async def test_register_and_deregister_same_identity(self):
        client, _ = self.client(18088)
        env = {"SERVER_PORT": "18088", "NACOS_INSTANCE_IP": "127.0.0.1"}
        with patch.dict("os.environ", env, clear=True), \
             patch("v2.nacos.NacosNamingService", return_value=client) as constructor:
            async with registration("ocr-svc", 8088) as state:
                self.assertEqual(constructor.call_args.args[0].namespace_id, "public")
                param = client.register_instance.call_args.args[0]
                self.assertEqual((param.service_name, param.ip, param.port), ("ocr-svc", "127.0.0.1", 18088))
                client.deregister_instance.assert_not_called()
                self.assertTrue(state.ready)
            removed = client.deregister_instance.call_args.args[0]
            self.assertEqual((removed.service_name, removed.ip, removed.port,
                              removed.group_name, removed.cluster_name),
                             (param.service_name, param.ip, param.port, param.group_name, param.cluster_name))
            client.grpc_client_proxy.close_client.assert_awaited_once()
            client.service_info_updater.stop.assert_called_once()
            self.assertTrue(client.grpc_client_proxy.redo_service._listen_task.done())
            self.assertTrue(state.task.done())

    async def test_discovery_enabled_by_default_for_both_services(self):
        for service, port in (("asr-svc", 8089), ("ocr-svc", 8088)):
            client, _ = self.client(port)
            with self.subTest(service=service), patch.dict("os.environ", {}, clear=True), \
                 patch("v2.nacos.NacosNamingService", return_value=client):
                async with registration(service, port):
                    param = client.register_instance.call_args.args[0]
                    self.assertEqual((param.service_name, param.ip, param.port), (service, "127.0.0.1", port))
                    self.assertEqual((param.group_name, param.cluster_name), ("DEFAULT_GROUP", "DEFAULT"))

    async def test_explicit_opt_out_skips_discovery_and_remains_ready(self):
        with patch.dict("os.environ", {"NACOS_ENABLED": "false"}, clear=True), \
             patch("v2.nacos.NacosNamingService") as constructor:
            async with registration("ocr-svc", 8088) as state:
                constructor.assert_not_called()
                self.assertTrue(state.ready)
                self.assertEqual(state.status(), {"enabled": False, "registered": False})

    async def test_failed_initial_registration_recovers_without_restarting_models(self):
        client, _ = self.client()
        recovered, _ = self.client()
        client.register_instance.return_value = False
        with patch.dict("os.environ", {}, clear=True), \
             patch("v2.nacos.NacosNamingService", side_effect=[client, recovered]), \
             patch.object(Registration, "_retry_delay", return_value=0.01):
            async with registration("asr-svc", 8089) as state:
                self.assertFalse(state.ready)
                await self.until(lambda: state.client is recovered and state.ready)
        client.grpc_client_proxy.close_client.assert_awaited_once()
        self.assertTrue(client.grpc_client_proxy.redo_service._listen_task.done())

    async def test_startup_timeout_cleans_partial_client_and_recovers(self):
        client, _ = self.client()
        recovered, _ = self.client()
        async def blocked():
            await asyncio.Event().wait()
        client.grpc_client_proxy.start.side_effect = blocked
        with patch.dict("os.environ", {"NACOS_OPERATION_TIMEOUT": "0.02"}, clear=True), \
             patch("v2.nacos.NacosNamingService", side_effect=[client, recovered]), \
             patch.object(Registration, "_retry_delay", return_value=0.01):
            async with registration("asr-svc", 8089) as state:
                self.assertFalse(state.ready)
                await self.until(lambda: state.client is recovered and state.ready)
        client.grpc_client_proxy.close_client.assert_awaited_once()
        self.assertTrue(client.grpc_client_proxy.redo_service._listen_task.done())

    async def test_missing_registration_is_repaired_without_reloading_models(self):
        client, host = self.client()
        client.grpc_client_proxy.query_instance_of_service.side_effect = [
            SimpleNamespace(hosts=[]), SimpleNamespace(hosts=[host])]
        with patch.dict("os.environ", {}, clear=True), patch("v2.nacos.NacosNamingService", return_value=client):
            async with registration("asr-svc", 8089) as state:
                await state._verify()
                self.assertEqual(client.register_instance.await_count, 2)
                self.assertTrue(state.ready)
                client.grpc_client_proxy.query_instance_of_service.assert_awaited_with(
                    "asr-svc", "DEFAULT_GROUP", "DEFAULT", False)

    async def test_operator_disabled_instance_is_not_forced_online(self):
        client, host = self.client()
        host.enabled = False
        with patch.dict("os.environ", {}, clear=True), patch("v2.nacos.NacosNamingService", return_value=client):
            async with registration("asr-svc", 8089) as state:
                await state._verify()
                self.assertFalse(state.ready)
                self.assertEqual(client.register_instance.await_count, 1)

    async def test_disconnect_and_failed_reconnect_eventually_recover(self):
        old, _ = self.client()
        failed, _ = self.client()
        recovered, _ = self.client()
        old.grpc_client_proxy.query_instance_of_service.side_effect = ConnectionError("disconnected")
        failed.register_instance.return_value = False
        env = {"NACOS_CHECK_INTERVAL": "0.01", "NACOS_OPERATION_TIMEOUT": "0.1"}
        with patch.dict("os.environ", env, clear=True), \
             patch("v2.nacos.NacosNamingService", side_effect=[old, failed, recovered]) as constructor, \
             patch.object(Registration, "_retry_delay", return_value=0.01):
            async with registration("asr-svc", 8089) as state:
                await self.until(lambda: old.grpc_client_proxy.close_client.await_count == 1)
                self.assertFalse(state.ready)
                self.assertEqual(REGISTERED.labels("asr-svc")._value.get(), 0)
                await self.until(lambda: state.client is recovered and state.ready)
                self.assertEqual(constructor.call_count, 3)
                failed.grpc_client_proxy.close_client.assert_awaited_once()
                self.assertEqual(REGISTERED.labels("asr-svc")._value.get(), 1)
            for client in (old, failed, recovered):
                self.assertTrue(client.grpc_client_proxy.redo_service._listen_task.done())

    async def test_probe_timeout_recovers_and_shutdown_stops_reconnection(self):
        old, _ = self.client()
        recovered, _ = self.client()
        async def blocked(*args):
            await asyncio.Event().wait()
        old.grpc_client_proxy.query_instance_of_service.side_effect = blocked
        env = {"NACOS_CHECK_INTERVAL": "0.01", "NACOS_OPERATION_TIMEOUT": "0.02"}
        with patch.dict("os.environ", env, clear=True), \
             patch("v2.nacos.NacosNamingService", side_effect=[old, recovered]) as constructor, \
             patch.object(Registration, "_retry_delay", return_value=0.01):
            async with registration("asr-svc", 8089) as state:
                await self.until(lambda: state.client is recovered and state.ready)
            await asyncio.sleep(0.03)
            self.assertEqual(constructor.call_count, 2)
            self.assertTrue(state.task.done())
            self.assertFalse(state.ready)

    async def test_deregistration_failure_still_cleans_background_tasks(self):
        client, _ = self.client()
        client.deregister_instance.side_effect = ConnectionError("offline")
        with patch.dict("os.environ", {}, clear=True), patch("v2.nacos.NacosNamingService", return_value=client):
            async with registration("asr-svc", 8089):
                pass
        client.grpc_client_proxy.close_client.assert_awaited_once()
        self.assertTrue(client.grpc_client_proxy.redo_service._listen_task.done())

    async def test_backoff_is_capped_and_invalid_settings_fail(self):
        with patch.dict("os.environ", {}, clear=True):
            state = Registration("asr-svc", 8089)
            for failures, ceiling in ((1, 1), (3, 4), (50, 60)):
                delay = state._retry_delay(failures)
                self.assertGreaterEqual(delay, ceiling / 2)
                self.assertLessEqual(delay, ceiling)
        for value in ("0", "-1", "nan", "inf"):
            with self.subTest(value=value), patch.dict("os.environ", {"NACOS_CHECK_INTERVAL": value}, clear=True):
                with self.assertRaises(ValueError):
                    Registration("asr-svc", 8089)


if __name__ == "__main__":
    unittest.main()
