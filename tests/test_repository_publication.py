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


def test_publication_docs_match_current_release_flow() -> None:
    publication_docs = "\n".join(
        [
            read(".github/ABOUT.md"),
            read("docs/contributing/publication.md"),
        ]
    )

    assert "android-vX.Y.Z" in publication_docs
    assert "python-vX.Y.Z" in publication_docs
    assert "python-temporal-vX.Y.Z" in publication_docs
    assert "Central Portal" in publication_docs
    assert "Portal token" in publication_docs
    assert "pypi-v*" not in publication_docs
    assert "maven-v*" not in publication_docs
    assert "Sonatype Jira username" not in publication_docs
