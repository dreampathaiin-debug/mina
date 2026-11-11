"""Phase 8 tests: long-term memory system.

Covers retrieval relevance, ownership/tenant isolation, deletion,
dedup and corrections (memory conflicts), the safety rules, expiry,
extraction and the API surface.
"""

from __future__ import annotations

import asyncio
import json
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest

from mina_api.database.models import Memory
from tests.conftest import headers_for


def _service(app):
    return app.state.memory_service


# ---------------------------------------------------------------- helpers

def _create(app, user, content, **kwargs):
    async def _go():
        async with app.state.database.session() as session:
            return await _service(app).create(session, user, content, **kwargs)

    return asyncio.run(_go())


def _search(app, user, query, **kwargs):
    async def _go():
        async with app.state.database.session() as session:
            return await _service(app).search(session, user, query, **kwargs)

    return asyncio.run(_go())


# ---------------------------------------------------------------- retrieval

def test_retrieval_ranks_relevant_first(app, user_a):
    _create(app, user_a, "User prefers dark mode in every app")
    _create(app, user_a, "User's favourite editor is Vim")
    _create(app, user_a, "User takes the metro to office on weekdays")

    results = _search(app, user_a, "which editor does the user like for coding")
    assert results, "search returned nothing"
    assert "Vim" in results[0]["content"]

    metro = _search(app, user_a, "metro commute office")
    assert "metro" in metro[0]["content"]


def test_retrieval_never_leaks_other_users(app, user_a, user_b):
    _create(app, user_a, "User's favourite editor is Vim")
    _create(app, user_b, "User's favourite editor is Notepad")

    for_a = _search(app, user_a, "favourite editor")
    for_b = _search(app, user_b, "favourite editor")
    assert all("Notepad" not in r["content"] for r in for_a)
    assert all("Vim" not in r["content"] for r in for_b)


def test_retrieval_prefers_high_confidence_and_fresh(app, user_a):
    _create(app, user_a, "User prefers window seats on flights", source="user_stated")
    _create(app, user_a, "User sometimes likes aisle seats on trains", source="extracted", confidence=0.3)
    results = _search(app, user_a, "window seats on flights")
    assert results[0]["content"] == "User prefers window seats on flights"
    assert results[0]["confidence"] >= 0.9


def test_legacy_fact_rows_read_as_semantic(app, user_a):
    _create(app, user_a, "User keeps meetings short", kind="semantic")
    results = _search(app, user_a, "meetings length", kinds=["semantic"])
    assert any("meetings short" in r["content"] for r in results)


# ---------------------------------------------------------------- ownership

def test_ownership_isolation_on_every_operation(app, user_a, user_b):
    row, _ = _create(app, user_a, "Anil likes filter coffee")
    other = str(uuid4())

    async def _try_other():
        async with app.state.database.session() as session:
            await _service(app).get(session, user_b, row.id)

    with pytest.raises(Exception):
        asyncio.run(_try_other())
    assert str(row.id) != other


def test_other_user_search_never_returns_foreign_rows(app, user_a, user_b):
    _create(app, user_a, "secret: Anil's surprise party is on Saturday")
    results = _search(app, user_b, "surprise party")
    assert results == []


# ---------------------------------------------------------------- deletion

def test_soft_delete_hides_and_purge_removes(app, user_a):
    row, _ = _create(app, user_a, "User owns a blue bicycle")
    assert any("bicycle" in r["content"] for r in _search(app, user_a, "bicycle"))

    async def _delete():
        async with app.state.database.session() as session:
            return await _service(app).delete(session, user_a, row.id)

    assert asyncio.run(_delete())["deleted"]
    assert _search(app, user_a, "bicycle") == []

    async def _get():
        async with app.state.database.session() as session:
            await _service(app).get(session, user_a, row.id)

    with pytest.raises(Exception):
        asyncio.run(_get())

    async def _purge():
        async with app.state.database.session() as session:
            return await _service(app).delete(session, user_a, row.id, purge=True)

    assert asyncio.run(_purge())["purged"]


# ---------------------------------------------------------------- conflicts

def test_duplicate_fact_updates_existing_row(app, user_a):
    row1, created1 = _create(app, user_a, "User prefers window seats on flights")
    row2, created2 = _create(
        app, user_a, "User prefers window seats when flying", source="extracted"
    )
    assert created1 is True
    assert created2 is False, "the same fact must not be stored twice"
    assert row2.id == row1.id

    async def _count():
        import sqlalchemy as sa

        async with app.state.database.session() as session:
            rows = (
                await session.execute(
                    sa.select(Memory).where(
                        Memory.user_id == user_a
                    )
                )
            ).scalars().all()
            return [r for r in rows if r.status == "active"]

    assert len(asyncio.run(_count())) == 1


def test_correction_supersedes_and_search_serves_corrected(app, user_a):
    row, _ = _create(app, user_a, "User's favourite colour is blue")

    async def _correct():
        async with app.state.database.session() as session:
            return await _service(app).update(session, user_a, row.id, content="User's favourite colour is green")

    replacement = asyncio.run(_correct())
    assert replacement.content == "User's favourite colour is green"
    assert replacement.source == "corrected"
    assert replacement.confidence == 1.0

    async def _old():
        import sqlalchemy as sa

        async with app.state.database.session() as session:
            return (
                await session.execute(
                    sa.select(Memory).where(
                        Memory.id == row.id
                    )
                )
            ).scalar_one()

    old_row = asyncio.run(_old())
    assert old_row.status == "superseded"
    assert old_row.superseded_by == replacement.id

    results = _search(app, user_a, "favourite colour")
    assert [r["content"] for r in results] == ["User's favourite colour is green"]


# ---------------------------------------------------------------- rules

def test_secrets_are_never_stored(app, user_a):
    from mina_api.memory.safety import SecretDetectedError

    with pytest.raises(SecretDetectedError):
        _create(app, user_a, "my openai key is sk-abcdefghijklmnopqrstuvwx")
    with pytest.raises(SecretDetectedError):
        _create(app, user_a, "password: hunter2hunter2")
    assert _search(app, user_a, "openai key") == []


def test_sensitive_content_needs_approval_and_reason(app, user_a):
    from mina_api.memory.safety import SensitiveWithoutPermissionError

    with pytest.raises(SensitiveWithoutPermissionError):
        _create(app, user_a, "User's blood pressure report says 140/90")
    row, _ = _create(
        app, user_a, "User's blood pressure report says 140/90",
        user_approved=True, reason="user asked to track it",
    )
    assert row.user_approved is True
    assert _search(app, user_a, "blood pressure") != []


def test_extracted_confidence_is_capped(app, user_a):
    row, _ = _create(
        app, user_a, "User probably likes hiking",
        source="extracted", confidence=0.99,
    )
    assert row.confidence <= 0.55, "a guess must never carry confirmed confidence"


def test_expired_memories_leave_retrieval(app, user_a):
    past = datetime.now(timezone.utc) - timedelta(days=1)
    _create(app, user_a, "User's temporary access badge is #4412", expires_at=past)
    results = _search(app, user_a, "access badge")
    assert results == []

    async def _list():
        async with app.state.database.session() as session:
            return await _service(app).list_memories(session, user_a)

    assert all("badge" not in r.content for r in asyncio.run(_list()))


# ---------------------------------------------------------------- extraction

def test_extraction_confirms_only_stated_and_drops_secrets(app, user_a):
    raw = json.dumps(
        {
            "memories": [
                {"content": "User takes black coffee every morning", "kind": "semantic", "confirmed": True},
                {"content": "User probably enjoys trekking on weekends", "kind": "semantic", "confirmed": False},
                {"content": "User's api key is sk-abcdefghijklmnopqrstuvwx", "kind": "semantic", "confirmed": True},
            ]
        }
    )
    class StubCaller:
        async def call(self, db, user_id, *, task_type, system, user, max_tokens=1024):
            return raw, "fake-model"

    caller = StubCaller()
    service = _service(app)

    async def _go():
        async with app.state.database.session() as session:
            return await service.extract(session, user_a, "long chat transcript", caller)

    results = asyncio.run(_go())
    assert len(results) == 2, "the secret must never be stored"
    by_content = {r["content"]: r for r in results}
    assert by_content["User takes black coffee every morning"]["confirmed"] is True
    assert by_content["User probably enjoys trekking on weekends"]["confirmed"] is False

    async def _rows():
        async with app.state.database.session() as session:
            import sqlalchemy as sa

            return (
                await session.execute(
                    sa.select(Memory).where(
                        Memory.user_id == user_a
                    )
                )
            ).scalars().all()

    rows = asyncio.run(_rows())
    for r in rows:
        assert r.source == "extracted"
        assert r.confidence <= 0.55
        assert "api key" not in r.content


# ---------------------------------------------------------------- API

def test_api_requires_auth(app, client):
    assert client.get("/v1/memory").status_code == 401
    assert client.post("/v1/memory", json={"content": "x"}).status_code == 401
    assert client.post("/v1/memory/search", json={"query": "x"}).status_code == 401


def test_api_roundtrip(app, client, user_a):
    response = client.post(
        "/v1/memory", json={"content": "User likes window seats", "kind": "semantic"},
        headers=headers_for(user_a),
    )
    assert response.status_code == 201
    body = response.json()
    memory_id = body["id"]
    assert body["source"] == "user_stated"
    assert body["status"] == "active"

    listed = client.get("/v1/memory", headers=headers_for(user_a))
    assert listed.status_code == 200
    assert any(m["id"] == memory_id for m in listed.json())

    found = client.post(
        "/v1/memory/search", json={"query": "window seats"}, headers=headers_for(user_a)
    )
    assert found.status_code == 200
    assert any("window seats" in r["content"] for r in found.json())

    corrected = client.patch(
        f"/v1/memory/{memory_id}", json={"content": "User likes aisle seats"},
        headers=headers_for(user_a),
    )
    assert corrected.status_code == 200
    assert corrected.json()["content"] == "User likes aisle seats"
    assert corrected.json()["source"] == "corrected"

    deleted = client.delete(
        f"/v1/memory/{corrected.json()['id']}", headers=headers_for(user_a)
    )
    assert deleted.status_code == 200
    gone = client.post(
        "/v1/memory/search", json={"query": "aisle seats"}, headers=headers_for(user_a)
    )
    assert gone.json() == []


def test_api_cross_user_is_404(app, client, user_a, user_b):
    row, _ = _create(app, user_a, "Anil drinks filter coffee")
    assert client.patch(
        f"/v1/memory/{row.id}", json={"content": "x"}, headers=headers_for(user_b)
    ).status_code == 404
    assert client.delete(f"/v1/memory/{row.id}", headers=headers_for(user_b)).status_code == 404


def test_api_secret_rejected_with_422(app, client, user_a):
    response = client.post(
        "/v1/memory", json={"content": "my token is Bearer abcdefghijk123456789"},
        headers=headers_for(user_a),
    )
    assert response.status_code == 422
