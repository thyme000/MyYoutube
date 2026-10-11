#!/bin/sh
set -eu
cd "$(dirname "$0")"
export PYTHONPATH="..${PYTHONPATH:+:$PYTHONPATH}"
exec .venv/bin/python -m model_runtime.server --default-port 8088
