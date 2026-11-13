"""Supabase ES256 verification: the production sign-in path.

Supabase projects sign session JWTs with an asymmetric ES256 key published at
the auth JWKS endpoint, not the legacy shared secret. These tests pin the
contract: strict algorithm dispatch (an ES256 token must never reach the HS256
secret), issuer/audience/exp checks, UUID subjects, kid selection, and the
HS256 fallback still working.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
import uuid

import httpx
import jwt as pyjwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import HTTPException

from mina_api.security import auth as auth_module
from mina_api.security.auth import TokenVerifier
from tests.conftest import SUPABASE_URL, JWT_SECRET, token_for

KEY = ec.generate_private_key(ec.SECP256R1())
KID = "11111111-2222-3333-4444-555555555555"
USER = uuid.uuid4()


def _jwks() -> dict:
    entry = pyjwt.algorithms.ECAlgorithm.to_jwk(KEY.public_key(), as_dict=True)
    entry["kid"] = KID
    entry["alg"] = "ES256"
    entry["use"] = "sig"
    return {KID: pyjwt.algorithms.ECAlgorithm.from_jwk(json.dumps(entry))}


def _verifier(**kwargs) -> TokenVerifier:
    verifier = TokenVerifier(JWT_SECRET, SUPABASE_URL, "authenticated", **kwargs)
    verifier._fetch_supabase_jwks = _jwks  # type: ignore[method-assign]
    return verifier


def _claims(**overrides) -> dict:
    now = int(time.time())
    claims = {
        "iss": f"{SUPABASE_URL}/auth/v1",
        "aud": "authenticated",
        "sub": str(USER),
        "role": "authenticated",
        "iat": now - 5,
        "exp": now + 3600,
    }
    claims.update(overrides)
    return claims


def _sign(key=None, kid=KID, claims=None, missing=()) -> str:
    payload = {k: v for k, v in (claims or _claims()).items() if k not in missing}
    return pyjwt.encode(
        payload,
        key if key is not None else KEY,
        algorithm="ES256",
        headers={"kid": kid},
    )


def test_es256_token_verifies_and_maps_uuid():
    claims = _verifier().verify(_sign())
    assert claims["user_id"] == USER
    assert claims["role"] == "authenticated"


def test_es256_wrong_issuer_rejected():
    with pytest.raises(HTTPException) as exc:
        _verifier().verify(_sign(claims=_claims(iss="https://evil.example.com/auth/v1")))
    assert exc.value.status_code == 401


def test_es256_wrong_audience_rejected():
    with pytest.raises(HTTPException) as exc:
        _verifier().verify(_sign(claims=_claims(aud="anon")))
    assert exc.value.status_code == 401


@pytest.mark.parametrize("missing", ["exp", "sub", "aud", "iss"])
def test_es256_missing_required_claim_rejected(missing):
    with pytest.raises(HTTPException) as exc:
        _verifier().verify(_sign(missing=(missing,)))
    assert exc.value.status_code == 401


def test_es256_non_uuid_subject_rejected():
    with pytest.raises(HTTPException) as exc:
        _verifier().verify(_sign(claims=_claims(sub="not-a-uuid")))
    assert exc.value.status_code == 401
    assert exc.value.detail == "Invalid token subject"


def test_es256_expired_rejected():
    with pytest.raises(HTTPException) as exc:
        _verifier().verify(_sign(claims=_claims(exp=int(time.time()) - 100)))
    assert exc.value.status_code == 401
    assert exc.value.detail == "Token expired"


def test_es256_wrong_key_rejected():
    other = ec.generate_private_key(ec.SECP256R1())
    with pytest.raises(HTTPException) as exc:
        _verifier().verify(_sign(key=other))
    assert exc.value.status_code == 401


def test_es256_unknown_kid_rejected():
    with pytest.raises(HTTPException) as exc:
        _verifier().verify(_sign(kid="99999999-9999-9999-9999-999999999999"))
    assert exc.value.status_code == 401


def test_alg_confusion_es256_header_hmac_signature_rejected():
    """Header says ES256, signature is HMAC with the shared secret: 401, never accepted."""
    def b64(raw: bytes) -> str:
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

    header = b64(json.dumps({"alg": "ES256", "typ": "JWT", "kid": KID}).encode())
    payload = b64(json.dumps(_claims()).encode())
    signing_input = f"{header}.{payload}".encode()
    sig = b64(hmac.new(JWT_SECRET.encode(), signing_input, hashlib.sha256).digest())
    with pytest.raises(HTTPException) as exc:
        _verifier().verify(f"{header}.{payload}.{sig}")
    assert exc.value.status_code == 401


def test_unsupported_algorithm_rejected():
    other = __import__("cryptography.hazmat.primitives.asymmetric.rsa", fromlist=["rsa"]).generate_private_key(public_exponent=65537, key_size=2048)
    token = pyjwt.encode(_claims(), other, algorithm="RS256", headers={"kid": KID})
    with pytest.raises(HTTPException) as exc:
        _verifier().verify(token)
    assert exc.value.status_code == 401


def test_hs256_fallback_still_works():
    verifier = TokenVerifier(JWT_SECRET, SUPABASE_URL, "authenticated")
    claims = verifier.verify(token_for(USER))
    assert claims["user_id"] == USER


def test_jwks_fetch_failure_is_503():
    verifier = TokenVerifier(JWT_SECRET, SUPABASE_URL, "authenticated")

    def boom(*args, **kwargs):
        raise RuntimeError("network down")

    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(auth_module.httpx, "get", boom)
        with pytest.raises(HTTPException) as exc:
            verifier.verify(_sign())
    assert exc.value.status_code == 503
    assert exc.value.detail == "Auth keys unavailable"
