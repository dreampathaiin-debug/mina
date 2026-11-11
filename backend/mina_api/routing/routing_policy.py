"""Routing policy: task complexity to model group, candidate selection.

Pure decision logic (no I/O): given the model registry, the task's needs,
the user's preferences and the resolved credentials per provider, it
returns an ordered RoutingPlan. Health and rate-limit gates are injected
so the same logic is testable and reusable.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from uuid import UUID, uuid4


class ModelGroup(str, Enum):
    FAST = "fast"          # intent classification, simple extraction
    CHEAP = "cheap"        # simple chat, short summaries, memory extraction
    BALANCED = "balanced"  # normal conversations, research, routine automation
    POWERFUL = "powerful"  # complex reasoning, planning, coding


TASK_GROUP_MAP: dict[str, ModelGroup] = {
    "intent_classification": ModelGroup.FAST,
    "simple_extraction": ModelGroup.FAST,
    "simple_chat": ModelGroup.CHEAP,
    "short_summary": ModelGroup.CHEAP,
    "memory_extraction": ModelGroup.CHEAP,
    "conversation": ModelGroup.BALANCED,
    "research": ModelGroup.BALANCED,
    "routine_automation": ModelGroup.BALANCED,
    "complex_reasoning": ModelGroup.POWERFUL,
    "planning": ModelGroup.POWERFUL,
    "coding": ModelGroup.POWERFUL,
}


@dataclass(frozen=True)
class TaskSpec:
    task_type: str
    needs_tools: bool = False
    input_tokens: int = 0
    max_output_tokens: int = 4096
    allow_context_escalation: bool = True

    @property
    def required_context(self) -> int:
        return self.input_tokens + self.max_output_tokens


@dataclass(frozen=True)
class UserPrefs:
    policy: str = "byok_only"
    allow_managed_fallback: bool = False
    preferred_model: str | None = None
    preferred_group: str | None = None


@dataclass(frozen=True)
class ResolvedCredential:
    """A decrypted, ready-to-use credential. Never serialized or logged."""

    credential_id: UUID
    provider: str
    source: str  # byok | managed
    api_key: str


@dataclass
class Rejection:
    model_id: str
    provider: str
    reason: str


@dataclass(frozen=True)
class Candidate:
    spec: object  # ModelSpec (avoided import to keep modules decoupled)
    credential: ResolvedCredential
    reason: str = "group_match"

    @property
    def model_id(self) -> str:
        return self.spec.model_id  # type: ignore[attr-defined]

    @property
    def provider(self) -> str:
        return self.spec.provider  # type: ignore[attr-defined]


@dataclass
class RoutePlan:
    trace_id: UUID
    request_id: str | None
    group: ModelGroup
    candidates: list[Candidate] = field(default_factory=list)
    rejected: list[Rejection] = field(default_factory=list)
    task: TaskSpec | None = None
    prefs: UserPrefs | None = None

    @property
    def primary(self) -> Candidate | None:
        return self.candidates[0] if self.candidates else None


def group_for_task(task_type: str) -> ModelGroup:
    if task_type not in TASK_GROUP_MAP:
        raise ValueError(
            f"Unknown task_type {task_type!r}; known: {sorted(TASK_GROUP_MAP)}"
        )
    return TASK_GROUP_MAP[task_type]


def _credential_is_sourced_ok(credential: ResolvedCredential, prefs: UserPrefs) -> bool:
    """BYOK never silently becomes managed billing: managed credentials only
    reach a plan when the Phase 3 key service returned them (which already
    encodes byok_only / byok_first+opt-in / managed_only). This is the
    second, defense-in-depth check."""
    if credential.source == "managed":
        return prefs.policy == "managed_only" or (
            prefs.policy == "byok_first" and prefs.allow_managed_fallback
        )
    return True


def build_plan(
    registry,
    task: TaskSpec,
    prefs: UserPrefs,
    credentials_by_provider: dict[str, list[ResolvedCredential]],
    *,
    health=None,
    usage=None,
    now=None,
    trace_id: UUID | None = None,
    request_id: str | None = None,
) -> RoutePlan:
    import datetime as _dt

    now = now or _dt.datetime.now(_dt.timezone.utc)
    group = ModelGroup(prefs.preferred_group) if prefs.preferred_group else group_for_task(task.task_type)
    plan = RoutePlan(trace_id=trace_id or uuid4(), request_id=request_id, group=group, task=task, prefs=prefs)

    pools: list[tuple[object, str]] = [
        (spec, "group_match") for spec in registry.models_for_group(group.value)
    ]
    # Context escalation: only when nothing in the task's group fits the
    # prompt; otherwise other groups' models stay out of the plan. Last
    # resort, largest context first.
    primary_fits = any(spec.context_window >= task.required_context for spec, _ in pools)
    if task.allow_context_escalation and not primary_fits:
        oversized = [
            spec
            for spec in registry.enabled_models()
            if spec.context_window >= task.required_context
            and spec not in {c for c, _ in pools}
        ]
        oversized.sort(key=lambda s: (-s.context_window, s.priority))
        pools.extend((spec, "context_escalated") for spec in oversized)

    seen_ids: set[str] = set()
    for spec, reason in pools:
        if spec.model_id in seen_ids:
            continue
        seen_ids.add(spec.model_id)

        if task.needs_tools and not spec.tools:
            plan.rejected.append(Rejection(spec.model_id, spec.provider, "task needs tool calling; model does not support it"))
            continue
        if task.required_context > spec.context_window:
            plan.rejected.append(Rejection(spec.model_id, spec.provider, "context window too small even after escalation"))
            continue

        creds = credentials_by_provider.get(spec.provider, [])
        if not creds:
            plan.rejected.append(Rejection(spec.model_id, spec.provider, "no usable credential under the user's key policy"))
            continue

        added_for_model = 0
        for credential in creds:
            if not _credential_is_sourced_ok(credential, prefs):
                plan.rejected.append(Rejection(spec.model_id, spec.provider, "managed credential not allowed by policy"))
                continue
            if health is not None:
                ok, gate_reason = health.is_available(spec.provider, credential.credential_id, now=now)
                if not ok:
                    plan.rejected.append(Rejection(spec.model_id, spec.provider, f"credential gated: {gate_reason}"))
                    continue
            if usage is not None:
                ok, gate_reason = usage.can_attempt(spec.provider, credential.credential_id, task.required_context, now=now)
                if not ok:
                    plan.rejected.append(Rejection(spec.model_id, spec.provider, f"rate limited: {gate_reason}"))
                    continue
            plan.candidates.append(Candidate(spec=spec, credential=credential, reason=reason))
            added_for_model += 1
        if creds and added_for_model == 0 and not any(
            r.model_id == spec.model_id for r in plan.rejected
        ):
            plan.rejected.append(Rejection(spec.model_id, spec.provider, "all credentials gated or rate limited"))

    # User's pinned model comes first whenever it is compatible.
    if prefs.preferred_model:
        pinned = [c for c in plan.candidates if c.model_id == prefs.preferred_model]
        rest = [c for c in plan.candidates if c.model_id != prefs.preferred_model]
        if pinned and pinned[0].reason == "group_match":
            pinned = [c for c in pinned if c.spec.supports_group(group.value)]  # type: ignore[attr-defined]
            pinned.sort(key=lambda c: c.spec.priority)  # type: ignore[attr-defined]
        plan.candidates = pinned + rest
        if not pinned and not any(r.model_id == prefs.preferred_model for r in plan.rejected):
            plan.rejected.insert(0, Rejection(prefs.preferred_model, "", "user-preferred model not available for this task"))
    else:
        plan.candidates.sort(key=lambda c: (c.spec.priority,))  # type: ignore[attr-defined]

    return plan
