# Architectural Decision Records

TaskBridge keeps formal design decisions in `docs/adr/`.

## ADR summary

### ADR 0001: Backend stack

- FastAPI-based reusable backend package
- explicit host-owned integrations for auth, storage, and execution

### ADR 0002: Android networking

- Kotlin-first Android SDK
- explicit transport and serialization boundaries

### ADR 0003: Streaming protocol

- stable event envelope
- replay-safe semantics based on monotonic `eventId`

### ADR 0004: Backend library shape

- reusable package shape over app-template coupling
- clear separation between core backend and adapters

### ADR 0005: Android toolchain and build quality

- Gradle conventions for quality and publishing
- Dokka as the generated Android documentation source of truth

For full source documents, see the repository files under `docs/adr/`.
