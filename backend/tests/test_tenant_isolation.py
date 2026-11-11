"""Two real identities: User A can never read, modify or run User B's rows."""

from tests.conftest import headers_for


def _create_conversation(client, user, title):
    response = client.post("/v1/conversations", json={"title": title}, headers=headers_for(user))
    assert response.status_code == 201
    return response.json()


def _create_provider_key(client, user, name="sk-openai-secret-key-9876"):
    response = client.post(
        "/v1/provider-keys",
        json={"provider": "openai", "label": "main", "key": name},
        headers=headers_for(user),
    )
    assert response.status_code == 201
    return response.json()


def _create_task(client, user, title="summarize inbox"):
    response = client.post(
        "/v1/tasks", json={"title": title, "payload": {}}, headers=headers_for(user)
    )
    assert response.status_code == 201
    return response.json()


def test_user_b_cannot_read_user_a_conversations(client, user_a, user_b):
    conv = _create_conversation(client, user_a, "A's private notes")
    assert client.get("/v1/conversations", headers=headers_for(user_b)).json() == []
    assert client.get(f"/v1/conversations/{conv['id']}", headers=headers_for(user_b)).status_code == 404


def test_user_b_cannot_touch_user_a_conversation_messages(client, user_a, user_b):
    conv = _create_conversation(client, user_a, "A's notes")
    message = client.post(
        f"/v1/conversations/{conv['id']}/messages",
        json={"role": "user", "content": "private"},
        headers=headers_for(user_a),
    )
    assert message.status_code == 201
    assert client.get(f"/v1/conversations/{conv['id']}/messages", headers=headers_for(user_b)).status_code == 404
    assert client.post(
        f"/v1/conversations/{conv['id']}/messages",
        json={"role": "user", "content": "injected by B"},
        headers=headers_for(user_b),
    ).status_code == 404


def test_user_b_cannot_delete_or_modify_user_a_provider_keys(client, user_a, user_b):
    key = _create_provider_key(client, user_a)
    assert client.get("/v1/provider-keys", headers=headers_for(user_b)).json() == []
    assert client.delete(f"/v1/provider-keys/{key['id']}", headers=headers_for(user_b)).status_code == 404
    # A's key still there after B's failed attempt.
    assert client.get("/v1/provider-keys", headers=headers_for(user_a)).json()[0]["id"] == key["id"]


def test_user_b_cannot_execute_user_a_tasks(client, user_a, user_b):
    task = _create_task(client, user_a)
    assert client.post(f"/v1/tasks/{task['id']}/execute", headers=headers_for(user_b)).status_code == 404


def test_user_id_in_body_is_never_trusted(client, user_a, user_b):
    response = client.post(
        "/v1/conversations",
        json={"title": "spoof attempt", "user_id": str(user_b)},
        headers=headers_for(user_a),
    )
    assert response.status_code == 201
    assert client.get("/v1/conversations", headers=headers_for(user_b)).json() == []
    titles = [c["title"] for c in client.get("/v1/conversations", headers=headers_for(user_a)).json()]
    assert titles == ["spoof attempt"]


def test_provider_keys_are_masked_and_isolated(client, user_a, user_b):
    key = _create_provider_key(client, user_a)
    body = client.get("/v1/provider-keys", headers=headers_for(user_a)).json()[0]
    assert "sk-openai-secret-key-9876" not in body["key_preview"]
    assert body["key_preview"].endswith("9876")
    assert key["id"] == body["id"]
