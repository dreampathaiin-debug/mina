"""Requests exceeding the configured timeout return 503."""

import asyncio

from fastapi.testclient import TestClient

from mina_api.core.config import Settings
from mina_api.main import create_app


def test_slow_request_times_out():
    app = create_app(Settings(log_json=False, request_timeout_seconds=0.05))

    @app.get("/v1/slow")
    async def slow() -> None:
        await asyncio.sleep(0.5)

    client = TestClient(app, raise_server_exceptions=False)
    response = client.get("/v1/slow")
    assert response.status_code == 503
    assert response.json()["error"] == "request_timeout"


def test_fast_request_is_not_affected():
    app = create_app(Settings(log_json=False, request_timeout_seconds=5))
    client = TestClient(app)
    response = client.get("/v1/health")
    assert response.status_code == 200
