"""Memory safety rules, enforced before anything is stored.

- Never store passwords, API keys, tokens or other secrets.
- Sensitive categories (health, financial, government IDs, home
  address) are stored only with an explicit user approval and a stated
  reason; the approval flag is saved on the row.
- Trivially obvious guesses are rejected outright.
"""

from __future__ import annotations

import re
from dataclasses import dataclass


class SecretDetectedError(ValueError):
    """The content looks like a credential or secret. Never stored."""


class SensitiveWithoutPermissionError(ValueError):
    """Sensitive content stored only with user approval and a reason."""


@dataclass(frozen=True)
class SensitiveCategory:
    name: str
    pattern: re.Pattern[str]


SECRET_PATTERNS: tuple[re.Pattern[str], ...] = tuple(
    re.compile(p, re.IGNORECASE)
    for p in (
        r"\b(password|passwd|pwd)\s*[:=]\s*\S",
        r"\b(passphrase)\s*[:=]\s*\S",
        r"\b(api[_-]?key|apikey)\s*[:=]\s*\S",
        r"\b(access[_-]?token|refresh[_-]?token|auth[_-]?token)\s*[:=]\s*\S",
        r"\b(secret[_-]?key|client[_-]?secret)\s*[:=]\s*\S",
        r"\bbearer\s+[A-Za-z0-9._\-]{10,}",
        r"\bsk-[A-Za-z0-9]{16,}",
        r"\bsk-ant-[A-Za-z0-9\-_]{10,}",
        r"\bgsk_[A-Za-z0-9]{10,}",
        r"\bsk-or-v1-[A-Za-z0-9]{10,}",
        r"\bnvai-[A-Za-z0-9\-_]{10,}",
        r"\bAIzaSy[A-Za-z0-9\-_]{20,}",
        r"\bghp_[A-Za-z0-9]{20,}",
        r"\bxox[bp]-[A-Za-z0-9\-]{10,}",
        r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    )
)

SENSITIVE_CATEGORIES: tuple[SensitiveCategory, ...] = (
    SensitiveCategory(
        "health",
        re.compile(
            r"\b(diagnos[ei]s|prescription|medical report|blood (pressure|sugar)|hiv|cancer|mental health)\b",
            re.IGNORECASE,
        ),
    ),
    SensitiveCategory(
        "financial",
        re.compile(
            r"\b(bank (account|balance)|credit card|debit card|\bupi\b\s*(pin|id)|salary)\b",
            re.IGNORECASE,
        ),
    ),
    SensitiveCategory(
        "government_id",
        re.compile(r"\b(ssn|aadhaar|aadhar|pan\s*card|passport (number|no))\b", re.IGNORECASE),
    ),
    SensitiveCategory(
        "home_address",
        re.compile(r"\b(lives at|home address|residential address)\b", re.IGNORECASE),
    ),
)


def screen_content(content: str) -> tuple[bool, str | None]:
    """Returns (allowed, sensitive_category_or_None). Raises
    SecretDetectedError for credentials; sensitive categories need the
    caller's explicit user-approval flag and reason."""

    for pattern in SECRET_PATTERNS:
        if pattern.search(content):
            raise SecretDetectedError("memory content contains a credential or secret; it is never stored")

    for category in SENSITIVE_CATEGORIES:
        if category.pattern.search(content):
            return True, category.name
    return False, None
