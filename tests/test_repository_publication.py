from __future__ import annotations

from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]


def read(relative_path: str) -> str:
    return (REPO_ROOT / relative_path).read_text(encoding="utf-8")


def test_root_license_exists_for_publication() -> None:
    assert (REPO_ROOT / "LICENSE").is_file()


def test_security_policy_uses_real_reporting_route() -> None:
    security = read("SECURITY.md")

    assert "security@example.com" not in security
    assert "GitHub" in security


def test_public_docs_do_not_reference_internal_or_stale_paths() -> None:
    files = [
        "README.md",
        "CONTRIBUTING.md",
        ".github/ABOUT.md",
        "examples/README.md",
        "docs/index.md",
        "docs/getting-started.md",
        "docs/documentation/index.md",
        "docs/adr/0005-android-toolchain-build-quality.md",
    ]

    contents = "\n".join(read(path) for path in files)

    assert ".ai/" not in contents
    assert "`adapters/`" not in contents
    assert "taskbridge-android" not in contents


def test_workflows_target_current_branch_and_repo_layout() -> None:
    workflow_files = sorted((REPO_ROOT / ".github" / "workflows").glob("*.yml"))
    workflow_contents = "\n".join(path.read_text(encoding="utf-8") for path in workflow_files)

    assert 'branches: ["master"]' not in workflow_contents
    assert "refs/heads/master" not in workflow_contents
    assert '"adapters/**"' not in workflow_contents
    assert '"adapters/temporal/**"' not in workflow_contents
    assert "working-directory: adapters/temporal" not in workflow_contents


def test_release_workflow_uses_current_publish_contract() -> None:
    release_workflow = read(".github/workflows/publish-release.yml")

    assert "android-v*" in release_workflow
    assert "python-v*" in release_workflow
    assert "python-*-v*" in release_workflow
    assert "fetch-depth: 0" in release_workflow
    assert "set-package-version" in release_workflow
    assert "Verify changelog section exists for tag" in release_workflow
    assert "gh release create" in release_workflow or "gh release edit" in release_workflow
    assert "publishAllPublicationsToOSSRHRepository" in release_workflow
    assert "ossrh-staging-api.central.sonatype.com" in release_workflow
    assert "manual/upload/defaultRepository/io.github.nikkiw" in release_workflow
    assert "publishing_type=automatic" in release_workflow


def test_release_smoke_workflow_covers_all_publishable_packages() -> None:
    smoke_workflow = read(".github/workflows/release-smoke.yml")

    assert ":taskbridge-core:publishReleasePublicationToMavenLocal" in smoke_workflow
    assert ":taskbridge-transport-okhttp:publishReleasePublicationToMavenLocal" in smoke_workflow
    assert "backend/taskbridge-fastapi" in smoke_workflow
    assert "backend/adapters/temporal" in smoke_workflow


def test_release_contract_workflow_exists() -> None:
    workflow = read(".github/workflows/release-contract.yml")

    assert "pull_request" in workflow
    assert "workflow_dispatch" not in workflow
    assert "Conventional" in workflow or "conventional" in workflow
    assert "release-bearing" in workflow or "android/**" in workflow
    assert "python-temporal-v" in workflow or "temporal-adapter" in workflow


def test_prepare_release_workflow_exists() -> None:
    workflow = read(".github/workflows/prepare-release.yml")

    assert "workflow_dispatch" in workflow
    assert "component" in workflow
    assert "android" in workflow
    assert "backend-fastapi" in workflow
    assert "temporal-adapter" in workflow
    assert "version" in workflow
    assert "git-cliff" in workflow
    assert "--from " not in workflow
    assert "..HEAD" in workflow
    assert "RELEASE_PR_TOKEN" in workflow
    assert "Open the PR manually" in workflow
    assert "chore(release): prepare" in workflow


def test_package_changelog_layout_exists() -> None:
    assert (REPO_ROOT / "android" / "CHANGELOG.md").is_file()
    assert (REPO_ROOT / "backend" / "taskbridge-fastapi" / "CHANGELOG.md").is_file()
    assert (REPO_ROOT / "backend" / "adapters" / "temporal" / "CHANGELOG.md").is_file()

    root_changelog = read("CHANGELOG.md")
    assert "android/CHANGELOG.md" in root_changelog
    assert "backend/taskbridge-fastapi/CHANGELOG.md" in root_changelog
    assert "backend/adapters/temporal/CHANGELOG.md" in root_changelog
    assert "## [Unreleased]" not in root_changelog


def test_publication_docs_match_current_release_flow() -> None:
    publication_docs = "\n".join(
        [
            read("README.md"),
            read(".github/ABOUT.md"),
            read("docs/contributing/publication.md"),
            read("android/README.md"),
            read("backend/taskbridge-fastapi/README.md"),
            read("backend/adapters/temporal/README.md"),
        ]
    )

    assert "android-vX.Y.Z" in publication_docs
    assert "python-vX.Y.Z" in publication_docs
    assert "python-temporal-vX.Y.Z" in publication_docs
    assert "Conventional Commits" in publication_docs
    assert "squash merge" in publication_docs
    assert "prepare-release" in publication_docs
    assert "GitHub Release" in publication_docs
    assert "0.0.0.dev0" in publication_docs
    assert "published version comes from the tag" in publication_docs
    assert "package changelog" in publication_docs or "package-level changelog" in publication_docs
    assert "Central Portal" in publication_docs
    assert "Portal token" in publication_docs
    assert "pypi-v*" not in publication_docs
    assert "maven-v*" not in publication_docs
    assert "Sonatype Jira username" not in publication_docs


def test_docs_workflow_and_entrypoints_reference_llms_txt() -> None:
    docs_related = "\n".join(
        [
            read("README.md"),
            read("docs/documentation/index.md"),
            read("scripts/docs_prepare.py"),
        ]
    )

    assert "llms.txt" in docs_related


def test_android_and_backend_pages_link_to_llms_txt() -> None:
    docs_entrypoints = "\n".join(
        [
            read("docs/android/index.md"),
            read("docs/backend/index.md"),
        ]
    )

    assert "llms.txt" in docs_entrypoints


def test_android_installation_examples_use_current_group_id() -> None:
    installation_docs = "\n".join(
        [
            read("docs/getting-started.md"),
            read("docs/android/index.md"),
            read("android/README.md"),
        ]
    )

    assert "io.github.nikkiw.taskbridge:taskbridge-core" in installation_docs
    assert "io.github.nikkiw.taskbridge:taskbridge-transport-okhttp" in installation_docs
    assert "io.github.nikkiw:taskbridge-core" not in installation_docs
    assert "io.github.nikkiw:taskbridge-transport-okhttp" not in installation_docs


def test_python_package_versions_use_dev_placeholders_in_repo() -> None:
    fastapi_pyproject = read("backend/taskbridge-fastapi/pyproject.toml")
    temporal_pyproject = read("backend/adapters/temporal/pyproject.toml")

    assert 'version = "0.0.0.dev0"' in fastapi_pyproject
    assert 'version = "0.0.0.dev0"' in temporal_pyproject
