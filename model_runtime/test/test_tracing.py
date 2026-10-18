import os
import sys
import types
import unittest
from unittest.mock import Mock, patch

from model_runtime import tracing
from model_runtime.settings import TracingSettings


class TracingBootstrapTests(unittest.TestCase):
    def test_explicit_disabled_snapshot_ignores_parent_environment(self):
        with patch.dict(os.environ, {"PIPELINE_TRACING_ENABLED": "true"}), \
             patch("model_runtime.tracing.version") as package:
            tracing.bootstrap("asr-svc", TracingSettings(enabled=False))
            package.assert_not_called()

    def test_enabled_agent_uses_bounded_local_sampling(self):
        agent = Mock()
        agent.started.return_value = False
        config = Mock()
        package = types.ModuleType("skywalking")
        package.config = config
        module = types.ModuleType("skywalking.agent")
        module.agent = agent
        with patch.dict(os.environ, {"PIPELINE_TRACING_ENABLED": "true", "MODEL_TRACE_SAMPLE_PER_3_SECS": "7"}), \
             patch.dict(sys.modules, {"skywalking": package, "skywalking.agent": module}), \
             patch("model_runtime.tracing.version", return_value="1.3.0"):
            tracing.bootstrap("asr-svc")
        self.assertEqual(config.init.call_args.kwargs["sample_n_per_3_secs"], 7)
        self.assertEqual(config.init.call_args.kwargs["agent_name"], "asr-svc")
        agent.start.assert_called_once()


if __name__ == "__main__":
    unittest.main()
