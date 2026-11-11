"""Response models for the ops endpoints."""

from pydantic import BaseModel


class HealthResponse(BaseModel):
    status: str


class VersionResponse(BaseModel):
    service: str
    version: str
    environment: str
