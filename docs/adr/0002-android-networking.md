# ADR 0002: Android Networking - Retrofit & Kotlin Coroutines Flow

## Status
Accepted

## Context
The `task-bridge` library must provide a clean, modern, and idiomatic API for Android developers. As of 2026, Kotlin Coroutines and Flow are the industry standards for asynchronous and reactive programming in the Android ecosystem.

While Kotlin Multiplatform (KMP) and Ktor are gaining popularity, the current focus is on a high-performance, robust Android-only implementation that integrates seamlessly with existing enterprise apps often built on the Square stack (Retrofit/OkHttp).

## Decision
We will use the following networking stack:
1. **Retrofit + OkHttp**: For REST API calls and underlying transport.
2. **OkHttp WebSockets**: For the primary streaming channel.
3. **Kotlin Coroutines Flow**: As the public-facing API for consuming event streams.

## Rationale
- **Maturity**: Retrofit and OkHttp are battle-tested and handle edge cases (connection pooling, proxy support, certificate pinning) more robustly than newer alternatives in an Android-specific context.
- **Developer Experience**: Kotlin `Flow` provides a native way to handle streams with built-in support for operators like `map`, `filter`, and `retry`, making it easier for developers to integrate `task-bridge` into their ViewModels.
- **Interoperability**: Most Android apps already use OkHttp. By using it as our engine, we can share the same connection pool and configuration (e.g., custom DNS or Interceptors) provided by the host app.
- **Future-proofing**: While we are not using KMP yet, a Flow-based API is easier to migrate to KMP in the future compared to callback-based or RxJava-based APIs.
