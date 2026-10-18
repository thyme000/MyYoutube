#!/bin/sh
set -eu
cd "$(dirname "$0")"
project_root=$(cd .. && pwd)
export PYTHONPATH="$project_root/resources/skywalking-python-agent:$project_root${PYTHONPATH:+:$PYTHONPATH}"
exec .venv/bin/python -m model_runtime.server --service asr --directory "$PWD"
