# Mina API (Phase 1)

FastAPI foundation for the Mina backend. Python 3.12+, async endpoints,
environment-driven config (`MINA_*`), structured JSON logs, request IDs,
global exception handling, timeouts, CORS allowlist and `/v1` versioning.

## Endpoints

| Method | Path         | Purpose                          |
|--------|--------------|----------------------------------|
| GET    | /v1/health   | Liveness                         |
| GET    | /v1/ready    | Readiness (DB optional)          |
| GET    | /v1/version  | Service, version, environment    |
| GET    | /docs        | Swagger UI (OpenAPI at /v1/openapi.json) |

## Local startup

    cd backend
    python3.12 -m venv .venv
    source .venv/bin/activate
    pip install -r requirements.txt
    uvicorn mina_api.main:app --reload --port 8000

Then: curl http://localhost:8000/v1/health

## Tests

    pip install -r requirements.txt
    pytest

## Docker

    docker build -t mina-api .
    docker run -p 8000:8000 -e MINA_ENVIRONMENT=development mina-api

## Render (free tier)

`render.yaml` defines a free web service rooted at `backend/`, health check
`/v1/health`, one worker. Set `MINA_CORS_ORIGINS` (comma-separated
allowlist) in the dashboard; `MINA_DATABASE_URL` is optional in Phase 1.

## Configuration (all MINA_-prefixed)

| Variable | Default | Notes |
|----------|---------|-------|
| MINA_ENVIRONMENT | development | production validates strictly |
| MINA_DEBUG | false | rejected in production |
| MINA_CORS_ORIGINS | (empty) | required non-empty allowlist in production |
| MINA_REQUEST_TIMEOUT_SECONDS | 30 | per-request cap, returns 503 |
| MINA_LOG_LEVEL | INFO | |
| MINA_LOG_JSON | true | set false for local dev |
| MINA_DATABASE_URL | unset | optional; /v1/ready reports "skipped" |
