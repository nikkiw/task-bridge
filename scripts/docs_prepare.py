from __future__ import annotations

import argparse
import shutil
import subprocess
from pathlib import Path


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def build_android_reference(android_dir: Path) -> None:
    subprocess.run(
        ["./gradlew", "dokkaGeneratePublicationHtml"],
        cwd=android_dir,
        check=True,
    )


def build_backend_reference(root: Path) -> None:
    # Future-proofing: could generate OpenAPI static docs here
    # For now, we ensure the protocol directory is accessible if needed
    pass


def remove_legacy_android_reference(legacy_destination: Path) -> None:
    if legacy_destination.is_dir():
        shutil.rmtree(legacy_destination)


def prepare_android_reference(source: Path, destination: Path) -> Path:
    if not source.is_dir():
        # Fallback to check if it's in a different location or if we should aggregate manually
        raise FileNotFoundError(f"Dokka output directory not found: {source}")

    if destination.exists():
        shutil.rmtree(destination)

    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, destination)

    # Inject "Back to Manual" link via main.js to appear on every page
    main_js = destination / "scripts" / "main.js"
    if main_js.exists():
        back_link_js = """
window.addEventListener('DOMContentLoaded', () => {
    const nav = document.getElementById('navigation-wrapper');
    if (nav) {
        const link = document.createElement('a');
        link.href = '../../'; 
        link.innerText = '← Back to Manual';
        link.className = 'library-name--link';
        link.style.marginRight = '20px';
        link.style.display = 'inline-block';
        nav.prepend(link);
    }
});
"""
        with open(main_js, "a", encoding="utf-8") as f:
            f.write(back_link_js)
        print(f"Injected back-link into {main_js}")

    return destination


def prepare_backend_reference(root: Path, destination_dir: Path) -> None:
    openapi_source = root / "protocol" / "openapi" / "taskbridge.openapi.yaml"
    schemas_source = root / "protocol" / "schemas"
    
    if openapi_source.exists():
        destination_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(openapi_source, destination_dir / "openapi.yaml")
        print(f"Copied OpenAPI spec to {destination_dir / 'openapi.yaml'}")

        # Also copy schemas because they are referenced relatively (../schemas/...)
        schemas_destination = destination_dir.parent / "schemas"
        if schemas_source.is_dir():
            if schemas_destination.exists():
                shutil.rmtree(schemas_destination)
            shutil.copytree(schemas_source, schemas_destination)
            print(f"Copied protocol schemas to {schemas_destination}")

        # Generate a simple Redoc index.html with a back link
        redoc_html = f"""<!DOCTYPE html>
<html>
  <head>
    <title>TaskBridge Backend API Reference</title>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link href="https://fonts.googleapis.com/css?family=Montserrat:300,400,700|Roboto:300,400,700" rel="stylesheet">
    <style>
      body {{
        margin: 0;
        padding: 0;
      }}
      .back-nav {{
        padding: 12px 24px;
        background: #fafafa;
        border-bottom: 1px solid #e5e5e5;
      }}
      .back-nav a {{
        text-decoration: none;
        color: #1DA2BD;
        font-family: Montserrat, sans-serif;
        font-weight: 700;
        font-size: 14px;
      }}
    </style>
  </head>
  <body>
    <div class="back-nav">
      <a href="../../">← Back to Manual</a>
    </div>
    <redoc spec-url='openapi.yaml'></redoc>
    <script src="https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js"> </script>
  </body>
</html>
"""
        (destination_dir / "index.html").write_text(redoc_html, encoding="utf-8")
        print(f"Generated Redoc HTML at {destination_dir / 'index.html'}")


def parse_args() -> argparse.Namespace:
    root = repo_root()
    parser = argparse.ArgumentParser(
        description="Build and stage generated documentation assets for the TaskBridge MkDocs site."
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="Reuse existing build outputs instead of invoking build tools.",
    )
    parser.add_argument(
        "--dokka-source",
        type=Path,
        default=root / "android" / "build" / "dokka" / "html",
        help="Path to the generated Dokka HTML directory.",
    )
    parser.add_argument(
        "--destination-android",
        type=Path,
        default=root / "docs" / "reference" / "android-ref",
        help="Destination inside the MkDocs source tree for Android API reference.",
    )
    parser.add_argument(
        "--destination-backend",
        type=Path,
        default=root / "docs" / "reference" / "backend-api",
        help="Destination inside the MkDocs source tree for Backend API reference assets.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = repo_root()

    if not args.skip_build:
        print("Building Android API reference...")
        build_android_reference(root / "android")
        print("Building Backend API reference assets...")
        build_backend_reference(root)

    remove_legacy_android_reference(root / "docs" / "site" / "reference" / "android")
    remove_legacy_android_reference(root / "docs" / "site" / "reference" / "android-ref")
    remove_legacy_android_reference(root / "docs" / "site" / "reference" / "backend-api")
    remove_legacy_android_reference(root / "docs" / "site" / "reference" / "schemas")
    remove_legacy_android_reference(root / "docs" / "reference" / "android")

    prepared_android = prepare_android_reference(
        source=args.dokka_source.resolve(),
        destination=args.destination_android.resolve(),
    )
    print(f"Prepared Android API reference at {prepared_android}")

    prepare_backend_reference(
        root=root,
        destination_dir=args.destination_backend.resolve(),
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
