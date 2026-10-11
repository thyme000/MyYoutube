import logging
import os
from contextlib import asynccontextmanager

logger = logging.getLogger("uvicorn.error")


@asynccontextmanager
async def registration(service, default_port):
    if os.getenv("NACOS_ENABLED", "true").lower() != "true":
        logger.warning("Nacos registration disabled for %s", service)
        yield
        return
    from v2.nacos import (NacosNamingService, ClientConfigBuilder,
                          RegisterInstanceParam, DeregisterInstanceParam)
    address = os.getenv("NACOS_INSTANCE_IP", "127.0.0.1")
    port = int(os.getenv("SERVER_PORT", str(default_port)))
    group = os.getenv("NACOS_GROUP", "DEFAULT_GROUP")
    cluster = os.getenv("NACOS_CLUSTER", "DEFAULT")
    config = (ClientConfigBuilder().server_address(os.getenv("NACOS_ADDR", "localhost:8848"))
              .namespace_id(os.getenv("NACOS_NAMESPACE", "") or "public")
              .username(os.getenv("NACOS_USERNAME", ""))
              .password(os.getenv("NACOS_PASSWORD", "")).build())
    client = await NacosNamingService.create_naming_service(config)
    try:
        registered = await client.register_instance(RegisterInstanceParam(
            service_name=service, group_name=group, ip=address, port=port,
            cluster_name=cluster, enabled=True, healthy=True, ephemeral=True))
        if not registered:
            raise RuntimeError("Nacos instance registration failed")
        logger.info("Nacos registered %s at %s:%s (namespace=%s, group=%s, cluster=%s)",
                    service, address, port, config.namespace_id, group, cluster)
        yield
    finally:
        try:
            await client.deregister_instance(DeregisterInstanceParam(
                service_name=service, group_name=group, ip=address, port=port,
                cluster_name=cluster, ephemeral=True))
        finally:
            # SDK 2.0.11 shutdown incorrectly awaits the synchronous updater.stop().
            try:
                await client.grpc_client_proxy.close_client()
            finally:
                client.service_info_updater.stop()
