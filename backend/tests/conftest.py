"""Shared fixtures: an app built from explicit Settings (never the cached
get_settings(), so env leaks between tests stay impossible)."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from mina_api.core.config import Settings
from mina_api.main import create_app


@pytest.fixture()
def settings() -> Settings:
    return Settings(log_json=False, cors_origins=["https://app.mina.example"])


@pytest.fixture()
def client(settings: Settings) -> TestClient:
    return TestClient(create_app(settings))
