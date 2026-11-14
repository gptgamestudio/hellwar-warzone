#!/usr/bin/env python3
"""Automatically updates release metadata across README.md, GitHub Pages, Firebase Hosting, and sitemaps."""
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path


def replace_block(text: str, marker: str, replacement: str) -> str:
    pattern = re.compile(
        rf"<!-- {re.escape(marker)}_START -->.*?<!-- {re.escape(marker)}_END -->",
        re.DOTALL,
    )
    new_block = f"<!-- {marker}_START -->\n{replacement}\n<!-- {marker}_END -->"
    if not pattern.search(text):
        raise ValueError(f"Missing marker block {marker} in file")
    return pattern.sub(lambda _: new_block, text)


def main() -> int:
    if len(sys.argv) < 3:
        print("Usage: update-release-metadata.py <tag> <apk_path>")
        return 1

    tag = sys.argv[1].strip()
    apk_path = Path(sys.argv[2])
    if not apk_path.is_file():
        print(f"APK file not found at {apk_path}")
        return 1

    apk_bytes = apk_path.stat().st_size
    if apk_bytes <= 0:
        print(f"APK file is empty: {apk_path}")
        return 1

    apk_mb = f"{apk_bytes / (1024 * 1024):.2f} MB"
    release_date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    version_clean = tag.lstrip("v")
    repo = os.environ.get("GITHUB_REPOSITORY", "gptgamestudio/hellwar-warzone")
    download_url = f"https://github.com/{repo}/releases/download/{tag}/helicopter-war.apk"
    release_url = f"https://github.com/{repo}/releases/tag/{tag}"

    metadata = {
        "version": tag,
        "versionName": version_clean,
        "apkFileName": "helicopter-war.apk",
        "apkSizeBytes": apk_bytes,
        "apkSizeFormatted": apk_mb,
        "releaseDate": release_date,
        "downloadUrl": download_url,
        "releaseUrl": release_url,
        "packageName": "com.hellwar.game",
        "minAndroid": "Android 7.0 (API 24+)",
        "status": "published",
    }

    for meta_file in (Path("release-info.json"), Path("hosting/release-info.json")):
        meta_file.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")

    readme_path = Path("README.md")
    if readme_path.is_file():
        readme = readme_path.read_text(encoding="utf-8")
        readme_block = (
            f"| Field | Details |\n"
            f"|---|---|\n"
            f"| **Latest Version** | `{tag}` |\n"
            f"| **APK File** | `helicopter-war.apk` ({apk_mb}) |\n"
            f"| **Release Date** | `{release_date}` |\n"
            f"| **Direct Download** | [Download helicopter-war.apk]({download_url}) |\n"
            f"| **Release Page** | [View {tag} Release Notes]({release_url}) |"
        )
        readme = replace_block(readme, "RELEASE_INFO", readme_block)
        readme_path.write_text(readme, encoding="utf-8")

    html_block = (
        f'          <a id="download-apk-btn" class="btn btn-primary" href="{download_url}" rel="noopener">\n'
        f'            Download helicopter-war.apk ({tag} &middot; {apk_mb})\n'
        f'          </a>\n'
        f'          <a id="all-releases-btn" class="btn btn-secondary" href="{release_url}" target="_blank" rel="noopener">\n'
        f'            Release {tag} Notes\n'
        f'          </a>'
    )
    stats_block = (
        f'        <div class="stat-item"><span>Latest Version</span><strong id="meta-version">{tag}</strong></div>\n'
        f'        <div class="stat-item"><span>APK File</span><strong id="meta-filename">helicopter-war.apk</strong></div>\n'
        f'        <div class="stat-item"><span>File Size</span><strong id="meta-size">{apk_mb}</strong></div>\n'
        f'        <div class="stat-item"><span>Release Date</span><strong id="meta-date">{release_date}</strong></div>'
    )

    for html_path in (Path("index.html"), Path("hosting/index.html")):
        if html_path.is_file():
            html = html_path.read_text(encoding="utf-8")
            html = replace_block(html, "RELEASE_BUTTONS", html_block)
            html = replace_block(html, "RELEASE_STATS", stats_block)
            html = re.sub(
                r'"softwareVersion":\s*"[^"]*"',
                f'"softwareVersion": "{version_clean}"',
                html,
            )
            html = re.sub(
                r'"datePublished":\s*"[^"]*"',
                f'"datePublished": "{release_date}"',
                html,
            )
            html = re.sub(
                r'"fileSize":\s*"[^"]*"',
                f'"fileSize": "{apk_mb}"',
                html,
            )
            html = re.sub(
                r'"downloadUrl":\s*"[^"]*"',
                f'"downloadUrl": "{download_url}"',
                html,
            )
            html_path.write_text(html, encoding="utf-8")

    for sm_path in (Path("sitemap.xml"), Path("hosting/sitemap.xml")):
        if sm_path.is_file():
            sm = sm_path.read_text(encoding="utf-8")
            sm = re.sub(
                r"<lastmod>\d{4}-\d{2}-\d{2}</lastmod>",
                f"<lastmod>{release_date}</lastmod>",
                sm,
            )
            sm_path.write_text(sm, encoding="utf-8")

    print(f"Updated release metadata for {tag} ({apk_mb}) on {release_date}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
