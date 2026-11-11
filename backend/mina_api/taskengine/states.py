"""Task states for Mina's durable background task engine."""

from __future__ import annotations

from enum import StrEnum


class TaskState(StrEnum):
    CREATED = "CREATED"
    QUEUED = "QUEUED"
    PLANNING = "PLANNING"
    RUNNING = "RUNNING"
    WAITING_FOR_APPROVAL = "WAITING_FOR_APPROVAL"
    PAUSED = "PAUSED"
    RETRYING = "RETRYING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


TERMINAL_STATES = {
    TaskState.COMPLETED,
    TaskState.FAILED,
    TaskState.CANCELLED,
}

# States a worker can claim work from.
CLAIMABLE_STATES = {TaskState.QUEUED, TaskState.RETRYING}

# States a lease-bearing worker owns.
ACTIVE_STATES = {
    TaskState.PLANNING,
    TaskState.RUNNING,
}
