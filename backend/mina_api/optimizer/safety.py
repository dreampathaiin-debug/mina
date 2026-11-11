"""Cache safety: operations with real-world side effects are never served
from a cache.

A cached reply for "send this email", "pay the invoice", "book the table"
or "check my live balance" would replay a decision the user has not made
again. The gate below denies caching both before STORE and before REPLAY,
so a result produced under an unsafe configuration can never resurface
later either.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# Task types whose outcome is a live, one-time action. These are denied
# outright regardless of wording.
UNSAFE_TASK_TYPES = frozenset(
    {
        "payment", "pay", "transfer", "order", "purchase", "checkout",
        "booking", "reservation", "email_send", "send_email", "messaging",
        "account_change", "account_update", "balance", "balance_check",
        "availability_check", "cancel", "refund", "delete",
    }
)

# Tool names that mutate the world or read volatile state. Requests carrying
# any of these as selected tools are never cached.
UNSAFE_TOOL_PATTERNS = re.compile(
    r"(send_email|send_message|send_sms|make_call|place_call|payment|pay_|transfer"
    r"|place_order|create_order|book_|booking|reserve|purchase|checkout|charge"
    r"|delete|cancel|refund|update_account|account_update|set_password|move_money"
    r"|check_balance|account_balance|availability|calendar_write|create_event)",
    re.IGNORECASE,
)

# Belt-and-braces wording scan over the final user message: catches side
# effects that arrive through conversation text even when the task type or
# tool list looks safe.
UNSAFE_INTENT_PATTERNS = re.compile(
    r"\b(send|reply|forward)\b.{0,24}\b(email|mail|message|text)\b"
    r"|\b(pay|transfer|purchase|order|book|reserve|subscribe|cancel|refund)\b"
    r"|\bbalance\b|\bhow much (do i|is my) (have|owe|balance)"
    r"|\b(available|availability)\b.{0,20}\b(now|today|right now|tonight)\b"
    r"|\b(update|change|delete)\b.{0,16}\b(account|password|payment method)\b",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class CacheDecision:
    cacheable: bool
    reason: str


def evaluate_cache_safety(
    *,
    task_type: str,
    tools: list[dict] | None,
    final_user_text: str | None,
    has_tool_state: bool,
    temperature: float | None,
    tool_execution_id: str | None,
    force_uncacheable: bool = False,
) -> CacheDecision:
    if force_uncacheable:
        return CacheDecision(False, "explicit opt-out")
    if tool_execution_id is not None:
        return CacheDecision(False, "mid tool round")
    if has_tool_state:
        return CacheDecision(False, "pending tool calls in history")
    if task_type in UNSAFE_TASK_TYPES:
        return CacheDecision(False, f"task type '{task_type}' has external side effects")
    for tool in tools or []:
        name = _tool_name(tool)
        if UNSAFE_TOOL_PATTERNS.search(name):
            return CacheDecision(False, f"side-effectful tool '{name}' in request")
    if final_user_text and UNSAFE_INTENT_PATTERNS.search(final_user_text):
        return CacheDecision(False, "request wording implies a live external action")
    if temperature not in (None, 0.0, 0):
        return CacheDecision(False, "sampling temperature above 0 is not deterministic")
    return CacheDecision(True, "safe")


def _tool_name(tool: dict) -> str:
    function = tool.get("function") or tool
    return str(function.get("name", ""))
