"""Post-deploy smoke test against a live deployment.
Usage: MINA_SMOKE_URL=https://api.example.com python scripts/smoke_deploy.py
Optional: MINA_SMOKE_JWT (authed checks), --probe-rate-limit (confirms 429s fire)."""
import os
import sys
import asyncio
import httpx

failures: list[str] = []

def check(name: str, ok: bool, detail: str = "") -> None:
    print(("PASS " if ok else "FAIL ") + name + (f" ({detail})" if detail else ""))
    if not ok:
        failures.append(name)

async def main() -> int:
    base = os.environ.get("MINA_SMOKE_URL", "").rstrip("/")
    if not base:
        print("FAIL: MINA_SMOKE_URL not set"); return 2
    jwt = os.environ.get("MINA_SMOKE_JWT")
    probe_rate_limit = "--probe-rate-limit" in sys.argv
    async with httpx.AsyncClient(timeout=20) as c:
        r = await c.get(f"{base}/v1/health")
        check("health", r.status_code == 200)
        r = await c.get(f"{base}/v1/ready")
        check("ready", r.status_code == 200, r.text[:120])
        r = await c.get(f"{base}/v1/version")
        check("version", r.status_code == 200)
        r = await c.get(f"{base}/v1/me", headers={"Authorization": "Bearer x"*1 if not jwt else f"Bearer {jwt}"})
        if jwt:
            check("authed /v1/me", r.status_code == 200)
        else:
            check("auth required on /v1/me", r.status_code == 401)
        r = await c.get(f"{base}/v1/providers/keys")
        check("auth required on keys", r.status_code == 401)
        if probe_rate_limit:
            codes = [await (c.get(f"{base}/v1/health")).status_code for _ in range(130)]
            check("rate limit 429 fires", 429 in codes)
    print("RESULT:", "PASS" if not failures else f"FAIL {failures}")
    return 0 if not failures else 1

if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
