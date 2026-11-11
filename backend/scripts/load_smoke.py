"""Lightweight in-process concurrency smoke (NOT a production load test).
Fires concurrent requests at the ASGI app (SQLite in-memory) and reports
latency percentiles and error count. Run: .venv/bin/python scripts/load_smoke.py"""
import asyncio
import statistics
import time

import httpx
from cryptography.fernet import Fernet

CONCURRENCY = 40
ROUNDS = 5  # 200 requests total across rounds


async def main() -> None:
    from mina_api.core.config import Settings
    from mina_api.main import create_app

    settings = Settings(
        log_json=False,
        cors_origins=["https://app.mina.example"],
        supabase_url="https://smoke.supabase.co",
        supabase_jwt_secret="s" * 40,
        encryption_key=Fernet.generate_key().decode(),
        supabase_audience="authenticated",
        database_url="sqlite+aiosqlite://",
    )
    app = create_app(settings)
    latencies: list[float] = []
    errors = 0
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://smoke"
    ) as client:
        for r in range(ROUNDS):
            async def one() -> None:
                nonlocal errors
                start = time.perf_counter()
                try:
                    resp = await client.get("/v1/health")
                    if resp.status_code != 200:
                        errors += 1
                except Exception:
                    errors += 1
                latencies.append(time.perf_counter() - start)

            await asyncio.gather(*(one() for _ in range(CONCURRENCY)))
    latencies.sort()
    p50 = latencies[len(latencies) // 2] * 1000
    p99 = latencies[int(len(latencies) * 0.99)] * 1000
    print(f"requests={len(latencies)} concurrency={CONCURRENCY} errors={errors}")
    print(f"p50={p50:.1f}ms p99={p99:.1f}ms max={latencies[-1]*1000:.1f}ms")
    assert errors == 0, "concurrent requests produced errors"

if __name__ == "__main__":
    asyncio.run(main())
