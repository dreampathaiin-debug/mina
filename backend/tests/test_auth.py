"""Token verification: identity comes only from a verified Supabase JWT."""

from tests.conftest import headers_for, token_for
from uuid import uuid4


def test_missing_token_is_rejected(client):
    assert client.get("/v1/me").status_code == 401


def test_invalid_signature_is_rejected(client, user_a):
    headers = {"Authorization": f"Bearer {token_for(user_a, key='not-the-real-secret')}"}
    assert client.get("/v1/me", headers=headers).status_code == 401


def test_expired_token_is_rejected(client, user_a):
    response = client.get("/v1/me", headers=headers_for(user_a, expired=True))
    assert response.status_code == 401
    assert response.json()["detail"] == "Token expired"


def test_wrong_audience_is_rejected(client, user_a):
    response = client.get("/v1/me", headers=headers_for(user_a, audience="wrong-aud"))
    assert response.status_code == 401


def test_wrong_issuer_is_rejected(client, user_a):
    response = client.get(
        "/v1/me", headers=headers_for(user_a, issuer_url="https://evil.example.com")
    )
    assert response.status_code == 401


def test_non_uuid_subject_is_rejected(client):
    from tests.conftest import SUPABASE_URL, JWT_SECRET
    from uuid import uuid4
    import time
    now = int(time.time())
    fake = {
        "sub": "not-a-uuid", "aud": "authenticated", "iss": f"{SUPABASE_URL}/auth/v1",
        "iat": now - 5, "exp": now + 3600,
    }
    import jwt as pyjwt
    headers = {"Authorization": f"Bearer {pyjwt.encode(fake, JWT_SECRET, algorithm='HS256')}"}
    assert client.get("/v1/me", headers=headers).status_code == 401


def test_valid_token_resolves_identity(client, user_a):
    response = client.get("/v1/me", headers=headers_for(user_a))
    assert response.status_code == 200
    assert response.json()["id"] == str(user_a)


def test_profile_auto_provisions_with_user_role(client, user_a):
    response = client.get("/v1/me", headers=headers_for(user_a))
    assert response.json()["role"] == "user"
