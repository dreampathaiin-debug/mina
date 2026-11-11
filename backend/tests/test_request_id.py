"""Every response carries an x-request-id; a sane client id is echoed."""


def test_request_id_generated_when_missing(client):
    response = client.get("/v1/health")
    assert len(response.headers["x-request-id"]) >= 16


def test_request_id_echoed_from_client(client):
    response = client.get("/v1/health", headers={"X-Request-ID": "abc123"})
    assert response.headers["x-request-id"] == "abc123"


def test_malicious_request_id_is_replaced(client):
    response = client.get("/v1/health", headers={"X-Request-ID": "bad id with spaces"})
    assert response.headers["x-request-id"] != "bad id with spaces"
