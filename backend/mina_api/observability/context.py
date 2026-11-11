"""Phase 10 observability context.

Ambient correlation ids threaded through a request so every recorded
event carries the request/conversation/task chain without plumbing ids
through every call signature. Auth-level user id is recorded here too;
keys and tokens never are.
"""

from __future__ import annotations

from contextvars import ContextVar, Token

# Authenticated user (internal id only).
obs_user_id_var: ContextVar[str | None] = ContextVar("obs_user_id", default=None)
# Correlation ids.
obs_conversation_id_var: ContextVar[str | None] = ContextVar("obs_conversation_id", default=None)
obs_task_id_var: ContextVar[str | None] = ContextVar("obs_task_id", default=None)
# Model-routing facts set by the routing stack per attempt.
obs_cache_hit_var: ContextVar[str | None] = ContextVar("obs_cache_hit", default=None)
obs_retry_count_var: ContextVar[int] = ContextVar("obs_retry_count", default=0)
obs_fallback_count_var: ContextVar[int] = ContextVar("obs_fallback_count", default=0)


def bind_observation_context(
    *,
    user_id: str | None = None,
    conversation_id: str | None = None,
    task_id: str | None = None,
) -> list[Token]:
    """Bind ambient ids; returns tokens to reset()."""
    tokens: list[Token] = []
    if user_id is not None:
        tokens.append(obs_user_id_var.set(user_id))
    if conversation_id is not None:
        tokens.append(obs_conversation_id_var.set(conversation_id))
    if task_id is not None:
        tokens.append(obs_task_id_var.set(task_id))
    return tokens


def reset_observation_context(tokens: list[Token]) -> None:
    for token in reversed(tokens):
        token.var.reset(token)
