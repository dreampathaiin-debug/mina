"""The orchestrator: Mina's central agent engine.

It coordinates the roles and owns ONLY the loop: state, limits,
cancellation, pausing, resuming, tracing and error recovery. The
planner plans, the executor runs, the verifier checks; none of them
decides the flow.

Pipeline per run:
  intent classification -> complexity assessment -> memory retrieval ->
  task planning -> model selection (per call) -> tool discovery ->
  permission validation -> tool execution (parallel where independent
  and read-only, sequential across dependencies) -> result
  verification -> final response.

Hard limits: max steps, wall-clock deadline, bounded replans, and a
repeated-call guard so the loop can never run away. Every observable
transition is recorded as a stable agent event and persisted as a
trace.
"""

from __future__ import annotations

import asyncio
import time
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable
from uuid import UUID, uuid4

from mina_api.agent import approvals as approval_policy
from mina_api.agent.device import classify_device_step, instruction_payload
from mina_api.agent.events import AgentEvent, AgentEventType
from mina_api.agent.executor import StepExecutor, resolve_references
from mina_api.agent.llm import ModelCaller
from mina_api.agent.memory import AgentMemory
from mina_api.agent.planner import Planner
from mina_api.agent.schemas import JsonValidationError, PlannedStep, TaskPlan
from mina_api.agent.state import RunState, RunStatus, StepState, StepStatus
from mina_api.agent.tools import ToolRegistry
from mina_api.agent.verifier import Verifier

MAX_REPLANS = 2
MAX_DEVICE_RESULT_CHARS = 4000


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


class RunBlocked(Exception):
    """The run reached a waiting state (approval / device / pause)."""

    def __init__(self, status: RunStatus, step_id: str | None = None) -> None:
        super().__init__(status.value)
        self.status = status
        self.step_id = step_id


class Orchestrator:
    def __init__(
        self,
        *,
        planner: Planner,
        caller: ModelCaller,
        registry: ToolRegistry,
        verifier: Verifier,
        memory: AgentMemory | None = None,
        executor: StepExecutor | None = None,
        max_replans: int = MAX_REPLANS,
    ) -> None:
        self.planner = planner
        self.caller = caller
        self.registry = registry
        self.verifier = verifier
        self.memory = memory or AgentMemory()
        self.executor = executor or StepExecutor(registry, caller)
        self.max_replans = max_replans
        # In-process control plane for runs currently executing here.
        self._cancel_requested: set[str] = set()
        self._pause_requested: set[str] = set()
        self._active_tasks: dict[str, asyncio.Task] = {}
        self._active_run_ids: set[str] = set()

    # ------------------------------------------------------------------ API

    async def start_run(
        self, db, user_id: UUID, user_input: str, *,
        conversation_id: UUID | None = None,
        mode: str = "ask",
        max_steps: int = 15,
        timeout_seconds: float = 300.0,
        spending_limit_cents: int | None = None,
        run_id: str | None = None,
        on_event: Callable[[AgentEvent], None] | None = None,
    ) -> RunState:
        """Run one agent turn to completion or to a waiting checkpoint.

        Awaiting-approval and awaiting-device runs persist their state
        and are continued later through continue_run(). Callers that
        persist the run pass their own run_id so approvals and trace
        rows reference the same record.
        """
        run_id = run_id or str(uuid4())
        self._active_run_ids.add(run_id)
        events: list[AgentEvent] = []

        def record(event_type: AgentEventType, payload: dict) -> None:
            event = AgentEvent(seq=len(events), type=event_type, timestamp=_now(), payload=payload)
            events.append(event)
            if on_event:
                on_event(event)

        if mode not in {"ask", "autonomous"}:
            raise ValueError(f"unknown mode: {mode}")

        # 1. Memory retrieval FIRST so planning sees relevant context.
        context = await self.memory.retrieve(db, user_id, user_input, conversation_id)

        # 2. Intent classification + complexity assessment.
        try:
            classification = await self.planner.classify(db, user_id, user_input, context, on_event=record)
        except (JsonValidationError, Exception) as exc:  # provider failure -> fail fast, recoverable
            record(AgentEventType.PLANNING, {"intent": "unknown", "complexity": "unknown", "error": str(exc)[:300]})
            return self._finish_failed(run_id, user_input, mode, max_steps, timeout_seconds,
                                       spending_limit_cents, events, f"intent classification failed: {exc}")

        record(
            AgentEventType.PLANNING,
            {"intent": classification.intent, "complexity": classification.complexity,
             "summary": classification.summary},
        )

        # 3. Task planning.
        try:
            plan = await self.planner.build_plan(
                db, user_id, user_input, context,
                complexity=classification.complexity, on_event=record,
            )
        except Exception as exc:
            record(AgentEventType.PLANNING, {"error": f"planning failed: {str(exc)[:300]}"})
            return self._finish_failed(run_id, user_input, mode, max_steps, timeout_seconds,
                                       spending_limit_cents, events, f"planning failed: {exc}")

        # 4. Tool discovery for the tools the plan names.
        tool_slugs = sorted({s.tool for s in plan.steps if s.kind == "tool" and s.tool})
        if tool_slugs:
            try:
                discovered = await self.registry.discover(db, user_id, " ".join(tool_slugs), limit=10)
                record(AgentEventType.TOOL_DISCOVERED, {"tools": discovered, "requested": tool_slugs})
            except Exception as exc:
                record(AgentEventType.TOOL_DISCOVERED, {"error": str(exc)[:300]})

        steps = [StepState(s.id, i, s.model_dump(mode="json")) for i, s in enumerate(plan.steps)]
        state = RunState(
            run_id=run_id, goal=plan.goal, mode=mode, steps=steps,
            max_steps=max_steps, timeout_seconds=timeout_seconds,
            spending_limit_cents=spending_limit_cents,
        )
        state._events = events  # type: ignore[attr-defined]
        state._input = user_input  # type: ignore[attr-defined]
        state._conversation_id = conversation_id  # type: ignore[attr-defined]

        deadline = time.monotonic() + timeout_seconds
        try:
            await self._run_loop(db, user_id, state, deadline, record)
        except RunBlocked as blocked:
            state._blocked_step = blocked.step_id  # type: ignore[attr-defined]
        finally:
            self._active_run_ids.discard(run_id)
        self._settle(state)
        return state

    async def continue_run(
        self, db, user_id: UUID, state: RunState, *,
        on_event: Callable[[AgentEvent], None] | None = None,
        device_results: dict[str, dict] | None = None,
    ) -> RunState:
        """Continue a persisted run (after approval, device results,
        pause or interruption). Completed steps are never re-run."""
        events: list[AgentEvent] = list(getattr(state, "_events", []) or [])

        def record(event_type: AgentEventType, payload: dict) -> None:
            event = AgentEvent(seq=len(events), type=event_type, timestamp=_now(), payload=payload)
            events.append(event)
            if on_event:
                on_event(event)

        state._events = events  # type: ignore[attr-defined]
        # Merge device results into waiting steps.
        for step_id, payload in (device_results or {}).items():
            step = state.step(step_id)
            if step and step.status == StepStatus.WAITING_DEVICE:
                text = str(payload)[:MAX_DEVICE_RESULT_CHARS]
                step.status = StepStatus.COMPLETED
                step.result = {"device_result": text, "untrusted": True}
                record(AgentEventType.TOOL_COMPLETED, {"step_id": step_id, "kind": "device_result", "ok": True})
        # Resolve waiting approvals against their recorded decisions so a
        # continued run neither re-asks nor re-runs a denied step.
        for step in state.steps:
            if step.status != StepStatus.WAITING_APPROVAL:
                continue
            decision = await approval_policy.approval_decision(db, user_id, state.run_id, step.id)
            if decision == "approved":
                step.status = StepStatus.PENDING
            elif decision == "denied":
                step.status = StepStatus.FAILED
                step.error = "approval_denied"
                state.error = "approval_denied"
        deadline = time.monotonic() + state.timeout_seconds
        await self._run_loop(db, user_id, state, deadline, record)
        self._settle(state)
        return state

    # ------------------------------------------------------------- control

    def cancel_active(self) -> list[str]:
        """Cancel every run executing in this process right now."""
        targets = list(self._active_run_ids)
        for run_id in targets:
            self.request_cancel(run_id)
        return targets

    def pause_active(self) -> list[str]:
        targets = list(self._active_run_ids)
        for run_id in targets:
            self.request_pause(run_id)
        return targets

    def request_cancel(self, run_id: str) -> bool:
        """Cancel a run. In-flight runs stop at their next checkpoint;
        waiting runs are cancelled immediately by the caller."""
        self._cancel_requested.add(run_id)
        task = self._active_tasks.pop(run_id, None)
        if task and not task.done():
            task.cancel()
            return True
        return run_id in self._cancel_requested

    def request_pause(self, run_id: str) -> None:
        self._pause_requested.add(run_id)

    def clear_control(self, run_id: str) -> None:
        self._cancel_requested.discard(run_id)
        self._pause_requested.discard(run_id)

    # ----------------------------------------------------------- the loop

    async def _run_loop(self, db, user_id: UUID, state: RunState, deadline: float, record) -> None:
        calls_seen: dict[tuple, str] = {}
        final_input = getattr(state, "_input", state.goal)

        while True:
            if state.run_id in self._cancel_requested:
                self._cancel_requested.discard(state.run_id)
                for s in state.steps:
                    if s.status in {StepStatus.PENDING, StepStatus.RUNNING}:
                        s.status = StepStatus.CANCELLED
                record(AgentEventType.TASK_FAILED, {"error": "cancelled", "cancelled": True})
                state.error = "cancelled"
                self._finish(state, RunStatus.CANCELLED)
                self.clear_control(state.run_id)
                return
            if state.run_id in self._pause_requested:
                self._pause_requested.discard(state.run_id)
                record(AgentEventType.TASK_PAUSED, {"reason": "user_requested"})
                self._finish(state, RunStatus.PAUSED)
                return
            if time.monotonic() > deadline:
                for s in state.steps:
                    if s.status in {StepStatus.PENDING, StepStatus.RUNNING}:
                        s.status = StepStatus.FAILED
                        s.error = "timeout"
                record(AgentEventType.TASK_FAILED, {"error": "execution time limit reached"})
                state.error = "timeout"
                return
            if state.executed_steps >= state.max_steps:
                record(AgentEventType.TASK_FAILED, {"error": "max_steps_exceeded"})
                state.error = "max_steps_exceeded"
                return

            ready = state.ready_steps()
            if not ready:
                waiting_now = [s for s in state.steps if s.status in {StepStatus.WAITING_APPROVAL, StepStatus.WAITING_DEVICE}]
                if waiting_now:
                    self._finish(
                        state,
                        RunStatus.WAITING_APPROVAL
                        if any(s.status == StepStatus.WAITING_APPROVAL for s in waiting_now)
                        else RunStatus.WAITING_DEVICE,
                    )
                    return
                if any(s.status == StepStatus.PENDING for s in state.steps):
                    # Dependencies unmet: a failed dependency should have
                    # propagated; unblock via bounded replan.
                    blocked = [s for s in state.steps if s.status == StepStatus.PENDING]
                    ok = await self._maybe_replan(db, user_id, state, record,
                                                 f"steps blocked: {[s.id for s in blocked]}")
                    if ok:
                        continue
                    for s in blocked:
                        s.status = StepStatus.FAILED
                        s.error = "dependency_unresolved"
                    record(AgentEventType.TASK_FAILED, {"error": "dependency_unresolved"})
                    state.error = "dependency_unresolved"
                    return
                break  # no pending steps left

            # Waiting steps end the turn here; state is persisted by the caller.
            waiting_device = [s for s in state.steps if s.status == StepStatus.WAITING_DEVICE]
            if waiting_device:
                self._finish(state, RunStatus.WAITING_DEVICE)
                return

            # Permission validation for every ready step, before execution.
            gated: list[StepState] = []
            for step in ready:
                if await approval_policy.approval_decision(db, user_id, state.run_id, step.id) == "approved":
                    gated.append(step)  # explicit user approval already on record
                    continue
                needs, reason = approval_policy.needs_approval(
                    step.spec, state.mode, state.spent_estimate_cents, state.spending_limit_cents
                )
                if needs:
                    step.status = StepStatus.WAITING_APPROVAL
                    spec_summary = f"{step.spec.get('kind')} {step.spec.get('tool') or step.spec.get('action') or ''}".strip()
                    row = await approval_policy.create_approval(
                        db, user_id, state.run_id, step.id, reason, spec_summary
                    )
                    step.approval_id = str(row.id)
                    record(AgentEventType.APPROVAL_REQUIRED, {
                        "approval_id": str(row.id), "step_id": step.id, "reason": reason,
                        "summary": spec_summary,
                    })
                else:
                    gated.append(step)

            still_waiting = [s for s in state.steps if s.status == StepStatus.WAITING_APPROVAL]
            if still_waiting and not gated:
                self._finish(state, RunStatus.WAITING_APPROVAL)
                return

            if not gated:
                continue

            # Parallel fan-out for independent read-only steps; the rest
            # run sequentially to keep ordering predictable.
            parallel = [s for s in gated if s.spec.get("readonly") and not any(
                o is not s and s.id in (o.spec.get("depends_on") or []) for o in state.steps
            )]
            sequential = [s for s in gated if s not in parallel]

            if len(parallel) > 1:
                record(AgentEventType.TOOL_STARTED, {"parallel": [s.id for s in parallel]})
                await asyncio.gather(*(
                    self._execute_step(db, user_id, state, step, record, calls_seen, final_input)
                    for step in parallel
                ))
                for step in parallel:
                    if step.status == StepStatus.FAILED:
                        ok = await self._maybe_replan(db, user_id, state, record, f"step {step.id} failed")
                        if not ok:
                            return
            elif gated:
                step = gated[0]
                ok = await self._execute_step(db, user_id, state, step, record, calls_seen, final_input)
                if not ok:
                    ok_replan = await self._maybe_replan(db, user_id, state, record, f"step {step.id} failed")
                    if not ok_replan:
                        return

        # All steps done: final response.
        await self._finalize(db, user_id, state, record, final_input)

    async def _execute_step(
        self, db, user_id: UUID, state: RunState, step: StepState, record, calls_seen: set, final_input: str
    ) -> bool:
        """Execute one step. Returns False when the run must stop."""
        step.attempts += 1
        resolved, missing = resolve_references(step.spec.get("arguments") or {}, {
            s.id: (s.result or {}) for s in state.steps if s.status == StepStatus.COMPLETED
        })
        if missing:
            step.status = StepStatus.FAILED
            step.error = f"unresolved step references: {missing}"
            record(AgentEventType.TASK_FAILED, {"error": step.error, "step_id": step.id})
            state.error = step.error
            return False

        # Loop prevention: a different step repeating an identical call
        # means the agent is going in circles. Retrying the SAME step is
        # legitimate error recovery, never a loop.
        if step.spec.get("kind") == "tool":
            fingerprint = (step.spec.get("tool"), repr(sorted((resolved or {}).items())))
            seen_step = calls_seen.get(fingerprint)
            if seen_step is not None and seen_step != step.id:
                step.status = StepStatus.FAILED
                step.error = "repeated_identical_call"
                record(AgentEventType.TASK_FAILED, {"error": "loop_detected", "step_id": step.id})
                state.error = "loop_detected"
                return False
            calls_seen[fingerprint] = step.id

        step.status = StepStatus.RUNNING
        if step.spec.get("kind") == "device_action":
            try:
                instruction, readonly = classify_device_step(
                    resolved, step.spec.get("risk") or "safe", action_fallback=step.spec.get("action")
                )
            except Exception as exc:
                step.status = StepStatus.FAILED
                step.error = f"invalid device action: {exc}"
                record(AgentEventType.TASK_FAILED, {"error": step.error, "step_id": step.id})
                state.error = step.error
                return False
            payload = instruction_payload(step.id, instruction, readonly)
            record(AgentEventType.DEVICE_ACTION_REQUESTED, {"step_id": step.id, "request": payload})
            step.status = StepStatus.WAITING_DEVICE
            step.result = {"instruction": payload}
            self._finish(state, RunStatus.WAITING_DEVICE)
            return True

        record(AgentEventType.TOOL_STARTED, {"step_id": step.id, "tool": step.spec.get("tool"), "kind": step.spec.get("kind")})
        outcome = await self.executor.execute(
            db, user_id, step.spec, resolved,
            mode=state.mode, spent_estimate_cents=state.spent_estimate_cents,
        )

        if outcome.status == "waiting_approval":
            step.status = StepStatus.WAITING_APPROVAL
            row = await approval_policy.create_approval(
                db, user_id, state.run_id, step.id, "tool requires approval",
                f"tool {step.spec.get('tool')}",
            )
            step.approval_id = str(row.id)
            record(AgentEventType.APPROVAL_REQUIRED, {
                "approval_id": str(row.id), "step_id": step.id,
                "reason": "composio dangerous-tool gate", "summary": f"tool {step.spec.get('tool')}",
            })
            self._finish(state, RunStatus.WAITING_APPROVAL)
            return False

        if outcome.status == "failed":
            # Transient provider failures (rate limits, timeouts) are
            # retryable once before the failure is final.
            if (outcome.result or {}).get("transient") and step.attempts < 2:
                step.status = StepStatus.PENDING
                record(AgentEventType.TOOL_COMPLETED, {
                    "step_id": step.id, "tool": step.spec.get("tool"),
                    "ok": False, "retrying": True, "error": outcome.error,
                })
                return True
            step.status = StepStatus.FAILED
            step.error = outcome.error
            state.error = outcome.error
            record(AgentEventType.TASK_FAILED, {"step_id": step.id, "error": outcome.error})
            return False

        step.status = StepStatus.COMPLETED
        step.result = outcome.result or {}
        step.execution_id = (outcome.result or {}).get("id")
        verdict = self.verifier.verify_step(step.spec, step.result)
        if verdict.ok:
            record(AgentEventType.TOOL_COMPLETED, {
                "step_id": step.id, "tool": step.spec.get("tool"),
                "execution_id": step.execution_id, "ok": True,
            })
            if int(step.spec.get("cost_estimate_cents") or 0) > 0:
                state.spent_estimate_cents += int(step.spec["cost_estimate_cents"])
            return True
        # Retry a transient failure once, then treat as failed.
        if verdict.retry and step.attempts < 2:
            step.status = StepStatus.PENDING
            record(AgentEventType.TOOL_COMPLETED, {"step_id": step.id, "ok": False, "retry": True, "reason": verdict.reason})
            return True
        record(AgentEventType.TOOL_COMPLETED, {"step_id": step.id, "ok": False, "reason": verdict.reason})
        step.status = StepStatus.FAILED
        step.error = verdict.reason
        state.error = verdict.reason
        record(AgentEventType.TASK_FAILED, {"step_id": step.id, "error": verdict.reason})
        return False

    async def _maybe_replan(self, db, user_id: UUID, state: RunState, record, reason: str) -> bool:
        """Bounded replanning: replace unexecuted steps with a fresh plan."""
        if state.replans >= self.max_replans:
            record(AgentEventType.TASK_FAILED, {"error": "replan_limit_reached"})
            state.error = "replan_limit_reached"
            return False
        state.replans += 1
        try:
            plan = await self.planner.replan(db, user_id, getattr(state, "_input", state.goal), {}, reason)
        except Exception as exc:
            record(AgentEventType.TASK_FAILED, {"error": f"replan failed: {str(exc)[:200]}"})
            state.error = state.error or f"replan failed: {exc}"
            return False
        keep = [s for s in state.steps if s.status == StepStatus.COMPLETED]
        fresh = [StepState(s.id, len(keep) + i, s.model_dump(mode="json")) for i, s in enumerate(plan.steps)]
        state.steps = keep + fresh
        for i, s in enumerate(state.steps):
            s.index = i
        record(AgentEventType.PLANNING, {"replan": True, "reason": reason[:200], "steps": len(fresh)})
        return True

    async def _finalize(self, db, user_id: UUID, state: RunState, record, final_input: str) -> None:
        results_summary = []
        for s in state.steps:
            if s.status == StepStatus.COMPLETED:
                results_summary.append({"step": s.id, "result": _cap(s.result)})
        prompt = (
            f"User request: {final_input}\n"
            f"Step results: {results_summary}\n"
            "Write the final answer for the user."
        )
        try:
            final, model_id = await self.caller.call(
                db, user_id, task_type="agent_final",
                system="Write the final answer for the user from the completed steps.",
                user=prompt, max_tokens=1024,
            )
            record(AgentEventType.MODEL_SELECTED, {"purpose": "final", "model_id": model_id})
        except Exception as exc:
            record(AgentEventType.TASK_FAILED, {"error": f"final response failed: {str(exc)[:200]}"})
            state.error = f"final response failed: {exc}"
            return
        verdict = await self.verifier.verify_final(db, user_id, final_input, final)
        if not verdict.ok and verdict.retry:
            # One bounded rewrite attempt.
            try:
                final, model_id = await self.caller.call(
                    db, user_id, task_type="agent_final",
                    system="Write the final answer for the user from the completed steps.",
                    user=prompt + f"\nYour previous attempt was rejected: {verdict.reason}",
                    max_tokens=1024,
                )
                record(AgentEventType.MODEL_SELECTED, {"purpose": "final_retry", "model_id": model_id})
            except Exception:
                pass
        state.final_response = final
        record(AgentEventType.TASK_COMPLETED, {"response_length": len(final)})
        self._finish(state, RunStatus.COMPLETED)

    def _finish(self, state: RunState, status: RunStatus) -> None:
        state._status = status  # type: ignore[attr-defined]

    def _settle(self, state: RunState) -> None:
        """Guarantee a definitive terminal status on every exit path."""
        if not hasattr(state, "_status"):
            self._finish(
                state,
                RunStatus.FAILED if getattr(state, "error", None) else RunStatus.COMPLETED,
            )

    def _finish_failed(self, run_id, user_input, mode, max_steps, timeout_seconds,
                       spending_limit_cents, events, error) -> RunState:
        state = RunState(
            run_id=run_id, goal=user_input[:1000], mode=mode, steps=[],
            max_steps=max_steps, timeout_seconds=timeout_seconds,
            spending_limit_cents=spending_limit_cents,
        )
        state.error = error
        state._events = events  # type: ignore[attr-defined]
        state._status = RunStatus.FAILED  # type: ignore[attr-defined]
        events.append(AgentEvent(seq=len(events), type=AgentEventType.TASK_FAILED,
                                 timestamp=_now(), payload={"error": error[:300]}))
        return state


def _cap(value: Any, limit: int = 500) -> Any:
    text = str(value)
    return text[:limit]
