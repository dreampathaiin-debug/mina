"""Conversation summarization for messages that fall out of the window.

The default summarizer is deterministic and extractive: it keeps the role,
the gist and the last sentence of each dropped turn so downstream models
still see what happened without paying full price for it. A real LLM
summarizer can be injected anywhere the pipeline is constructed.
"""

from __future__ import annotations

import abc
from dataclasses import dataclass

_TRIM = 220


@dataclass(frozen=True)
class TurnSummary:
    role: str
    gist: str


class Summarizer(abc.ABC):
    @abc.abstractmethod
    def summarize(self, messages: list[dict]) -> list[TurnSummary]:
        """Summarize the dropped turns, in order."""


class ExtractiveSummarizer(Summarizer):
    def summarize(self, messages: list[dict]) -> list[TurnSummary]:
        out: list[TurnSummary] = []
        for message in messages:
            content = message.get("content")
            text = content if isinstance(content, str) else ""
            if not text:
                if message.get("tool_calls"):
                    text = "(made tool calls: " + ", ".join(
                        c.get("name", "?") for c in message["tool_calls"]
                    ) + ")"
                elif message.get("tool_call_id"):
                    text = "(tool result)"
                else:
                    text = "(non-text message)"
            text = " ".join(text.split())
            if len(text) > _TRIM:
                head = text[: _TRIM // 2].rsplit(" ", 1)[0]
                tail = text[-_TRIM // 2 :].lstrip()
                text = f"{head} ... {tail}"
            out.append(TurnSummary(role=message.get("role", "unknown"), gist=text))
        return out


def summary_system_text(summaries: list[TurnSummary]) -> str:
    lines = [f"{s.role}: {s.gist}" for s in summaries]
    body = "\n".join(lines)
    return (
        "Summary of earlier conversation (summary of earlier turns). "
        f"Details may be abbreviated:\n{body}"
    )
