from __future__ import annotations

import json
import sys
from pathlib import Path
import pytest
import textwrap

from scripts.release_tools import (
    COMPONENTS,
    ReleaseToolError,
    UnknownComponentError,
    UnknownTagError,
    validate_conventional_title,
    normalize_cliff_output,
    set_package_version,
    main,
)


def test_validate_conventional_title_valid_cases() -> None:
    # Both scoped and unscoped conventional commits should validate successfully
    validate_conventional_title("feat: add some cool feature")
    validate_conventional_title("fix: resolve some issue")
    validate_conventional_title("feat(core): add some cool feature")
    validate_conventional_title("fix(transport)!: fix serious critical bug")
    validate_conventional_title("chore(deps): clean up build scripts")
    validate_conventional_title("feat!: major rewrite")


def test_validate_conventional_title_invalid_format() -> None:
    # Invalid conventional formats should raise ReleaseToolError
    with pytest.raises(ReleaseToolError, match="does not match Conventional Commits"):
        validate_conventional_title("WIP: working on stuff")
    with pytest.raises(ReleaseToolError, match="does not match Conventional Commits"):
        validate_conventional_title("feat (android): spaces not allowed")


def test_validate_conventional_title_valid_scope_for_component() -> None:
    # Scoped titles within allowed aliases should validate successfully
    android = COMPONENTS["android"]
    validate_conventional_title("feat(android): add new UI module", component=android)
    validate_conventional_title("fix(android)!: breaking API change", component=android)

    fastapi = COMPONENTS["backend-fastapi"]
    validate_conventional_title("feat(backend): add middleware", component=fastapi)
    validate_conventional_title("chore(fastapi): update deps", component=fastapi)


def test_validate_conventional_title_invalid_scope_for_component() -> None:
    # Scoped titles outside allowed aliases should raise ReleaseToolError
    android = COMPONENTS["android"]
    with pytest.raises(ReleaseToolError, match="Unsupported scope 'ios' for component 'android'"):
        validate_conventional_title("feat(ios): add swift integration", component=android)

    fastapi = COMPONENTS["backend-fastapi"]
    with pytest.raises(ReleaseToolError, match="Unsupported scope 'android' for component 'backend-fastapi'"):
        validate_conventional_title("feat(android): wrong component", component=fastapi)


def test_normalize_cliff_output_basic() -> None:
    notes = textwrap.dedent(
        """\
        ### Features
        - Added client logging configuration.
        - Supported high throughput pipelines.

        ### Fixes
        - Resolved memory leak in event loop.

        ### Docs
        - Updated publication workflow guides.

        ### Other
        - Cleaned up obsolete imports.
        """
    )
    result = normalize_cliff_output(notes)

    assert result["Features"] == ["Added client logging configuration.", "Supported high throughput pipelines."]
    assert result["Fixes"] == ["Resolved memory leak in event loop."]
    assert result["Documentation"] == ["Updated publication workflow guides."]
    assert result["Other"] == ["Cleaned up obsolete imports."]


def test_normalize_cliff_output_fallback_and_unknown_headings() -> None:
    notes = textwrap.dedent(
        """\
        - Bullet points before any heading.

        ### Unknown Section
        - An entry under unknown category.
        """
    )
    result = normalize_cliff_output(notes)

    assert result["Features"] == []
    assert result["Other"] == ["Bullet points before any heading.", "An entry under unknown category."]


def test_set_package_version_success(tmp_path: Path) -> None:
    pyproject_file = tmp_path / "pyproject.toml"
    pyproject_file.write_text(
        textwrap.dedent(
            """\
            [project]
            name = "taskbridge-fastapi"
            version = "0.0.0.dev0"
            description = "FastAPI backend support"
            """
        ),
        encoding="utf-8",
    )

    set_package_version(pyproject_file, "0.2.5")
    updated_content = pyproject_file.read_text(encoding="utf-8")

    assert 'version = "0.2.5"' in updated_content
    assert 'name = "taskbridge-fastapi"' in updated_content


def test_set_package_version_failure_missing_field(tmp_path: Path) -> None:
    pyproject_file = tmp_path / "pyproject.toml"
    pyproject_file.write_text(
        textwrap.dedent(
            """\
            [project]
            name = "taskbridge-fastapi"
            """
        ),
        encoding="utf-8",
    )

    with pytest.raises(ReleaseToolError, match="Could not rewrite version in"):
        set_package_version(pyproject_file, "0.2.5")


def test_cli_lint_title(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setattr(
        sys, "argv", ["release_tools.py", "lint-title", "--title", "feat(android): hello", "--component", "android"]
    )
    exit_code = main()
    captured = capsys.readouterr()

    assert exit_code == 0
    assert captured.out.strip() == "ok"


def test_cli_lint_title_fails(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        sys, "argv", ["release_tools.py", "lint-title", "--title", "feat(ios): hello", "--component", "android"]
    )
    with pytest.raises(ReleaseToolError, match="Unsupported scope 'ios'"):
        main()


def test_cli_resolve_component(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setattr(sys, "argv", ["release_tools.py", "resolve", "--component", "android"])
    exit_code = main()
    captured = capsys.readouterr()

    assert exit_code == 0
    data = json.loads(captured.out)
    assert data["component"] == "android"
    assert data["tag_prefix"] == "android-v"


def test_cli_resolve_tag(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setattr(sys, "argv", ["release_tools.py", "resolve", "--tag", "python-v1.4.2"])
    exit_code = main()
    captured = capsys.readouterr()

    assert exit_code == 0
    data = json.loads(captured.out)
    assert data["component"] == "backend-fastapi"
    assert data["version"] == "1.4.2"


def test_cli_release_title(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    monkeypatch.setattr(
        sys, "argv", ["release_tools.py", "release-title", "--component", "backend-fastapi", "--version", "2.1.0"]
    )
    exit_code = main()
    captured = capsys.readouterr()

    assert exit_code == 0
    assert captured.out.strip() == "taskbridge-fastapi v2.1.0"


def test_cli_normalize_cliff_notes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    input_file = tmp_path / "notes.txt"
    input_file.write_text(
        textwrap.dedent(
            """\
            ### Features
            - Added dynamic caching to core components.
            """
        ),
        encoding="utf-8",
    )

    monkeypatch.setattr(
        sys,
        "argv",
        [
            "release_tools.py",
            "normalize-cliff-notes",
            "--input",
            str(input_file),
            "--version",
            "1.2.0-beta.1",
            "--date",
            "2026-05-20",
        ],
    )
    exit_code = main()
    captured = capsys.readouterr()

    assert exit_code == 0
    assert "## 1.2.0-beta.1 - 2026-05-20" in captured.out
    assert "### Features" in captured.out
    assert "- Added dynamic caching to core components." in captured.out
