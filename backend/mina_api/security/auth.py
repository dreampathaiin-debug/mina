"""Identity verification: Firebase ID tokens first, Supabase JWT fallback.

Identity is extracted EXCLUSIVELY from a verified bearer token. Firebase ID
tokens are verified with RS256 against Google's published signing keys (issuer
https://securetoken.google.com/<project>, audience <project>), so the Supabase
JWT secret alone is no longer the trust root. A Supabase HS256 JWT is still
accepted as a fallback while configured. Request bodies never carry a trusted
user_id. get_current_user is the single dependency every protected endpoint
uses; require_admin layers role-based authorization on top of it.

Firebase subjects are opaque ~28-character strings, not UUIDs. Every Mina user
id stays a UUID: the profile id is derived deterministically from the Firebase
UID (uuid5 under a fixed namespace), so profiles, foreign keys and RLS policies
keep their UUID contract and the same Google account always maps to the same row.
"""

from __future__ import annotations

import json
import logging
import os
import time
import uuid
from typing import Annotated, Any

import httpx
import jwt
from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from mina_api.database.models import Profile
from mina_api.database.session import get_db

logger = logging.getLogger("mina.auth")

_bearer = HTTPBearer(auto_error=False)

# Fixed namespace so Firebase UID -> UUID is deterministic across deploys.
MINA_IDENTITY_NAMESPACE = uuid.UUID("1d80183d-ee88-5629-aa33-095d9e273252")

_FIREBASE_JWKS_URL = (
    "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
)
_JWKS_MAX_AGE_SECONDS = 6 * 3600


class TokenVerifier:
    def __init__(
        self,
        secret: str | None,
        url: str | None,
        audience: str = "authenticated",
        firebase_project_id: str | None = None,
    ) -> None:
        self.secret = secret
        self.url = url.rstrip("/") if url else None
        self.audience = audience
        self.firebase_project_id = firebase_project_id or None
        self._keys: dict[str, Any] | None = None
        self._keys_at: float = 0.0

    def verify(self, token: str) -> dict[str, Any]:
        if self.firebase_project_id:
            try:
                return self._verify_firebase(token)
            except HTTPException:
                raise
            except jwt.ExpiredSignatureError:
                raise HTTPException(status_code=401, detail="Token expired") from None
            except jwt.InvalidTokenError:
                if not (self.secret and self.url):
                    raise HTTPException(status_code=401, detail="Invalid token") from None
                # Not a Firebase token: fall through to the Supabase verifier.
        if not (self.secret and self.url):
            raise HTTPException(status_code=503, detail="Auth is not configured on this server")
        try:
            claims = jwt.decode(
                token,
                self.secret,
                algorithms=["HS256"],
                audience=self.audience,
                issuer=f"{self.url}/auth/v1",
                options={"require": ["exp", "sub", "aud", "iss"]},
            )
        except jwt.ExpiredSignatureError:
            raise HTTPException(status_code=401, detail="Token expired") from None
        except jwt.InvalidTokenError:
            raise HTTPException(status_code=401, detail="Invalid token") from None
        try:
            claims["user_id"] = uuid.UUID(str(claims["sub"]))
        except (TypeError, ValueError):
            raise HTTPException(status_code=401, detail="Invalid token subject") from None
        return claims

    def _verify_firebase(self, token: str) -> dict[str, Any]:
        header = jwt.get_unverified_header(token)
        if header.get("alg") != "RS256" or not header.get("kid"):
            raise jwt.InvalidTokenError("not a Firebase token")
        key = self._signing_key(str(header["kid"]))
        if key is None:
            raise jwt.InvalidTokenError("unknown Firebase signing key")
        claims = jwt.decode(
            token,
            key,
            algorithms=["RS256"],
            audience=self.firebase_project_id,
            issuer=f"https://securetoken.google.com/{self.firebase_project_id}",
            options={"require": ["exp", "sub", "aud", "iss"]},
        )
        uid = str(claims["sub"])
        claims["firebase_uid"] = uid
        claims["user_id"] = uuid.uuid5(MINA_IDENTITY_NAMESPACE, uid)
        return claims

    def _signing_key(self, kid: str) -> Any:
        keys = self._keys
        if keys is None or kid not in keys:
            keys = self._fetch_jwks()
            self._keys = keys
        return keys.get(kid)

    def _fetch_jwks(self) -> dict[str, Any]:
        """Google's rotating securetoken signing keys, refreshed at most hourly."""
        now = time.monotonic()
        if self._keys is not None and now - self._keys_at < _JWKS_MAX_AGE_SECONDS:
            return self._keys
        try:
            response = httpx.get(_FIREBASE_JWKS_URL, timeout=10.0)
            response.raise_for_status()
            entries = response.json().get("keys", [])
        except Exception as error:
            logger.warning("Firebase signing keys unavailable: %s", error)
            raise HTTPException(status_code=503, detail="Auth keys unavailable") from None
        keys: dict[str, Any] = {}
        for entry in entries:
            try:
                keys[entry["kid"]] = jwt.algorithms.RSAAlgorithm.from_jwk(json.dumps(entry))
            except Exception:  # malformed entry: skip it, the rest still verify
                continue
        self._keys_at = now
        return keys


def _ensure_role_claim(firebase_uid: str) -> None:
    """Stamp role='authenticated' on a freshly provisioned Firebase user.

    Supabase third-party auth maps the RLS role from token claims, so a Firebase
    user without the claim would land in the anon role on any direct Supabase
    access. Best effort via the Admin SDK; requires MINA_FIREBASE_SERVICE_ACCOUNT
    (raw service-account JSON or base64) and is silently skipped otherwise.
    """
    raw = os.environ.get("MINA_FIREBASE_SERVICE_ACCOUNT", "").strip()
    if not raw:
        return
    try:
        import base64

        from firebase_admin import auth as firebase_auth
        from firebase_admin import credentials as firebase_credentials
        import firebase_admin

        payload = json.loads(base64.b64decode(raw)) if not raw.startswith("{") else json.loads(raw)
        try:
            firebase_admin.get_app()
        except ValueError:
            firebase_admin.initialize_app(firebase_credentials.Certificate(payload))
        user = firebase_auth.get_user(firebase_uid)
        claims = dict(user.custom_claims or {})
        if claims.get("role") != "authenticated":
            claims["role"] = "authenticated"
            firebase_auth.set_custom_user_claims(firebase_uid, claims)
    except Exception:
        logger.warning("Firebase role claim update failed", exc_info=True)


def get_verifier(request: Request) -> TokenVerifier:
    return request.app.state.auth


def get_token_claims(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
    verifier: Annotated[TokenVerifier, Depends(get_verifier)],
) -> dict[str, Any]:
    if credentials is None or not credentials.credentials:
        raise HTTPException(status_code=401, detail="Missing bearer token")
    return verifier.verify(credentials.credentials)


async def get_current_user(
    claims: Annotated[dict[str, Any], Depends(get_token_claims)],
    db: Annotated[Any, Depends(get_db)],
) -> Profile:
    """Resolve the verified subject to a live profile row.

    Auto-provisions the profile on first sight of a user (the production
    equivalent is the on_auth_user_created trigger). A profile with deleted_at
    set has lost access, whatever token it presents.
    """
    user_id: uuid.UUID = claims["user_id"]
    profile = await db.get(Profile, user_id)
    if profile is None:
        profile = Profile(id=user_id, email=claims.get("email"), role="user")
        db.add(profile)
        await db.commit()
        await db.refresh(profile)
        firebase_uid = claims.get("firebase_uid")
        if firebase_uid:
            _ensure_role_claim(firebase_uid)
    if profile.deleted_at is not None:
        raise HTTPException(status_code=403, detail="Account deleted")
    return profile


CurrentUser = Annotated[Profile, Depends(get_current_user)]


def require_admin(user: CurrentUser) -> Profile:
    if user.role != "admin":
        raise HTTPException(status_code=403, detail="Admin role required")
    return user


AdminUser = Annotated[Profile, Depends(require_admin)]
