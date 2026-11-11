"""Context-window management: build the smallest context that keeps the
task solvable.

Context strategy, in order:
  1. preserve the current user request (never dropped, never summarized)
  2. preserve relevant system instructions (deduplicated, never dropped)
  3. preserve active task state (injected as a system block)
  4. preserve recent relevant conversation (newest turns within budget)
  5. retrieve only relevant memories (scored, budgeted)
  6. summarize older messages (extractive by default, injectable)
  7. never truncate required tool-call history blindly: an assistant turn
     that made tool calls and its tool results move as one atom; if the
     atom does not fit, the optimizer stops there instead of splitting it
"""

from __future__ import annotations

from dataclasses import dataclass, field

from mina_api.optimizer.memory_retrieval import MemoryItem, retrieve_relevant
from mina_api.optimizer.summarization import Summarizer, summary_system_text
from mina_api.optimizer.token_estimation import estimate_messages

TASK_STATE_TAG = "active task state"
SUMMARY_TAG = "(summary of earlier turns)"


@dataclass
class OptimizedContext:
    messages: list[dict]
    tokens_before: int
    tokens_after: int
    dropped_turns: int = 0
    summarized_turns: int = 0
    duplicated_instructions_removed: int = 0
    memories_used: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


def _is_tool_message(message: dict) -> bool:
    return message.get("role") == "tool" or bool(message.get("tool_call_id"))


def _makes_tool_calls(message: dict) -> bool:
    return bool(message.get("tool_calls"))


def _atomic_turns(conversation: list[dict]) -> list[list[dict]]:
    """Group conversation messages into atomic turns.

    An assistant message that made tool calls and every following tool
    message (up to the next non-tool message) form one unit: the optimizer
    keeps or drops them together, so no tool result is ever orphaned from
    the call that produced it.
    """
    turns: list[list[dict]] = []
    index = 0
    current: list[dict] = []

    def _flush() -> None:
        nonlocal current
        if current:
            turns.append(current)
            current = []

    while index < len(conversation):
        message = conversation[index]
        if message.get("role") == "user":
            _flush()
            current = [message]
            index += 1
        elif _makes_tool_calls(message):
            _flush()
            current = [message]
            index += 1
            while index < len(conversation) and _is_tool_message(conversation[index]):
                current.append(conversation[index])
                index += 1
            _flush()
        else:
            current.append(message)
            index += 1
    _flush()
    return turns


def _dedupe_system(system_texts: list[str]) -> tuple[list[str], int]:
    seen: set[str] = set()
    kept: list[str] = []
    removed = 0
    for text in system_texts:
        if text in seen:
            removed += 1
            continue
        seen.add(text)
        kept.append(text)
    return kept, removed


def optimize_context(
    *,
    messages: list[dict],
    tools_tokens: int,
    model_context_window: int,
    output_reserve: int,
    query_text: str,
    task_state: str | None,
    memories: list[MemoryItem],
    memory_budget_tokens: int,
    summarizer: Summarizer,
    context_reserve_ratio: float = 0.85,
    provider: str | None = None,
    preserve_full_history: bool = False,
) -> OptimizedContext:
    """Build the optimized message list. `preserve_full_history` is used for
    mid-tool-rounds and other situations where history must stay verbatim."""
    count_before = estimate_messages(messages, provider=provider)
    tokens_before = count_before.total

    budget = max(
        0,
        int(model_context_window * context_reserve_ratio) - output_reserve - tools_tokens,
    )

    system_messages = [m for m in messages if m.get("role") == "system"]
    conversation = [m for m in messages if m.get("role") != "system"]

    # 1. Duplicate instruction removal, system level: keep the first copy.
    system_texts = [
        m.get("content") if isinstance(m.get("content"), str) else "" for m in system_messages
    ]
    deduped_texts, removed_system = _dedupe_system(system_texts)
    if task_state:
        deduped_texts.append(f"{TASK_STATE_TAG}: {task_state}")

    # 2. Relevant memory retrieval: only what scores against the request.
    selected_memories = retrieve_relevant(
        query_text, memories, budget_tokens=memory_budget_tokens, provider=provider
    )
    if selected_memories:
        deduped_texts.append(
            "Relevant memory:\n" + "\n".join(f"- {m.text}" for m in selected_memories)
        )

    # 3. Recent message selection + summarization, newest-first within budget.
    turns = _atomic_turns(conversation)
    kept_turns: list[list[dict]] = []
    dropped_turns: list[list[dict]] = []
    used = estimate_messages(
        [{"role": "system", "content": t} for t in deduped_texts], provider=provider
    ).total
    # The current user request (last user message) is always preserved.
    last_user_index = max(
        (i for i, turn in enumerate(turns) if turn[-1].get("role") == "user"),
        default=-1,
    )
    final_user_turn = turns[last_user_index] if last_user_index >= 0 else None

    for position in range(len(turns) - 1, -1, -1):
        turn = turns[position]
        if preserve_full_history:
            kept_turns.insert(0, turn)
            continue
        turn_tokens = estimate_messages(turn, provider=provider).total
        if turn is final_user_turn:
            kept_turns.insert(0, turn)
            used += turn_tokens
            continue
        if used + turn_tokens <= budget:
            kept_turns.insert(0, turn)
            used += turn_tokens
        else:
            dropped_turns.insert(0, turn)

    # 4. Summarize whatever fell out of the window.
    dropped_messages = [m for turn in dropped_turns for m in turn]
    summaries = summarizer.summarize(dropped_messages) if dropped_messages else []
    if summaries:
        deduped_texts.append(summary_system_text(summaries))

    # 5. Duplicate instruction removal inside the kept history: an older user
    #    message repeating the current request verbatim is redundant.
    current_request_text = None
    if final_user_turn is not None:
        content = final_user_turn[0].get("content")
        if isinstance(content, str):
            current_request_text = content
    duplicated_removed = removed_system
    if current_request_text:
        kept_messages: list[dict] = []
        for turn in kept_turns:
            for i, message in enumerate(turn):
                if (
                    message.get("role") == "user"
                    and message.get("content") == current_request_text
                    and message is not final_user_turn[0]
                    and all(m is not message for m in kept_messages)
                ):
                    duplicated_removed += 1
                    continue
                kept_messages.append(message)
    else:
        kept_messages = [m for turn in kept_turns for m in turn]

    built: list[dict] = [{"role": "system", "content": t} for t in deduped_texts]
    built.extend(kept_messages)
    count_after = estimate_messages(built, provider=provider)

    notes: list[str] = []
    if removed_system:
        notes.append(f"removed {removed_system} duplicate system instruction(s)")
    if selected_memories:
        notes.append(f"retrieved {len(selected_memories)} relevant memories")
    if summaries:
        notes.append(f"summarized {len(summaries)} older turn(s)")
    if duplicated_removed:
        notes.append(f"removed {duplicated_removed} duplicate instruction(s)")

    return OptimizedContext(
        messages=built,
        tokens_before=tokens_before,
        tokens_after=count_after.total,
        dropped_turns=len(dropped_turns),
        summarized_turns=len(summaries),
        duplicated_instructions_removed=duplicated_removed,
        memories_used=[m.id for m in selected_memories],
        notes=notes,
    )
