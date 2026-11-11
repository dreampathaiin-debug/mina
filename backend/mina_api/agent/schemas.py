"""Pydantic contracts for everything the model produces.

The planner, executor and verifier are separate roles, and every JSON
payload a model emits is validated here before it is trusted. Invalid
JSON never enters the run loop; it is fed back to the planner once,
then the run fails instead of improvising.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

IntentKind = Literal["chat", "tool_use", "device_action", "task", "mixed"]
Complexity = Literal["trivial", "simple", "complex"]
StepKind = Literal["tool", "device_action", "model"]
RiskLevel = Literal["safe", "sensitive", "money", "destructive"]


class IntentClassification(BaseModel):
    """What the user asked for and how hard it is."""

    model_config = ConfigDict(extra="forbid")

    intent: IntentKind
    complexity: Complexity
    summary: str = Field(min_length=1, max_length=500)


class PlannedStep(BaseModel):
    """One step of a structured task plan."""

    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1, max_length=40)
    description: str = Field(min_length=1, max_length=500)
    kind: StepKind
    # tool: Composio tool slug. device_action: one of the device verbs.
    # model: an LLM-only step (summarise, compose).
    tool: str | None = None
    action: str | None = None
    arguments: dict[str, Any] = Field(default_factory=dict)
    depends_on: list[str] = Field(default_factory=list)
    readonly: bool = False
    risk: RiskLevel = "safe"
    cost_estimate_cents: int = Field(default=0, ge=0)

    @field_validator("tool")
    @classmethod
    def _tool_required_for_tool_steps(cls, v, info):
        if info.data.get("kind") == "tool" and not v:
            raise ValueError("tool steps need a tool slug")
        if info.data.get("kind") == "tool" and v and not v.replace("_", "").isalnum():
            raise ValueError("tool slug must be a plain slug")
        return v

    @field_validator("action")
    @classmethod
    def _action_required_for_device_steps(cls, v, info):
        if info.data.get("kind") == "device_action" and not v:
            raise ValueError("device_action steps need an action")
        return v

    @field_validator("depends_on")
    @classmethod
    def _no_self_dependency(cls, v, info):
        own = (info.data or {}).get("id")
        if own and own in v:
            raise ValueError("a step cannot depend on itself")
        return v


class TaskPlan(BaseModel):
    """A structured, ordered plan. The executor owns the ordering gates
    (dependencies, parallelism); the plan only declares intent."""

    model_config = ConfigDict(extra="forbid")

    goal: str = Field(min_length=1, max_length=1000)
    steps: list[PlannedStep] = Field(min_length=0, max_length=20)
    notes: str | None = Field(default=None, max_length=500)


class StepResultRef(BaseModel):
    """A value inside step arguments that pulls from an earlier result."""
    model_config = ConfigDict(extra="forbid")
    step: str
    key: str = Field(min_length=1)


class ToolCall(BaseModel):
    """The model's choice of tool + arguments, validated before execution."""

    model_config = ConfigDict(extra="forbid")
    tool: str = Field(min_length=1, max_length=160)
    arguments: dict[str, Any] = Field(default_factory=dict)


class VerificationVerdict(BaseModel):
    model_config = ConfigDict(extra="forbid")
    ok: bool
    reason: str | None = Field(default=None, max_length=500)
    retry: bool = False


class FinalResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    response: str = Field(min_length=1, max_length=8000)


class JsonValidationError(Exception):
    """Raised when model output fails JSON parsing or schema validation."""


def parse_model_json(raw: str, schema_cls: type[BaseModel]) -> BaseModel:
    """Parse fenced or bare JSON from model output into a Pydantic model."""
    import json
    import re

    text = (raw or "").strip()
    fence = re.match(r"^```(?:json)?\s*(.*?)\s*```$", text, re.DOTALL)
    if fence:
        text = fence.group(1).strip()
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise JsonValidationError(f"not valid JSON: {exc}") from exc
    try:
        return schema_cls.model_validate(data)
    except Exception as exc:  # pydantic.ValidationError and friends
        raise JsonValidationError(f"schema mismatch: {exc}") from exc
