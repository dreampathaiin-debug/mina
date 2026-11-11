"""Conversations and their messages, strictly tenant-scoped."""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, Response
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.models import Conversation, Message
from mina_api.database.session import get_db
from mina_api.schemas.tenant import ConversationCreate, ConversationOut, MessageCreate, MessageOut
from mina_api.security.auth import CurrentUser
from mina_api.services.tenant import get_owned, list_owned

router = APIRouter(prefix="/conversations", tags=["conversations"])


@router.post("", response_model=ConversationOut, status_code=201)
async def create_conversation(
    payload: ConversationCreate, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> Conversation:
    conversation = Conversation(user_id=user.id, title=payload.title)
    db.add(conversation)
    await db.commit()
    await db.refresh(conversation)
    return conversation


@router.get("", response_model=list[ConversationOut])
async def list_conversations(user: CurrentUser, db: AsyncSession = Depends(get_db)) -> list[Conversation]:
    return await list_owned(db, Conversation, user.id)


@router.get("/{conversation_id}", response_model=ConversationOut)
async def get_conversation(
    conversation_id: UUID, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> Conversation:
    return await get_owned(db, Conversation, conversation_id, user.id)


@router.delete("/{conversation_id}", status_code=204)
async def delete_conversation(
    conversation_id: UUID, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> Response:
    conversation = await get_owned(db, Conversation, conversation_id, user.id)
    await db.delete(conversation)
    await db.commit()
    return Response(status_code=204)


@router.post("/{conversation_id}/messages", response_model=MessageOut, status_code=201)
async def create_message(
    conversation_id: UUID,
    payload: MessageCreate,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
) -> Message:
    conversation = await get_owned(db, Conversation, conversation_id, user.id)
    message = Message(
        conversation_id=conversation.id, user_id=user.id, role=payload.role, content=payload.content
    )
    db.add(message)
    await db.commit()
    await db.refresh(message)
    return message


@router.get("/{conversation_id}/messages", response_model=list[MessageOut])
async def list_messages(
    conversation_id: UUID, user: CurrentUser, db: AsyncSession = Depends(get_db)
) -> list[Message]:
    conversation = await get_owned(db, Conversation, conversation_id, user.id)
    result = await db.execute(
        select(Message)
        .where(Message.conversation_id == conversation.id, Message.user_id == user.id)
        .order_by(Message.created_at.asc())
    )
    return list(result.scalars().all())
