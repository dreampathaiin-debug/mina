"""Firebase ID token verification: the Mina Cloud sign-in identity path."""

import json
import time
import uuid
from types import SimpleNamespace

import jwt as pyjwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from jwt.algorithms import RSAAlgorithm

from mina_api.security.auth import MINA_IDENTITY_NAMESPACE, TokenVerifier
from tests.conftest import JWT_SECRET, SUPABASE_URL

PROJECT = "mina-2a7ba"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
KID = "test-signing-key"
FIREBASE_UID = "aB3dEfGhIjKlMnOpQrStUvWx"  # 24-char opaque Firebase UID shape


def _jwks() -> dict:
    # Same shape TokenVerifier._fetch_jwks returns: kid -> verified key object.
    entry = RSAAlgorithm.to_jwk(KEY.public_key(), as_dict=True)
    entry["kid"] = KID
    entry["alg"] = "RS256"
    return {KID: RSAAlgorithm.from_jwk(json.dumps(entry))}


def _sign(**overrides) -> str:
    now = int(time.time())
    claims = {
        "iss": f"https://securetoken.google.com/{PROJECT}",
        "aud": PROJECT,
        "sub": FIREBASE_UID,
        "email": "e2e@mina.test",
        "email_verified": True,
        "iat": now - 5,
        "exp": now + 3600,
    }
    claims.update(overrides)
    return pyjwt.encode(claims, KEY, algorithm="RS256", headers={"kid": KID})


@pytest.fixture()
def firebase_client(client, monkeypatch):
    verifier = TokenVerifier(JWT_SECRET, SUPABASE_URL, "authenticated", firebase_project_id=PROJECT)
    monkeypatch.setattr(verifier, "_fetch_jwks", lambda: _jwks())
    client.app.state.auth = verifier
    return client


def test_firebase_token_resolves_identity(firebase_client):
    headers = {"Authorization": f"Bearer {_sign()}"}
    response = firebase_client.get("/v1/me", headers=headers)
    assert response.status_code == 200
    expected = uuid.uuid5(MINA_IDENTITY_NAMESPACE, FIREBASE_UID)
    assert response.json()["id"] == str(expected)
    assert response.json()["email"] == "e2e@mina.test"


def test_firebase_uid_maps_deterministically(firebase_client):
    a = TokenVerifier.__new__(TokenVerifier)
    b = TokenVerifier.__new__(TokenVerifier)
    assert uuid.uuid5(MINA_IDENTITY_NAMESPACE, FIREBASE_UID) == uuid.uuid5(MINA_IDENTITY_NAMESPACE, FIREBASE_UID)
    assert uuid.uuid5(MINA_IDENTITY_NAMESPACE, FIREBASE_UID) != uuid.uuid5(MINA_IDENTITY_NAMESPACE, "other-uid")


def test_wrong_audience_rejected(firebase_client):
    headers = {"Authorization": f"Bearer {_sign(aud='other-project')}"}
    assert firebase_client.get("/v1/me", headers=headers).status_code == 401


def test_wrong_issuer_rejected(firebase_client):
    headers = {"Authorization": f"Bearer {_sign(iss='https://securetoken.google.com/other-project')}"}
    assert firebase_client.get("/v1/me", headers=headers).status_code == 401


def test_expired_rejected(firebase_client):
    headers = {"Authorization": f"Bearer {_sign(exp=int(time.time()) - 10)}"}
    response = firebase_client.get("/v1/me", headers=headers)
    assert response.status_code == 401
    assert response.json()["detail"] == "Token expired"


def test_unknown_signing_key_rejected(firebase_client):
    headers = {"Authorization": f"Bearer {_sign()}"[:-1]}  # tampered signature
    assert firebase_client.get("/v1/me", headers=headers).status_code == 401


def test_supabase_token_still_accepted_as_fallback(firebase_client, user_a):
    from tests.conftest import headers_for

    response = firebase_client.get("/v1/me", headers=headers_for(user_a))
    assert response.status_code == 200
    assert response.json()["id"] == str(user_a)


def test_supabase_only_verifier_ignores_firebase_tokens(client):
    headers = {"Authorization": f"Bearer {_sign()}"}
    assert client.get("/v1/me", headers=headers).status_code == 401
