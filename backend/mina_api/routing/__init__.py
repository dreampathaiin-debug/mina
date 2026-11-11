"""Mina's model routing engine (Phase 4).

Selects the best model per request based on task complexity, provider
health, cost, latency, capabilities and the user's API-key policy. Uses
the LiteLLM Python SDK today and stays compatible with LiteLLM Proxy
later (each registry entry carries a litellm_model string that works for
both; proxy mode only changes where the call is sent).
"""
