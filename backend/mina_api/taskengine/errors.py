"""Signals handlers raise to steer the worker."""

from __future__ import annotations


class TaskSignal(Exception):
    """Base for cooperative worker signals."""


class TaskCancelled(TaskSignal):
    """The user asked to cancel; the worker stops at the next checkpoint."""


class TaskPaused(TaskSignal):
    """The user asked to pause; the worker stops at the next checkpoint."""


class TaskApprovalRequested(TaskSignal):
    """The handler needs user approval before continuing."""


class TransientTaskError(Exception):
    """Retryable failure (rate limits, network blips)."""



class PermanentTaskError(Exception):
    """Non-retryable failure."""


class SimulatedWorkerCrash(BaseException):
    """NOT an Exception: simulates a process death mid-run in tests.
    It bypasses every failure handler, exactly like a real crash."""
