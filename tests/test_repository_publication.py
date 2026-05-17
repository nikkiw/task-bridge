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
        ".github/README.md",
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
