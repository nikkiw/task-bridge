from __future__ import annotations

from pathlib import Path
import pytest
import textwrap

from scripts.release_tools import (
    update_readme_version,
    ReleaseToolError,
)

def test_update_readme_version_android(tmp_path: Path) -> None:
    readme_path = tmp_path / "README.md"
    readme_path.write_text(
        textwrap.dedent(
            """\
            # TaskBridge
            
            Add the dependencies:
            ```kotlin
            dependencies {
                implementation("io.github.nikkiw.taskbridge:taskbridge-core:0.1.0")
                implementation("io.github.nikkiw.taskbridge:taskbridge-transport-okhttp:0.1.0")
            }
            ```
            """
        ),
        encoding="utf-8"
    )

    update_readme_version(readme_path, "android", "0.2.0")
    content = readme_path.read_text(encoding="utf-8")

    assert 'implementation("io.github.nikkiw.taskbridge:taskbridge-core:0.2.0")' in content
    assert 'implementation("io.github.nikkiw.taskbridge:taskbridge-transport-okhttp:0.2.0")' in content


def test_update_readme_version_backend(tmp_path: Path) -> None:
    readme_path = tmp_path / "README.md"
    readme_path.write_text(
        textwrap.dedent(
            """\
            ### 1. Backend (FastAPI)
            ```bash
            pip install taskbridge-fastapi==0.1.1
            ```
            """
        ),
        encoding="utf-8"
    )

    update_readme_version(readme_path, "backend-fastapi", "0.1.2")
    content = readme_path.read_text(encoding="utf-8")

    assert "pip install taskbridge-fastapi==0.1.2" in content


def test_update_readme_version_temporal(tmp_path: Path) -> None:
    readme_path = tmp_path / "README.md"
    readme_path.write_text(
        textwrap.dedent(
            """\
            ```bash
            pip install taskbridge-temporal==0.1.0
            ```
            """
        ),
        encoding="utf-8"
    )

    update_readme_version(readme_path, "temporal-adapter", "0.2.0")
    content = readme_path.read_text(encoding="utf-8")

    assert "pip install taskbridge-temporal==0.2.0" in content


def test_update_readme_version_missing_target_raises_error(tmp_path: Path) -> None:
    readme_path = tmp_path / "README.md"
    readme_path.write_text("Hello World", encoding="utf-8")

    with pytest.raises(ReleaseToolError) as exc_info:
        update_readme_version(readme_path, "backend-fastapi", "0.1.2")
    
    assert "Could not rewrite Backend version" in str(exc_info.value)


def test_update_readme_version_unknown_component_raises_error(tmp_path: Path) -> None:
    readme_path = tmp_path / "README.md"
    readme_path.write_text("Hello World", encoding="utf-8")

    with pytest.raises(ReleaseToolError) as exc_info:
        update_readme_version(readme_path, "invalid-key", "0.1.2")
    
    assert "Unknown component key: invalid-key" in str(exc_info.value)
