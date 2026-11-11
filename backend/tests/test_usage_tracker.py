"""RPM/TPM windows, cost estimation and the ledger."""
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from mina_api.routing.usage_tracker import RateWindows, UsageTracker

NOW = datetime.now(timezone.utc)


class Spec:
    def __init__(self, input_price, output_price):
        self.input_price = input_price
        self.output_price = output_price


def test_rpm_window_blocks_when_exhausted():
    windows = RateWindows(window_seconds=60)
    cred = uuid4()
    for _ in range(3):
        windows.record_attempt("groq", cred, 10, now=NOW)
    ok, reason = windows.can_attempt("groq", cred, 10, rpm_limit=3, now=NOW + timedelta(seconds=5))
    assert not ok and "rpm" in reason
    ok, _ = windows.can_attempt("groq", cred, 10, now=NOW + timedelta(seconds=61))
    assert ok  # window slid


def test_tpm_window_blocks_on_token_budget():
    windows = RateWindows()
    cred = uuid4()
    windows.record_attempt("gemini", cred, 900, now=NOW)
    ok, reason = windows.can_attempt("gemini", cred, 200, tpm_limit=1000, now=NOW)
    assert not ok and "tpm" in reason


def test_cost_estimation_known_and_unknown():
    tracker = UsageTracker()
    assert tracker.estimate_cost(Spec(0.10, 0.50), 1_000_000, 100_000) == 0.15
    assert tracker.estimate_cost(Spec(None, None), 1000, 100) is None


def test_ledger_records_usage_and_summary():
    tracker = UsageTracker()
    spec = Spec(0.10, 0.50)
    cred = uuid4()
    tracker.record_usage(
        trace_id=uuid4(), request_id="req-1", model_id="m", provider="openai", source="byok",
        credential_id=cred, prompt_tokens=1000, completion_tokens=100,
        cost_usd=tracker.estimate_cost(spec, 1000, 100), latency_ms=120, spec=spec,
    )
    summary = tracker.summary()
    assert summary["requests"] == 1
    assert summary["prompt_tokens"] == 1000
    assert summary["cost_usd_known"] == 0.00015
    assert summary["tokens_by_source"] == {"byok": 1100}
