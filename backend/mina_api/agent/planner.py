"""The planner role: intent, complexity, plan.

The planner only decides WHAT should happen. It never executes. All of
its output passes through Pydantic validation before the executor sees
it, and every model call records a model_selected event through the
caller's observer.
"""

from __future__ import annotations

import json
from uuid import UUID

from mina_api.agent.events import AgentEventType
from mina_api.agent.llm import ModelCaller
from mina_api.agent.schemas import IntentClassification, JsonValidationError, TaskPlan

CLASSIFY_SYSTEM = """You classify a user request for an agent system.
Reply with ONLY a JSON object:
{"intent": "chat"|"tool_use"|"device_action"|"task"|"mixed",
 "complexity": "trivial"|"simple"|"complex",
 "summary": "<one sentence>"}"""

PLAN_SYSTEM = """You are the planner of an agent system. Produce a structured plan.
Reply with ONLY a JSON object:
{"goal": "...", "notes": "...",
 "steps": [
   {"id": "s1", "description": "...", "kind": "tool"|"device_action"|"model",
    "tool": "<composio tool slug, tool steps only>",
    "action": "<open_app|tap|type|scroll|read_screen|screenshot, device steps only>",
    "arguments": {}, "depends_on": [], "readonly": false,
    "risk": "safe"|"sensitive"|"money"|"destructive",
    "cost_estimate_cents": 0}
 ]}
Rules:
- Keep steps minimal and ordered; use depends_on for real data dependencies only.
- Mark steps that spend money as risk "money" with a cost_estimate_cents.
- Mark destructive or sensitive steps with the matching risk.
- Device actions only describe what Android should do; they never assume access.
- readonly=true only for pure-read steps.
- If the request is a plain chat answer, use one "model" step."""


class Planner:
    def __init__(self, caller: ModelCaller, *, max_plan_steps: int = 20) -> None:
        self.caller = caller
        self.max_plan_steps = max_plan_steps

    async def classify(
        self, db, user_id: UUID, user_input: str, context: dict, *, on_event=None
    ) -> IntentClassification:
        prompt = self._context_block(context) + f"\nUser request: {json.dumps(user_input)}"
        classification, model_id = await self.caller.call_json(
            db, user_id, task_type="agent_classify", system=CLASSIFY_SYSTEM,
            user=prompt, schema_cls=IntentClassification,
        )
        if on_event:
            on_event(AgentEventType.MODEL_SELECTED, {"purpose": "classify", "model_id": model_id})
        return classification

    async def build_plan(
        self, db, user_id: UUID, user_input: str, context: dict, *,
        complexity: str = "simple", on_event=None,
    ) -> TaskPlan:
        prompt = (
            self._context_block(context)
            + f"\nComplexity: {complexity}\n"
            + f"User request: {json.dumps(user_input)}\n"
            + f"Use at most {self.max_plan_steps} steps."
        )
        plan, model_id = await self.caller.call_json(
            db, user_id, task_type="agent_planning", system=PLAN_SYSTEM,
            user=prompt, schema_cls=TaskPlan, max_tokens=2048,
        )
        plan.steps = plan.steps[: self.max_plan_steps]
        if on_event:
            on_event(AgentEventType.MODEL_SELECTED, {"purpose": "plan", "model_id": model_id})
        return plan

    async def replan(
        self, db, user_id: UUID, original_input: str, context: dict,
        failed_summary: str, *, on_event=None,
    ) -> TaskPlan:
        prompt = (
            self._context_block(context)
            + f"\nUser request: {json.dumps(original_input)}\n"
            + f"A previous attempt failed: {json.dumps(failed_summary)}\n"
            + "Produce a fresh plan that avoids that failure."
        )
        plan, model_id = await self.caller.call_json(
            db, user_id, task_type="agent_planning", system=PLAN_SYSTEM,
            user=prompt, schema_cls=TaskPlan, max_tokens=2048,
        )
        if on_event:
            on_event(AgentEventType.MODEL_SELECTED, {"purpose": "replan", "model_id": model_id})
        return plan

    @staticmethod
    def _context_block(context: dict) -> str:
        parts = []
        history = context.get("history") or []
        if history:
            turns = "\n".join(f"{h['role']}: {h['content']}" for h in history[-4:])
            parts.append(f"Recent conversation:\n{turns}")
        memories = context.get("memories") or []
        if memories:
            parts.append("Relevant memories:\n" + "\n".join(f"- {m}" for m in memories[:5]))
        return "\n\n".join(parts) or "(no extra context)"
