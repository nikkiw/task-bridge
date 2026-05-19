# Getting Started

This guide covers how to add TaskBridge to your existing project and how to set up the repository for local development.

## 1. Installation (Production)

To use TaskBridge in your applications, install the published packages via your language's standard package manager. Releases are triggered automatically via GitHub tags (e.g., `android-v1.0.0` or `python-v0.1.0`).

### Backend (Python / FastAPI)

TaskBridge is available on PyPI. Using `uv` or `pip`, you can install the core FastAPI package:

```bash
uv add taskbridge-fastapi
```
*(Package managers automatically resolve the latest stable version from PyPI).*

If you are using Temporal as an execution engine, install the official adapter:
```bash
uv add taskbridge-temporal-adapter
```

### Android (Kotlin)

TaskBridge artifacts are published to Maven Central under the `io.github.nikkiw.taskbridge` group.

Add the core library and the OkHttp transport adapter to your `app/build.gradle.kts`:

```kotlin
dependencies {
    // Replace <latest_version> with the latest android-v* tag from GitHub Releases
    val taskbridgeVersion = "<latest_version>" 
    
    implementation("io.github.nikkiw.taskbridge:taskbridge-core:$taskbridgeVersion")
    implementation("io.github.nikkiw.taskbridge:taskbridge-transport-okhttp:$taskbridgeVersion")
}
```

---

## 2. Building from Source (Local Development)

If you want to contribute to TaskBridge, run the examples, or build custom adapters, you should clone the repository and build from source.

### Prerequisites

- Python 3.11+
- `uv` (for Python dependency management)
- JDK 17 (for Android SDK and Dokka generation)
- Android SDK (for building the Android modules)

### Repository Setup

Clone the repository:

```bash
git clone https://github.com/nikkiw/task-bridge.git
cd task-bridge
```

#### Running Backend Checks
Validate the Python source code:
```bash
cd backend/taskbridge-fastapi
uv sync --group dev
uv run ruff check
uv run ruff format --check
uv run pytest
```

#### Running Android Checks
Validate the Android SDK:
```bash
cd android
./gradlew :taskbridge-core:testDebugUnitTest
./gradlew :taskbridge-transport-okhttp:testDebugUnitTest
```

#### Running Examples
TaskBridge includes a minimal standalone example to verify your setup without extra infrastructure:
```bash
cd examples/01-minimal-greeter
uv run --no-project app.py
```

### Local Documentation Workflow

To preview this documentation site locally:

```bash
# From the repository root
uv sync --group docs --group dev
uv run python scripts/docs_prepare.py
uv run mkdocs serve
```
For more details on docs generation, see the [Documentation Guide](documentation/index.md).
