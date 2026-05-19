# Publication Guide

This guide explains how to publish new versions of TaskBridge libraries for Android (Maven Central) and Python (PyPI).

## Versioning Policy

We use [Semantic Versioning](https://semver.org/). 
- Android versions (all modules): `android-vX.Y.Z`
- Python core (`taskbridge-fastapi`): `python-vX.Y.Z`
- Python Temporal adapter: `python-temporal-vX.Y.Z`

Release history is tracked at the package level:

- `android/CHANGELOG.md`
- `backend/taskbridge-fastapi/CHANGELOG.md`
- `backend/adapters/temporal/CHANGELOG.md`

The root `CHANGELOG.md` is only an index.

## Release Contract

Release-bearing pull requests must use Conventional Commits and squash merge so the final commit on `main` is machine-readable.

Accepted title shapes:

- `feat(android): ...`
- `fix(backend): ...`
- `docs(android): ...`
- `feat(temporal)!: ...`

Breaking changes are recognized only by:

- `!` after the type or scope
- `BREAKING CHANGE:` footer

Non-conventional merge messages should not reach `main`; `release-contract.yml` validates that contract.

## Automatic Publication (Recommended)

Publication remains tag-driven, but maintainers first prepare the package changelog through GitHub Actions.

### Step 1: Prepare the release PR

Run the `prepare-release` workflow with:

- `component`: `android`, `backend-fastapi`, or `temporal-adapter`
- `version`: `X.Y.Z`

The workflow:

- fetches the full git history and tags
- generates package-scoped notes with `git-cliff`
- updates only the target package `CHANGELOG.md`
- leaves publication untouched
- opens a PR with title `chore(release): prepare <component> vX.Y.Z`

Merge that PR with squash merge.

### Step 2: Push the release tag

### For Android (Maven Central)

1. Ensure the release prep PR is merged and all checks pass.
2. Create and push the tag:
   ```bash
   git tag android-v0.1.0
   git push origin android-v0.1.0
   ```
3. The `publish-release` workflow will:
   - verify that `android/CHANGELOG.md` already contains the `0.1.0` section
   - publish both Android modules (`taskbridge-core`, `taskbridge-transport-okhttp`)
   - transfer the deployment to the Central Portal for automatic release
   - create or update the matching GitHub Release from that changelog section

### For Python (PyPI)

#### Core Library
1. Create and push the tag:
   ```bash
   git tag python-v0.1.0
   git push origin python-v0.1.0
   ```
2. `publish-release` verifies `backend/taskbridge-fastapi/CHANGELOG.md`, publishes to PyPI, and creates or updates the GitHub Release from the same section.

#### Temporal Adapter
1. Create and push the tag:
   ```bash
   git tag python-temporal-v0.1.0
   git push origin python-temporal-v0.1.0
   ```
2. `publish-release` verifies `backend/adapters/temporal/CHANGELOG.md`, publishes to PyPI, and creates or updates the GitHub Release from the same section.

If the relevant package changelog section is missing, publication fails before artifact upload starts.

## Local Publication (Dry Run)

### Android

To test the publication logic locally (without uploading to remote repositories), you can publish to your local Maven repository:

```bash
cd android
./gradlew \
  :taskbridge-core:publishReleasePublicationToMavenLocal \
  :taskbridge-transport-okhttp:publishReleasePublicationToMavenLocal \
  -PVERSION_NAME=0.1.0-LOCAL
```

This smoke run should produce, for each Android module:
- `.aar`
- `-sources.jar`
- `-javadoc.jar`
- `.pom`
- `.module`

### Python

To build the artifacts locally and verify their content:

```bash
cd backend/taskbridge-fastapi
uv build
```

The artifacts will be generated in the `dist/` directory.

## GitHub Secrets Configuration

The following secrets must be configured in the GitHub repository:

### Android
- `OSSRH_USERNAME`: Your Sonatype **Central Portal token username**.
- `OSSRH_PASSWORD`: Your Sonatype **Central Portal token password**.
- `SIGNING_KEY`: Your GPG private key (exported as an ASCII-armored string).
- `SIGNING_PASSWORD`: The passphrase for your GPG private key.

Even though the secret names still say `OSSRH`, the values must be the credentials from the Portal token you generated in Sonatype Central. Legacy OSSRH credentials will return `401`.

### Python
- `PYPI_API_TOKEN`: Your PyPI API token.

## Notes on the Android Release Flow

TaskBridge uses Gradle's built-in `maven-publish` plugin for Android. Per Sonatype's official Central Portal guidance for `maven-publish`, the release flow is:

1. upload artifacts to `https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/`
2. from the same CI job, call the documented manual endpoint:

```text
POST /manual/upload/defaultRepository/io.github.nikkiw?publishing_type=automatic
```

This is what makes the deployment visible to the Central Portal and eligible for automatic release to Maven Central.
