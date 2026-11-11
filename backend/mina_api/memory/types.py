"""Memory types for Mina's long-term memory system.

Four kinds, mirroring cognitive memory: working (the current task
state), episodic (important past interactions), semantic (facts and
preferences), procedural (approved repeatable workflows). Every memory
carries a source, a confidence, a status, and timestamps, and every
query is tenant-scoped by construction.
"""

from __future__ import annotations

from enum import StrEnum


class MemoryKind(StrEnum):
    WORKING = "working"
    EPISODIC = "episodic"
    SEMANTIC = "semantic"
    PROCEDURAL = "procedural"
    # Legacy rows written before Phase 8 used kind="fact"; they read as
    # semantic memory.
    FACT = "fact"


class MemorySource(StrEnum):
    USER_STATED = "user_stated"
    EXTRACTED = "extracted"
    CORRECTED = "corrected"
    SYSTEM = "system"


class MemoryStatus(StrEnum):
    ACTIVE = "active"
    SUPERSEDED = "superseded"
    EXPIRED = "expired"
    DELETED = "deleted"


# Source confidence bounds. Extracted memories can never claim the
# confidence of something the user said: guesses never harden into
# confirmed facts just by being stored.
CONFIDENCE_FLOOR = 0.05
CONFIDENCE_CEILING = 1.0
USER_STATED_CONFIDENCE = 0.95
EXTRACTED_CONFIDENCE_CAP = 0.55
CORRECTED_CONFIDENCE = 1.0

# Deduplication: a new memory this similar to an existing active one of
# the same kind updates the existing row instead of adding a duplicate.
DEDUP_SIMILARITY = 0.90
