"""Model access for the orchestrator.

The orchestrator talks to models through one narrow interface so the
planner/executor/verifier roles stay testable with a fake. The default
implementation routes through the Phase 4 ModelRouter, which already
owns credential policy, fallback, health and usage accounting.
"""

from __future__ import annotations

import json
import re
from dataclasses import replace
from uuid import UUID

from mina_api.agent.schemas import JsonValidationError, parse_model_json
from mina_api.routing.fallback_manager import ChatRequestSpec
from mina_api.routing.routing_policy import TaskSpec


class ModelCaller:
    """Call a model and get back text, or validated Pydantic JSON."""

    async def call(
        self, db, user_id: UUID, *, task_type: str, system: str, user: str, max_tokens: int = 1024
    ) -> tuple[str, str | None]:
        raise NotImplementedError

    async def call_json(
        self, db, user_id: UUID, *, task_type: str, system: str, user: str,
        schema_cls: type, max_tokens: int = 1024, retries: int = 1,
    ):
        """Call the model and validate its JSON with the given schema.

        One validation failure is fed back to the model with the error;
        after that the failure propagates so the orchestrator can replan.
        """
        prompt = user
        last_error: Exception | None = None
        for attempt in range(retries + 1):
            raw, model_id = await self.call(
                db, user_id, task_type=task_type, system=system, user=prompt, max_tokens=max_tokens
            )
            try:
                return parse_model_json(raw, schema_cls), model_id
            except JsonValidationError as exc:
                last_error = exc
                prompt = (
                    f"{user}\n\nYour previous reply was not valid: {exc}. "
                    "Reply with ONLY the JSON object matching the schema."
                )
        raise last_error or JsonValidationError("model returned invalid JSON")


class RouterModelCaller(ModelCaller):
    """Real implementation over the Phase 4 ModelRouter."""

    # Agent roles map onto the router's catalog of task types; the
    # orchestrator's internal names stay stable even if routing renames.
    ROUTING_TASK_TYPE = {
        "agent_classify": "intent_classification",
        "agent_planning": "planning",
        "agent_step_model": "complex_reasoning",
        "agent_final": "conversation",
        "agent_verify": "short_summary",
    }

    def __init__(self, model_router) -> None:
        self.router = model_router

    async def call(
        self, db, user_id: UUID, *, task_type: str, system: str, user: str, max_tokens: int = 1024
    ) -> tuple[str, str | None]:
        task = TaskSpec(
            task_type=self.ROUTING_TASK_TYPE.get(task_type, task_type),
            needs_tools=False, max_output_tokens=max_tokens,
        )
        request = ChatRequestSpec(
            messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
            temperature=0.1,
            max_tokens=max_tokens,
        )
        outcome = await self.router.complete(db, user_id, task, request)
        return outcome.result.content or "", outcome.result.model_id
