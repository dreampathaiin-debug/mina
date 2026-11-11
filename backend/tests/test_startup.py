"""Application startup and route registration."""


def test_create_app_builds_and_serves_v1_routes(client):
    for path in ("/v1/health", "/v1/ready", "/v1/version"):
        assert client.get(path).status_code == 200


def test_app_is_never_in_debug_mode():
    from fastapi.testclient import TestClient

    from mina_api.core.config import Settings
    from mina_api.main import create_app

    app = create_app(Settings(debug=True))
    assert app.debug is False


def test_openapi_document_is_served(client):
    response = client.get("/v1/openapi.json")
    assert response.status_code == 200
    assert "/v1/health" in response.json()["paths"]
