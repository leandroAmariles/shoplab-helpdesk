"""Test setup: lets ``shoplab_callbacks`` be imported without installing LiteLLM."""

import sys
import types


def _install_fake_litellm() -> None:
    if "litellm.integrations.custom_logger" in sys.modules:
        return
    litellm = types.ModuleType("litellm")
    integrations = types.ModuleType("litellm.integrations")
    custom_logger = types.ModuleType("litellm.integrations.custom_logger")

    class CustomLogger:  # minimal stand-in for litellm.integrations.custom_logger.CustomLogger
        pass

    custom_logger.CustomLogger = CustomLogger
    litellm.integrations = integrations
    integrations.custom_logger = custom_logger
    sys.modules["litellm"] = litellm
    sys.modules["litellm.integrations"] = integrations
    sys.modules["litellm.integrations.custom_logger"] = custom_logger


_install_fake_litellm()
