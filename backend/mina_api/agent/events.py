"""The stable agent event format.

Every observable step of an orchestration run is recorded as an
AgentEvent. The type set is fixed and stable: Android and any other
consumer may switch on these strings without versioning surprises.
Event payloads are plain dicts and never contain secrets: tool results
reach events only as length-capped excerpts.
"""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class AgentEventType(str, Enum):
    PLANNING = "planning"
    MODEL_SELECTED = "model_selected"
    TOOL_DISCOVERED = "tool_discovered"
    APPROVAL_REQUIRED = "approval_required"
    TOOL_STARTED = "tool_started"
    TOOL_COMPLETED = "tool_completed"
    DEVICE_ACTION_REQUESTED = "device_action_requested"
    TASK_PAUSED = "task_paused"
    TASK_COMPLETED = "task_completed"
    TASK_FAILED = "task_failed"


class AgentEvent(BaseModel):
    """One trace event. `seq` is unique per run and zero-based."""

    seq: int
    type: AgentEventType
    timestamp: str
    payload: dict[str, Any] = Field(default_factory=dict)
