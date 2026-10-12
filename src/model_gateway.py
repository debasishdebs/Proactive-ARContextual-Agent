"""Uniform model gateway for proactive context agent.

This keeps cloud APIs and local models swappable behind a single interface,
following the same intent as the larger reference language_models.py.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import Enum
from typing import Dict, List, Optional


class BackendType(str, Enum):
    CLOUD_API = "cloud_api"
    LOCAL_MODEL = "local_model"


@dataclass
class ModelRequest:
    prompt: str
    system_prompt: Optional[str] = None
    max_tokens: int = 300
    temperature: float = 0.2
    metadata: Optional[Dict[str, str]] = None


@dataclass
class ModelResponse:
    text: str
    backend: BackendType
    model_name: str
    usage: Optional[Dict[str, int]] = None


class ModelBackend(ABC):
    def __init__(self, model_name: str, backend_type: BackendType):
        self.model_name = model_name
        self.backend_type = backend_type

    @abstractmethod
    def generate(self, request: ModelRequest) -> ModelResponse:
        raise NotImplementedError


class CloudApiBackend(ModelBackend):
    def __init__(self, model_name: str, endpoint: str):
        super().__init__(model_name=model_name, backend_type=BackendType.CLOUD_API)
        self.endpoint = endpoint

    def generate(self, request: ModelRequest) -> ModelResponse:
        # Placeholder for real HTTP call.
        return ModelResponse(
            text="TODO: cloud response",
            backend=self.backend_type,
            model_name=self.model_name,
        )


class LocalModelBackend(ModelBackend):
    def __init__(self, model_name: str):
        super().__init__(model_name=model_name, backend_type=BackendType.LOCAL_MODEL)

    def generate(self, request: ModelRequest) -> ModelResponse:
        # Placeholder for on-device inference.
        return ModelResponse(
            text="TODO: local response",
            backend=self.backend_type,
            model_name=self.model_name,
        )


class ModelGateway:
    def __init__(self, cloud_backend: ModelBackend, local_backend: ModelBackend):
        self.cloud_backend = cloud_backend
        self.local_backend = local_backend

    def generate(
        self,
        request: ModelRequest,
        prefer_local: bool,
        local_allowed: bool,
    ) -> ModelResponse:
        if prefer_local and local_allowed:
            return self.local_backend.generate(request)
        return self.cloud_backend.generate(request)

    def batched_generate(
        self,
        requests: List[ModelRequest],
        prefer_local: bool,
        local_allowed: bool,
    ) -> List[ModelResponse]:
        return [self.generate(r, prefer_local=prefer_local, local_allowed=local_allowed) for r in requests]
