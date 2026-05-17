# ADR 0005: Android Toolchain - Gradle Build Logic, Code Quality, and Docs

## Status

Proposed

## Context

The monorepo includes three Android modules:

- publishable `taskbridge-core` (SDK without transport implementation);
- publishable `taskbridge-transport-okhttp` (OkHttp transport adapter);
- `sample` app for downstream integration verification.

Without a shared build policy, `build.gradle.kts` files quickly drift on `compileSdk`, JDK/Kotlin targets, lint policies, and quality plugins. This also increases onboarding cost for contributors and automation.

Required baseline:

- consistent Kotlin formatting (plus optional Gradle Kotlin DSL/Markdown formatting);
- static Kotlin analysis (coroutines, complexity, bug-prone patterns);
- Android Lint as a mandatory platform-level check;
- generated public API docs from KDoc (Dokka artifacts for CI);
- machine-readable reports (for example SARIF) for CI and code scanning.

Network behavior and stream API are defined in [ADR 0002: Android Networking](0002-android-networking.md). This ADR is limited to build and quality tooling under `android/`.

## External Alignment

Google publicly promotes CLI-first workflows for agentic Android development (Android CLI, Android skills, Android Knowledge Base). For this repository, that does not replace Gradle.

Practical direction:

- keep build and quality checks in Gradle tasks;
- optionally document Android CLI for environment/bootstrap convenience;
- keep repository behavior deterministic through explicit Gradle commands.

## Option Analysis

### Build Logic Centralization

| Approach | Pros | Cons |
|---|---|---|
| Copy-paste per module | Easy to start | Version drift and high maintenance |
| `buildSrc` | Single place for scripts | Frequent cache invalidation, weaker scalability |
| `build-logic` included build + convention plugins | Precompiled plugins, predictable cache, clear plugin IDs | Slightly higher initial setup cost |

### Formatting vs Deep Static Analysis

| Tool | Role |
|---|---|
| `ktlint` | Kotlin formatting and style |
| `detekt` | AST-level quality analysis |
| `spotless` | Unified Gradle formatting entrypoint |

Recommended combination: `spotless` + `ktlint` for formatting, `detekt` for code-quality rules and reporting.

### API Docs

`Dokka` remains the standard choice for generating Kotlin/Android API docs from KDoc.

### Sample UI

`Jetpack Compose + Material 3` is the default for the sample app due to lower boilerplate and easier maintenance.

## Decision

1. Android root project uses:
   - `settings.gradle.kts`
   - `gradle/libs.versions.toml` (single version catalog)
   - `gradle.properties`
   - Gradle Wrapper

2. Use `android/build-logic` as an included build with convention plugins (no `buildSrc`).

3. Convention plugin responsibilities:
   - `taskbridge.android.library`: shared Android Library configuration (`compileSdk`, `minSdk`, Kotlin/JVM 17, lint defaults).
   - `taskbridge.android.application`: same baseline for sample app.
   - `taskbridge.quality`: `spotless` + `detekt` integration with repository config.

4. Formatting policy: `spotless` + `ktlint` for Kotlin; `.editorconfig` as shared style anchor.

5. Static analysis policy: `detekt` with repository config and CI-friendly report output; baseline is optional if justified.

6. Android Lint is enabled through convention plugins and treated as a required quality layer.

7. Dokka tasks are required for `taskbridge-core` and `taskbridge-transport-okhttp`.

8. The sample app depends only on public SDK APIs and does not embed product-domain logic.

9. Agent/CI command contract is documented in `android/README.md`:
   - `./gradlew check`
   - `./gradlew spotlessCheck`
   - `./gradlew detekt`
   - module lint/test tasks
   - Dokka tasks where needed

## Deferred Alternatives

- `detekt` only (without dedicated formatter): weaker style consistency.
- `ktlint` only (without AST analyzer): weaker correctness/complexity coverage.
- Kotlin Multiplatform for SDK v1: out of current scope.
- Dependency analysis plugin adoption can be revisited later without conflicting with this ADR.
- `ktfmt` may be reconsidered later; current default remains `ktlint` + `spotless`.

## Consequences

- Initial setup PR is larger due to `build-logic`, wrapper, and quality config.
- Contributors are expected to run formatting before commit (for example `spotlessApply`).
- AGP/Kotlin/Detekt/Spotless versions are managed centrally via `libs.versions.toml`.

## Related Documents

- [ADR 0002: Android Networking](0002-android-networking.md)
- [Android Overview](../android/index.md)
