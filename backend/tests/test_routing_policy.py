"""Task-to-group mapping, gating and candidate ordering."""
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

import pytest

from mina_api.routing.routing_policy import (
    Candidate,
    ResolvedCredential,
    TaskSpec,
    UserPrefs,
    build_plan,
    group_for_task,
)
from mina_api.routing.provider_registry import load_registry

NOW = datetime.now(timezone.utc)
KEY = "sk-test-0000000000000000"


def creds(provider: str, source: str = "byok", n: int = 1) -> list:
    return [
        ResolvedCredential(credential_id=uuid4(), provider=provider, source=source, api_key=KEY)
        for _ in range(n)
    ]


class OkGate:
    def is_available(self, *a, **k):
        return True, "ok"
    def can_attempt(self, *a, **k):
        return True, "ok"


def test_task_types_map_to_groups():
    assert group_for_task("intent_classification") is group_for_task.__globals__["ModelGroup"].FAST
    assert group_for_task("coding") is group_for_task.__globals__["ModelGroup"].POWERFUL
    with pytest.raises(ValueError):
        group_for_task("quantum_leap")


def test_unknown_task_type_rejected():
    with pytest.raises(ValueError):
        build_plan(
            load_registry(), TaskSpec(task_type="quantum_leap"), UserPrefs(), {},
            health=OkGate(), usage=OkGate(), now=NOW,
        )


def test_tools_requirement_gates_models():
    """A model without tool support is rejected when the task needs tools."""
    import json as _json
    from mina_api.routing.provider_registry import DEFAULT_REGISTRY_PATH, registry_from_dict

    data = _json.loads(DEFAULT_REGISTRY_PATH.read_text())
    data["models"].append(
        {"model_id": "no-tools", "provider": "openai", "litellm_model": "openai/no-tools",
         "groups": ["fast"], "context_window": 100000, "priority": 1,
         "capabilities": {"tools": False, "vision": False, "json_mode": True}}
    )
    registry = registry_from_dict(data)
    plan = build_plan(
        registry, TaskSpec(task_type="intent_classification", needs_tools=True),
        UserPrefs(), {"openai": creds("openai")},
        health=OkGate(), usage=OkGate(), now=NOW,
    )
    assert all(c.model_id != "no-tools" for c in plan.candidates)
    assert any("tool calling" in r.reason for r in plan.rejected)


def test_context_overflow_triggers_escalation():
    """When nothing in the group fits the prompt, a larger-context model from
    another group is the last resort."""
    import json as _json
    from mina_api.routing.provider_registry import DEFAULT_REGISTRY_PATH, registry_from_dict

    data = _json.loads(DEFAULT_REGISTRY_PATH.read_text())
    # Leave exactly one cheap model, with a tiny context window, so nothing in
    # the cheap group fits a 52k-token prompt and escalation must kick in.
    for entry in data["models"]:
        if entry["model_id"] == "gpt-oss-20b":
            entry["groups"] = ["cheap"]
            entry["context_window"] = 4000
        else:
            entry["groups"] = [g for g in entry["groups"] if g != "cheap"]
    registry = registry_from_dict(data)
    by_provider = {"openai": creds("openai"), "anthropic": creds("anthropic")}
    task = TaskSpec(task_type="simple_chat", input_tokens=50_000, max_output_tokens=2_000)
    plan = build_plan(registry, task, UserPrefs(), by_provider, health=OkGate(), usage=OkGate(), now=NOW)
    assert any("context window too small" in r.reason for r in plan.rejected)
    assert any(c.reason == "context_escalated" for c in plan.candidates)
    assert all(c.spec.context_window >= task.required_context for c in plan.candidates)


def test_no_escalation_when_primary_group_fits():
    """A fitting group keeps the plan inside its own group."""
    registry = load_registry()
    by_provider = {"openai": creds("openai"), "gemini": creds("gemini")}
    task = TaskSpec(task_type="simple_chat", input_tokens=500_000, max_output_tokens=8_000)
    plan = build_plan(registry, task, UserPrefs(), by_provider, health=OkGate(), usage=OkGate(), now=NOW)
    assert all(c.reason == "group_match" for c in plan.candidates)
    assert plan.candidates


def test_byok_only_policy_never_receives_managed_credentials():
    registry = load_registry()
    by_provider = {"openai": creds("openai", source="managed")}
    plan = build_plan(
        registry, TaskSpec(task_type="simple_chat"), UserPrefs(policy="byok_only"),
        by_provider, health=OkGate(), usage=OkGate(), now=NOW,
    )
    assert plan.candidates == []
    assert all("not allowed by policy" in r.reason or "no usable credential" in r.reason
               for r in plan.rejected)


def test_byok_first_without_explicit_opt_in_stays_byok():
    registry = load_registry()
    by_provider = {
        "openai": [ResolvedCredential(uuid4(), "openai", "byok", KEY),
                   ResolvedCredential(uuid4(), "openai", "managed", KEY)]
    }
    plan = build_plan(
        registry, TaskSpec(task_type="simple_chat"), UserPrefs(policy="byok_first"),
        by_provider, health=OkGate(), usage=OkGate(), now=NOW,
    )
    assert {c.credential.source for c in plan.candidates} == {"byok"}
    # with the explicit opt-in, managed joins the tail
    plan2 = build_plan(
        registry, TaskSpec(task_type="simple_chat"),
        UserPrefs(policy="byok_first", allow_managed_fallback=True),
        by_provider, health=OkGate(), usage=OkGate(), now=NOW,
    )
    sources = [c.credential.source for c in plan2.candidates]
    assert "managed" in sources and sources[0] == "byok"


def test_cooldown_and_rate_limit_gates_skip_candidates():
    registry = load_registry()
    by_provider = {"openai": creds("openai", n=2)}
    c1, c2 = by_provider["openai"]

    class Gated:
        def __init__(self, blocked_ids):
            self.blocked_ids = set(blocked_ids)  # credential UUIDs
        def is_available(self, provider, credential_id, now=None):
            return (credential_id not in self.blocked_ids, "cooling down")
        def can_attempt(self, provider, credential_id, tokens, now=None):
            return (credential_id not in self.blocked_ids, "rate limited")

    plan = build_plan(
        registry, TaskSpec(task_type="conversation"),
        UserPrefs(), {"openai": [c1, c2]},
        health=Gated({c1.credential_id}), usage=Gated({c2.credential_id}), now=NOW,
    )
    assert plan.candidates == []
    assert any("gated" in r.reason for r in plan.rejected)
    assert any("rate limited" in r.reason for r in plan.rejected)


def test_preferred_model_pinned_first():
    registry = load_registry()
    by_provider = {p: creds(p) for p in ("openai", "gemini", "groq")}
    plan = build_plan(
        registry, TaskSpec(task_type="conversation"), UserPrefs(preferred_model="gemini-3.8-flash"),
        by_provider, health=OkGate(), usage=OkGate(), now=NOW,
    )
    assert plan.candidates[0].model_id == "gemini-3.8-flash"
    assert plan.candidates[0].reason == "group_match"


def test_preferred_group_overrides_task_group():
    registry = load_registry()
    by_provider = {"openai": creds("openai")}
    plan = build_plan(
        registry, TaskSpec(task_type="intent_classification"), UserPrefs(preferred_group="powerful"),
        by_provider, health=OkGate(), usage=OkGate(), now=NOW,
    )
    assert plan.group.value == "powerful"
    assert all("powerful" in c.spec.groups for c in plan.candidates)
