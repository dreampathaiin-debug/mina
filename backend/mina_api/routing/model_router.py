"""ModelRouter: credential resolution, planning and execution.

Ties the pieces together:
- user preferences come from user_ai_settings (policy, managed fallback,
  preferred model/group) — the same row the /v1/settings/ai-policy API
  maintains;
- credentials resolve through the Phase 3 key service (select_keys_for_request),
  which already encodes byok_only / byok_first+explicit-opt-in /
  managed_only; plaintext keys exist only inside ResolvedCredential and are
  never logged, cached or serialized;
- LiteLLMExecutor calls the LiteLLM Python SDK. For LiteLLM Proxy later,
  point MINA_LITELLM_PROXY_URL at the proxy and the executor switches its
  base URL + virtual-key auth; registry entries are unchanged.
"""

from __future__ import annotations

import time
from dataclasses import replace
from uuid import UUID

from mina_api.routing.fallback_manager import (
    ChatRequestSpec,
    CompletionOutcome,
    CompletionResult,
    FallbackManager,
    ModelCallError,
)
from mina_api.routing.provider_registry import ModelSpec, Registry
from mina_api.routing.routing_policy import (
    Candidate,
    ModelGroup,
    ResolvedCredential,
    RoutePlan,
    TaskSpec,
    UserPrefs,
    build_plan,
    group_for_task,
)
from mina_api.routing.provider_health import ProviderHealth
from mina_api.routing.usage_tracker import UsageTracker
from mina_api.security.crypto import EnvelopeCrypto
from mina_api.services.provider_keys import decrypt_stored, get_or_default_settings, select_keys_for_request


class LiteLLMExecutor:
    """Calls litellm.acompletion. Exceptions are mapped to ModelCallError
    kinds by status code / exception class."""

    def __init__(self, *, proxy_base_url: str | None = None, proxy_virtual_key: str | None = None) -> None:
        self.proxy_base_url = proxy_base_url
        self.proxy_virtual_key = proxy_virtual_key

    async def complete(self, candidate: Candidate, request: ChatRequestSpec) -> CompletionResult:
        spec: ModelSpec = candidate.spec  # type: ignore[assignment]
        import litellm

        kwargs: dict = {
            "model": spec.litellm_model,
            "messages": request.messages,
            "api_key": candidate.credential.api_key,
            "timeout": spec.timeout_seconds,
        }
        if spec.base_url:
            kwargs["base_url"] = spec.base_url
        elif self.proxy_base_url:
            # LiteLLM Proxy mode: same model strings, different endpoint.
            kwargs["base_url"] = self.proxy_base_url
            if self.proxy_virtual_key:
                kwargs["api_key"] = self.proxy_virtual_key
        if request.tools:
            kwargs["tools"] = request.tools
        if request.temperature is not None:
            kwargs["temperature"] = request.temperature
        if request.max_tokens is not None:
            kwargs["max_tokens"] = request.max_tokens

        started = time.monotonic()
        try:
            response = await litellm.acompletion(**kwargs)
        except litellm.exceptions.ContextWindowExceededError as exc:
            raise ModelCallError("context_overflow", detail=str(exc)) from exc
        except litellm.exceptions.UnsupportedParamsError as exc:
            raise ModelCallError("unsupported_tools", detail=str(exc)) from exc
        except litellm.exceptions.AuthenticationError as exc:
            raise ModelCallError("auth", status_code=401, detail=str(exc)) from exc
        except litellm.exceptions.PermissionDeniedError as exc:
            raise ModelCallError("forbidden", status_code=403, detail=str(exc)) from exc
        except litellm.exceptions.RateLimitError as exc:
            retry_after = None
            headers = getattr(exc, "headers", None) or {}
            raw = headers.get("retry-after")
            if raw:
                try:
                    retry_after = float(raw)
                except (TypeError, ValueError):
                    retry_after = None
            raise ModelCallError("rate_limited", status_code=429, retry_after=retry_after, detail=str(exc)) from exc
        except litellm.exceptions.Timeout as exc:
            raise ModelCallError("timeout", detail=str(exc)) from exc
        except litellm.exceptions.APIConnectionError as exc:
            raise ModelCallError("network", detail=str(exc)) from exc
        except litellm.exceptions.APIError as exc:
            status = getattr(exc, "status_code", None)
            if isinstance(status, int) and status >= 500:
                raise ModelCallError("server", status_code=status, detail=str(exc)) from exc
            raise ModelCallError("unknown", status_code=status, detail=str(exc)) from exc

        latency_ms = int((time.monotonic() - started) * 1000)
        usage = getattr(response, "usage", None)
        prompt = int(getattr(usage, "prompt_tokens", 0) or 0)
        completion = int(getattr(usage, "completion_tokens", 0) or 0)
        message = response.choices[0].message  # type: ignore[index]
        tool_calls = None
        raw_calls = getattr(message, "tool_calls", None)
        if raw_calls:
            tool_calls = [
                {
                    "id": call.id,
                    "name": call.function.name,
                    "arguments": call.function.arguments,
                }
                for call in raw_calls
            ]
        return CompletionResult(
            content=getattr(message, "content", None),
            tool_calls=tool_calls,
            model_id=spec.model_id,
            prompt_tokens=prompt,
            completion_tokens=completion,
            latency_ms=latency_ms,
            finish_reason=getattr(response.choices[0], "finish_reason", None),  # type: ignore[index]
        )


class ModelRouter:
    def __init__(
        self,
        registry: Registry,
        health: ProviderHealth,
        usage: UsageTracker,
        executor,
        crypto: EnvelopeCrypto | None,
        *,
        max_retries: int = 2,
        retry_backoff_seconds: float = 0.5,
    ) -> None:
        self.registry = registry
        self.health = health
        self.usage = usage
        self.executor = executor
        self.crypto = crypto
        self.fallback = FallbackManager(
            health, usage, max_retries=max_retries, retry_backoff_seconds=retry_backoff_seconds
        )

    async def _resolve_credentials(
        self, db, user_id: UUID, providers: set[str]
    ) -> dict[str, list[ResolvedCredential]]:
        if self.crypto is None:
            return {}
        resolved: dict[str, list[ResolvedCredential]] = {}
        for provider in providers:
            rows = await select_keys_for_request(db, user_id, provider)
            creds: list[ResolvedCredential] = []
            for row in rows:
                plaintext = decrypt_stored(row, self.crypto)
                if plaintext is None:
                    continue  # undecryptable: skip silently, never log material
                creds.append(
                    ResolvedCredential(
                        credential_id=row.id, provider=row.provider, source=row.source, api_key=plaintext
                    )
                )
            if creds:
                resolved[provider] = creds
        return resolved

    async def build_plan(
        self,
        db,
        user_id: UUID,
        task: TaskSpec,
        *,
        request_id: str | None = None,
        prefs_override: UserPrefs | None = None,
    ) -> RoutePlan:
        settings_row = await get_or_default_settings(db, user_id)
        prefs = prefs_override or UserPrefs(
            policy=settings_row.policy,
            allow_managed_fallback=settings_row.allow_managed_fallback,
            preferred_model=getattr(settings_row, "preferred_model", None),
            preferred_group=getattr(settings_row, "preferred_group", None),
        )
        self._last_policy = settings_row.policy
        if prefs.preferred_group and prefs.preferred_group not in {"fast", "cheap", "balanced", "powerful"}:
            prefs = UserPrefs(
                policy=prefs.policy,
                allow_managed_fallback=prefs.allow_managed_fallback,
                preferred_model=prefs.preferred_model,
                preferred_group=None,
            )

        group = (
            ModelGroup(prefs.preferred_group)
            if prefs.preferred_group
            else group_for_task(task.task_type)
        )
        pools = list(self.registry.models_for_group(group.value))
        if task.allow_context_escalation:
            pools.extend(self.registry.enabled_models())
        providers = {spec.provider for spec in pools}
        credentials = await self._resolve_credentials(db, user_id, providers)

        caps = {
            provider: self.registry.provider_caps(provider)
            for provider in providers
        }
        plan = build_plan(
            self.registry,
            task,
            prefs,
            credentials,
            health=self.health,
            usage=self.usage,
            trace_id=None,
            request_id=request_id,
        )
        # Provider-level RPM/TPM limits from the capability registry are
        # advisory context for the usage gate; per-credential windows already
        # ran inside build_plan via usage.can_attempt.
        self._last_provider_caps = caps
        return plan

    async def complete(
        self,
        db,
        user_id: UUID,
        task: TaskSpec,
        request: ChatRequestSpec,
        *,
        request_id: str | None = None,
    ) -> CompletionOutcome:
        plan = await self.build_plan(db, user_id, task, request_id=request_id)
        # Phase 5: the optimizing executor scopes caches by user + model +
        # policy and reads the task type from metadata; inject it here.
        request = replace(
            request,
            metadata={
                **request.metadata,
                "user_id": str(user_id),
                "policy": getattr(self, "_last_policy", "byok_only"),
                "task_type": task.task_type,
            },
        )
        return await self.fallback.execute(plan, request, self.executor)
