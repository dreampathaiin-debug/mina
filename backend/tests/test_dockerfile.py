"""Static checks on the Docker/Render config (the actual image build runs
in CI where a Docker daemon is available)."""

from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent


def test_dockerfile_installs_locked_deps_and_runs_uvicorn():
    dockerfile = (BACKEND / "Dockerfile").read_text()
    assert "COPY requirements.lock ./" in dockerfile
    assert "pip install -r requirements.lock" in dockerfile
    assert "uvicorn mina_api.main:app" in dockerfile
    assert "python:3.12" in dockerfile


def test_dockerfile_does_not_ship_dotenv():
    dockerfile = (BACKEND / "Dockerfile").read_text()
    assert ".env" not in dockerfile


def test_render_config_is_production_ready():
    render = (BACKEND / "render.yaml").read_text()
    # Web service: Docker runtime inside backend/, readiness probe, migrations pre-deploy.
    assert "rootDir: backend" in render
    assert "runtime: docker" in render
    assert "dockerfilePath: ./Dockerfile" in render
    assert "healthCheckPath: /v1/ready" in render
    assert 'preDeployCommand: ["python", "scripts/apply_migrations.py"]' in render
    # Free plan: no card, so plan: free and no paid services in the blueprint.
    assert "plan: free" in render
    assert "type: keyvalue" not in render
    assert "type: worker" not in render
    # Secrets are dashboard-filled, never committed.
    assert render.count("sync: false") >= 9
    # Production guardrails from the security audit stay pinned.
    assert "MINA_ENVIRONMENT" in render and "value: production" in render
    assert "MINA_RATE_LIMIT_REQUESTS_PER_MINUTE" in render
    # Free-plan worker runs in-process; Redis comes from Upstash via env var.
    assert "MINA_WORKER_ENABLED" in render and 'value: "true"' in render
    assert "MINA_REDIS_URL" in render
    assert "MINA_WORKER_TOKEN" in render
    # Image ships migrations + ops scripts for the pre-deploy step.
    dockerfile = (BACKEND / "Dockerfile").read_text()
    assert "COPY migrations/" in dockerfile
    assert "COPY scripts/" in dockerfile
