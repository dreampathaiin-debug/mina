"""Account deletion: data purge, access revocation, isolation of others."""

from tests.conftest import count_rows, headers_for
from tests.test_tenant_isolation import _create_conversation, _create_provider_key, _create_task

from mina_api.database.models import Conversation, ProviderKey, Task


def test_deleted_user_loses_access(client, db, user_a, user_b):
    conv = _create_conversation(client, user_a, "will be deleted")
    key = _create_provider_key(client, user_a)
    task = _create_task(client, user_a)
    response = client.delete("/v1/account", headers=headers_for(user_a))
    assert response.status_code == 204

    assert client.get("/v1/me", headers=headers_for(user_a)).status_code == 403
    assert client.get("/v1/conversations", headers=headers_for(user_a)).status_code == 403
    assert count_rows(db, Conversation, user_a) == 0
    assert count_rows(db, ProviderKey, user_a) == 0
    assert count_rows(db, Task, user_a) == 0

    # User B keeps their own data and access.
    b_conv = _create_conversation(client, user_b, "B still here")
    assert client.get(f"/v1/conversations/{b_conv['id']}", headers=headers_for(user_b)).status_code == 200


def test_purged_data_is_gone_even_for_new_tokens(client, db, user_a):
    _create_conversation(client, user_a, "gone soon")
    client.delete("/v1/account", headers=headers_for(user_a))
    assert client.get("/v1/conversations", headers=headers_for(user_a)).status_code == 403
    assert count_rows(db, Conversation, user_a) == 0
