# Publication Guide

This guide explains how to publish new versions of TaskBridge libraries for Android (Maven Central) and Python (PyPI).

## Versioning Policy

We use [Semantic Versioning](https://semver.org/). 
- Android versions (all modules): `android-vX.Y.Z`
- Python core (`taskbridge-fastapi`): `python-vX.Y.Z`
- Python Temporal adapter: `python-temporal-vX.Y.Z`

## Automatic Publication (Recommended)

Publication is handled automatically by GitHub Actions when a tag is pushed to the repository.

### For Android (Maven Central)

1. Ensure the code is ready and all tests pass.
2. Create and push a tag:
   ```bash
   git tag android-v0.1.0
   git push origin android-v0.1.0
   ```
3. The `publish-release` workflow will trigger, sign both Android modules (`taskbridge-core`, `taskbridge-transport-okhttp`), publish them through Sonatype's OSSRH compatibility API, and then transfer the deployment to the Central Portal for automatic release.

### For Python (PyPI)

#### Core Library
1. Create and push a tag:
   ```bash
   git tag python-v0.1.0
   git push origin python-v0.1.0
   ```

#### Temporal Adapter
1. Create and push a tag:
   ```bash
   git tag python-temporal-v0.1.0
   git push origin python-temporal-v0.1.0
   ```

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
