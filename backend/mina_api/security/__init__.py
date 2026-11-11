"""Security primitives.

Phase 1 keeps only the CORS allowlist policy here (enforced in
create_app and validated in core.config). Authentication, rate limiting
and secrets handling land in later phases inside this package.
"""

__all__ = []
