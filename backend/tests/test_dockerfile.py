"""Static checks on the Docker/Render config (the actual image build runs
in CI where a Docker daemon is available)."""

from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent


def test_dockerfile_installs_locked_deps_and_runs_uvicorn():
    dockerfile = (BACKEND / "Dockerfile").read_text()
    assert "pip install -r requirements.txt" in dockerfile
    assert "uvicorn mina_api.main:app" in dockerfile
    assert "python:3.12" in dockerfile


def test_dockerfile_does_not_ship_dotenv():
    dockerfile = (BACKEND / "Dockerfile").read_text()
    assert ".env" not in dockerfile


def test_render_config_uses_free_tier_and_health_check():
    render = (BACKEND / "render.yaml").read_text()
    assert "plan: free" in render
    assert "healthCheckPath: /v1/health" in render
    assert "rootDir: backend" in render
