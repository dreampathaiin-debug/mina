"""The verifier role: check results before the run moves on.

Step verification is heuristic and cheap: a tool that reports failure,
an empty result or an untrusted error means the step did not achieve
its description. The final response gets one model-verified check,
bounded so verification cannot itself become a loop.
"""

from __future__ import annotations

from uuid import UUID

from mina_api.agent.llm import ModelCaller
from mina_api.agent.schemas import FinalResponse, VerificationVerdict

VERIFY_SYSTEM = """You verify an agent's final answer against the user's request.
Reply with ONLY JSON: {"ok": true|false, "reason": "...", "retry": true|false}.
Set ok=false when the answer does not address the request."""


class Verifier:
    def __init__(self, caller: ModelCaller | None = None) -> None:
        self.caller = caller

    def verify_step(self, step_spec: dict, result: dict) -> VerificationVerdict:
        """Heuristic check of one executed step's result."""
        if result.get("_failed"):
            return VerificationVerdict(ok=False, reason=str(result.get("kind")), retry=bool(result.get("transient")))
        if result.get("error"):
            return VerificationVerdict(ok=False, reason=str(result.get("error")), retry=True)
        data = result.get("data")
        if isinstance(data, dict) and data.get("successful") is False:
            return VerificationVerdict(ok=False, reason="tool reported failure", retry=True)
        if result.get("data_excerpt") is not None or result.get("ok") is True or result.get("response"):
            return VerificationVerdict(ok=True)
        # A result with any content at all passes; empty output fails.
        if any(v not in (None, "", [], {}) for v in result.values()):
            return VerificationVerdict(ok=True)
        return VerificationVerdict(ok=False, reason="empty result", retry=False)

    async def verify_final(
        self, db, user_id: UUID, request: str, response: str
    ) -> VerificationVerdict:
        """Model-checked final response. Falls back to accepting a
        non-empty response when verification is unavailable, so a broken
        verifier never destroys good work."""
        if self.caller is None:
            return VerificationVerdict(ok=bool(response.strip()))
        try:
            verdict, _model_id = await self.caller.call_json(
                db, user_id, task_type="agent_verify", system=VERIFY_SYSTEM,
                user=f"Request: {request}\n\nAnswer: {response[:4000]}",
                schema_cls=VerificationVerdict,
            )
            return verdict
        except Exception:
            return VerificationVerdict(ok=bool(response.strip()))
