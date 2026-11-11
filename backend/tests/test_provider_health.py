"""Breaker, cooldowns and the auth-failure rule."""
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from mina_api.routing.provider_health import ProviderHealth

NOW = datetime.now(timezone.utc)


def fresh_health(**kwargs) -> ProviderHealth:
    defaults = dict(failure_threshold=3, failure_window_seconds=60, open_seconds=30,
                    auth_cooldown_seconds=900, default_rate_cooldown_seconds=60)
    defaults.update(kwargs)
    return ProviderHealth(**defaults)


def test_auth_failure_marks_credential_unhealthy_and_blocks():
    health = fresh_health()
    cred = uuid4()
    health.mark_failure("openai", cred, "auth", now=NOW)
    ok, reason = health.is_available("openai", cred, now=NOW + timedelta(seconds=1))
    assert not ok and "cooling down" in reason  # immediate cooldown too
    # Even after the cooldown lapses, the credential stays unhealthy until re-tested
    ok, reason = health.is_available("openai", cred, now=NOW + timedelta(seconds=901))
    assert not ok and "unhealthy" in reason


def test_success_clears_unhealthy():
    health = fresh_health()
    cred = uuid4()
    health.mark_failure("openai", cred, "auth", now=NOW)
    health.mark_success("openai", cred)
    ok, _ = health.is_available("openai", cred, now=NOW + timedelta(seconds=1))
    assert ok


def test_reset_credential_clears_auth_gate():
    health = fresh_health()
    cred = uuid4()
    health.mark_failure("openai", cred, "auth", now=NOW)
    health.reset_credential("openai", cred)
    ok, _ = health.is_available("openai", cred, now=NOW)
    assert ok


def test_rate_limit_cooldown_honours_retry_after():
    health = fresh_health()
    cred = uuid4()
    health.mark_failure("openai", cred, "rate_limited", retry_after_seconds=120, now=NOW)
    ok, reason = health.is_available("openai", cred, now=NOW + timedelta(seconds=60))
    assert not ok and "cooling down" in reason
    ok, _ = health.is_available("openai", cred, now=NOW + timedelta(seconds=121))
    assert ok


def test_forbidden_records_restriction_without_cooldown():
    health = fresh_health()
    cred = uuid4()
    health.mark_failure("openai", cred, "forbidden", now=NOW)
    ok, _ = health.is_available("openai", cred, now=NOW)
    assert ok
    assert health._state("openai", cred).last_restriction == "access restricted (403)"


def test_circuit_opens_after_threshold_then_half_opens():
    health = fresh_health(failure_threshold=3, open_seconds=30)
    cred = uuid4()
    for i in range(3):
        health.mark_failure("openai", cred, "server", now=NOW + timedelta(seconds=i))
    ok, reason = health.is_available("openai", cred, now=NOW + timedelta(seconds=4))
    assert not ok and "circuit open" in reason
    # open window elapsed: one probe allowed (half_open)
    ok, _ = health.is_available("openai", cred, now=NOW + timedelta(seconds=40))
    assert ok
    # a failure during half-open reopens for longer
    health.mark_failure("openai", cred, "server", now=NOW + timedelta(seconds=41))
    ok, reason = health.is_available("openai", cred, now=NOW + timedelta(seconds=45))
    assert not ok and "circuit open" in reason
