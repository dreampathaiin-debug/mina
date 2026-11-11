"""Runtime execution state for one agent run.

State is plain data so it can be persisted into the agent_runs payload
column and rebuilt on resume. Everything in here is JSON-safe.
"""

from __future__ import annotations

from enum import Enum
from typing import Any


class StepStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    WAITING_APPROVAL = "waiting_approval"
    WAITING_DEVICE = "waiting_device"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"
    CANCELLED = "cancelled"


class RunStatus(str, Enum):
    RUNNING = "running"
    WAITING_APPROVAL = "waiting_approval"
    WAITING_DEVICE = "waiting_device"
    PAUSED = "paused"
    CANCELLED = "cancelled"
    COMPLETED = "completed"
    FAILED = "failed"


class StepState:
    """Execution state of one planned step."""

    def __init__(self, step_id: str, index: int, spec: dict[str, Any]) -> None:
        self.id = step_id
        self.index = index
        self.spec = spec
        self.status = StepStatus.PENDING
        self.result: dict[str, Any] | None = None
        self.error: str | None = None
        self.attempts = 0
        self.approval_id: str | None = None
        self.execution_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "index": self.index,
            "spec": self.spec,
            "status": self.status.value,
            "result": self.result,
            "error": self.error,
            "attempts": self.attempts,
            "approval_id": self.approval_id,
            "execution_id": self.execution_id,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "StepState":
        state = cls(data["id"], data["index"], data["spec"])
        state.status = StepStatus(data["status"])
        state.result = data.get("result")
        state.error = data.get("error")
        state.attempts = data.get("attempts", 0)
        state.approval_id = data.get("approval_id")
        state.execution_id = data.get("execution_id")
        return state


class RunState:
    """The full resumable state of a run."""

    def __init__(
        self,
        run_id: str,
        goal: str,
        mode: str,
        steps: list[StepState],
        *,
        max_steps: int = 15,
        timeout_seconds: float = 300.0,
        spending_limit_cents: int | None = None,
        replans: int = 0,
        spent_estimate_cents: int = 0,
        final_response: str | None = None,
        error: str | None = None,
    ) -> None:
        self.run_id = run_id
        self.goal = goal
        self.mode = mode
        self.steps = steps
        self.max_steps = max_steps
        self.timeout_seconds = timeout_seconds
        self.spending_limit_cents = spending_limit_cents
        self.replans = replans
        self.spent_estimate_cents = spent_estimate_cents
        self.final_response = final_response
        self.error = error

    @property
    def executed_steps(self) -> int:
        return sum(1 for s in self.steps if s.status in {StepStatus.COMPLETED, StepStatus.FAILED, StepStatus.CANCELLED, StepStatus.SKIPPED})

    def step(self, step_id: str) -> StepState | None:
        return next((s for s in self.steps if s.id == step_id), None)

    def ready_steps(self) -> list[StepState]:
        """Pending steps whose dependencies are all completed."""
        done = {s.id for s in self.steps if s.status == StepStatus.COMPLETED}
        return [
            s for s in self.steps
            if s.status == StepStatus.PENDING and all(dep in done for dep in s.spec.get("depends_on") or [])
        ]

    def to_dict(self) -> dict[str, Any]:
        return {
            "run_id": self.run_id,
            "goal": self.goal,
            "mode": self.mode,
            "steps": [s.to_dict() for s in self.steps],
            "max_steps": self.max_steps,
            "timeout_seconds": self.timeout_seconds,
            "spending_limit_cents": self.spending_limit_cents,
            "replans": self.replans,
            "spent_estimate_cents": self.spent_estimate_cents,
            "final_response": self.final_response,
            "error": self.error,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "RunState":
        return cls(
            run_id=data["run_id"],
            goal=data["goal"],
            mode=data["mode"],
            steps=[StepState.from_dict(s) for s in data.get("steps", [])],
            max_steps=data.get("max_steps", 15),
            timeout_seconds=data.get("timeout_seconds", 300.0),
            spending_limit_cents=data.get("spending_limit_cents"),
            replans=data.get("replans", 0),
            spent_estimate_cents=data.get("spent_estimate_cents", 0),
            final_response=data.get("final_response"),
            error=data.get("error"),
        )
