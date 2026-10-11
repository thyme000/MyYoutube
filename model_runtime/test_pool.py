import asyncio
import threading
import unittest
from unittest.mock import AsyncMock, Mock, patch
from fastapi import HTTPException
from model_runtime.pool import BoundedPool


class PoolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.release = threading.Event()
        self.started = threading.Event()
        release, started = self.release, self.started
        class Worker:
            def run(self, request):
                started.set()
                release.wait(5)
                return request
            def stop(self): pass
        self.pool = BoundedPool("test", Worker, 1, 4, 0.3)
        await self.pool.start()

    async def asyncTearDown(self):
        self.release.set()
        await self.pool.close()

    async def test_active_plus_four_waiters_then_503(self):
        jobs = [asyncio.create_task(self.pool.run(i)) for i in range(5)]
        await asyncio.to_thread(self.started.wait, 1)
        await asyncio.sleep(0.02)
        self.assertEqual(self.pool.running, 1)
        self.assertEqual(self.pool.admitted, 5)
        with self.assertRaises(HTTPException) as caught: await self.pool.run(6)
        self.assertEqual(caught.exception.status_code, 503)
        self.assertEqual(caught.exception.headers["Retry-After"], "30")
        self.release.set()
        self.assertEqual(await asyncio.gather(*jobs), list(range(5)))
        self.assertEqual(self.pool.admitted, 0)

    async def test_cancelled_client_does_not_release_busy_model(self):
        job = asyncio.create_task(self.pool.run(1))
        await asyncio.to_thread(self.started.wait, 1)
        job.cancel()
        with self.assertRaises(asyncio.CancelledError): await job
        self.assertEqual(self.pool.running, 1)
        self.assertEqual(self.pool.admitted, 1)
        self.release.set()
        await asyncio.gather(*self.pool.jobs)
        await asyncio.sleep(0)
        self.assertEqual(self.pool.admitted, 0)

    async def test_wait_timeout_returns_503_and_frees_admission(self):
        job = asyncio.create_task(self.pool.run(1))
        await asyncio.to_thread(self.started.wait, 1)
        with self.assertRaises(HTTPException) as caught: await self.pool.run(2)
        self.assertEqual(caught.exception.status_code, 503)
        self.assertEqual(self.pool.admitted, 1)
        self.release.set()
        await job


class DiscoveryTests(unittest.IsolatedAsyncioTestCase):
    async def test_register_and_deregister_same_identity(self):
        from model_runtime.discovery import registration
        from v2.nacos import NacosNamingService
        client = AsyncMock()
        client.service_info_updater.stop = Mock()
        client.register_instance.return_value = True
        with patch.dict("os.environ", {"NACOS_ENABLED": "true", "NACOS_INSTANCE_IP": "127.0.0.1", "SERVER_PORT": "18088"}), \
             patch.object(NacosNamingService, "create_naming_service", return_value=client):
            async with registration("ocr-svc", 8088):
                self.assertEqual(NacosNamingService.create_naming_service.call_args.args[0].namespace_id, "public")
                param = client.register_instance.call_args.args[0]
                self.assertEqual((param.service_name, param.ip, param.port), ("ocr-svc", "127.0.0.1", 18088))
                client.deregister_instance.assert_not_called()
            client.deregister_instance.assert_awaited_once()
            client.grpc_client_proxy.close_client.assert_awaited_once()
            client.service_info_updater.stop.assert_called_once()

    async def test_discovery_enabled_by_default_for_both_services(self):
        from model_runtime.discovery import registration
        from v2.nacos import NacosNamingService
        for service, port in (("asr-svc", 8089), ("ocr-svc", 8088)):
            client = AsyncMock()
            client.service_info_updater.stop = Mock()
            client.register_instance.return_value = True
            with self.subTest(service=service), patch.dict("os.environ", {}, clear=True), \
                 patch.object(NacosNamingService, "create_naming_service", return_value=client):
                async with registration(service, port):
                    param = client.register_instance.call_args.args[0]
                    self.assertEqual((param.service_name, param.ip, param.port), (service, "127.0.0.1", port))
                    self.assertEqual((param.group_name, param.cluster_name), ("DEFAULT_GROUP", "DEFAULT"))
                removed = client.deregister_instance.call_args.args[0]
                self.assertEqual((removed.service_name, removed.ip, removed.port,
                                  removed.group_name, removed.cluster_name),
                                 (service, "127.0.0.1", port, "DEFAULT_GROUP", "DEFAULT"))

    async def test_explicit_opt_out_skips_discovery(self):
        from model_runtime.discovery import registration
        from v2.nacos import NacosNamingService
        with patch.dict("os.environ", {"NACOS_ENABLED": "false"}, clear=True), \
             patch.object(NacosNamingService, "create_naming_service") as create:
            async with registration("ocr-svc", 8088):
                create.assert_not_called()

    async def test_failed_registration_does_not_start_service(self):
        from model_runtime.discovery import registration
        from v2.nacos import NacosNamingService
        client = AsyncMock()
        client.service_info_updater.stop = Mock()
        client.register_instance.return_value = False
        with patch.dict("os.environ", {}, clear=True), \
             patch.object(NacosNamingService, "create_naming_service", return_value=client):
            with self.assertRaisesRegex(RuntimeError, "registration failed"):
                async with registration("ocr-svc", 8088):
                    self.fail("Failed registration must prevent startup")
            client.grpc_client_proxy.close_client.assert_awaited_once()
            client.service_info_updater.stop.assert_called_once()


if __name__ == "__main__": unittest.main()
