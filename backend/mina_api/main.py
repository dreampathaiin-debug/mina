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
from mina_api.integrations.composio import ComposioService
from mina_api.core.config import Settings, get_settings
from mina_api.core.errors import register_exception_handlers
from mina_api.core.logging import configure_logging
from mina_api.core.middleware import RateLimitMiddleware, RequestIdMiddleware, TimeoutMiddleware
from mina_api.database.session import Database
from mina_api.security.auth import TokenVerifier
from mina_api.providers.registry import ProviderRegistry, parse_extra_providers
from mina_api.optimizer.metrics import MetricsLedger
from mina_api.optimizer.pipeline import OptimizerConfig, OptimizingExecutor
from mina_api.optimizer.semantic import HashEmbedder, InMemorySemanticStore, PgVectorSemanticStore
from mina_api.optimizer.stores import InMemoryTemporaryCache, RedisTemporaryCache
from mina_api.optimizer.summarization import ExtractiveSummarizer
from mina_api.routing.model_router import LiteLLMExecutor, ModelRouter
from mina_api.routing.provider_health import ProviderHealth
from mina_api.routing.provider_registry import load_registry
from mina_api.routing.usage_tracker import UsageTracker
from mina_api.security.crypto import EnvelopeCrypto, SecretBox
from mina_api.services.provider_keys import provision_managed_keys


def _persist_optimizer_event(database):
    """Best-effort persistence of optimizer metrics events; failures never
    break a model request. Portable SQL: works on SQLite (tests) and
    PostgreSQL (production)."""

    async def _persist(record: dict) -> None:
        try:
            import uuid as _uuid

            from sqlalchemy import text

            async with database.session() as session:
                await session.execute(
                    text(
                        "INSERT INTO optimizer_events "
                        "(id, user_id, model_id, task_type, tokens_before, tokens_after, output_tokens, "
                        " cache_hit, cache_saved_tokens, est_savings_usd, latency_ms, quality_failure, failure_reason) "
                        "VALUES (:id, :uid, :model_id, :task_type, :tokens_before, :tokens_after, :output_tokens, "
                        " :cache_hit, :cache_saved_tokens, :est_savings_usd, :latency_ms, :quality_failure, :failure_reason)"
                    ),
                    {
                        "id": str(_uuid.uuid4()),
                        "uid": record.get("user_id") or "00000000-0000-0000-0000-000000000000",
                        **{k: v for k, v in record.items() if k != "user_id"},
                    },
                )
                await session.commit()
        except Exception:  # noqa: BLE001 - metrics must never break inference
            return

    return _persist


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
        # Phase 9: the background worker runs in-process ONLY when the
        # hosting platform is configured for it (MINA_WORKER_ENABLED);
        # on free tiers that sleep, drive it with the tick endpoint
        # instead. See docs/BACKGROUND_TASKS.md.
        application.state.worker_loop = None
        if application.state.settings.worker_enabled:
            import asyncio

            application.state.worker_loop = asyncio.create_task(
                application.state.worker.run_forever(application.state.database.session)
            )
        try:
            yield
        finally:
            if application.state.worker_loop is not None:
                application.state.worker_loop.cancel()

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
    app.state.auth = TokenVerifier(
        settings.supabase_jwt_secret, settings.supabase_url, settings.supabase_audience,
        firebase_project_id=settings.firebase_project_id,
    )
    app.state.secrets = SecretBox(settings.encryption_key)
    # Phase 3: envelope encryption for provider keys + provider registry.
    app.state.crypto = EnvelopeCrypto.from_env_mapping(settings.encryption_keys_json, settings.encryption_key)
    app.state.registry = ProviderRegistry(parse_extra_providers(settings.extra_providers))
    # Phase 4: the AI model routing engine.
    _model_registry = load_registry(settings.model_registry_path or None)
    app.state.model_registry = _model_registry
    app.state.provider_health = ProviderHealth(
        failure_threshold=settings.circuit_failure_threshold,
        open_seconds=settings.circuit_open_seconds,
        auth_cooldown_seconds=settings.auth_cooldown_seconds,
        default_rate_cooldown_seconds=settings.default_rate_cooldown_seconds,
    )
    app.state.usage_tracker = UsageTracker()
    _base_executor = LiteLLMExecutor(
        proxy_base_url=settings.litellm_proxy_url,
        proxy_virtual_key=settings.litellm_proxy_virtual_key,
    )
    app.state.model_router = ModelRouter(
        _model_registry,
        app.state.provider_health,
        app.state.usage_tracker,
        _base_executor,
        app.state.crypto,
        max_retries=settings.routing_max_retries,
        retry_backoff_seconds=settings.routing_retry_backoff_seconds,
    )
    # Phase 5: context and token optimizer, wrapped around the executor.
    _temp_cache = (
        RedisTemporaryCache(settings.redis_url)
        if settings.redis_url
        else InMemoryTemporaryCache()
    )
    app.state.optimizer_cache = _temp_cache
    # Phase 6: server-side Composio (managed). Unavailable without a key;
    # connections/tools endpoints then answer 503.
    app.state.composio = ComposioService(settings)

    # Phase 7: the agent orchestrator rides on the router (model access),
    # the Composio service (tools) and the conversation store (context).
    from mina_api.agent.llm import RouterModelCaller
    from mina_api.agent.memory import AgentMemory
    from mina_api.agent.orchestrator import Orchestrator
    from mina_api.agent.planner import Planner
    from mina_api.agent.tools import ComposioToolRegistry
    from mina_api.agent.verifier import Verifier

    _agent_caller = RouterModelCaller(app.state.model_router)
    app.state.agent_orchestrator = Orchestrator(
        planner=Planner(_agent_caller),
        caller=_agent_caller,
        registry=ComposioToolRegistry(app.state.composio),
        verifier=Verifier(_agent_caller),
        memory=AgentMemory(),
    )
    _semantic_store = (
        PgVectorSemanticStore(app.state.database.session)
        if settings.database_url and settings.database_url.startswith("postgresql")
        else InMemorySemanticStore()
    )
    app.state.semantic_store = _semantic_store

    from mina_api.memory.service import MemoryService

    app.state.memory_service = MemoryService(cache=_temp_cache)
    app.state.optimizer_ledger = MetricsLedger(
        persist=_persist_optimizer_event(app.state.database)
    )
    app.state.optimizer = OptimizerConfig(
        enabled=settings.optimizer_enabled,
        response_cache_ttl_seconds=settings.optimizer_response_cache_ttl_seconds,
        semantic_cache_enabled=settings.optimizer_semantic_cache_enabled,
        semantic_threshold=settings.optimizer_semantic_threshold,
        min_cache_input_tokens=settings.optimizer_min_cache_input_tokens,
        memory_budget_tokens=settings.optimizer_memory_budget_tokens,
        tool_budget_tokens=settings.optimizer_tool_budget_tokens,
        tool_selection_enabled=settings.optimizer_tool_selection_enabled,
        prompt_caching_enabled=settings.optimizer_prompt_caching_enabled,
        context_reserve_ratio=settings.optimizer_context_reserve_ratio,
    )
    if settings.optimizer_enabled:
        app.state.model_router.executor = OptimizingExecutor(
            _base_executor,
            cache=_temp_cache,
            semantic_store=_semantic_store,
            embedder=HashEmbedder(),
            summarizer=ExtractiveSummarizer(),
            ledger=app.state.optimizer_ledger,
            config=app.state.optimizer,
        )

    # Phase 9: durable background task engine.
    from mina_api.taskengine.engine import TaskEngine
    from mina_api.taskengine.worker import TaskWorker

    app.state.task_engine = TaskEngine(
        lease_seconds=settings.worker_lease_seconds,
        backoff_base_seconds=settings.worker_backoff_base_seconds,
        backoff_cap_seconds=settings.worker_backoff_cap_seconds,
        per_user_concurrency=settings.tasks_per_user_concurrency,
    )
    app.state.worker = TaskWorker(app.state.task_engine, worker_id="inline", poll_seconds=settings.worker_poll_seconds)

    async def _noop_handler(ctx):
        await ctx.step("done", {})
        return ctx.payload.get("result", "done")

    app.state.worker.register("legacy", _noop_handler)
    app.state.worker.register("noop", _noop_handler)

    # Phase 10: observability and usage analytics. One recorder fans out
    # to structured logs and (optionally) Langfuse; budgets gate every
    # model attempt before it spends.
    import json as _json

    from mina_api.api.v1.observability import budget_router, router as observability_router
    from mina_api.observability import (
        ActiveRequestRegistry,
        BudgetService,
        LangfuseExporter,
        ObservabilityRecorder,
    )
    from mina_api.observability.executor import ObservabilityExecutor

    _langfuse = LangfuseExporter.from_settings(settings)
    app.state.obs_recorder = ObservabilityRecorder(
        exporters=[_langfuse] if _langfuse else [],
        session_factory=app.state.database.session,
    )
    _provider_budgets: dict[str, float] = {}
    if settings.provider_daily_budgets_json:
        try:
            _raw_budgets = _json.loads(settings.provider_daily_budgets_json)
            _provider_budgets = {str(k): float(v) for k, v in _raw_budgets.items() if float(v) > 0}
        except (ValueError, TypeError):
            _provider_budgets = {}
    _alert_thresholds = tuple(
        int(part.strip()) for part in str(settings.budget_alert_thresholds or "").split(",") if part.strip().isdigit()
    )
    app.state.budget_service = BudgetService(
        provider_daily_budgets=_provider_budgets,
        default_daily_budget_usd=settings.default_daily_budget_usd,
        default_monthly_budget_usd=settings.default_monthly_budget_usd,
        default_daily_token_limit=settings.default_daily_token_limit,
        default_monthly_token_limit=settings.default_monthly_token_limit,
        alert_thresholds=_alert_thresholds or (80, 95),
    )
    app.state.active_requests = ActiveRequestRegistry()
    app.state.composio.set_recorder(app.state.obs_recorder)

    async def _budget_gate(user_id: str, provider: str | None):
        async with app.state.database.session() as gate_db:
            return await app.state.budget_service.check_request(gate_db, user_id, provider=provider)

    app.state.model_router.executor = ObservabilityExecutor(
        app.state.model_router.executor,
        recorder=app.state.obs_recorder,
        budget_check=_budget_gate,
        active_requests=app.state.active_requests,
        cost_estimator=app.state.usage_tracker.estimate_cost,
    )
    app.include_router(observability_router, prefix="/v1")
    app.include_router(budget_router, prefix="/v1")

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
    if settings.rate_limit_enabled:
        app.add_middleware(RateLimitMiddleware, requests_per_minute=settings.rate_limit_requests_per_minute)

    register_exception_handlers(app)
    return app


app = create_app()
