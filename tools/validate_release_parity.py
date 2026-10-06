#!/usr/bin/env python3
"""Fail when published Survey2026 release metadata disagrees across surfaces."""

import argparse
import html.parser
import json
import re
import sys
from pathlib import Path


REQUIRED_PATHS = (
    "repository",
    "source_commit_sha",
    "release_tag",
    "release_url",
    "download_url",
    "apk_name",
    "apk_sha256",
    "signing_cert_sha256",
    "config_files.english.sha256",
    "config_files.swahili.sha256",
    "deployment_status",
    "verified_device.model",
    "verified_device.android_version",
    "verified_device.api_level",
)


def read_json(path: str) -> dict:
    with open(path, encoding="utf-8") as source:
        return json.load(source)


def value_at(data: dict, path: str):
    value = data
    for component in path.split("."):
        if not isinstance(value, dict) or component not in value:
            raise ValueError(f"missing {path}")
        value = value[component]
    return value


def metadata_from_release_body(body: str) -> dict:
    match = re.search(
        r"<!-- release-metadata:start -->\s*```json\s*(\{.*?\})\s*```\s*<!-- release-metadata:end -->",
        body,
        re.DOTALL,
    )
    if not match:
        raise ValueError("release notes do not contain the release-metadata JSON block")
    return json.loads(match.group(1))


class MetadataScriptParser(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.in_metadata_script = False
        self.parts = []

    def handle_starttag(self, tag, attrs):
        self.in_metadata_script = tag == "script" and dict(attrs).get("id") == "release-metadata"

    def handle_endtag(self, tag):
        if tag == "script":
            self.in_metadata_script = False

    def handle_data(self, data):
        if self.in_metadata_script:
            self.parts.append(data)


def metadata_from_html(path: str) -> dict:
    parser = MetadataScriptParser()
    parser.feed(Path(path).read_text(encoding="utf-8"))
    if not parser.parts:
        raise ValueError("download page does not contain #release-metadata JSON")
    return json.loads("".join(parser.parts))


def compare_metadata(expected: dict, actual: dict, surface: str, errors: list[str]) -> None:
    for path in REQUIRED_PATHS:
        try:
            expected_value = value_at(expected, path)
            actual_value = value_at(actual, path)
        except ValueError as error:
            errors.append(f"{surface}: {error}")
            continue
        if actual_value != expected_value:
            errors.append(
                f"{surface}: {path} expected {expected_value!r}, got {actual_value!r}"
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected", required=True)
    parser.add_argument("--release", required=True)
    parser.add_argument("--latest", required=True)
    parser.add_argument("--html", required=True)
    args = parser.parse_args()

    expected = read_json(args.expected)
    release = read_json(args.release)
    latest = read_json(args.latest)
    errors: list[str] = []

    try:
        release_metadata = metadata_from_release_body(release["body"])
    except (KeyError, ValueError, json.JSONDecodeError) as error:
        errors.append(f"GitHub Release: {error}")
        release_metadata = {}
    try:
        page_metadata = metadata_from_html(args.html)
    except (ValueError, json.JSONDecodeError) as error:
        errors.append(f"download page: {error}")
        page_metadata = {}

    page_html = Path(args.html).read_text(encoding="utf-8")
    visible_device_text = (
        f"{expected.get('deployment_status', '')}</code>"
        in page_html
        and expected.get("verified_device", {}).get("model", "") in page_html
        and f"API {expected.get('verified_device', {}).get('api_level', '')}" in page_html
    )
    if not visible_device_text:
        errors.append("rendered download page: deployment status or verified device is not visible")

    for surface, metadata in (
        ("GitHub Release notes", release_metadata),
        ("gh-pages/latest.json", latest),
        ("rendered download page", page_metadata),
    ):
        compare_metadata(expected, metadata, surface, errors)

    if release.get("tagName") != expected.get("release_tag"):
        errors.append("GitHub Release API: tagName does not match release_tag")
    if release.get("targetCommitish") != expected.get("source_commit_sha"):
        errors.append("GitHub Release API: targetCommitish does not match source_commit_sha")
    if release.get("url") != expected.get("release_url"):
        errors.append("GitHub Release API: url does not match release_url")
    assets = release.get("assets", [])
    asset = next((item for item in assets if item.get("name") == expected.get("apk_name")), None)
    if asset is None:
        errors.append("GitHub Release API: APK asset is missing")
    elif asset.get("url") != expected.get("download_url"):
        errors.append("GitHub Release API: APK asset URL does not match download_url")

    if errors:
        print("Release parity validation failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1
    print("Release parity validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
