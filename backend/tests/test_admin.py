"""Role-based authorization: admin-only endpoints."""

from tests.conftest import headers_for, set_role


def test_non_admin_gets_403(client, user_a):
    assert client.get("/v1/admin/users", headers=headers_for(user_a)).status_code == 403


def test_admin_can_list_users(client, db, user_a, user_b):
    client.get("/v1/me", headers=headers_for(user_a))
    client.get("/v1/me", headers=headers_for(user_b))
    set_role(db, user_a, "admin")
    response = client.get("/v1/admin/users", headers=headers_for(user_a))
    assert response.status_code == 200
    ids = {row["id"] for row in response.json()}
    assert {str(user_a), str(user_b)} <= ids
