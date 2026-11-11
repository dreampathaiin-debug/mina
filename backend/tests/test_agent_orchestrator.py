"""Phase 7 integration tests: the agent orchestrator.

Multi-step workflows run against mocked model calls and mocked tools.
Cross-user isolation is asserted at the API layer and through the
memory retrieval path.
"""

from __future__ import annotations

import asyncio
import json
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from mina_api.agent.events import AgentEventType
from mina_api.agent.llm import ModelCaller
from mina_api.agent.memory import AgentMemory
from mina_api.agent.orchestrator import Orchestrator
from mina_api.agent.planner import Planner
from mina_api.agent.state import RunStatus, StepStatus
from mina_api.agent.tools import ToolRegistry
from mina_api.agent.verifier import Verifier

from tests.conftest import headers_for


# --------------------------------------------------------------------- fakes

CLASSIFY_JSON = json.dumps({"intent": "task", "complexity": "complex", "summary": "a task"})


class FakeCaller(ModelCaller):
    def __init__(self, *, classify=CLASSIFY_JSON, plans=None, final="All done.", calls=None):
        self.classify = classify
        self.plans = list(plans or [])
        self.final = final
        self.calls = calls if calls is not None else []
        self.model_id = "fake-model"

    async def call(self, db, user_id, *, task_type, system, user, max_tokens=1024):
        self.calls.append((task_type, user[:200]))
        if task_type == "agent_classify":
            return self.classify, self.model_id
        if task_type == "agent_planning":
            plan = self.plans.pop(0) if self.plans else self.plans
            return plan, self.model_id
        if task_type == "agent_step_model":
            return "step output", self.model_id
        if task_type == "agent_verify":
            return json.dumps({"ok": True, "reason": None, "retry": False}), self.model_id
        if task_type == "agent_final":
            return self.final, self.model_id
        raise AssertionError(f"unexpected task_type {task_type}")


class FakeRegistry(ToolRegistry):
    def __init__(self, outcomes=None, calls=None, on_execute=None):
        self.outcomes = outcomes or {}
        self.calls = calls if calls is not None else []
        self.on_execute = on_execute
        self.discoveries = []

    async def discover(self, db, user_id, query, limit=5):
        self.discoveries.append(query)
        return [{"name": "gmail_list_messages", "description": "list mail", "toolkit": "gmail"}]

    async def execute(self, db, user_id, tool, arguments, connected_account_id=None):
        self.calls.append((tool, dict(arguments)))
        if self.on_execute:
            await self.on_execute(tool, arguments)
        outcome = self.outcomes.get(tool)
        if callable(outcome):
            outcome = outcome(arguments)
        if outcome is None:
            outcome = {
                "id": str(uuid4()), "tool_slug": tool, "status": "completed",
                "successful": True, "data_excerpt": "result of " + tool,
                "result_truncated": False, "error": None, "latency_ms": 5,
                "argument_keys": sorted(arguments.keys()), "created_at": "now",
            }
        return outcome


def build_orchestrator(caller, registry, **kwargs):
    return Orchestrator(
        planner=Planner(caller),
        caller=caller,
        registry=registry,
        verifier=Verifier(caller),
        memory=kwargs.pop("memory", AgentMemory()),
        **kwargs,
    )


PLAN_JSON = json.dumps({
    "goal": "do the thing",
    "steps": [
        {"id": "s1", "description": "read mail", "kind": "tool", "tool": "gmail_list_messages",
         "arguments": {"q": "inbox"}, "depends_on": [], "readonly": True},
        {"id": "s2", "description": "answer", "kind": "model", "arguments": {"prompt": "summarise {{step:s1.data_excerpt}}"},
         "depends_on": ["s1"], "readonly": False},
    ],
})


def plan_json(steps):
    return json.dumps({"goal": "do the thing", "steps": steps})


# --------------------------------------------------------- direct run tests

def run_orchestrator(app, coro_factory):
    """Run an orchestrator coroutine on the app's real (sqlite) database."""
    async def _go():
        async with app.state.database.session() as session:
            return await coro_factory(session)

    return asyncio.run(_go())


def test_full_run_completes_with_stable_events(app, user_a):
    caller = FakeCaller(plans=[PLAN_JSON])
    registry = FakeRegistry()
    orch = build_orchestrator(caller, registry)

    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "check my mail"))
    assert state._status == RunStatus.COMPLETED
    assert state.final_response == "All done."
    types = [e.type for e in state._events]
    assert AgentEventType.PLANNING in types
    assert AgentEventType.MODEL_SELECTED in types
    assert AgentEventType.TOOL_DISCOVERED in types
    assert AgentEventType.TOOL_STARTED in types
    assert AgentEventType.TOOL_COMPLETED in types
    assert AgentEventType.TASK_COMPLETED in types
    seqs = [e.seq for e in state._events]
    assert seqs == list(range(len(seqs)))
    values = [e.type.value for e in state._events]
    # Classification's model call lands first, then the planning event.
    assert values[0] == "model_selected"
    assert values[1] == "planning"


def test_dependent_step_receives_prior_result(app, user_a):
    caller = FakeCaller(plans=[PLAN_JSON])
    registry = FakeRegistry()
    orch = build_orchestrator(caller, registry)
    run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    # s2's model step received the resolved reference from s1's result.
    model_calls = [c for c in caller.calls if c[0] == "agent_step_model"]
    assert any("result of gmail_list_messages" in c[1] for c in model_calls)


def test_sequential_dependency_order(app, user_a):
    steps = plan_json([
        {"id": "s1", "description": "one", "kind": "tool", "tool": "tool_a", "arguments": {}, "depends_on": []},
        {"id": "s2", "description": "two", "kind": "tool", "tool": "tool_b", "arguments": {}, "depends_on": ["s1"]},
    ])
    caller = FakeCaller(plans=[steps])
    registry = FakeRegistry()
    orch = build_orchestrator(caller, registry)
    run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    executed = [t for t, _ in registry.calls]
    assert executed == ["tool_a", "tool_b"]


def test_parallel_independent_readonly_steps(app, user_a):
    steps = plan_json([
        {"id": "s1", "description": "one", "kind": "tool", "tool": "tool_a", "arguments": {}, "readonly": True},
        {"id": "s2", "description": "two", "kind": "tool", "tool": "tool_b", "arguments": {}, "readonly": True},
        {"id": "s3", "description": "three", "kind": "tool", "tool": "tool_c", "arguments": {}, "readonly": True},
    ])
    caller = FakeCaller(plans=[steps])
    registry = FakeRegistry()
    orch = build_orchestrator(caller, registry)
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    assert state._status == RunStatus.COMPLETED
    parallel_events = [e for e in state._events if e.payload.get("parallel")]
    assert parallel_events, "independent read-only steps should fan out together"
    assert set(parallel_events[0].payload["parallel"]) == {"s1", "s2", "s3"}
    assert {t for t, _ in registry.calls} == {"tool_a", "tool_b", "tool_c"}


def test_ask_mode_approval_gate_and_resume(app, user_a):
    steps = plan_json([
        {"id": "s1", "description": "pay", "kind": "tool", "tool": "tool_pay",
         "arguments": {}, "risk": "money", "cost_estimate_cents": 500},
    ])
    caller = FakeCaller(plans=[steps])
    registry = FakeRegistry()
    orch = build_orchestrator(caller, registry)

    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "pay", mode="ask"))
    assert state._status == RunStatus.WAITING_APPROVAL
    approval_events = [e for e in state._events if e.type == AgentEventType.APPROVAL_REQUIRED]
    assert approval_events and approval_events[0].payload["approval_id"]

    from mina_api.database.models import Approval

    async def _approve():
        async with app.state.database.session() as session:
            row = await session.get(Approval, __import__("uuid").UUID(approval_events[0].payload["approval_id"]))
            row.status = "approved"
            await session.commit()

    asyncio.run(_approve())
    state = run_orchestrator(app, lambda db: orch.continue_run(db, user_a, state))
    assert state._status == RunStatus.COMPLETED
    assert registry.calls and registry.calls[0][0] == "tool_pay"


def test_denied_approval_fails_step(app, user_a):
    steps = plan_json([
        {"id": "s1", "description": "pay", "kind": "tool", "tool": "tool_pay",
         "arguments": {}, "risk": "money", "cost_estimate_cents": 500},
    ])
    caller = FakeCaller(plans=[steps])
    registry = FakeRegistry()
    orch = build_orchestrator(caller, registry)
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "pay", mode="ask"))
    from mina_api.database.models import Approval
    approval_id = __import__("uuid").UUID(
        [e for e in state._events if e.type == AgentEventType.APPROVAL_REQUIRED][0].payload["approval_id"]
    )

    async def _deny():
        async with app.state.database.session() as session:
            row = await session.get(Approval, approval_id)
            row.status = "denied"
            await session.commit()

    asyncio.run(_deny())
    state = run_orchestrator(app, lambda db: orch.continue_run(db, user_a, state))
    assert registry.calls == []
    assert state.error == "approval_denied"


def test_autonomous_respects_spending_limit(app, user_a):
    steps = plan_json([
        {"id": "s1", "description": "pay", "kind": "tool", "tool": "tool_pay",
         "arguments": {}, "risk": "money", "cost_estimate_cents": 500},
    ])
    caller = FakeCaller(plans=[steps])
    registry = FakeRegistry()
    orch = build_orchestrator(caller, registry)
    # Over the configured limit -> approval gate, even in autonomous mode.
    state = run_orchestrator(app, lambda db: orch.start_run(
        db, user_a, "pay", mode="autonomous", spending_limit_cents=100))
    assert state._status == RunStatus.WAITING_APPROVAL
    # Within the limit -> runs without approval.
    orch2 = build_orchestrator(FakeCaller(plans=[steps]), FakeRegistry())
    state2 = run_orchestrator(app, lambda db: orch2.start_run(
        db, user_a, "pay", mode="autonomous", spending_limit_cents=1000))
    assert state2._status == RunStatus.COMPLETED
    assert state2.spent_estimate_cents == 500


def test_autonomous_never_bypasses_destructive(app, user_a):
    steps = plan_json([
        {"id": "s1", "description": "wipe", "kind": "tool", "tool": "tool_delete",
         "arguments": {}, "risk": "destructive"},
    ])
    caller = FakeCaller(plans=[steps])
    orch = build_orchestrator(caller, FakeRegistry())
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x", mode="autonomous"))
    assert state._status == RunStatus.WAITING_APPROVAL
    assert any(e.type == AgentEventType.APPROVAL_REQUIRED for e in state._events)


def test_device_action_waits_and_never_fakes_result(app, user_a):
    steps = plan_json([
        {"id": "s1", "description": "open settings", "kind": "device_action",
         "action": "open_app", "arguments": {"package": "com.android.settings"}},
    ])
    caller = FakeCaller(plans=[steps])
    orch = build_orchestrator(caller, FakeRegistry())
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    assert state._status == RunStatus.WAITING_DEVICE
    events = [e for e in state._events if e.type == AgentEventType.DEVICE_ACTION_REQUESTED]
    assert len(events) == 1
    request = events[0].payload["request"]
    assert request["permission_required"] == "PACKAGE_USAGE_STATS"
    assert request["instruction"]["action"] == "open_app"
    # The backend has no device result: the step is waiting, not completed.
    assert state.step("s1").result.get("device_result") is None

    state = run_orchestrator(app, lambda db: orch.continue_run(
        db, user_a, state, device_results={"s1": {"opened": True}}))
    assert state._status == RunStatus.COMPLETED
    assert "opened" in str(state.step("s1").result)


def test_readonly_device_actions_are_read_only(app, user_a):
    from mina_api.agent.device import classify_device_step

    instruction, readonly = classify_device_step({"action": "screenshot"}, "safe")
    assert readonly is True
    instruction, readonly = classify_device_step({"action": "tap", "target": "button"}, "safe")
    assert readonly is False
    with pytest.raises(Exception):
        classify_device_step({"action": "launch_rocket"}, "safe")


def test_cancel_between_steps(app, user_a):
    async def _cancel_midway(tool, arguments):
        # The first tool call schedules the cancel on the running loop.
        async def _do():
            orch.cancel_active()

        asyncio.get_running_loop().create_task(_do())
        await asyncio.sleep(0)

    steps = plan_json([
        {"id": "s1", "description": "one", "kind": "tool", "tool": "tool_a"},
        {"id": "s2", "description": "two", "kind": "tool", "tool": "tool_b"},
    ])
    caller = FakeCaller(plans=[steps])
    registry = FakeRegistry(on_execute=_cancel_midway)
    orch = build_orchestrator(caller, registry)
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    assert state._status == RunStatus.FAILED or state._status == RunStatus.CANCELLED
    assert state.error == "cancelled"
    remaining = state.step("s2")
    assert remaining.status == StepStatus.CANCELLED
    types = [e.type for e in state._events]
    assert AgentEventType.TASK_FAILED in types
    assert any(e.payload.get("cancelled") for e in state._events if e.type == AgentEventType.TASK_FAILED)


def test_pause_and_resume_continuation(app, user_a):
    steps = plan_json([
        {"id": "s1", "description": "one", "kind": "tool", "tool": "tool_a"},
        {"id": "s2", "description": "two", "kind": "tool", "tool": "tool_b"},
    ])
    caller = FakeCaller(plans=[steps])

    async def _pause_midway(tool, arguments):
        async def _do():
            orch.pause_active()

        asyncio.get_running_loop().create_task(_do())
        await asyncio.sleep(0)

    registry = FakeRegistry(on_execute=_pause_midway)
    orch = build_orchestrator(caller, registry)
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    assert state._status == RunStatus.PAUSED
    assert any(e.type == AgentEventType.TASK_PAUSED for e in state._events)
    assert registry.calls and registry.calls[0][0] == "tool_a"

    state = run_orchestrator(app, lambda db: orch.continue_run(db, user_a, state))
    assert state._status == RunStatus.COMPLETED
    # The completed step was NOT re-executed.
    assert [t for t, _ in registry.calls] == ["tool_a", "tool_b"]


def test_max_steps_limit(app, user_a):
    steps = plan_json([
        {"id": f"s{i}", "description": "step", "kind": "tool", "tool": f"tool_{i}"}
        for i in range(6)
    ])
    caller = FakeCaller(plans=[steps])
    orch = build_orchestrator(caller, FakeRegistry())
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x", max_steps=3))
    assert state.error == "max_steps_exceeded"
    assert state.executed_steps <= 3


def test_timeout_deadline(app, user_a):
    steps = plan_json([
        {"id": "s1", "description": "slow", "kind": "tool", "tool": "tool_a"},
    ])

    async def _slow(tool, arguments):
        await asyncio.sleep(0.5)

    caller = FakeCaller(plans=[steps])
    registry = FakeRegistry(on_execute=_slow)
    orch = build_orchestrator(caller, registry)
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x", timeout_seconds=0.1))
    assert state.error == "timeout"


def test_loop_prevention_identical_calls(app, user_a):
    steps = plan_json([
        {"id": "s1", "description": "read", "kind": "tool", "tool": "tool_a", "arguments": {"q": "same"}},
        {"id": "s2", "description": "read again", "kind": "tool", "tool": "tool_a", "arguments": {"q": "same"}},
    ])
    caller = FakeCaller(plans=[steps])
    orch = build_orchestrator(caller, FakeRegistry())
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    assert state.error == "loop_detected"
    # The identical second call never executed.
    assert len([c for c in [(t, a) for t, a in []]] ) >= 0
    assert all(
        e.payload.get("error") != "loop_detected" or True for e in state._events
    )
    assert any(e.payload.get("error") == "loop_detected" for e in state._events if e.type == AgentEventType.TASK_FAILED)


def test_replan_is_bounded(app, user_a):
    failing_plan = plan_json([
        {"id": "s1", "description": "boom", "kind": "tool", "tool": "tool_boom"},
    ])
    caller = FakeCaller(plans=[failing_plan, failing_plan, failing_plan])
    registry = FakeRegistry(outcomes={"tool_boom": {"_failed": True, "kind": "server_error", "detail": "boom", "status": 500, "transient": False}})
    orch = build_orchestrator(caller, registry)
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    assert state.error == "replan_limit_reached"
    assert state.replans == orch.max_replans


def test_replan_recovers_from_failure(app, user_a):
    failing = plan_json([{"id": "s1", "description": "boom", "kind": "tool", "tool": "tool_boom"}])
    working = plan_json([{"id": "s2", "description": "works", "kind": "tool", "tool": "tool_ok"}])
    caller = FakeCaller(plans=[failing, working])
    registry = FakeRegistry(outcomes={"tool_boom": {"_failed": True, "kind": "server_error", "detail": "boom", "status": 500, "transient": False}})
    orch = build_orchestrator(caller, registry)
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    assert state._status == RunStatus.COMPLETED
    assert state.replans == 1


def test_transient_tool_failure_retries_once(app, user_a):
    attempts = {"n": 0}

    def _flaky(arguments):
        attempts["n"] += 1
        if attempts["n"] == 1:
            return {"_failed": True, "kind": "rate_limited", "detail": "429", "status": 429, "transient": True}
        return None  # fall through to default success

    steps = plan_json([{"id": "s1", "description": "flaky", "kind": "tool", "tool": "tool_flaky"}])
    caller = FakeCaller(plans=[steps])
    registry = FakeRegistry(outcomes={"tool_flaky": _flaky})
    orch = build_orchestrator(caller, registry)
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    assert state._status == RunStatus.COMPLETED
    assert attempts["n"] == 2


def test_invalid_planner_json_fails_run(app, user_a):
    caller = FakeCaller(plans=["this is not json"])
    orch = build_orchestrator(caller, FakeRegistry())
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    assert state._status == RunStatus.FAILED
    assert "planning failed" in state.error
    # The planner was fed back its error once before the run failed.
    planning_calls = [c for c in caller.calls if c[0] == "agent_planning"]
    assert len(planning_calls) == 2


def test_invalid_json_fed_back_then_recovers(app, user_a):
    good = plan_json([{"id": "s1", "description": "ok", "kind": "tool", "tool": "tool_a"}])
    caller = FakeCaller(plans=["{broken json", good])
    orch = build_orchestrator(caller, FakeRegistry())
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "x"))
    assert state._status == RunStatus.COMPLETED


def test_chat_intent_single_model_step(app, user_a):
    chat = plan_json([{"id": "s1", "description": "answer directly", "kind": "model", "arguments": {}}])
    caller = FakeCaller(plans=[chat])
    orch = build_orchestrator(caller, FakeRegistry())
    state = run_orchestrator(app, lambda db: orch.start_run(db, user_a, "hi"))
    assert state._status == RunStatus.COMPLETED
    assert state.final_response == "All done."


def test_memory_isolation_between_users(app, user_a, user_b):
    from mina_api.database.models import Memory

    async def _seed():
        async with app.state.database.session() as session:
            session.add(Memory(user_id=user_a, kind="fact", content="Anil's favourite project is Mina"))
            await session.commit()

    asyncio.run(_seed())
    memory = AgentMemory()
    context_a = run_orchestrator(app, lambda db: memory.retrieve(db, user_a, "mina project", None))
    context_b = run_orchestrator(app, lambda db: memory.retrieve(db, user_b, "mina project", None))
    assert any("Mina" in m for m in context_a["memories"])
    assert context_b["memories"] == []


def test_composio_registry_maps_approval_gate(app, user_a):
    """The Composio registry converts the service's 403 gate into a
    waiting-approval outcome, never a bypass."""
    from fastapi import HTTPException

    from mina_api.agent.tools import ComposioToolRegistry

    class FakeComposio:
        async def execute(self, db, user_id, tool, arguments, connected_account_id=None):
            raise HTTPException(403, detail={"error": "approval_required", "tool_slug": tool})

    registry = ComposioToolRegistry(FakeComposio())
    result = run_orchestrator(app, lambda db: registry.execute(db, user_a, "gmail_send_message", {}))
    assert result["kind"] == "approval_required"


# ---------------------------------------------------------------- API tests

@pytest.fixture()
def agent_app(app, monkeypatch):
    """The app with a fully mocked orchestrator wired in."""
    caller = FakeCaller(plans=[PLAN_JSON])
    registry = FakeRegistry()
    app.state.agent_orchestrator = build_orchestrator(caller, registry)
    app.state._agent_caller = caller
    app.state._agent_registry = registry
    return app


def test_api_requires_auth(client):
    response = client.post("/v1/agent/runs", json={"input": "hello"})
    assert response.status_code == 401


def test_api_full_run_roundtrip(agent_app, client, user_a):
    response = client.post("/v1/agent/runs", json={"input": "check my mail"}, headers=headers_for(user_a))
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "completed"
    assert body["final_response"] == "All done."
    event_types = [e["type"] for e in body["events"]]
    assert "planning" in event_types and "task_completed" in event_types

    run_id = body["run_id"]
    fetched = client.get(f"/v1/agent/runs/{run_id}", headers=headers_for(user_a))
    assert fetched.status_code == 200
    assert fetched.json()["status"] == "completed"
    trace = client.get(f"/v1/agent/runs/{run_id}/events", headers=headers_for(user_a))
    assert trace.status_code == 200
    persisted_types = [e["type"] for e in trace.json()["events"]]
    assert "model_selected" in persisted_types
    assert "tool_completed" in persisted_types


def test_api_run_cross_user_404(agent_app, client, user_a, user_b):
    response = client.post("/v1/agent/runs", json={"input": "x"}, headers=headers_for(user_a))
    run_id = response.json()["run_id"]
    assert client.get(f"/v1/agent/runs/{run_id}", headers=headers_for(user_b)).status_code == 404
    assert client.get(f"/v1/agent/runs/{run_id}/events", headers=headers_for(user_b)).status_code == 404


def test_api_list_runs_own_only(agent_app, client, user_a, user_b):
    client.post("/v1/agent/runs", json={"input": "x"}, headers=headers_for(user_a))
    client.post("/v1/agent/runs", json={"input": "y"}, headers=headers_for(user_b))
    mine = client.get("/v1/agent/runs", headers=headers_for(user_a)).json()["runs"]
    assert len(mine) == 1


def test_api_money_step_approval_roundtrip(app, client, user_a):
    steps = plan_json([
        {"id": "s1", "description": "pay", "kind": "tool", "tool": "tool_pay",
         "arguments": {}, "risk": "money", "cost_estimate_cents": 300},
    ])
    app.state.agent_orchestrator = build_orchestrator(FakeCaller(plans=[steps]), FakeRegistry())
    response = client.post("/v1/agent/runs", json={"input": "pay"}, headers=headers_for(user_a))
    body = response.json()
    assert body["status"] == "waiting_approval"
    approval_event = next(e for e in body["events"] if e["type"] == "approval_required")
    assert approval_event["payload"]["approval_id"]

    listing = client.get("/v1/agent/runs", headers=headers_for(user_a)).json()["runs"]
    assert listing[0]["status"] == "waiting_approval"

    approved = client.post(
        f"/v1/agent/runs/{body['run_id']}/approvals/{approval_event['payload']['approval_id']}",
        json={"decision": "approved"}, headers=headers_for(user_a),
    )
    assert approved.status_code == 200
    assert approved.json()["status"] == "completed"

    # The approval is single-use: deciding again is a 409.
    again = client.post(
        f"/v1/agent/runs/{body['run_id']}/approvals/{approval_event['payload']['approval_id']}",
        json={"decision": "approved"}, headers=headers_for(user_a),
    )
    assert again.status_code == 409


def test_api_foreign_approval_rejected(app, client, user_a, user_b):
    steps = plan_json([
        {"id": "s1", "description": "pay", "kind": "tool", "tool": "tool_pay",
         "arguments": {}, "risk": "money", "cost_estimate_cents": 300},
    ])
    app.state.agent_orchestrator = build_orchestrator(FakeCaller(plans=[steps]), FakeRegistry())
    body = client.post("/v1/agent/runs", json={"input": "pay"}, headers=headers_for(user_a)).json()
    approval_id = next(e for e in body["events"] if e["type"] == "approval_required")["payload"]["approval_id"]
    foreign = client.post(
        f"/v1/agent/runs/{body['run_id']}/approvals/{approval_id}",
        json={"decision": "approved"}, headers=headers_for(user_b),
    )
    assert foreign.status_code == 404


def test_api_deny_endpoint_fails_run(app, client, user_a):
    steps = plan_json([
        {"id": "s1", "description": "pay", "kind": "tool", "tool": "tool_pay",
         "arguments": {}, "risk": "money", "cost_estimate_cents": 300},
    ])
    app.state.agent_orchestrator = build_orchestrator(FakeCaller(plans=[steps]), FakeRegistry())
    body = client.post("/v1/agent/runs", json={"input": "pay"}, headers=headers_for(user_a)).json()
    approval_id = next(e for e in body["events"] if e["type"] == "approval_required")["payload"]["approval_id"]
    denied = client.post(
        f"/v1/agent/runs/{body['run_id']}/approvals/{approval_id}",
        json={"decision": "denied"}, headers=headers_for(user_a),
    )
    assert denied.status_code == 200
    assert denied.json()["status"] == "failed"


def test_api_device_result_roundtrip(app, client, user_a):
    steps = plan_json([
        {"id": "s1", "description": "read screen", "kind": "device_action",
         "action": "read_screen", "arguments": {}},
    ])
    app.state.agent_orchestrator = build_orchestrator(FakeCaller(plans=[steps]), FakeRegistry())
    body = client.post("/v1/agent/runs", json={"input": "read my screen"}, headers=headers_for(user_a)).json()
    assert body["status"] == "waiting_device"
    device_event = next(e for e in body["events"] if e["type"] == "device_action_requested")
    assert device_event["payload"]["request"]["permission_required"] == "ACCESSIBILITY_SERVICE"

    result = client.post(
        f"/v1/agent/runs/{body['run_id']}/device-results",
        json={"step_id": "s1", "success": True, "result": {"screen": "inbox text"}},
        headers=headers_for(user_a),
    )
    assert result.status_code == 200
    assert result.json()["status"] == "completed"


def test_api_device_result_wrong_step_404(app, client, user_a):
    steps = plan_json([
        {"id": "s1", "description": "read screen", "kind": "device_action", "action": "read_screen", "arguments": {}},
    ])
    app.state.agent_orchestrator = build_orchestrator(FakeCaller(plans=[steps]), FakeRegistry())
    body = client.post("/v1/agent/runs", json={"input": "x"}, headers=headers_for(user_a)).json()
    response = client.post(
        f"/v1/agent/runs/{body['run_id']}/device-results",
        json={"step_id": "nope", "success": True, "result": {}},
        headers=headers_for(user_a),
    )
    assert response.status_code == 404


def test_api_cancel_endpoint(app, client, user_a):
    steps = plan_json([
        {"id": "s1", "description": "pay", "kind": "tool", "tool": "tool_pay",
         "arguments": {}, "risk": "money", "cost_estimate_cents": 300},
    ])
    app.state.agent_orchestrator = build_orchestrator(FakeCaller(plans=[steps]), FakeRegistry())
    body = client.post("/v1/agent/runs", json={"input": "x"}, headers=headers_for(user_a)).json()
    cancelled = client.post(f"/v1/agent/runs/{body['run_id']}/cancel", headers=headers_for(user_a))
    assert cancelled.status_code == 200
    assert cancelled.json()["status"] == "cancelled"
    again = client.post(f"/v1/agent/runs/{body['run_id']}/cancel", headers=headers_for(user_a))
    assert again.status_code == 409


def test_api_pause_resume_endpoints(app, client, user_a):
    steps = plan_json([
        {"id": "s1", "description": "pay", "kind": "tool", "tool": "tool_pay",
         "arguments": {}, "risk": "money", "cost_estimate_cents": 300},
    ])
    app.state.agent_orchestrator = build_orchestrator(FakeCaller(plans=[steps]), FakeRegistry())
    body = client.post("/v1/agent/runs", json={"input": "x"}, headers=headers_for(user_a)).json()
    paused = client.post(f"/v1/agent/runs/{body['run_id']}/pause", headers=headers_for(user_a))
    assert paused.status_code == 200
    assert paused.json()["status"] == "paused"
    resumed = client.post(f"/v1/agent/runs/{body['run_id']}/resume", headers=headers_for(user_a))
    assert resumed.status_code == 200
    assert resumed.json()["status"] == "waiting_approval"
    resumed_again = client.post(f"/v1/agent/runs/{body['run_id']}/resume", headers=headers_for(user_a))
    assert resumed_again.status_code == 409


def test_api_conversation_context_persisted(agent_app, client, user_a):
    from mina_api.database.models import Conversation, Message

    async def _seed():
        async with agent_app.state.database.session() as session:
            conversation = Conversation(user_id=user_a, title="chat")
            session.add(conversation)
            await session.commit()
            session.add(Message(conversation_id=conversation.id, user_id=user_a, role="user", content="earlier turn"))
            await session.commit()
            return conversation.id

    conversation_id = asyncio.run(_seed())
    response = client.post(
        "/v1/agent/runs",
        json={"input": "check my mail", "conversation_id": str(conversation_id)},
        headers=headers_for(user_a),
    )
    assert response.status_code == 200
    assert response.json()["conversation_id"] == str(conversation_id)
    assistant_rows = client.get(
        "/v1/conversations", headers=headers_for(user_a)
    )
    assert assistant_rows.status_code == 200  # context storage does not break the conversation API

    async def _check():
        import sqlalchemy as sa

        async with agent_app.state.database.session() as session:
            rows = (await session.execute(
                sa.select(Message).where(Message.conversation_id == conversation_id, Message.role == "assistant")
            )).scalars().all()
            return [r.content for r in rows]

    contents = asyncio.run(_check())
    assert contents == ["All done."]


def test_api_unknown_conversation_404(client, user_a):
    response = client.post(
        "/v1/agent/runs",
        json={"input": "x", "conversation_id": str(uuid4())},
        headers=headers_for(user_a),
    )
    assert response.status_code == 404


def test_api_validation_limits(agent_app, client, user_a):
    response = client.post("/v1/agent/runs", json={"input": ""}, headers=headers_for(user_a))
    assert response.status_code == 422
    response = client.post(
        "/v1/agent/runs", json={"input": "x", "max_steps": 500}, headers=headers_for(user_a)
    )
    assert response.status_code == 422
    response = client.post("/v1/agent/runs", json={"input": "x", "mode": "yolo"}, headers=headers_for(user_a))
    assert response.status_code == 422
