from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = REPO_ROOT / "scripts" / "docs_prepare.py"


def load_docs_prepare_module():
    assert MODULE_PATH.exists(), f"Expected docs prepare script at {MODULE_PATH}"
    spec = importlib.util.spec_from_file_location("docs_prepare", MODULE_PATH)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_prepare_android_reference_copies_tree_and_removes_stale_files(tmp_path: Path):
    module = load_docs_prepare_module()
    source = tmp_path / "dokka-html"
    source.mkdir()
    (source / "index.html").write_text("<html>android api</html>", encoding="utf-8")
    (source / "nested").mkdir()
    (source / "nested" / "api.html").write_text("<html>nested</html>", encoding="utf-8")

    destination = tmp_path / "site-reference"
    destination.mkdir()
    (destination / "stale.txt").write_text("remove me", encoding="utf-8")

    prepared = module.prepare_android_reference(source=source, destination=destination)

    assert prepared == destination
    assert not (destination / "stale.txt").exists()
    assert (destination / "index.html").read_text(encoding="utf-8") == "<html>android api</html>"
    assert (destination / "nested" / "api.html").read_text(encoding="utf-8") == "<html>nested</html>"


def test_prepare_android_reference_fails_for_missing_dokka_output(tmp_path: Path):
    module = load_docs_prepare_module()
    source = tmp_path / "missing-dokka-html"
    destination = tmp_path / "site-reference"

    with pytest.raises(FileNotFoundError, match="Dokka output directory not found"):
        module.prepare_android_reference(source=source, destination=destination)


def test_remove_legacy_android_reference_deletes_previous_path(tmp_path: Path):
    module = load_docs_prepare_module()
    legacy = tmp_path / "reference" / "android"
    legacy.mkdir(parents=True)
    (legacy / "index.html").write_text("legacy", encoding="utf-8")

    module.remove_legacy_android_reference(legacy)

    assert not legacy.exists()


def test_parse_args_defaults_use_flat_docs_destinations(monkeypatch: pytest.MonkeyPatch):
    module = load_docs_prepare_module()
    monkeypatch.setattr("sys.argv", ["docs_prepare.py"])

    args = module.parse_args()

    assert args.destination_android == REPO_ROOT / "docs" / "reference" / "android-ref"
    assert args.destination_backend == REPO_ROOT / "docs" / "reference" / "backend-api"
