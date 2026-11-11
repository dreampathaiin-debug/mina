"""Environment-driven configuration.

Every value comes from MINA_* environment variables (with an optional
.env file for local development). Production fails fast on bad config:
missing CORS allowlist, wildcard CORS or debug mode all refuse to boot.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="MINA_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "Mina API"
    version: str = "0.1.0"
    environment: Literal["development", "staging", "production"] = "development"
    debug: bool = False
    log_level: str = "INFO"
    log_json: bool = True

    # Explicit allowlist only; a wildcard is rejected in production.
    cors_origins: list[str] = Field(default_factory=list)
    cors_allow_credentials: bool = False

    request_timeout_seconds: float = 30.0

    # Optional in Phase 1. When set, /v1/ready probes it; when unset the
    # readiness check reports "skipped" so free-tier deploys need nothing.
    database_url: str | None = None

    @field_validator("cors_origins", mode="before")
    @classmethod
    def _split_origins(cls, value: object) -> object:
        # Accept both JSON arrays and comma-separated strings, so
        # MINA_CORS_ORIGINS="https://a,https://b" works on Render.
        if isinstance(value, str):
            return [item.strip() for item in value.split(",") if item.strip()]
        return value

    @field_validator("log_level")
    @classmethod
    def _check_log_level(cls, value: str) -> str:
        level = value.upper()
        if level not in {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}:
            raise ValueError(f"MINA_LOG_LEVEL must be one of DEBUG/INFO/WARNING/ERROR/CRITICAL, got {value!r}")
        return level

    @model_validator(mode="after")
    def _validate_production(self) -> "Settings":
        if self.environment == "production":
            if self.debug:
                raise ValueError("MINA_DEBUG must not be enabled in production")
            if not self.cors_origins:
                raise ValueError("MINA_CORS_ORIGINS must be a non-empty allowlist in production")
            if "*" in self.cors_origins:
                raise ValueError("CORS wildcard '*' is not allowed in production")
        if self.request_timeout_seconds <= 0:
            raise ValueError("MINA_REQUEST_TIMEOUT_SECONDS must be positive")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
