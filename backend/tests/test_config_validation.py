"""Production configuration fails fast on invalid values."""

import pytest
from pydantic import ValidationError

from mina_api.core.config import Settings


def test_production_rejects_debug_mode():
    with pytest.raises(ValidationError):
        Settings(environment="production", debug=True, cors_origins=["https://app.mina.example"])


def test_production_requires_cors_allowlist():
    with pytest.raises(ValidationError):
        Settings(environment="production", cors_origins=[])


def test_production_rejects_cors_wildcard():
    with pytest.raises(ValidationError):
        Settings(environment="production", cors_origins=["*"])


def test_rejects_non_positive_timeout():
    with pytest.raises(ValidationError):
        Settings(request_timeout_seconds=0)


def test_rejects_unknown_log_level():
    with pytest.raises(ValidationError):
        Settings(log_level="LOUD")


def test_cors_origins_accept_comma_separated_string():
    settings = Settings(cors_origins="https://a.example, https://b.example")
    assert settings.cors_origins == ["https://a.example", "https://b.example"]
