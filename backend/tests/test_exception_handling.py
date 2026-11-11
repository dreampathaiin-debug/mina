"""Unhandled exceptions return a safe 500, never internal details."""

from mina_api.main import create_app
from mina_api.core.config import Settings


def test_unhandled_exception_returns_safe_json():
    app = create_app(Settings(log_json=False))

    @app.get("/v1/boom")
    async def boom() -> None:
        raise RuntimeError("secret internals leaked")

    from fastapi.testclient import TestClient

    client = TestClient(app, raise_server_exceptions=False)
    response = client.get("/v1/boom")
    assert response.status_code == 500
    body = response.json()
    assert body["error"] == "internal_error"
    assert "secret internals" not in response.text
    assert body["request_id"]


def test_request_id_header_is_echoed_on_errors():
    app = create_app(Settings(log_json=False))

    @app.get("/v1/boom2")
    async def boom2() -> None:
        raise RuntimeError("boom2")

    from fastapi.testclient import TestClient

    client = TestClient(app, raise_server_exceptions=False)
    response = client.get("/v1/boom2", headers={"X-Request-ID": "test-id-123"})
    assert response.headers["x-request-id"] == "test-id-123"
    assert response.json()["request_id"] == "test-id-123"
