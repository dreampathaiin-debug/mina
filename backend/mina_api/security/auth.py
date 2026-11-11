"""Supabase JWT verification and the reusable identity dependency.

Identity is extracted EXCLUSIVELY from a verified bearer token: signature
(HS256 against the Supabase JWT secret), issuer (the Supabase URL), audience
("authenticated") and expiration are all checked. Request bodies never
carry a trusted user_id. get_current_user is the single dependency every
protected endpoint uses; require_admin layers role-based authorization on
top of it.
"""

from __future__ import annotations

import uuid
from typing import Annotated, Any

import jwt
from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from mina_api.database.models import Profile
from mina_api.database.session import get_db

_bearer = HTTPBearer(auto_error=False)


class TokenVerifier:
    def __init__(self, secret: str | None, url: str | None, audience: str) -> None:
        self.secret = secret
        self.url = url.rstrip("/") if url else None
        self.audience = audience

    def verify(self, token: str) -> dict[str, Any]:
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

    Auto-provisions the profile on first sight of a Supabase user (the
    production equivalent is the on_auth_user_created trigger). A profile
    with deleted_at set has lost access, whatever token it presents.
    """
    user_id: uuid.UUID = claims["user_id"]
    profile = await db.get(Profile, user_id)
    if profile is None:
        profile = Profile(id=user_id, email=claims.get("email"), role="user")
        db.add(profile)
        await db.commit()
        await db.refresh(profile)
    if profile.deleted_at is not None:
        raise HTTPException(status_code=403, detail="Account deleted")
    return profile


CurrentUser = Annotated[Profile, Depends(get_current_user)]


def require_admin(user: CurrentUser) -> Profile:
    if user.role != "admin":
        raise HTTPException(status_code=403, detail="Admin role required")
    return user


AdminUser = Annotated[Profile, Depends(require_admin)]
