# Backend Test Matrix

Phase 11 testing in `taskbridge-fastapi` is intentionally split by responsibility:

| Area | Primary files | Why |
|------|---------------|-----|
| Models and schema mapping | `test_models.py`, `test_protocol_contracts.py` | Validate wire-level event shape and model contracts. |
| Services (idempotency, ownership, cancellations) | `test_services.py`, `test_interfaces.py` | Ensure policy hooks and lifecycle transitions are deterministic. |
| Event replay semantics | `test_redis_event_store.py`, `test_integration_flow.py` | Keep `eventId` ordering and replay checkpoints stable. |
| HTTP/WS route contracts | `test_http_api.py`, `test_ws_api.py`, `test_fastapi_contract.py` | Verify envelope/status mapping and router dependency requirements. |
| Executor behavior | `test_deterministic_executor.py` | Ensure deterministic progress/failure/cancel behavior. |
| Readiness/ops | `test_readiness.py` | Validate dependency health composition semantics. |

Run full suite from `backend/taskbridge-fastapi`:

```bash
uv run pytest
```
