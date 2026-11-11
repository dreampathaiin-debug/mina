"""The three Phase 1 endpoints."""


def test_health_is_ok(client):
    response = client.get("/v1/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_ready_reports_skipped_database(client):
    response = client.get("/v1/ready")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ready"
    assert body["checks"]["database"] == "skipped"


def test_version_reports_build_info(client):
    response = client.get("/v1/version")
    assert response.status_code == 200
    body = response.json()
    assert body["service"] == "mina-api"
    assert body["version"]
    assert body["environment"] == "development"
