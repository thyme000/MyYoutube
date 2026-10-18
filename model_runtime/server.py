import argparse
from importlib import import_module
import os
import sys
import threading

import uvicorn

from model_runtime.settings import SERVICES, load_settings, service_directory


class ModelServer(uvicorn.Server):
    async def shutdown(self, sockets=None):
        pool = getattr(self.config.app.state, "pool", None)
        if pool is not None:
            await pool.stop_accepting()
        await super().shutdown(sockets=sockets)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--service", choices=SERVICES, required=True)
    parser.add_argument("--directory")
    parser.add_argument("--managed", action="store_true")
    args = parser.parse_args()
    requested = threading.Event()
    server = None

    def shutdown():
        requested.set()
        if server is not None:
            server.should_exit = True

    if args.managed:
        def read_control():
            if sys.stdin.readline().strip() == "shutdown":
                shutdown()
        threading.Thread(target=read_control, daemon=True, name="model-parent-control").start()
    identity = SERVICES[args.service]
    directory = service_directory(identity, args.directory)
    settings = load_settings(identity, directory)
    os.chdir(directory)
    sys.path.insert(0, str(directory))
    from model_runtime.logs import configure_logging
    from model_runtime.lifecycle import server_record
    configure_logging(identity.name)
    application = import_module("app").app
    application.state.settings = settings
    server = ModelServer(uvicorn.Config(application, host="0.0.0.0", port=settings.port, workers=1))

    with server_record(identity, directory, settings.port, shutdown):
        if not requested.is_set():
            server.run()


if __name__ == "__main__":
    main()
