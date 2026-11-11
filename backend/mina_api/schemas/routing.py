"""Routing API schemas. Credentials never appear here: previews return
model ids, sources and estimates only."""

from __future__ import annotations

from datetime import datetime
from typing import List, Literal, Optional
from uuid import UUID

from pydantic import BaseModel, Field


class CatalogModelOut(BaseModel):
    model_id: str
    provider: str
    display_name: str
    litellm_model: str
    groups: List[str]
    capabilities: dict
    context_window: int
    max_output_tokens: int
    priority: int
    timeout_seconds: float
    pricing_usd_per_mtok: Optional[dict] = None
    enabled: bool


class CatalogProviderOut(BaseModel):
    provider: str
    rpm_limit: Optional[int] = None
    tpm_limit: Optional[int] = None
    latency_class: str


class CatalogOut(BaseModel):
    models: List[CatalogModelOut]
    providers: List[CatalogProviderOut]


class RoutingPreviewIn(BaseModel):
    task_type: str = Field(examples=["conversation", "coding", "intent_classification"])
    needs_tools: bool = False
    input_tokens: int = Field(default=0, ge=0)
    max_output_tokens: int = Field(default=4096, ge=1)
    allow_context_escalation: bool = True


class CandidateOut(BaseModel):
    model_id: str
    provider: str
    source: str
    reason: str
    priority: int
    context_window: int
    timeout_seconds: float
    estimated_cost_usd: Optional[float] = None


class RejectionOut(BaseModel):
    model_id: str
    provider: str
    reason: str


class RoutingPreviewOut(BaseModel):
    trace_id: UUID
    group: str
    candidates: List[CandidateOut]
    rejected: List[RejectionOut]
    primary_model: Optional[str] = None


class HealthSnapshotOut(BaseModel):
    snapshot: dict


class UsageSummaryOut(BaseModel):
    summary: dict
    generated_at: datetime
