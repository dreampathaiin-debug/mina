# Phase 9: Durable background tasks

Mina's background task engine runs long and scheduled jobs without an
active Android chat session. Tasks live in PostgreSQL
(`durable_tasks` + `task_events`); Redis is optional (unused today,
reserved for cross-worker coordination if a second worker is added).

## What is durable

- Task state, attempts, checkpoints and progress events are rows in
  Postgres. A worker process can die at any point; nothing is lost.
- Handlers checkpoint after every completed step
  (`ctx.step(name, data)`), so a crash resumes from the last commit
  instead of repeating non-idempotent external actions.
- Claiming is atomic: a single conditional UPDATE per task, so two
  workers racing on the same task cannot both win. (Postgres should
  use `FOR UPDATE SKIP LOCKED` at scale; the current single-worker
  loop does not need it.)
- Workers hold a lease (default 60 s) they renew as they work. A
  worker that stops renewing is treated as dead: its tasks are
  requeued (attempts are NOT burned by a crash, only by failures).
- Retries use exponential backoff with jitter (base 5 s, doubling,
  capped at 1 h). After `max_attempts` failures the task dead-letters:
  status FAILED plus a `task_dead_letter` event.
- Per-user concurrency: a user never has more than
  `MINA_TASKS_PER_USER_CONCURRENCY` tasks running at once.

## API

- `POST /v1/tasks` — create (`kind`, `title`, `payload`,
  `idempotency_key`, `scheduled_at`, `requires_approval`,
  `max_attempts`). A repeat submission with the same
  `idempotency_key` returns the existing task and creates nothing.
- `GET /v1/tasks`, `GET /v1/tasks/{id}` — list/detail.
- `POST /v1/tasks/{id}/cancel|pause|resume|approve` — control.
  Cancel/pause on a RUNNING task are cooperative: they take effect at
  the handler's next checkpoint. Approval releases
  `WAITING_FOR_APPROVAL` back to the queue.
- `GET /v1/tasks/{id}/events?after_seq=` — progress events.
- `GET /v1/tasks/{id}/events/stream` — SSE. Every event carries an id
  (its seq); clients reconnect with `Last-Event-ID` (or `?after_seq=`)
  and resume exactly where they left off.
- `POST /v1/tasks/{id}/execute` — legacy inline path (owner only).

## Running the worker

- `MINA_WORKER_ENABLED=true`: an in-process loop starts with the app
  (one asyncio task, one short DB session per tick).
- Any hosting platform: `POST /v1/tasks/worker/tick?ticks=N` runs N
  polling cycles. Guard it with `MINA_WORKER_TOKEN` (sent as
  `X-Worker-Token`). Point an external cron at it.

## Free-hosting limitations (read this before promising anything)

- Free tiers (Render free, similar) put idle apps to sleep and allow
  no background processes: an in-process worker WILL stop when the
  instance sleeps, and tasks wait until the next wake-up. This is a
  property of the platform, not of the engine.
- Scheduled tasks therefore run "when the worker next runs", not at a
  guaranteed wall-clock time. Never promise continuous or on-time
  execution on a sleeping host.
- The honest setup on free hosting: an external cron (cron-job.org,
  UptimeRobot, GitHub Actions) pings `/v1/tasks/worker/tick` every
  few minutes, which both keeps the instance awake where the tier
  allows it and drives the queue. Latency is then bounded by the
  cron interval, not zero.
- Guaranteed timely execution needs an always-on host (Render paid
  instance, Fly.io, a VPS) with `MINA_WORKER_ENABLED=true`.
