"""Task-specific output token limits.

Every task type gets an output ceiling; the effective limit is the task
ceiling, clamped by what the model can produce and by any explicit
request. This stops runaway generations from burning tokens on tasks
that never need them.
"""

from __future__ import annotations

# Output ceiling per task type. Conservative defaults; operators override
# through settings when needed.
TASK_OUTPUT_LIMITS: dict[str, int] = {
    "conversation": 2048,
    "summary": 512,
    "summarization": 512,
    "classification": 256,
    "extraction": 1024,
    "translation": 2048,
    "coding": 4096,
    "research": 4096,
    "planning": 4096,
    "agent": 4096,
}
DEFAULT_OUTPUT_LIMIT = 2048


def output_limit_for(task_type: str) -> int:
    return TASK_OUTPUT_LIMITS.get(task_type, DEFAULT_OUTPUT_LIMIT)


def apply_output_limit(task_type: str, requested: int | None, model_max: int) -> int:
    """Effective max_tokens: the task ceiling, then model and request caps."""
    limit = min(output_limit_for(task_type), model_max)
    if requested is not None:
        limit = min(limit, requested)
    return max(1, limit)
