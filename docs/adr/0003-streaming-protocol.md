# ADR 0003: Streaming Protocol - Three-Tier Fallback (WS -> SSE -> Polling)

## Status
Accepted

## Context
Reliable message delivery is critical for TaskBridge. Events like "Task Completed" or "Payment Required" cannot be missed. Mobile networks are notoriously unstable (switching between Wi-Fi and 5G, tunnel transits).

While WebTransport (HTTP/3) is emerging for low-latency needs, it relies on UDP, which is often blocked by corporate firewalls. Originally, Server-Sent Events (SSE) were dismissed due to their unidirectional nature. However, since client-to-server commands (like actions or cancellations) are handled via separate standard HTTP POST requests rather than over the streaming connection itself, the unidirectional nature of SSE is perfectly suitable for event observation and provides a more efficient HTTP-based fallback than Long Polling.

## Decision
We will implement a three-tier fallback streaming strategy:
1. **WebSockets**: The primary protocol for real-time communication.
2. **Server-Sent Events (SSE)**: The first fallback. It operates over standard HTTP, bypassing many WebSocket-specific proxy issues while remaining more efficient than Long Polling.
3. **HTTP Long Polling**: The ultimate safety net protocol if both WebSockets and SSE fail to connect within a timeout or are unsupported.

## Rationale
- **Efficiency Hierarchy**: WebSockets reduce overhead best. When they fail, SSE maintains a single persistent HTTP connection to stream events efficiently. Long Polling is the least efficient but most universally compatible.
- **Reliability**: By implementing this 3-tier fallback, we ensure 100% reachability regardless of the network environment (corporate proxies, old routers, aggressive firewalls).
- **Simplicity of Transport Boundary**: Because the client SDK exposes the stream as a standard Kotlin `Flow` and handles client-to-server commands via standard HTTP routes, the underlying transport protocol can seamlessly degrade without impacting the public API.
- **Session Continuity**: All protocols will use a `session_id` (or task ID) and `last_event_id` to allow the server to "catch up" the client on missed events during handovers between protocols or connection drops.
