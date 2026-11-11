"""At-rest encryption for provider API keys.

Two layers live here:

- SecretBox: the Phase 2 single-Fernet-key box. Rows encrypted before the
  envelope scheme existed are still readable through it, so nothing breaks.
- EnvelopeCrypto: Phase 3 envelope encryption. A random per-record data key
  (DEK) encrypts the API key; the DEK is wrapped with a versioned
  key-encryption-key (KEK). KEKs come from the environment
  (MINA_ENCRYPTION_KEY_V1..N). Rotating the active KEK only re-wraps DEKs;
  data is never re-encrypted. A managed KMS can replace the env backend by
  passing a KeyWrapBackend (AwsKmsBackend ships as an opt-in adapter).
"""

from __future__ import annotations

import json
from typing import Protocol

from cryptography.fernet import Fernet, InvalidToken

import logging

LOG = logging.getLogger("mina.crypto")
class KeyWrapBackend(Protocol):
    def wrap(self, dek: bytes) -> str: ...

    def unwrap(self, wrapped: str, version: int) -> bytes: ...


class EnvKeyBackend:
    """Secure environment-key fallback: KEKs live only in the process env."""

    def __init__(self, keys: dict[int, str]) -> None:
        self._keys = {int(v): Fernet(k.encode()) for v, k in keys.items()}

    def wrap(self, dek: bytes) -> str:
        return self._active.encrypt(dek).decode()

    def unwrap(self, wrapped: str, version: int) -> bytes:
        try:
            return self._keys[version].decrypt(wrapped.encode())
        except KeyError as exc:  # unknown KEK version: fail loudly, not silently
            raise ValueError(f"unknown encryption key version {version}") from exc
        except InvalidToken as exc:
            raise ValueError("key-encryption key does not match wrapped DEK") from exc

    @property
    def _active(self) -> Fernet:
        return self._keys[max(self._keys)]


class AwsKmsBackend:
    """Optional managed-KMS adapter. Requires boto3 at runtime; unused unless
    explicitly passed in. Failures raise; they never fall back to plaintext."""

    def __init__(self, key_id: str) -> None:
        try:
            import base64

            import boto3  # type: ignore[import-untyped]
        except ImportError as exc:
            raise RuntimeError("boto3 is not installed; install it to use KMS") from exc
        self._client = boto3.client("kms")
        self._key_id = key_id

    def wrap(self, dek: bytes) -> str:
        import base64

        response = self._client.encrypt(KeyId=self._key_id, Plaintext=dek)
        return base64.b64encode(response["CiphertextBlob"]).decode()

    def unwrap(self, wrapped: str, version: int) -> bytes:
        import base64

        response = self._client.decrypt(CiphertextBlob=base64.b64decode(wrapped))
        return response["Plaintext"]


class EnvelopeCrypto:
    def __init__(
        self,
        keys: dict[int, str],
        wrap_backend: KeyWrapBackend | None = None,
    ) -> None:
        if not keys:
            raise ValueError("at least one encryption key is required")
        self._backend = wrap_backend or EnvKeyBackend(keys)
        self._keys = {int(v): Fernet(k.encode()) for v, k in keys.items()}

    @classmethod
    def from_env_mapping(cls, raw: str | None, legacy_key: str | None) -> "EnvelopeCrypto":
        """raw: JSON like {"1": "<fernet-key>", "2": "<fernet-key>"}; falls back
        to the legacy single MINA_ENCRYPTION_KEY as version 1."""
        keys: dict[int, str] = {}
        if raw:
            keys.update({int(v): k for v, k in json.loads(raw).items()})
        elif legacy_key:
            keys[1] = legacy_key
        return cls(keys) if keys else None

    @property
    def active_version(self) -> int:
        return max(self._keys)

    def encrypt(self, plaintext: str) -> tuple[int, str, str]:
        """Returns (key_version, wrapped_dek, data_token)."""
        dek = Fernet.generate_key()
        data_cipher = Fernet(dek)
        wrapped = self._backend.wrap(dek)
        return self.active_version, wrapped, data_cipher.encrypt(plaintext.encode()).decode()

    def decrypt(self, key_version: int, wrapped_dek: str, data_token: str) -> str | None:
        try:
            dek = self._backend.unwrap(wrapped_dek, key_version)
            return Fernet(dek).decrypt(data_token.encode()).decode()
        except (InvalidToken, ValueError) as exc:
            LOG.warning("envelope_decrypt_failed", extra={"reason": str(exc)})
            return None

    def rotate(self, key_version: int, wrapped_dek: str, data_token: str) -> tuple[int, str, str] | None:
        """Re-wrap the DEK under the active KEK; the data token is untouched."""
        dek = self._backend.unwrap(wrapped_dek, key_version)
        return self.active_version, self._backend.wrap(dek), data_token

    def decrypt_legacy(self, token: str) -> str | None:
        try:
            return self._keys[1].decrypt(token.encode()).decode()
        except (InvalidToken, KeyError) as exc:
            LOG.warning("legacy_decrypt_failed", extra={"reason": str(exc)})
            return None


class SecretBox:
    """Legacy single-key Fernet box (kept for Phase 2 rows)."""

    def __init__(self, key: str | None) -> None:
        try:
            self._fernet = Fernet(key.encode()) if key else None
        except ValueError:
            self._fernet = None

    @property
    def enabled(self) -> bool:
        return self._fernet is not None

    def encrypt(self, value: str) -> str:
        if self._fernet is None:
            return value
        return self._fernet.encrypt(value.encode()).decode()

    def decrypt(self, value: str) -> str | None:
        if self._fernet is None:
            return value
        try:
            return self._fernet.decrypt(value.encode()).decode()
        except InvalidToken:
            return None
