import argparse
from importlib import import_module
import os
import sys
import threading

import uvicorn

from model_runtime.settings import SERVICES, load_settings, service_directory

PARENT_SHUTDOWN_COMMAND = "shutdown"


def read_parent_shutdown(requested):
    if os.name != "nt":
        return sys.stdin.readline().strip() == PARENT_SHUTDOWN_COMMAND

    from _winapi import PeekNamedPipe
    from msvcrt import get_osfhandle

    command = (PARENT_SHUTDOWN_COMMAND + "\n").encode("ascii")
    handle = get_osfhandle(sys.stdin.fileno())
    # On Windows a pending read on inherited stdin can block worker startup.
    while not requested.is_set():
        try:
            message, _, _ = PeekNamedPipe(handle, len(command))
        except BrokenPipeError:
            return False
        if len(message) == len(command) or b"\n" in message:
            return message == command
        requested.wait(0.1)
    return False


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
            if read_parent_shutdown(requested):
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
