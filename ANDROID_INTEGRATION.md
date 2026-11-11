# ANDROID_INTEGRATION.md

Server integration is strictly additive: existing on-device BYOK flows are unchanged, and with no base URL configured the app behaves exactly as before.

## What was added

Package `app/src/main/java/com/meenai/mina/server/`:
- `ServerConfig.kt`: build-config-driven API base URL + Supabase URL/anon key; server mode only activates on an HTTPS base URL.
- `MinaAuth.kt`: `MinaTokenProvider` interface plus `SupabaseTokenProvider`: single-flight refresh via POST /auth/v1/token?grant_type=refresh_token, auto-refresh 60s before expiry, session cleared when refresh is rejected.
- `MinaServerClient.kt`: bearer-authenticated requests; on 401 one automatic refresh-and-replay; 429 honours Retry-After up to three attempts then surfaces RateLimited; typed failures Offline (no DNS), Unreachable (timeout), Api(status, detail), ReauthorizationRequired (409).
- `MinaSse.kt`: incremental SSE parser (events emitted as they arrive), exponential backoff reconnect 1s-30s, Last-Event-ID resume so no event is lost across a drop.
- `MinaApi.kt`: typed wrappers for provider keys (add/rotate/test/delete), model preferences + catalog + routing preview, Composio connections and tool execution, memory CRUD + search, usage/budgets/alerts, durable tasks incl. live progress stream, conversations, and cloud agent runs with approvals.

Build configuration (app/build.gradle.kts): MINA_API_BASE_URL, MINA_SUPABASE_URL, MINA_SUPABASE_ANON_KEY, fed by Gradle properties mina.apiBaseUrl, mina.supabaseUrl, mina.supabaseAnonKey. Empty defaults keep current behaviour.

## Coverage of the fifteen integration requirements

All fifteen implemented and mapped in the runbook published this phase: base URL, authenticated requests, token refresh, SSE streaming, reconnect handling, task progress, provider key management, model preferences, Composio authorization (409 reconnect flow typed), memory APIs, usage analytics, offline states, rate-limit responses, provider errors, backward compatibility.

## Honest status

The new Kotlin was hand-reviewed but NOT compiled in the review sandbox (no Android SDK) and NOT run on a device. Required before release: assemble a debug build, then the device matrix: sign-in + token refresh mid-call, SSE drop/reconnect with no lost events, airplane-mode offline behaviour, 429 messaging, one Composio connect-and-execute round trip, two devices on two accounts confirming isolation, and confirmation that with an empty base URL every existing screen still works.
