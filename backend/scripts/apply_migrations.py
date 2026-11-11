"""Apply backend/migrations/*.sql in order, once each, against MINA_SUPABASE_DB_URL.
Run by Render's preDeployCommand (or manually: python scripts/apply_migrations.py).
Never drops or rewrites data: only tracks and applies new migration files."""
import os
import sys
import glob
import asyncio
import asyncpg

TRACKING_TABLE = "_mina_migrations"

async def main() -> int:
    url = os.environ.get("MINA_SUPABASE_DB_URL") or os.environ.get("MINA_DATABASE_URL")
    if not url:
        print("FAIL: MINA_SUPABASE_DB_URL not set"); return 2
    files = sorted(glob.glob(os.path.join(os.path.dirname(__file__), "..", "migrations", "*.sql")))
    if not files:
        print("FAIL: no migration files found"); return 2
    ssl = "require" if "supabase" in url else None
    conn = await asyncpg.connect(url, ssl=ssl)
    try:
        await conn.execute(
            f"CREATE TABLE IF NOT EXISTS {TRACKING_TABLE} (name text primary key, applied_at timestamptz default now())"
        )
        applied = {r[0] for r in await conn.fetch(f"SELECT name FROM {TRACKING_TABLE}")}
        for path in files:
            name = os.path.basename(path)
            if name in applied:
                print(f"SKIP {name} (already applied)"); continue
            sql = open(path, encoding="utf-8").read()
            async with conn.transaction():
                await conn.execute(sql)
                await conn.execute(f"INSERT INTO {TRACKING_TABLE} (name) VALUES ($1)", name)
            print(f"APPLIED {name}")
    finally:
        await conn.close()
    print("OK migrations up to date")
    return 0

if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
