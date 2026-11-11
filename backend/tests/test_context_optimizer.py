"""Phase 5 proof tests: optimization never loses important task
instructions, never splits tool-call history, and measures every saving."""
import json

import pytest

from mina_api.optimizer.context_optimizer import optimize_context
from mina_api.optimizer.memory_retrieval import MemoryItem
from mina_api.optimizer.output_limits import apply_output_limit
from mina_api.optimizer.prompt_caching import apply_cache_markers
from mina_api.optimizer.summarization import ExtractiveSummarizer
from mina_api.optimizer.token_estimation import estimate_request
from mina_api.optimizer.tool_selection import select_tools


SYSTEM = "You are Mina, a helpful agent. ALWAYS confirm before spending money."
LONG_TURN = "We discussed the quarterly plan in detail. " * 40


def model():
    class S:
        pass
    s = S()
    s.provider = "openai"
    s.context_window = 4000
    s.max_output_tokens = 4096
    return s


def build_messages(turns=6):
    messages = [{"role": "system", "content": SYSTEM}]
    for i in range(turns):
        messages.append({"role": "user", "content": f"question {i} about quarterly plan"})
        messages.append({"role": "assistant", "content": f"answer {i} " + LONG_TURN})
    messages.append({"role": "user", "content": "FINAL REQUEST: summarize the quarterly plan"})
    return messages


def run(messages, **kwargs):
    return optimize_context(
        messages=messages,
        tools_tokens=0,
        model_context_window=kwargs.pop("context_window", 4000),
        output_reserve=kwargs.pop("output_reserve", 512),
        query_text=kwargs.pop("query_text", "summarize the quarterly plan"),
        task_state=kwargs.pop("task_state", None),
        memories=kwargs.pop("memories", []),
        memory_budget_tokens=kwargs.pop("memory_budget_tokens", 300),
        summarizer=kwargs.pop("summarizer", ExtractiveSummarizer()),
        provider="openai",
        **kwargs,
    )


def test_current_user_request_is_never_dropped():
    messages = build_messages()
    result = run(messages)
    assert result.messages[-1]["content"] == "FINAL REQUEST: summarize the quarterly plan"
    assert result.messages[-1]["role"] == "user"


def test_system_instructions_are_preserved_verbatim():
    messages = build_messages()
    result = run(messages)
    system_contents = [m["content"] for m in result.messages if m["role"] == "system"]
    assert SYSTEM in system_contents


def test_duplicate_system_instructions_are_removed_first_copy_kept():
    messages = [{"role": "system", "content": SYSTEM}] + build_messages()
    messages.insert(0, {"role": "system", "content": SYSTEM})  # exact duplicate
    result = run(messages)
    copies = [m["content"] for m in result.messages if m["role"] == "system" and m["content"] == SYSTEM]
    assert len(copies) == 1
    assert result.duplicated_instructions_removed >= 1


def test_repeated_user_instruction_dropped_only_for_older_copy():
    request_text = "FINAL REQUEST: summarize the quarterly plan"
    messages = build_messages()
    messages.insert(2, {"role": "user", "content": request_text})  # stale duplicate
    result = run(messages)
    user_texts = [m["content"] for m in result.messages if m["role"] == "user"]
    assert user_texts.count(request_text) == 1


def test_active_task_state_is_injected():
    result = run(build_messages(), task_state="Drafting Q3 report, step 2 of 3")
    system_block = " ".join(
        m["content"] for m in result.messages if m["role"] == "system"
    )
    assert "active task state" in system_block
    assert "step 2 of 3" in system_block


def test_old_messages_are_summarized_not_lost():
    messages = build_messages(turns=10)
    result = run(messages, context_window=1800, output_reserve=256)
    assert result.summarized_turns > 0
    summary_block = [m for m in result.messages if "(summary of earlier turns)" in m.get("content", "")]
    assert summary_block, "dropped turns must survive as a summary"
    summary_text = summary_block[0]["content"]
    assert "user:" in summary_text and "assistant:" in summary_text
    assert result.tokens_after < result.tokens_before, "optimization must actually shrink the input"


def test_tool_call_history_never_split():
    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": "book the venue please " + LONG_TURN},
        {
            "role": "assistant",
            "content": None,
            "tool_calls": [{"id": "call_1", "type": "function", "name": "book_venue", "arguments": "{}"}],
        },
        {"role": "tool", "tool_call_id": "call_1", "content": "booked: Grand Hall, 7pm"},
        {"role": "assistant", "content": "Done, Grand Hall at 7pm."},
        {"role": "user", "content": "FINAL REQUEST: now confirm the catering"},
    ]
    result = run(messages, context_window=900, output_reserve=256)
    roles = [m.get("role") for m in result.messages]
    # the tool result either survives WITH its call or the whole turn is gone
    if "tool" in roles:
        call_index = next(i for i, m in enumerate(result.messages) if m.get("tool_calls"))
        tool_index = roles.index("tool")
        assert call_index < tool_index, "tool result must follow its assistant tool_calls"
    # and the preserved history is well-formed: every tool message has a predecessor
    for i, m in enumerate(result.messages):
        if m.get("role") == "tool":
            assert any(prev.get("tool_calls") for prev in result.messages[:i])


def test_relevant_memories_only():
    memories = [
        MemoryItem(id="m1", text="quarterly plan review is scheduled for Friday"),
        MemoryItem(id="m2", text="user prefers window seats on flights"),
        MemoryItem(id="m3", text="the quarterly plan budget was approved in March"),
    ]
    result = run(build_messages(), memories=memories)
    system_text = " ".join(m["content"] for m in result.messages if m["role"] == "system")
    assert "quarterly plan review" in system_text
    assert "window seats" not in system_text


def test_output_limits_task_specific_and_clamped():
    assert apply_output_limit("summary", None, 8192) == 512
    assert apply_output_limit("conversation", None, 8192) == 2048
    assert apply_output_limit("coding", None, 8192) == 4096
    assert apply_output_limit("coding", None, 1024) == 1024, "model cap clamps the task limit"
    assert apply_output_limit("summary", 100, 8192) == 100, "explicit request still clamps"


def test_prompt_cache_markers_only_where_supported_and_on_stable_prefix():
    messages = [{"role": "system", "content": SYSTEM}] + build_messages(turns=3)
    result = apply_cache_markers(messages, "anthropic", 5000)
    assert result.mode == "explicit" and result.marked_prefix >= 1
    system_msg = next(m for m in messages if m["role"] == "system")
    assert system_msg["content"][0]["cache_control"] == {"type": "ephemeral"}
    # the final user message must never carry a marker
    assert "cache_control" not in json.dumps(messages[-1])
    # implicit providers get no markers
    openai_result = apply_cache_markers(messages, "openai", 5000)
    assert openai_result.mode == "implicit" and openai_result.marked_prefix == 0
    # small prefixes are not worth marking
    tiny = apply_cache_markers(messages, "anthropic", 10)
    assert tiny.marked_prefix == 0


def test_token_estimation_is_model_specific():
    ascii_text = "hello world, this is a plain english sentence about routing. " * 10
    cjk_text = "这是一段中文文本，用于测试分词器差异。" * 10
    assert estimate_request([{"role": "user", "content": ascii_text}], provider="openai") > 50
    # CJK costs more tokens per character than ASCII on every provider
    assert estimate_request([{"role": "user", "content": cjk_text}], provider="openai") > estimate_request(
        [{"role": "user", "content": ascii_text}], provider="openai"
    )
    # provider profiles differ
    assert estimate_request([{"role": "user", "content": ascii_text}], provider="anthropic") != estimate_request(
        [{"role": "user", "content": ascii_text}], provider="openai"
    )
    # tools add measurable tokens
    tool = {"type": "function", "function": {"name": "search_flights", "description": "search flights", "parameters": {"type": "object"}}}
    with_tools = estimate_request([{"role": "user", "content": "hi"}], tools=[tool], provider="openai")
    without = estimate_request([{"role": "user", "content": "hi"}], provider="openai")
    assert with_tools > without


def test_tool_selection_keeps_required_and_drops_irrelevant():
    tools = [
        {"type": "function", "function": {"name": "search_flights", "description": "search flights by date and city"}},
        {"type": "function", "function": {"name": "currency_convert", "description": "convert currency amounts"}},
        {"type": "function", "function": {"name": "calendar_write", "description": "create calendar events"}},
    ]
    picked = select_tools("find me flights to Delhi next week", tools)
    assert [t["function"]["name"] for t in picked] == ["search_flights"]
    forced = select_tools("anything", tools, required_tools=["currency_convert"])
    assert "currency_convert" in [t["function"]["name"] for t in forced]
    empty_ok = select_tools("gibberish query zzz", tools)
    assert empty_ok, "a task that needs tools must not end up with zero tools"
