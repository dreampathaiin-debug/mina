"""Envelope encryption: DEK wraps data, KEK wraps DEK, rotation re-wraps."""

import pytest
from cryptography.fernet import Fernet

from mina_api.security.crypto import EnvelopeCrypto, EnvKeyBackend

K1 = Fernet.generate_key().decode()
K2 = Fernet.generate_key().decode()
SECRET = "sk-openai-live-key-do-not-leak-0001"


def test_roundtrip():
    crypto = EnvelopeCrypto({"1": K1})
    version, wrapped, data = crypto.encrypt(SECRET)
    assert version == 1
    assert SECRET not in wrapped and SECRET not in data
    assert crypto.decrypt(version, wrapped, data) == SECRET


def test_wrong_kek_version_fails_closed():
    crypto = EnvelopeCrypto({"1": K1})
    _, wrapped, data = crypto.encrypt(SECRET)
    assert crypto.decrypt(9, wrapped, data) is None


def test_rotation_rewraps_dek_only():
    # The DEK was wrapped with the OLD kek version 1; rotating to version 2
    # rewraps the DEK under the new key and leaves the data token alone.
    old_crypto = EnvelopeCrypto({"1": K1})
    version, wrapped, data = old_crypto.encrypt(SECRET)
    assert version == 1
    crypto = EnvelopeCrypto({"1": K1, "2": K2})
    rotated = crypto.rotate(version, wrapped, data)
    assert rotated is not None
    new_version, new_wrapped, same_data = rotated
    assert new_version == 2
    assert new_wrapped != wrapped
    assert same_data == data  # data token untouched
    assert crypto.decrypt(new_version, new_wrapped, same_data) == SECRET
    # A keyring without the old KEK can no longer read the old wrapped DEK.
    assert EnvelopeCrypto({"2": K2}).decrypt(version, wrapped, data) is None


def test_legacy_phase2_path():
    crypto = EnvelopeCrypto({"1": K1})
    legacy_token = Fernet(K1.encode()).encrypt(SECRET.encode()).decode()
    assert crypto.decrypt_legacy(legacy_token) == SECRET


def test_backend_rejects_unknown_version():
    backend = EnvKeyBackend({1: K1})
    with pytest.raises(ValueError):
        backend.unwrap("bogus", 3)


def test_active_version_is_highest():
    crypto = EnvelopeCrypto({"1": K1, "2": K2})
    assert crypto.encrypt(SECRET)[0] == 2
