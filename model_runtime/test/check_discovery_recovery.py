"""使用独立测试服务检查 Nacos 注册恢复与客户端替换。"""
import asyncio
import logging
import os
import tempfile
import uuid
from unittest.mock import patch

import httpx
from v2.nacos import DeregisterInstanceParam

from model_runtime.discovery import registration


async def wait_for(condition):
    async with asyncio.timeout(30):
        while not await condition():
            await asyncio.sleep(0.1)


async def main():
    service = "pipeline-recovery-check-" + uuid.uuid4().hex
    address = os.getenv("NACOS_ADDR", "127.0.0.1:8848")
    env = {"NACOS_ENABLED": "true", "NACOS_ADDR": address, "NACOS_INSTANCE_IP": "127.0.0.1",
           "NACOS_NAMESPACE": "", "NACOS_GROUP": "DEFAULT_GROUP", "NACOS_CLUSTER": "DEFAULT",
           "SERVER_PORT": "18091", "NACOS_CHECK_INTERVAL": "3", "NACOS_OPERATION_TIMEOUT": "3"}
    with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {**env, "MODEL_LOG_DIRECTORY": directory}):
        handlers = set(logging.getLogger("naming").handlers)
        tasks = set(asyncio.all_tasks())
        async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
            url = f"http://{address}/nacos/v1/ns/instance"
            params = {"serviceName": service, "groupName": "DEFAULT_GROUP", "namespaceId": "public"}

            async def listed():
                response = await http.get(url + "/list", params=params)
                response.raise_for_status()
                return response.json()["hosts"]

            async with registration(service, 18091) as state:
                original = state.client
                assert await listed(), "Initial instance missing"
                assert await original.deregister_instance(DeregisterInstanceParam(
                    service_name=service, group_name="DEFAULT_GROUP", ip="127.0.0.1", port=18091,
                    cluster_name="DEFAULT", ephemeral=True))
                async def removed():
                    return not await listed()

                await wait_for(removed)
                async def restored():
                    return state.ready and bool(await listed())

                await wait_for(restored)
                print("LIVE Nacos: deleted test instance was automatically registered again")

                # 只断开当前测试客户端，不停止共享的 Nacos。
                original = state.client
                await original.grpc_client_proxy.close_client()

                async def replaced():
                    return state.client is not None and state.client is not original and state.ready and bool(await listed())

                await wait_for(replaced)
                print("LIVE Nacos: unusable SDK client was replaced and registration recovered")

            await wait_for(removed)
        await asyncio.sleep(0.1)
        assert set(logging.getLogger("naming").handlers) == handlers, "SDK log handlers leaked"
        pending = asyncio.all_tasks() - tasks
        assert not pending, f"Background tasks leaked: {pending}"
        print("LIVE Nacos: shutdown removed test instance; no background tasks or log handlers leaked")


if __name__ == "__main__":
    asyncio.run(main())
