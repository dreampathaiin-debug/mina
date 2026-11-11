# SECURITY_REPORT.md

Independent security review of the Mina backend for its first public production release. Completed 11 November 2026. No compliance certification is claimed or implied. No production secrets were touched; all tests run against throwaway keys and a local database.

## Verdict

All twenty audited areas pass or are fixed and tested. Three real vulnerabilities were found during the audit phase and fixed with regression tests; the full backend suite (288 tests) is green and the dependency scan reports no known vulnerabilities. The remaining pre-release security work is executional: run the migrations on a real Supabase project and verify RLS as a non-superuser role.

## Findings and disposition

| ID | Severity | Finding | Disposition |
|---|---|---|---|
| S-1 | High | Worker tick endpoint ran unauthenticated when MINA_WORKER_TOKEN was unset | Fixed fail-closed (403); test rewritten to prove the closed posture |
| S-2 | High | No HTTP rate limiting anywhere | Fixed: sliding 60s window keyed by client IP + credential hash, 120 rpm default, 429 + Retry-After |
| S-3 | Medium | Approval replay: one approved dangerous action reusable forever | Fixed: single-use approvals consumed on success |
| S-4 | Medium | Two optimizer tests patched a stale executor attribute (test-only) | Fixed |
| S-5 | Low | Client IP trusts the first X-Forwarded-For hop | Documented: keep behind a trusted proxy (Render plan does) |
| S-6 | Low | No in-app request body size cap | Documented: reverse proxy caps it |
| S-7 | Low | Test-only JWT secret under 32 bytes | Documented; production secrets come from Supabase |
| R-1 | Config | Production render.yaml broke the blueprint contract test during the final integration phase | Caught by this review's full-suite run; test updated to assert the production contract (docker runtime, /v1/ready, pre-deploy migrations, Redis + worker, secrets dashboard-filled) |

## Attack-scenario matrix (all verified by automated tests)

Expired/forged/wrong-audience/wrong-issuer/subject-less JWTs -> 401. Cross-user access on connections, tools, memories, tasks, executions, keys -> 404. Hostile request IDs sanitized. Request flood -> 429 with health spared. Approved dangerous action cannot be replayed. Provider error echo with Authorization header leaks nothing to client, database, or logs. Secret-shaped strings never enter stored memory. Tool executions store argument keys and fingerprints, never values.

## Remaining accepted risks

Prompt injection is inherent to any agent: a crafted email or page can steer what the model tries, but blast radius is contained by schema validation, ownership checks, single-use approvals for dangerous slugs, mandatory confirmations for destructive/sensitive steps, and secret scrubbing. Residual exposure is a wrong tool call with safe arguments. The rate limiter is per-process: a second replica needs a shared store. No webhooks exist; HMAC verification is mandatory the day one is added.

## Deployment security preconditions

MINA_SUPABASE_URL, MINA_SUPABASE_JWT_SECRET, non-wildcard MINA_CORS_ORIGINS, MINA_ENCRYPTION_KEYS_JSON (else key endpoints 503), MINA_WORKER_TOKEN (now required), optional MINA_COMPOSIO_API_KEY (503 by design while unset). Runtime DB role must not hold BYPASSRLS. Deploy behind a trusted proxy.
