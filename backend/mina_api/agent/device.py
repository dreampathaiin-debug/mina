"""Structured, permission-aware Android device action instructions.

The backend NEVER touches Accessibility services and never pretends it
can. It produces an instruction payload describing what the Android app
should do; the app validates it against its own permission-gated
executor and reports the result back through the API. The backend's
only claim on the device is this message.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

DeviceVerb = Literal["open_app", "tap", "type", "scroll", "read_screen", "screenshot"]

READONLY_VERBS = {"read_screen", "screenshot"}


class DeviceInstruction(BaseModel):
    """A single device action instruction, safe to hand to Android."""

    model_config = ConfigDict(extra="forbid")

    action: DeviceVerb
    target: str | None = Field(default=None, max_length=300)
    text: str | None = Field(default=None, max_length=2000)
    direction: Literal["up", "down", "left", "right"] | None = None
    package: str | None = Field(default=None, max_length=160)

    @field_validator("package")
    @classmethod
    def _package_shape(cls, v):
        if v is not None and not v.replace(".", "").replace("_", "").isalnum():
            raise ValueError("package must look like an Android package name")
        return v


class DeviceActionRequest(BaseModel):
    """What the orchestrator hands back to the client for one step."""

    model_config = ConfigDict(extra="forbid")

    step_id: str
    instruction: DeviceInstruction
    permission_required: str
    readonly: bool


def permission_for(verb: str) -> str:
    """The Android-side permission gate each verb needs. The Android app
    enforces these locally; this mapping only tells it what to check."""
    return {
        "open_app": "PACKAGE_USAGE_STATS",
        "tap": "ACCESSIBILITY_SERVICE",
        "type": "ACCESSIBILITY_SERVICE",
        "scroll": "ACCESSIBILITY_SERVICE",
        "read_screen": "ACCESSIBILITY_SERVICE",
        "screenshot": "SCREEN_CAPTURE",
    }.get(verb, "ACCESSIBILITY_SERVICE")


def classify_device_step(
    arguments: dict[str, Any], risk: str, action_fallback: str | None = None
) -> tuple[DeviceInstruction, bool]:
    """Validate a device step into an instruction. Returns the
    instruction and whether it is read-only. Invalid device steps fail
    closed here, never on the phone. The plan step's own `action` is the
    default when the arguments do not name one."""
    allowed = {"action", "target", "text", "direction", "package"}
    clean = {k: v for k, v in (arguments or {}).items() if k in allowed}
    clean.setdefault("action", action_fallback)
    instruction = DeviceInstruction.model_validate(clean)
    readonly = instruction.action in READONLY_VERBS and risk == "safe"
    return instruction, readonly


def instruction_payload(step_id: str, instruction: DeviceInstruction, readonly: bool) -> dict[str, Any]:
    return DeviceActionRequest(
        step_id=step_id,
        instruction=instruction,
        permission_required=permission_for(instruction.action),
        readonly=readonly,
    ).model_dump(mode="json")
