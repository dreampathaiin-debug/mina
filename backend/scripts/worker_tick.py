"""Background worker loop: drives the durable-task engine by POSTing the
worker tick on an interval. Deployed as a Render background worker; reads
MINA_API_URL (the public or internal API base) and MINA_WORKER_TOKEN."""
import asyncio
import os
import httpx

INTERVAL = float(os.environ.get("MINA_WORKER_TICK_SECONDS", "30"))

async def loop() -> None:
    base = os.environ.get("MINA_API_URL", "http://127.0.0.1:8000").rstrip("/")
    token = os.environ.get("MINA_WORKER_TOKEN")
    if not token:
        print("FAIL: MINA_WORKER_TOKEN not set; refusing to run (endpoint now requires it)")
        return
    async with httpx.AsyncClient(timeout=55) as client:
        while True:
            try:
                r = await client.post(
                    f"{base}/v1/tasks/worker/tick",
                    headers={"X-Worker-Token": token},
                )
                print("tick", r.status_code)
            except Exception as e:  # transient network/API outage: keep looping
                print("tick error", type(e).__name__)
            await asyncio.sleep(INTERVAL)

if __name__ == "__main__":
    asyncio.run(loop())
