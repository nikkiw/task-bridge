from __future__ import annotations

import textwrap

import pytest

from scripts.release_tools import (
    COMPONENTS,
    ChangelogSectionExistsError,
    component_for_name,
    component_from_tag,
    extract_release_body,
    find_previous_component_tag,
    render_release_section,
    update_changelog,
)


def test_component_from_tag_maps_android() -> None:
    component, version = component_from_tag("android-v1.2.3")

    assert component.key == "android"
    assert version == "1.2.3"
    assert component.changelog_path == "android/CHANGELOG.md"


def test_component_from_tag_maps_backend_fastapi() -> None:
    component, version = component_from_tag("python-v1.2.3")

    assert component.key == "backend-fastapi"
    assert version == "1.2.3"
    assert component.changelog_path == "backend/taskbridge-fastapi/CHANGELOG.md"


def test_component_from_tag_maps_temporal_adapter() -> None:
    component, version = component_from_tag("python-temporal-v1.2.3")

    assert component.key == "temporal-adapter"
    assert version == "1.2.3"
    assert component.changelog_path == "backend/adapters/temporal/CHANGELOG.md"


def test_component_for_name_returns_expected_component() -> None:
    component = component_for_name("backend-fastapi")

    assert component is COMPONENTS["backend-fastapi"]


def test_component_include_paths_use_glob_patterns() -> None:
    assert COMPONENTS["android"].include_paths == ("android/**/*",)
    assert COMPONENTS["backend-fastapi"].include_paths == ("backend/taskbridge-fastapi/**/*",)
    assert COMPONENTS["temporal-adapter"].include_paths == ("backend/adapters/temporal/**/*",)


def test_find_previous_component_tag_ignores_other_release_families() -> None:
    component = COMPONENTS["android"]
    tags = [
        "python-v1.2.3",
        "android-v1.0.0",
        "python-temporal-v1.9.0",
        "android-v1.1.0",
        "python-v2.0.0",
    ]

    previous = find_previous_component_tag(component=component, version="1.2.0", tags=tags)

    assert previous == "android-v1.1.0"


def test_find_previous_component_tag_for_temporal_ignores_backend_core_tags() -> None:
    component = COMPONENTS["temporal-adapter"]
    tags = [
        "python-v1.2.0",
        "python-temporal-v0.8.0",
        "python-v1.3.0",
        "python-temporal-v0.9.0",
    ]

    previous = find_previous_component_tag(component=component, version="1.0.0", tags=tags)

    assert previous == "python-temporal-v0.9.0"


def test_render_release_section_uses_machine_readable_format() -> None:
    section = render_release_section(
        version="1.2.3",
        date="2026-05-19",
        grouped_notes={
            "Features": ["Added package-aware release automation."],
            "Fixes": ["Rejected duplicate changelog entries."],
            "Documentation": ["Updated contributor publication guide."],
            "Other": ["Refreshed release smoke coverage."],
        },
    )

    assert section.startswith("## 1.2.3 - 2026-05-19\n")
    assert "### Features\n- Added package-aware release automation.\n" in section
    assert "### Fixes\n- Rejected duplicate changelog entries.\n" in section
    assert "### Documentation\n- Updated contributor publication guide.\n" in section
    assert "### Other\n- Refreshed release smoke coverage.\n" in section


def test_update_changelog_prepends_new_version_section() -> None:
    original = textwrap.dedent(
        """\
        # Changelog

        ## 1.1.0 - 2026-05-10

        ### Features
        - Existing entry.
        """
    )
    section = textwrap.dedent(
        """\
        ## 1.2.0 - 2026-05-19

        ### Features
        - New release.
        """
    )

    updated = update_changelog(original, version="1.2.0", new_section=section)

    assert updated.startswith("# Changelog\n\n## 1.2.0 - 2026-05-19")
    assert "## 1.1.0 - 2026-05-10" in updated


def test_update_changelog_rejects_duplicate_version() -> None:
    original = textwrap.dedent(
        """\
        # Changelog

        ## 1.2.0 - 2026-05-19

        ### Features
        - Existing entry.
        """
    )
    section = textwrap.dedent(
        """\
        ## 1.2.0 - 2026-05-19

        ### Features
        - Duplicate entry.
        """
    )

    with pytest.raises(ChangelogSectionExistsError):
        update_changelog(original, version="1.2.0", new_section=section)


def test_extract_release_body_matches_section_exactly() -> None:
    changelog = textwrap.dedent(
        """\
        # Changelog

        ## 1.2.0 - 2026-05-19

        ### Features
        - Added release prep workflow.

        ### Fixes
        - Fail fast when the changelog section is missing.

        ## 1.1.0 - 2026-05-10

        ### Other
        - Previous entry.
        """
    )

    body = extract_release_body(changelog, version="1.2.0")

    assert body == textwrap.dedent(
        """\
        ## 1.2.0 - 2026-05-19

        ### Features
        - Added release prep workflow.

        ### Fixes
        - Fail fast when the changelog section is missing.
        """
    )
