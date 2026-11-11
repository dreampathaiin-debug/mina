"""The executor role: run one planned step.

The executor never decides what to do next; it runs the step it is
given and reports the outcome. Tool arguments are validated against the
tool's schema by the Phase 6 ComposioService; device actions are
validated instructions that Android executes locally; model steps are
bounded LLM calls.
"""

from __future__ import annotations

import re
from typing import Any
from uuid import UUID

from mina_api.agent.device import classify_device_step, instruction_payload
from mina_api.agent.tools import ToolRegistry

STEP_REF = re.compile(r"^\{\{step:([A-Za-z0-9_-]+)\.([A-Za-z0-9_]+)\}\}$")
STEP_REF_EMBEDDED = re.compile(r"\{\{step:([A-Za-z0-9_-]+)\.([A-Za-z0-9_]+)\}\}")


def resolve_references(arguments: dict, results: dict[str, dict]) -> tuple[dict, list[str]]:
    """Resolve {{step:<id>.<key>}} references in step arguments from the
    results of completed steps. References may be the whole value or
    embedded in a longer string. Returns (resolved, unresolved_keys)."""
    resolved: dict[str, Any] = {}
    missing: list[str] = []

    def substitute(value: str):
        def _replace(match: re.Match) -> str:
            step_id, result_key = match.group(1), match.group(2)
            source = results.get(step_id)
            if source is not None and result_key in source:
                return str(source[result_key])
            return match.group(0)

        return STEP_REF_EMBEDDED.sub(_replace, value)

    for key, value in (arguments or {}).items():
        if isinstance(value, str) and "{{step:" in value:
            substituted = substitute(value)
            if "{{step:" in substituted:
                missing.append(key)
            elif substituted == value.strip() and (match := STEP_REF.match(value.strip())):
                # A bare reference keeps the referenced value's type.
                source = results.get(match.group(1))
                if source is not None and match.group(2) in source:
                    resolved[key] = source[match.group(2)]
                    continue
                missing.append(key)
            else:
                resolved[key] = substituted
            continue
        resolved[key] = value
    return resolved, missing


class StepOutcome:
    def __init__(
        self, status: str, result: dict | None = None, error: str | None = None,
        instruction: dict | None = None, approval_id: str | None = None,
    ) -> None:
        self.status = status  # completed | failed | waiting_approval | waiting_device
        self.result = result
        self.error = error
        self.instruction = instruction
        self.approval_id = approval_id


class StepExecutor:
    def __init__(
        self,
        registry: ToolRegistry,
        caller,
        *,
        device_result_handler=None,
    ) -> None:
        self.registry = registry
        self.caller = caller
        # device_result_handler(step, instruction) -> optional immediate
        # result dict. In production it stays None: real device results
        # arrive later through the API, never from the backend itself.
        self.device_result_handler = device_result_handler

    async def execute(
        self, db, user_id: UUID, step_spec: dict, resolved_args: dict,
        *, mode: str, spent_estimate_cents: int = 0,
    ) -> StepOutcome:
        kind = step_spec.get("kind")
        if kind == "tool":
            return await self._execute_tool(db, user_id, step_spec, resolved_args)
        if kind == "device_action":
            return await self._execute_device(step_spec, resolved_args, mode, spent_estimate_cents)
        if kind == "model":
            return await self._execute_model(db, user_id, step_spec, resolved_args)
        return StepOutcome("failed", error=f"unknown step kind: {kind}")

    async def _execute_tool(self, db, user_id: UUID, step_spec: dict, resolved_args: dict) -> StepOutcome:
        tool = step_spec.get("tool") or ""
        result = await self.registry.execute(db, user_id, tool, resolved_args)
        if result.get("_failed"):
            if result.get("kind") == "approval_required":
                # Composio's own dangerous-tool gate asked for approval;
                # surface it as a run-level approval, never bypassed.
                return StepOutcome(
                    "waiting_approval",
                    error="tool approval required",
                    result={"tool_slug": tool, "arguments": resolved_args},
                )
            return StepOutcome(
                "failed",
                result=result,
                error=f"{result.get('kind')}: {result.get('detail')}",
            )
        return StepOutcome("completed", result=result)

    async def _execute_device(
        self, step_spec: dict, resolved_args: dict, mode: str, spent_estimate_cents: int
    ) -> StepOutcome:
        try:
            instruction, readonly = classify_device_step(resolved_args, step_spec.get("risk") or "safe")
        except Exception as exc:
            return StepOutcome("failed", error=f"invalid device action: {exc}")
        payload = instruction_payload(step_spec.get("id"), instruction, readonly)
        if self.device_result_handler is not None:
            result = await self.device_result_handler(step_spec, payload)
            if result is not None:
                return StepOutcome("completed", result={"device_result": result, "instruction": payload})
        # No local handler: the instruction goes out and the result comes
        # back through the API. The backend never fakes the device result.
        return StepOutcome("waiting_device", instruction=payload)

    async def _execute_model(self, db, user_id: UUID, step_spec: dict, resolved_args: dict) -> StepOutcome:
        prompt = str(resolved_args.get("prompt") or step_spec.get("description") or "")
        try:
            content, model_id = await self.caller.call(
                db, user_id, task_type="agent_step_model",
                system="Complete this step for the agent. Be concise and factual.",
                user=prompt, max_tokens=1024,
            )
        except Exception as exc:
            return StepOutcome("failed", error=f"model step failed: {exc}")
        if not content.strip():
            return StepOutcome("failed", error="model step returned empty output")
        return StepOutcome("completed", result={"response": content, "model_id": model_id})
