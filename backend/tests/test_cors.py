"""CORS is an explicit allowlist: allowed origins pass, everything else
gets no access-control headers."""

def test_allowed_origin_gets_cors_headers(client):
    response = client.get("/v1/health", headers={"Origin": "https://app.mina.example"})
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "https://app.mina.example"


def test_disallowed_origin_gets_no_cors_headers(client):
    response = client.get("/v1/health", headers={"Origin": "https://evil.example"})
    assert response.status_code == 200
    assert "access-control-allow-origin" not in response.headers


def test_preflight_from_allowed_origin_passes(client):
    response = client.options(
        "/v1/health",
        headers={
            "Origin": "https://app.mina.example",
            "Access-Control-Request-Method": "GET",
        },
    )
    assert response.status_code == 200


def test_preflight_from_unknown_origin_is_rejected(client):
    response = client.options(
        "/v1/health",
        headers={
            "Origin": "https://evil.example",
            "Access-Control-Request-Method": "GET",
        },
    )
    assert response.status_code == 400
