import asyncio
import logging
import math
import os
import random
from contextlib import asynccontextmanager

from prometheus_client import Gauge

logger = logging.getLogger("uvicorn.error")
REGISTERED = Gauge("model_discovery_registered", "Last confirmed Nacos registration", ["service"])
ENABLED = Gauge("model_discovery_enabled", "Whether Nacos registration is required", ["service"])


def _seconds(name, default):
    value = float(os.getenv(name, str(default)))
    if not math.isfinite(value) or value <= 0:
        raise ValueError(f"{name} must be a finite positive number")
    return value


class Registration:
    def __init__(self, service, default_port):
        self.service = service
        self.enabled = os.getenv("NACOS_ENABLED", "true").lower() == "true"
        self.registered = False
        self.client = None
        self.log_handlers = []
        self.task = None
        ENABLED.labels(service).set(int(self.enabled))
        self.set_registered(False)
        if not self.enabled:
            return
        from v2.nacos import ClientConfigBuilder
        self.address = os.getenv("NACOS_INSTANCE_IP", "127.0.0.1")
        self.port = int(os.getenv("SERVER_PORT", str(default_port)))
        self.group = os.getenv("NACOS_GROUP", "DEFAULT_GROUP")
        self.cluster = os.getenv("NACOS_CLUSTER", "DEFAULT")
        self.interval = _seconds("NACOS_CHECK_INTERVAL", 15)
        self.timeout = _seconds("NACOS_OPERATION_TIMEOUT", 10)
        self.max_delay = _seconds("NACOS_RECONNECT_MAX_DELAY", 60)
        self.config = (ClientConfigBuilder().server_address(os.getenv("NACOS_ADDR", "127.0.0.1:8848"))
                       .namespace_id(os.getenv("NACOS_NAMESPACE", "") or "public")
                       .username(os.getenv("NACOS_USERNAME", ""))
                       .password(os.getenv("NACOS_PASSWORD", ""))
                       .log_dir(os.path.join(os.getenv("MODEL_LOG_DIRECTORY", "logs"), "nacos"))
                       .load_cache_at_start(False).build())
        self.config.cache_dir = os.path.join(self.config.log_dir, "cache")

    @property
    def ready(self):
        return not self.enabled or self.registered

    def status(self):
        return {"enabled": self.enabled, "registered": self.registered}

    def set_registered(self, registered):
        if registered and not self.registered:
            logger.info("Nacos registered %s at %s:%s (namespace=%s, group=%s, cluster=%s)",
                        self.service, self.address, self.port, self.config.namespace_id, self.group, self.cluster)
        self.registered = registered
        REGISTERED.labels(self.service).set(int(registered))

    async def _register(self):
        from v2.nacos import RegisterInstanceParam
        registered = await self.client.register_instance(RegisterInstanceParam(
            service_name=self.service, group_name=self.group, ip=self.address, port=self.port,
            cluster_name=self.cluster, enabled=True, healthy=True, ephemeral=True))
        if not registered:
            raise RuntimeError("Nacos instance registration failed")

    async def _connect(self):
        from v2.nacos import NacosNamingService
        # Retain the client before start(), so partial startup can also be cleaned up.
        sdk_logger = logging.getLogger("naming")
        previous = set(sdk_logger.handlers)
        self.client = NacosNamingService(self.config)
        self.log_handlers = [handler for handler in sdk_logger.handlers if handler not in previous]
        await self.client.grpc_client_proxy.start()
        await self._register()

    async def _instance(self):
        # SDK 2.0.11 list_instances(subscribe=False) can still return its subscription cache.
        info = await self.client.grpc_client_proxy.query_instance_of_service(
            self.service, self.group, self.cluster, False)
        if info is None:
            raise RuntimeError("Nacos instance query returned no service")
        return next((host for host in info.hosts
                     if (host.ip, host.port, host.clusterName) == (self.address, self.port, self.cluster)), None)

    async def _verify(self):
        instance = await self._instance()
        if instance is None:
            self.set_registered(False)
            await self._register()
            for _ in range(10):
                await asyncio.sleep(0.25)
                instance = await self._instance()
                if instance is not None:
                    break
            if instance is None:
                raise RuntimeError("Nacos registration was not visible after repair")
        # Respect an instance disabled by an operator; do not force it back online.
        self.set_registered(instance.healthy and instance.enabled and instance.weight > 0)

    async def _close_client(self):
        client, self.client = self.client, None
        if client is None:
            return
        handlers, self.log_handlers = self.log_handlers, []
        cleanup = asyncio.create_task(self._dispose_client(client, handlers))
        try:
            await asyncio.shield(cleanup)
        except asyncio.CancelledError:
            await cleanup
            raise

    async def _dispose_client(self, client, handlers):
        proxy = client.grpc_client_proxy
        redo = proxy.redo_service._listen_task
        redo.cancel()
        await asyncio.gather(redo, return_exceptions=True)
        try:
            async with asyncio.timeout(self.timeout):
                if proxy.rpc_client is not None:
                    await proxy.close_client()
        except Exception as error:
            logger.warning("Nacos client cleanup failed for %s (%s)", self.service, type(error).__name__)
        finally:
            # SDK 2.0.11 shutdown awaits synchronous updater.stop() and leaves redo running.
            client.service_info_updater.stop()
            for handler in handlers:
                client.logger.removeHandler(handler)
                handler.close()

    def _retry_delay(self, failures):
        ceiling = min(2 ** min(failures - 1, 16), self.max_delay)
        return random.uniform(ceiling / 2, ceiling)

    async def _supervise(self, initial_delay=None):
        failures = 0
        delay = self.interval if initial_delay is None else initial_delay
        while True:
            await asyncio.sleep(delay)
            try:
                async with asyncio.timeout(self.timeout):
                    if self.client is None:
                        await self._connect()
                    await self._verify()
                failures = 0
                delay = self.interval
            except Exception as error:
                self.set_registered(False)
                await self._close_client()
                failures += 1
                delay = self._retry_delay(failures)
                logger.warning("Nacos registration unavailable for %s (%s); reconnect in %.1fs",
                               self.service, type(error).__name__, delay)

    async def start(self):
        if not self.enabled:
            logger.warning("Nacos registration disabled for %s", self.service)
            return
        try:
            async with asyncio.timeout(self.timeout):
                await self._connect()
            self.set_registered(True)
            initial_delay = None
        except Exception as error:
            await self._close_client()
            initial_delay = self._retry_delay(1)
            logger.warning("Initial Nacos registration unavailable for %s (%s); reconnect in %.1fs",
                           self.service, type(error).__name__, initial_delay)
        self.task = asyncio.create_task(self._supervise(initial_delay), name=f"nacos-registration-{self.service}")

    async def close(self):
        self.set_registered(False)
        if self.task is not None:
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)
        if self.client is None:
            return
        from v2.nacos import DeregisterInstanceParam
        try:
            async with asyncio.timeout(self.timeout):
                await self.client.deregister_instance(DeregisterInstanceParam(
                    service_name=self.service, group_name=self.group, ip=self.address, port=self.port,
                    cluster_name=self.cluster, ephemeral=True))
        except Exception as error:
            logger.warning("Nacos deregistration failed for %s (%s)", self.service, type(error).__name__)
        finally:
            await self._close_client()


@asynccontextmanager
async def registration(service, default_port):
    state = Registration(service, default_port)
    try:
        await state.start()
        yield state
    finally:
        await state.close()
