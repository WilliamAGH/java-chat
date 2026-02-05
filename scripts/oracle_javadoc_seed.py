#!/usr/bin/env python3
"""
Generates a deterministic URL seed list for Oracle Javadoc sites (Java SE / JDK API docs).

Why this exists:
- Oracle Javadoc is large and recursive crawls can stall or miss portions of the graph.
- The Javadoc search index files contain an authoritative list of packages/types.
- Seeding wget with explicit URLs makes repeated runs incremental and complete.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class JavadocIndexUrls:
    base_url: str

    def package_index(self) -> str:
        return urllib.parse.urljoin(self.base_url, "package-search-index.js")

    def type_index(self) -> str:
        return urllib.parse.urljoin(self.base_url, "type-search-index.js")

    def index_file_1(self) -> str:
        return urllib.parse.urljoin(self.base_url, "index-files/index-1.html")


def fetch_text(url: str) -> str:
    print(f"fetch: {url}", file=sys.stderr)
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "java-chat-doc-fetcher/1.0 (+https://localhost)",
            "Accept": "text/plain,text/html,application/javascript,*/*;q=0.8",
        },
        method="GET",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        raw = response.read()
    return raw.decode("utf-8", errors="replace")


def parse_assigned_json_array(js_text: str, var_name: str) -> list[dict[str, Any]]:
    # Example: packageSearchIndex = [{...}, {...}];updateSearchResults();
    pattern = re.compile(rf"^\s*{re.escape(var_name)}\s*=\s*(\[\s*.*\s*\])\s*;.*$", re.DOTALL)
    match = pattern.match(js_text.strip())
    if not match:
        snippet = js_text.strip()[:200].replace("\n", "\\n")
        raise ValueError(f"Unexpected {var_name} format. Head: {snippet}")
    return json.loads(match.group(1))


def build_package_to_module(package_index: list[dict[str, Any]]) -> dict[str, str]:
    mapping: dict[str, str] = {}
    for entry in package_index:
        module = entry.get("m")
        package = entry.get("l")
        if isinstance(module, str) and isinstance(package, str) and module and package:
            mapping[package] = module
    return mapping


def package_path(package_name: str) -> str:
    return package_name.replace(".", "/")


def url_join(base_url: str, *parts: str) -> str:
    path = "/".join(part.strip("/") for part in parts if part is not None)
    return urllib.parse.urljoin(base_url, path + ("" if path.endswith(".html") else ""))


def encode_url(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    encoded_path = urllib.parse.quote(parsed.path, safe="/-._~")
    encoded_query = urllib.parse.quote_plus(parsed.query, safe="=&")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, encoded_path, encoded_query, parsed.fragment))


def parse_index_files(index_1_html: str) -> set[str]:
    # Collect all "index-N.html" references from index-1.
    matches = set(re.findall(r'href="(index-[0-9]+\\.html)"', index_1_html))
    return {m for m in matches if m.startswith("index-") and m.endswith(".html")}


def generate_seed_urls(base_url: str) -> list[str]:
    normalized_base = base_url if base_url.endswith("/") else base_url + "/"
    index_urls = JavadocIndexUrls(normalized_base)

    package_js = fetch_text(index_urls.package_index())
    type_js = fetch_text(index_urls.type_index())
    index_1_html = fetch_text(index_urls.index_file_1())

    package_index = parse_assigned_json_array(package_js, "packageSearchIndex")
    type_index = parse_assigned_json_array(type_js, "typeSearchIndex")

    package_to_module = build_package_to_module(package_index)
    modules = sorted(set(package_to_module.values()))
    print(
        f"parsed: modules={len(modules)} packages={len(package_to_module)} types={len(type_index)}",
        file=sys.stderr,
    )

    urls: set[str] = set()

    # Root pages (small but useful, and they link to assets).
    root_pages = [
        "index.html",
        "overview-tree.html",
        "preview-list.html",
        "new-list.html",
        "deprecated-list.html",
        "search.html",
        "help-doc.html",
        "allpackages-index.html",
        "allclasses-index.html",
        "allclasses.html",
        "constant-values.html",
        "serialized-form.html",
        "external-specs.html",
        "restricted-list.html",
        "search-tags.html",
        "system-properties.html",
    ]
    for page in root_pages:
        urls.add(urllib.parse.urljoin(normalized_base, page))

    # Index pages (A..Z.._).
    index_files = parse_index_files(index_1_html)
    index_files.add("index-1.html")
    for idx in sorted(index_files):
        urls.add(urllib.parse.urljoin(normalized_base, "index-files/" + idx))

    # Module summary pages.
    for module in modules:
        urls.add(urllib.parse.urljoin(normalized_base, f"{module}/module-summary.html"))

    # Package pages + trees.
    for package, module in package_to_module.items():
        package_dir = f"{module}/{package_path(package)}"
        urls.add(urllib.parse.urljoin(normalized_base, f"{package_dir}/package-summary.html"))
        urls.add(urllib.parse.urljoin(normalized_base, f"{package_dir}/package-tree.html"))
        urls.add(urllib.parse.urljoin(normalized_base, f"{package_dir}/package-use.html"))

    # Type pages.
    missing_modules = 0
    for entry in type_index:
        package = entry.get("p")
        type_name = entry.get("l")
        if not isinstance(package, str) or not isinstance(type_name, str) or not package or not type_name:
            continue
        module = package_to_module.get(package)
        if not module:
            missing_modules += 1
            continue
        type_file = f"{type_name}.html"
        type_dir = f"{module}/{package_path(package)}"
        urls.add(urllib.parse.urljoin(normalized_base, f"{type_dir}/{type_file}"))
        urls.add(urllib.parse.urljoin(normalized_base, f"{type_dir}/class-use/{type_file}"))

    if missing_modules > 0:
        # Keep output deterministic but surface the warning on stderr.
        print(
            f"warning: {missing_modules} types had packages not found in packageSearchIndex",
            file=sys.stderr,
        )

    encoded = [encode_url(u) for u in sorted(urls)]
    return encoded


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True, help="Javadoc base URL, e.g. .../docs/api/")
    parser.add_argument("--output", required=True, help="Output path for seed URLs (one per line)")
    args = parser.parse_args()

    urls = generate_seed_urls(args.base_url)
    with open(args.output, "w", encoding="utf-8") as f:
        for url in urls:
            f.write(url)
            f.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
