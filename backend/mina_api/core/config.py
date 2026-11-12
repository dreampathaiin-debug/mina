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
    # Per client+credential request cap per minute (sliding window).
    rate_limit_enabled: bool = True
    rate_limit_requests_per_minute: int = 120

    # Optional in Phase 1. When set, /v1/ready probes it; when unset the
    # readiness check reports "skipped" so free-tier deploys need nothing.
    database_url: str | None = None

    # Supabase Auth (Phase 2). Identity comes ONLY from these verified
    # JWTs; user ids in request bodies are never trusted.
    supabase_url: str | None = None
    supabase_jwt_secret: str | None = None
    supabase_audience: str = "authenticated"
    # Server-only secret for admin operations (account deletion). It is
    # never returned by any endpoint or included in any schema.
    supabase_service_key: str | None = None
    # Firebase third-party auth (Mina Cloud sign-in): identity is verified from
    # Firebase ID tokens (RS256, Google's public keys); UIDs map to
    # deterministic UUIDs so profiles and RLS keep their UUID contract.
    firebase_project_id: str | None = None
    # Firebase Admin SDK service-account JSON (raw or base64): stamps the
    # role:'authenticated' custom claim at provisioning. Never logged or returned.
    firebase_service_account_json: str | None = None
    # Optional Fernet key: encrypts provider API keys at rest.
    encryption_key: str | None = None
    # Envelope-encryption key ring: JSON {"1": "<key>", "2": "<key>"}. Falls
    # back to encryption_key as version 1. Rotating in a new version re-wraps
    # DEKs without touching data.
    encryption_keys_json: str | None = None
    # Extra LiteLLM-compatible providers: "name=https://.../v1/models,..."
    extra_providers: str = ""
    # Mina-managed provider keys (server-side only): JSON {"openai": "..."}.
    managed_provider_keys: str = ""
    # Phase 4 routing engine.
    model_registry_path: str = ""
    litellm_proxy_url: str | None = None
    litellm_proxy_virtual_key: str | None = None
    routing_max_retries: int = 2
    routing_retry_backoff_seconds: float = 0.5
    circuit_failure_threshold: int = 5
    circuit_open_seconds: float = 30.0
    auth_cooldown_seconds: float = 900.0
    default_rate_cooldown_seconds: float = 60.0

    # Phase 5 context and token optimizer.
    optimizer_enabled: bool = True
    redis_url: str | None = None
    optimizer_response_cache_ttl_seconds: int = 3600
    optimizer_semantic_cache_enabled: bool = True
    optimizer_semantic_threshold: float = 0.94
    optimizer_min_cache_input_tokens: int = 512
    optimizer_memory_budget_tokens: int = 1024
    optimizer_tool_budget_tokens: int = 4000
    optimizer_tool_selection_enabled: bool = True
    # Phase 6 production-grade Composio integration. The server holds the
    # Composio API key; Android only ever receives authorization URLs and
    # statuses. Unset, every connections/tools endpoint answers 503.
    composio_api_key: str | None = None
    # Phase 9: durable background task engine.
    worker_enabled: bool = False
    worker_token: str | None = None
    worker_poll_seconds: float = 1.0
    worker_lease_seconds: float = 60.0
    worker_backoff_base_seconds: float = 5.0
    worker_backoff_cap_seconds: float = 3600.0
    tasks_per_user_concurrency: int = 3
    task_default_max_attempts: int = 3
    composio_timeout_seconds: float = 30.0
    optimizer_prompt_caching_enabled: bool = True
    optimizer_context_reserve_ratio: float = 0.85

    # Phase 10: observability + usage analytics.
    langfuse_host: str | None = None
    langfuse_public_key: str | None = None
    langfuse_secret_key: str | None = None
    # Global default budget limits applied when a user has no override.
    default_daily_budget_usd: float | None = None
    default_monthly_budget_usd: float | None = None
    default_daily_token_limit: int | None = None
    default_monthly_token_limit: int | None = None
    # Per-provider daily USD caps for MANAGED spend: JSON like {"openai": 10.0}.
    provider_daily_budgets_json: str | None = None
    budget_alert_thresholds: str = "80,95"

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
            if not self.supabase_url:
                raise ValueError("MINA_SUPABASE_URL is required in production")
            if not self.supabase_jwt_secret:
                raise ValueError("MINA_SUPABASE_JWT_SECRET is required in production")
        if self.request_timeout_seconds <= 0:
            raise ValueError("MINA_REQUEST_TIMEOUT_SECONDS must be positive")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
