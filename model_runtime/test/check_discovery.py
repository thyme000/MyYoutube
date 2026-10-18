"""检查真实注册、双实例发现与注销。"""
import asyncio
import os
import uuid
import httpx
from model_runtime.discovery import registration


async def main():
    service = "pipeline-runtime-check-" + uuid.uuid4().hex
    os.environ.update(NACOS_ENABLED="true", NACOS_INSTANCE_IP="127.0.0.1", NACOS_ADDR="127.0.0.1:8848", SERVER_PORT="18089")
    async with httpx.AsyncClient() as client:
        async def instances():
            response = await client.get("http://127.0.0.1:8848/nacos/v1/ns/instance/list", params={"serviceName": service})
            response.raise_for_status()
            return response.json()["hosts"]
        async with registration(service, 18089):
            os.environ["SERVER_PORT"] = "18090"
            async with registration(service, 18090):
                for _ in range(50):
                    active = await instances()
                    if len(active) == 2: break
                    await asyncio.sleep(0.1)
                assert {i["port"] for i in active} == {18089, 18090}, active
        for _ in range(50):
            active = await instances()
            if not active: break
            await asyncio.sleep(0.1)
        assert not active, active
        print("LIVE Python Nacos: 2 registered instances, both deregistered, client shutdown clean")


if __name__ == "__main__": asyncio.run(main())
