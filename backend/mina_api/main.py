"""Application factory for the Mina API.

create_app() is the single entry point: it wires configuration, logging,
middleware, CORS, exception handlers and the /v1 router. `app` at module
level is what uvicorn and Docker run.
"""

from contextlib import asynccontextmanager
import json

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from mina_api.api import api_v1_router
from mina_api.core.config import Settings, get_settings
from mina_api.core.errors import register_exception_handlers
from mina_api.core.logging import configure_logging
from mina_api.core.middleware import RequestIdMiddleware, TimeoutMiddleware
from mina_api.database.session import Database
from mina_api.security.auth import TokenVerifier
from mina_api.providers.registry import ProviderRegistry, parse_extra_providers
from mina_api.security.crypto import EnvelopeCrypto, SecretBox
from mina_api.services.provider_keys import provision_managed_keys


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    # Debug mode is never enabled on the ASGI app, even in development;
    # enable it only through MINA_DEBUG locally if you really need it.
    configure_logging(settings.log_level, json_output=settings.log_json)

    @asynccontextmanager
    async def _lifespan(application: FastAPI):
        managed = json.loads(application.state.settings.managed_provider_keys or "{}")
        if managed and application.state.database.engine is not None:
            async with application.state.database.session() as db:
                await provision_managed_keys(db, application.state.crypto, managed)
        yield

    app = FastAPI(
        title=settings.app_name,
        version=settings.version,
        summary="Backend for the Mina Android AI agent",
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/v1/openapi.json",
        debug=False,
        lifespan=_lifespan,
    )
    app.state.settings = settings
    app.state.database = Database(settings.database_url)
    app.state.auth = TokenVerifier(settings.supabase_jwt_secret, settings.supabase_url, settings.supabase_audience)
    app.state.secrets = SecretBox(settings.encryption_key)
    # Phase 3: envelope encryption for provider keys + provider registry.
    app.state.crypto = EnvelopeCrypto.from_env_mapping(settings.encryption_keys_json, settings.encryption_key)
    app.state.registry = ProviderRegistry(parse_extra_providers(settings.extra_providers))

    app.include_router(api_v1_router, prefix="/v1")

    # CORS must stay outermost so preflight requests never reach our
    # handlers; RequestId sits inside it so every response (timeouts and
    # 500s included) carries an x-request-id.
    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=list(settings.cors_origins),
            allow_credentials=settings.cors_allow_credentials,
            allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
        )
    app.add_middleware(RequestIdMiddleware)
    app.add_middleware(TimeoutMiddleware, seconds=settings.request_timeout_seconds)

    register_exception_handlers(app)
    return app


app = create_app()
