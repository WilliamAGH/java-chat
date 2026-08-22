#!/usr/bin/env python3
"""
Generates a deterministic URL seed list for standard Javadoc sites.

Why this exists:
- Javadoc sites are large and recursive crawls can stall or miss portions of the graph.
- The Javadoc search index files contain an authoritative list of packages/types.
- Seeding Wget2 with explicit URLs makes repeated runs incremental and complete.
"""

from __future__ import annotations

import argparse
import html
import html.parser
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


class JavadocLinkParser(html.parser.HTMLParser):
    """Collects Javadoc anchor targets without interpreting HTML through regular expressions."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.link_targets: list[str] = []

    def handle_starttag(self, tag: str, attributes: list[tuple[str, str | None]]) -> None:
        if tag.casefold() != "a":
            return
        for attribute_name, attribute_text in attributes:
            if attribute_name.casefold() == "href" and attribute_text is not None:
                self.link_targets.append(attribute_text)


def parse_link_targets(index_html: str) -> list[str]:
    """Returns anchor targets from one Javadoc page."""
    link_parser = JavadocLinkParser()
    link_parser.feed(index_html)
    link_parser.close()
    return link_parser.link_targets


def fetch_text(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("http", "https"):
        raise ValueError(f"Unsupported URL scheme: {parsed.scheme!r}")
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


def is_java_package_name(package_name: object) -> bool:
    return isinstance(package_name, str) and re.fullmatch(
        r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*", package_name
    ) is not None


def url_join(base_url: str, *parts: str) -> str:
    path = "/".join(part.strip("/") for part in parts if part is not None)
    return urllib.parse.urljoin(base_url, path + ("" if path.endswith(".html") else ""))


def encode_url(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    encoded_path = urllib.parse.quote(parsed.path, safe="/-._~")
    encoded_query = urllib.parse.quote_plus(parsed.query, safe="=&")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, encoded_path, encoded_query, parsed.fragment))


def parse_index_files(index_1_html: str) -> set[str]:
    return {
        link_target
        for link_target in parse_link_targets(index_1_html)
        if link_target.startswith("index-")
        and link_target.removeprefix("index-").removesuffix(".html").isdigit()
        and link_target.endswith(".html")
    }


def root_pages_for_release(base_url: str) -> tuple[str, ...]:
    """Returns only root pages published by the standard doclet for the requested release."""
    common_root_pages = (
        "index.html",
        "overview-tree.html",
        "preview-list.html",
        "new-list.html",
        "deprecated-list.html",
        "search.html",
        "help-doc.html",
        "allpackages-index.html",
        "allclasses-index.html",
        "constant-values.html",
        "serialized-form.html",
        "system-properties.html",
    )
    release_match = re.search(r"/java/javase/([0-9]+)/docs/api/?$", urllib.parse.urlsplit(base_url).path)
    if release_match is None or int(release_match.group(1)) < 22:
        return common_root_pages
    return common_root_pages + ("external-specs.html", "restricted-list.html", "search-tags.html")


def root_pages_from_index(index_html: str) -> tuple[str, ...]:
    """Returns root HTML pages linked by a non-JDK standard-doclet index."""
    root_pages = {
        html.unescape(link_target).split("#", 1)[0]
        for link_target in parse_link_targets(index_html)
        if html.unescape(link_target).split("#", 1)[0].endswith(".html")
        and "/" not in html.unescape(link_target).split("#", 1)[0]
    }
    root_pages.add("index.html")
    return tuple(sorted(root_pages))


def generate_seed_urls(base_url: str) -> list[str]:
    normalized_base = base_url if base_url.endswith("/") else base_url + "/"
    index_urls = JavadocIndexUrls(normalized_base)

    package_js = fetch_text(index_urls.package_index())
    type_js = fetch_text(index_urls.type_index())
    index_1_html = fetch_text(index_urls.index_file_1())

    package_index = parse_assigned_json_array(package_js, "packageSearchIndex")
    type_index = parse_assigned_json_array(type_js, "typeSearchIndex")

    package_to_module = build_package_to_module(package_index)
    packages = {
        package_entry["l"]
        for package_entry in package_index
        if is_java_package_name(package_entry.get("l"))
    }
    modules = sorted(set(package_to_module.values()))
    print(
        f"parsed: modules={len(modules)} packages={len(packages)} types={len(type_index)}",
        file=sys.stderr,
    )

    urls: set[str] = set()

    # Root pages vary with the standard-doclet release. Legacy allclasses.html is
    # not emitted by the modern releases governed by this repository.
    oracle_release_match = re.search(
        r"/java/javase/([0-9]+)/docs/api/?$", urllib.parse.urlsplit(normalized_base).path
    )
    if oracle_release_match is None:
        root_index_html = fetch_text(urllib.parse.urljoin(normalized_base, "index.html"))
        root_pages = root_pages_from_index(root_index_html)
    else:
        root_pages = root_pages_for_release(normalized_base)
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
    for package in packages:
        module = package_to_module.get(package)
        package_dir = f"{module}/{package_path(package)}" if module else package_path(package)
        urls.add(urllib.parse.urljoin(normalized_base, f"{package_dir}/package-summary.html"))
        urls.add(urllib.parse.urljoin(normalized_base, f"{package_dir}/package-tree.html"))
        urls.add(urllib.parse.urljoin(normalized_base, f"{package_dir}/package-use.html"))

    # Type pages.
    missing_packages = 0
    for entry in type_index:
        package = entry.get("p")
        type_name = entry.get("l")
        if not isinstance(package, str) or not isinstance(type_name, str) or not package or not type_name:
            continue
        if package not in packages:
            missing_packages += 1
            continue
        module = package_to_module.get(package)
        type_file = f"{type_name}.html"
        type_dir = f"{module}/{package_path(package)}" if module else package_path(package)
        urls.add(urllib.parse.urljoin(normalized_base, f"{type_dir}/{type_file}"))

    if missing_packages > 0:
        # Keep output deterministic but surface the warning on stderr.
        print(
            f"warning: {missing_packages} types had packages not found in packageSearchIndex",
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
