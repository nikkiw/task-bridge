# ADR 0001: Backend Stack - FastAPI, Temporal & Redis Streams

## Status
Accepted

## Context
The system needs to handle long-running ML tasks (parsing, scanning, generation) that require high reliability and stateful execution. Temporal is already the standard in our infrastructure for durable execution. However, mobile clients need real-time progress updates (e.g., "15% done", "Scanning item X"). 

Temporal is not optimized for streaming high-frequency UI events because sending every progress update as a Signal or Activity would bloat the Workflow History, leading to performance degradation.

## Decision
We will use a multi-layered backend stack:
1. **FastAPI**: Serves as the primary entry point for mobile clients (REST/WebSockets).
2. **Temporal**: Handles the core business logic, task orchestration, and long-term state.
3. **Redis Streams**: Acts as an ephemeral "UI Event Bus" between Temporal Workers and FastAPI.

## Rationale
- **Temporal** ensures that tasks are never lost and can be retried across worker failures.
- **Redis Streams** provides a low-latency, high-throughput log for UI progress updates. It decouples the "heavy" workflow logic from the "light" real-time streaming layer.
- **Replayability**: Redis Streams allows clients to request missed events (using `XREAD` with a specific ID) if the WebSocket connection drops briefly.
- **Scalability**: FastAPI can scale horizontally to handle thousands of concurrent WebSockets, each subscribing to specific Redis Stream keys without putting load on the Temporal server.
- **Auto-cleanup**: Stream entries will have a TTL or a maximum length (`MAXLEN`) to ensure memory efficiency.
