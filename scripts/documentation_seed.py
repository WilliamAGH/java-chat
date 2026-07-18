#!/usr/bin/env python3
"""Builds deterministic documentation seeds from manifest-selected structured discovery files."""

from __future__ import annotations

import argparse
import html.parser
import pathlib
import urllib.parse
import xml.etree.ElementTree as element_tree


DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG = (
    pathlib.Path(__file__).resolve().parent.parent
    / "src"
    / "main"
    / "resources"
    / "documentation-seed-document-types.manifest"
)


class DocumentationLinkParser(html.parser.HTMLParser):
    """Collects hyperlink targets without interpreting HTML through regular expressions."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.discovered_urls: list[str] = []

    def handle_starttag(self, tag: str, attributes: list[tuple[str, str | None]]) -> None:
        if tag.casefold() != "a":
            return
        for attribute_name, attribute_text in attributes:
            if attribute_name.casefold() == "href" and attribute_text is not None:
                self.discovered_urls.append(attribute_text)


def read_sitemap_urls(discovery_file: pathlib.Path) -> list[str]:
    """Reads URL locations from a namespace-aware XML tree."""
    sitemap_tree = element_tree.parse(discovery_file)
    discovered_urls: list[str] = []
    for location_element in sitemap_tree.iter():
        element_name = location_element.tag.rsplit("}", 1)[-1] if isinstance(location_element.tag, str) else ""
        if element_name == "loc" and location_element.text is not None:
            discovered_urls.append(location_element.text.strip())
    return discovered_urls


def read_html_urls(discovery_file: pathlib.Path) -> list[str]:
    """Reads anchor targets from a tolerant structured HTML parser."""
    link_parser = DocumentationLinkParser()
    link_parser.feed(discovery_file.read_text(encoding="utf-8"))
    link_parser.close()
    return link_parser.discovered_urls


SEED_DOCUMENT_TYPE_READERS = {
    "html-links": read_html_urls,
    "xml-sitemap": read_sitemap_urls,
}


def load_seed_document_types() -> tuple[str, ...]:
    """Loads the manifest-owned document types that select implemented discovery readers."""
    seed_document_types = tuple(
        DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG.read_text(encoding="utf-8").splitlines()
    )
    if not seed_document_types:
        raise ValueError("Documentation seed document type catalog has no records")
    if any(
        not seed_document_type or seed_document_type != seed_document_type.strip()
        for seed_document_type in seed_document_types
    ):
        raise ValueError("Documentation seed document type catalog has invalid records")
    if len(set(seed_document_types)) != len(seed_document_types):
        raise ValueError("Documentation seed document type catalog has duplicate records")
    if set(seed_document_types) != set(SEED_DOCUMENT_TYPE_READERS):
        raise ValueError("Documentation seed document type catalog must match implemented discovery readers")
    return seed_document_types


def require_url_prefix(url_prefix: str, allow_http: bool) -> None:
    """Rejects unsafe or non-base URL prefixes before canonical mapping."""
    parsed_prefix = urllib.parse.urlsplit(url_prefix)
    allowed_schemes = {"https", "http"} if allow_http else {"https"}
    if (
        parsed_prefix.scheme not in allowed_schemes
        or not parsed_prefix.netloc
        or not parsed_prefix.path.endswith("/")
        or parsed_prefix.query
        or parsed_prefix.fragment
    ):
        raise ValueError(f"Invalid URL prefix: {url_prefix}")


def require_discovery_url(discovery_url: str) -> None:
    """Rejects discovery origins that cannot safely resolve relative links."""
    parsed_discovery_url = urllib.parse.urlsplit(discovery_url)
    if (
        parsed_discovery_url.scheme != "https"
        or not parsed_discovery_url.netloc
        or parsed_discovery_url.query
        or parsed_discovery_url.fragment
    ):
        raise ValueError(f"Invalid discovery URL: {discovery_url}")


def build_seed_urls(
    discovered_urls: list[str], discovery_url: str, source_prefix: str, canonical_prefix: str
) -> list[str]:
    """Maps only exact-prefix discovery URLs onto the pinned canonical prefix."""
    require_discovery_url(discovery_url)
    require_url_prefix(source_prefix, allow_http=True)
    require_url_prefix(canonical_prefix, allow_http=False)
    seed_urls: set[str] = set()
    for discovered_url in discovered_urls:
        if any(character.isspace() for character in discovered_url):
            raise ValueError(f"Discovery URL contains whitespace: {discovered_url!r}")
        resolved_url = urllib.parse.urljoin(discovery_url, discovered_url)
        fragmentless_url, _fragment = urllib.parse.urldefrag(resolved_url)
        parsed_source_url = urllib.parse.urlsplit(fragmentless_url)
        queryless_source_url = urllib.parse.urlunsplit(
            (parsed_source_url.scheme, parsed_source_url.netloc, parsed_source_url.path, "", "")
        )
        if not queryless_source_url.startswith(source_prefix):
            continue
        source_suffix = queryless_source_url[len(source_prefix) :]
        decoded_source_path = urllib.parse.unquote(urllib.parse.urlsplit(source_suffix).path)
        if any(character.isspace() for character in decoded_source_path):
            raise ValueError(f"Unsafe discovery URL path: {discovered_url}")
        source_path_segments = decoded_source_path.split("/")
        if any(path_segment in (".", "..") for path_segment in source_path_segments):
            raise ValueError(f"Unsafe discovery URL path: {discovered_url}")
        seed_urls.add(canonical_prefix + source_suffix)
    if not seed_urls:
        raise ValueError(f"Discovery produced no URLs beneath exact prefix {source_prefix}")
    return sorted(seed_urls)


def main() -> int:
    seed_document_types = load_seed_document_types()
    argument_parser = argparse.ArgumentParser()
    argument_parser.add_argument("--document-type", required=True, choices=seed_document_types)
    argument_parser.add_argument("--input", required=True, type=pathlib.Path)
    argument_parser.add_argument("--discovery-url", required=True)
    argument_parser.add_argument("--source-prefix", required=True)
    argument_parser.add_argument("--canonical-prefix", required=True)
    argument_parser.add_argument("--output", required=True, type=pathlib.Path)
    parsed_arguments = argument_parser.parse_args()

    discovered_urls = SEED_DOCUMENT_TYPE_READERS[parsed_arguments.document_type](parsed_arguments.input)
    seed_urls = build_seed_urls(
        discovered_urls,
        parsed_arguments.discovery_url,
        parsed_arguments.source_prefix,
        parsed_arguments.canonical_prefix,
    )
    parsed_arguments.output.write_text("".join(f"{seed_url}\n" for seed_url in seed_urls), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
