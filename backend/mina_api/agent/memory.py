"""Relevant memory retrieval for the orchestrator.

Two sources, both the user's own data only:
- recent conversation turns (last N messages of the bound conversation);
- the user's saved memories, scored with the Phase 5 lexical retriever
  against the current request, within a token budget.
Retrieved context is assembled once per run and reused by the planner
and verifier.
"""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import select

from mina_api.database.models import Memory, Message
from mina_api.optimizer.memory_retrieval import MemoryItem, retrieve_relevant
from mina_api.optimizer.token_estimation import estimate_text


class AgentMemory:
    def __init__(self, *, recent_turns: int = 8, budget_tokens: int = 1024) -> None:
        self.recent_turns = recent_turns
        self.budget_tokens = budget_tokens

    async def retrieve(
        self, db, user_id: UUID, query: str, conversation_id: UUID | None
    ) -> dict:
        """Returns {"history": [...], "memories": [...]} as text blocks."""
        history: list[dict] = []
        if conversation_id is not None:
            rows = (
                await db.execute(
                    select(Message)
                    .where(Message.conversation_id == conversation_id, Message.user_id == user_id)
                    .order_by(Message.created_at.desc())
                    .limit(self.recent_turns)
                )
            ).scalars().all()
            history = [
                {"role": m.role, "content": m.content[:500]} for m in reversed(rows)
            ]

        saved = (await db.execute(select(Memory).where(Memory.user_id == user_id))).scalars().all()
        items = [MemoryItem(id=str(m.id), text=m.content[:400]) for m in saved]
        picked = retrieve_relevant(query, items, budget_tokens=self.budget_tokens)
        return {
            "history": history,
            "memories": [p.text for p in picked],
            "context_tokens": sum(estimate_text(h["content"]) for h in history)
            + sum(estimate_text(t) for t in (p.text for p in picked)),
        }
