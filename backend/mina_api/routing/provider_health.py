"""Provider/credential health, cooldown and circuit breaking.

In-memory state per (provider, credential). Rules:
- 401 (auth): the credential is marked unhealthy and cooled down for the
  auth cooldown; the router never retries it blindly, it moves on.
- 403 (forbidden): recorded as an access restriction; no cooldown, but
  the candidate is deprioritized for this plan.
- 429 (rate limited): cooldown for Retry-After (or a small default), so
  the provider is skipped until then.
- 5xx/timeout/network: consecutive failures count; past the threshold the
  circuit opens for open_seconds, then half-opens (one probe allowed).
A success closes the circuit and clears the unhealthy flag.
"""

from __future__ import annotations

import collections
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Deque
from uuid import UUID


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


@dataclass
class HealthState:
    consecutive_failures: int = 0
    recent_failures: Deque[tuple[datetime, str]] = field(default_factory=collections.deque)
    state: str = "closed"  # closed | open | half_open
    cooldown_until: datetime | None = None
    credential_unhealthy: bool = False
    last_restriction: str | None = None

    def describe(self) -> dict[str, object]:
        return {
            "state": self.state,
            "cooldown_until": self.cooldown_until.isoformat() if self.cooldown_until else None,
            "credential_unhealthy": self.credential_unhealthy,
            "consecutive_failures": self.consecutive_failures,
            "last_restriction": self.last_restriction,
        }


class ProviderHealth:
    def __init__(
        self,
        *,
        failure_threshold: int = 5,
        failure_window_seconds: float = 300.0,
        open_seconds: float = 30.0,
        auth_cooldown_seconds: float = 900.0,
        default_rate_cooldown_seconds: float = 60.0,
    ) -> None:
        self.failure_threshold = failure_threshold
        self.failure_window_seconds = failure_window_seconds
        self.open_seconds = open_seconds
        self.auth_cooldown_seconds = auth_cooldown_seconds
        self.default_rate_cooldown_seconds = default_rate_cooldown_seconds
        self._states: dict[tuple[str, UUID], HealthState] = {}

    def _state(self, provider: str, credential_id: UUID) -> HealthState:
        return self._states.setdefault((provider, credential_id), HealthState())

    def is_available(
        self, provider: str, credential_id: UUID, *, now: datetime | None = None
    ) -> tuple[bool, str]:
        now = now or _utcnow()
        state = self._state(provider, credential_id)
        if state.state == "open":
            opened = next((t for t, _ in reversed(state.recent_failures) if t is not None), None)
            if state.cooldown_until and now < state.cooldown_until:
                return False, f"circuit open until {state.cooldown_until.isoformat()}"
            # open period elapsed: allow exactly one probe
            state.state = "half_open"
        if state.cooldown_until and now < state.cooldown_until:
            return False, f"cooling down until {state.cooldown_until.isoformat()}"
        if state.credential_unhealthy:
            return False, "credential marked unhealthy (auth failure); re-test the key to clear"
        return True, "ok"

    def mark_success(self, provider: str, credential_id: UUID) -> None:
        state = self._state(provider, credential_id)
        state.consecutive_failures = 0
        state.recent_failures.clear()
        state.state = "closed"
        state.cooldown_until = None
        state.credential_unhealthy = False

    def mark_failure(
        self,
        provider: str,
        credential_id: UUID,
        kind: str,
        *,
        retry_after_seconds: float | None = None,
        now: datetime | None = None,
    ) -> None:
        now = now or _utcnow()
        state = self._state(provider, credential_id)
        if kind == "auth":
            state.credential_unhealthy = True
            state.cooldown_until = now + timedelta(seconds=self.auth_cooldown_seconds)
            state.last_restriction = "auth failure"
            return
        if kind == "rate_limited":
            wait = retry_after_seconds or self.default_rate_cooldown_seconds
            state.cooldown_until = now + timedelta(seconds=wait)
            state.last_restriction = "rate limited"
            return
        if kind == "forbidden":
            state.last_restriction = "access restricted (403)"
            return
        # server / timeout / network / unknown: count toward the breaker
        state.recent_failures.append((now, kind))
        horizon = now - timedelta(seconds=self.failure_window_seconds)
        while state.recent_failures and state.recent_failures[0][0] < horizon:
            state.recent_failures.popleft()
        state.consecutive_failures += 1
        if state.state == "half_open":
            state.state = "open"
            state.cooldown_until = now + timedelta(seconds=self.open_seconds * 2)
            return
        if len(state.recent_failures) >= self.failure_threshold:
            state.state = "open"
            state.cooldown_until = now + timedelta(seconds=self.open_seconds)

    def reset_credential(self, provider: str, credential_id: UUID) -> None:
        """Called after the user re-tests a key: clears the auth flag."""
        state = self._state(provider, credential_id)
        state.credential_unhealthy = False
        state.cooldown_until = None
        state.state = "closed"
        state.consecutive_failures = 0
        state.recent_failures.clear()

    def snapshot(self) -> dict[str, dict[str, object]]:
        return {
            f"{provider}:{credential_id}": state.describe()
            for (provider, credential_id), state in self._states.items()
        }
