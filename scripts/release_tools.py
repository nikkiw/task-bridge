from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
SECTION_HEADER_RE = re.compile(r"^## (?P<version>\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?) - (?P<date>\d{4}-\d{2}-\d{2})$")
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
CONVENTIONAL_TITLE_RE = re.compile(
    r"^(feat|fix|docs|refactor|perf|test|build|ci|chore)(?:\([a-z0-9-]+\))?!?: .+$"
)


class ReleaseToolError(RuntimeError):
    pass


class UnknownComponentError(ReleaseToolError):
    pass


class UnknownTagError(ReleaseToolError):
    pass


class ChangelogSectionExistsError(ReleaseToolError):
    pass


class ChangelogSectionMissingError(ReleaseToolError):
    pass


@dataclass(frozen=True)
class Component:
    key: str
    display_name: str
    tag_prefix: str
    changelog_path: str
    include_paths: tuple[str, ...]
    release_title: str
    scope_aliases: tuple[str, ...]

    @property
    def changelog_file(self) -> Path:
        return REPO_ROOT / self.changelog_path


COMPONENTS: dict[str, Component] = {
    "android": Component(
        key="android",
        display_name="Android SDK",
        tag_prefix="android-v",
        changelog_path="android/CHANGELOG.md",
        include_paths=("android/**/*",),
        release_title="Android SDK v{version}",
        scope_aliases=("android",),
    ),
    "backend-fastapi": Component(
        key="backend-fastapi",
        display_name="taskbridge-fastapi",
        tag_prefix="python-v",
        changelog_path="backend/taskbridge-fastapi/CHANGELOG.md",
        include_paths=("backend/taskbridge-fastapi/**/*",),
        release_title="taskbridge-fastapi v{version}",
        scope_aliases=("backend", "backend-fastapi", "fastapi"),
    ),
    "temporal-adapter": Component(
        key="temporal-adapter",
        display_name="taskbridge-temporal-adapter",
        tag_prefix="python-temporal-v",
        changelog_path="backend/adapters/temporal/CHANGELOG.md",
        include_paths=("backend/adapters/temporal/**/*",),
        release_title="taskbridge-temporal-adapter v{version}",
        scope_aliases=("temporal", "temporal-adapter"),
    ),
}


def component_for_name(name: str) -> Component:
    try:
        return COMPONENTS[name]
    except KeyError as exc:
        raise UnknownComponentError(f"Unsupported component: {name}") from exc


def component_from_tag(tag: str) -> tuple[Component, str]:
    for component in COMPONENTS.values():
        prefix = component.tag_prefix
        if tag.startswith(prefix):
            version = tag.removeprefix(prefix)
            if not SEMVER_RE.fullmatch(version):
                raise UnknownTagError(f"Unsupported version in tag: {tag}")
            return component, version
    raise UnknownTagError(f"Unsupported tag format: {tag}")


def list_matching_tags(component: Component, tags: Iterable[str]) -> list[str]:
    return sorted(tag for tag in tags if tag.startswith(component.tag_prefix))


def find_previous_component_tag(component: Component, version: str, tags: Iterable[str]) -> str | None:
    expected = f"{component.tag_prefix}{version}"
    matching = list_matching_tags(component, tags)
    previous: str | None = None
    for tag in matching:
        if tag == expected:
            break
        previous = tag
    return previous


def render_release_section(version: str, date: str, grouped_notes: dict[str, list[str]]) -> str:
    ordered_sections = ("Features", "Fixes", "Documentation", "Other")
    parts = [f"## {version} - {date}", ""]
    for section_name in ordered_sections:
        notes = grouped_notes.get(section_name, [])
        if not notes:
            continue
        parts.append(f"### {section_name}")
        parts.extend(f"- {note}" for note in notes)
        parts.append("")
    return "\n".join(parts).rstrip() + "\n"


def update_changelog(existing: str, version: str, new_section: str) -> str:
    if re.search(rf"^## {re.escape(version)} - ", existing, re.MULTILINE):
        raise ChangelogSectionExistsError(f"Version {version} already exists in changelog")

    stripped = existing.strip()
    if not stripped:
        return new_section

    lines = existing.splitlines()
    insertion_index = len(lines)
    for index, line in enumerate(lines):
        if line.startswith("## "):
            insertion_index = index
            break

    before = "\n".join(lines[:insertion_index]).rstrip()
    after = "\n".join(lines[insertion_index:]).lstrip()
    parts = [before, new_section.rstrip()]
    if after:
        parts.append(after)
    return "\n\n".join(part for part in parts if part) + "\n"


def extract_release_body(changelog: str, version: str) -> str:
    lines = changelog.splitlines()
    start_index: int | None = None
    end_index = len(lines)
    header_prefix = f"## {version} - "

    for index, line in enumerate(lines):
        if line.startswith(header_prefix):
            start_index = index
            break

    if start_index is None:
        raise ChangelogSectionMissingError(f"Version {version} not found in changelog")

    for index in range(start_index + 1, len(lines)):
        if lines[index].startswith("## "):
            end_index = index
            break

    return "\n".join(lines[start_index:end_index]).rstrip() + "\n"


def validate_conventional_title(title: str, component: Component | None = None) -> None:
    if not CONVENTIONAL_TITLE_RE.fullmatch(title):
        raise ReleaseToolError(f"Title does not match Conventional Commits: {title}")

    if component is None:
        return

    allowed_scopes = set(component.scope_aliases)
    match = re.match(
        r"^(feat|fix|docs|refactor|perf|test|build|ci|chore)\((?P<scope>[a-z0-9-]+)\)!?: .+",
        title,
    )
    if match and match.group("scope") not in allowed_scopes:
        raise ReleaseToolError(
            f"Unsupported scope '{match.group('scope')}' for component '{component.key}'"
        )


def git_tags() -> list[str]:
    result = subprocess.run(
        ["git", "tag", "--list", "--sort=version:refname"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    return [line for line in result.stdout.splitlines() if line]


def normalize_cliff_output(text: str) -> dict[str, list[str]]:
    grouped: dict[str, list[str]] = {
        "Features": [],
        "Fixes": [],
        "Documentation": [],
        "Other": [],
    }
    current_section: str | None = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if line.startswith("### "):
            heading = line.removeprefix("### ").strip().lower()
            mapping = {
                "features": "Features",
                "fixes": "Fixes",
                "documentation": "Documentation",
                "docs": "Documentation",
                "other": "Other",
            }
            current_section = mapping.get(heading, "Other")
            continue
        if line.startswith("- "):
            target = current_section or "Other"
            grouped[target].append(line[2:].strip())

    return grouped


def emit_json(payload: dict[str, object]) -> None:
    print(json.dumps(payload, indent=2, sort_keys=True))


def set_package_version(pyproject_path: Path, version: str) -> None:
    content = pyproject_path.read_text(encoding="utf-8")
    updated, replacements = re.subn(
        r'^version = ".*"$',
        f'version = "{version}"',
        content,
        count=1,
        flags=re.MULTILINE,
    )
    if replacements != 1:
        raise ReleaseToolError(f"Could not rewrite version in {pyproject_path}")
    pyproject_path.write_text(updated, encoding="utf-8")


def update_readme_version(readme_path: Path, component_key: str, version: str) -> None:
    content = readme_path.read_text(encoding="utf-8")
    if component_key == "android":
        # Updates taskbridge-core and taskbridge-transport-okhttp
        pattern = r'(io\.github\.nikkiw\.taskbridge:taskbridge-(?:core|transport-okhttp)):(\d+\.\d+\.\d+[a-zA-Z0-9.+-]*)'
        updated, count = re.subn(pattern, rf'\g<1>:{version}', content)
        if count == 0:
            raise ReleaseToolError(f"Could not rewrite Android version in {readme_path}")
    elif component_key == "backend-fastapi":
        # Updates taskbridge-fastapi version in pip install
        pattern = r'(pip install taskbridge-fastapi==)(\d+\.\d+\.\d+[a-zA-Z0-9.+-]*)'
        updated, count = re.subn(pattern, rf'\g<1>{version}', content)
        if count == 0:
            raise ReleaseToolError(f"Could not rewrite Backend version in {readme_path}")
    elif component_key == "temporal-adapter":
        # Updates taskbridge-temporal version in pip install
        pattern = r'(pip install taskbridge-temporal==)(\d+\.\d+\.\d+[a-zA-Z0-9.+-]*)'
        updated, count = re.subn(pattern, rf'\g<1>{version}', content)
        if count == 0:
            raise ReleaseToolError(f"Could not rewrite Temporal version in {readme_path}")
    else:
        raise ReleaseToolError(f"Unknown component key: {component_key}")

    readme_path.write_text(updated, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="TaskBridge release automation helpers")
    subparsers = parser.add_subparsers(dest="command", required=True)

    resolve_parser = subparsers.add_parser("resolve")
    resolve_group = resolve_parser.add_mutually_exclusive_group(required=True)
    resolve_group.add_argument("--component")
    resolve_group.add_argument("--tag")

    previous_parser = subparsers.add_parser("previous-tag")
    previous_parser.add_argument("--component", required=True)
    previous_parser.add_argument("--version", required=True)

    verify_parser = subparsers.add_parser("verify-changelog")
    verify_group = verify_parser.add_mutually_exclusive_group(required=True)
    verify_group.add_argument("--component")
    verify_group.add_argument("--tag")
    verify_parser.add_argument("--version")

    extract_parser = subparsers.add_parser("extract-release-body")
    extract_group = extract_parser.add_mutually_exclusive_group(required=True)
    extract_group.add_argument("--component")
    extract_group.add_argument("--tag")
    extract_parser.add_argument("--version")

    title_parser = subparsers.add_parser("release-title")
    title_group = title_parser.add_mutually_exclusive_group(required=True)
    title_group.add_argument("--component")
    title_group.add_argument("--tag")
    title_parser.add_argument("--version")

    lint_parser = subparsers.add_parser("lint-title")
    lint_parser.add_argument("--title", required=True)
    lint_parser.add_argument("--component")

    normalize_parser = subparsers.add_parser("normalize-cliff-notes")
    normalize_parser.add_argument("--input", required=True)
    normalize_parser.add_argument("--version", required=True)
    normalize_parser.add_argument("--date", required=True)

    update_parser = subparsers.add_parser("update-changelog")
    update_parser.add_argument("--component", required=True)
    update_parser.add_argument("--version", required=True)
    update_parser.add_argument("--section-file", required=True)

    set_version_parser = subparsers.add_parser("set-package-version")
    set_version_parser.add_argument("--component", required=True)
    set_version_parser.add_argument("--version", required=True)

    update_readme_parser = subparsers.add_parser("update-readme")
    update_readme_parser.add_argument("--component", required=True)
    update_readme_parser.add_argument("--version", required=True)

    args = parser.parse_args()

    if args.command == "resolve":
        if args.component:
            component = component_for_name(args.component)
            payload: dict[str, object] = {"component": component.key}
        else:
            component, version = component_from_tag(args.tag)
            payload = {"component": component.key, "version": version}
        payload.update(
            {
                "tag_prefix": component.tag_prefix,
                "changelog_path": component.changelog_path,
                "include_paths": list(component.include_paths),
                "release_title_template": component.release_title,
                "scope_aliases": list(component.scope_aliases),
            }
        )
        emit_json(payload)
        return 0

    if args.command == "previous-tag":
        component = component_for_name(args.component)
        previous = find_previous_component_tag(component, args.version, git_tags())
        if previous:
            print(previous)
        return 0

    if args.command in {"verify-changelog", "extract-release-body", "release-title"}:
        if args.component:
            component = component_for_name(args.component)
            version = args.version
            if not version:
                raise ReleaseToolError("--version is required with --component")
        else:
            component, version = component_from_tag(args.tag)

        if args.command == "release-title":
            print(component.release_title.format(version=version))
            return 0

        changelog = component.changelog_file.read_text(encoding="utf-8")
        if args.command == "verify-changelog":
            print(extract_release_body(changelog, version), end="")
            return 0

        print(extract_release_body(changelog, version), end="")
        return 0

    if args.command == "lint-title":
        component = component_for_name(args.component) if args.component else None
        validate_conventional_title(args.title, component=component)
        print("ok")
        return 0

    if args.command == "normalize-cliff-notes":
        raw_notes = Path(args.input).read_text(encoding="utf-8")
        grouped = normalize_cliff_output(raw_notes)
        print(render_release_section(version=args.version, date=args.date, grouped_notes=grouped), end="")
        return 0

    if args.command == "update-changelog":
        component = component_for_name(args.component)
        section = Path(args.section_file).read_text(encoding="utf-8")
        existing = component.changelog_file.read_text(encoding="utf-8")
        updated = update_changelog(existing, version=args.version, new_section=section)
        component.changelog_file.write_text(updated, encoding="utf-8")
        print(component.changelog_path)
        return 0

    if args.command == "set-package-version":
        component = component_for_name(args.component)
        pyproject_path = component.changelog_file.parent / "pyproject.toml"
        set_package_version(pyproject_path, args.version)
        print(pyproject_path)
        return 0

    if args.command == "update-readme":
        readme_path = REPO_ROOT / "README.md"
        update_readme_version(readme_path, args.component, args.version)
        print(readme_path)
        return 0

    raise ReleaseToolError(f"Unsupported command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
