#!/usr/bin/env python3
"""Builds deterministic documentation seeds from manifest-selected structured discovery files."""

from __future__ import annotations

import argparse
from collections.abc import Callable
import html.parser
import ipaddress
import pathlib
import re
import sys
import urllib.parse
import xml.etree.ElementTree as element_tree


DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG = (
    pathlib.Path(__file__).resolve().parent.parent
    / "src"
    / "main"
    / "resources"
    / "documentation-seed-document-types.manifest"
)
REMOTE_URL_MINIMUM_PORT = 1
REMOTE_URL_MAXIMUM_PORT = 65535
DOCUMENTATION_URL_ASCII_CONTROL_MAXIMUM = 0x1F
DOCUMENTATION_URL_ASCII_DELETE_CHARACTER = 0x7F
# Per https://www.sitemaps.org/protocol.html, an uncompressed sitemap is capped at 52,428,800 bytes.
MAXIMUM_XML_SITEMAP_BYTES = 52_428_800


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


class SitemapDoctypeRejectingTreeBuilder(element_tree.TreeBuilder):
    """Rejects sitemap document types before declared entities can enter a parsed tree."""

    def doctype(
        self,
        _document_type_name: str,
        _public_identifier: str | None,
        _system_identifier: str | None,
    ) -> None:
        raise ValueError("XML sitemap must not declare a DOCTYPE")


def read_xml_sitemap_urls(discovery_file: pathlib.Path) -> list[str]:
    """Reads a protocol-bounded sitemap with no document type declaration."""
    if discovery_file.stat().st_size > MAXIMUM_XML_SITEMAP_BYTES:
        raise ValueError(
            f"XML sitemap exceeds {MAXIMUM_XML_SITEMAP_BYTES} bytes: {discovery_file}"
        )
    with discovery_file.open("rb") as sitemap_stream:
        sitemap_xml_bytes = sitemap_stream.read(MAXIMUM_XML_SITEMAP_BYTES + 1)
    if len(sitemap_xml_bytes) > MAXIMUM_XML_SITEMAP_BYTES:
        raise ValueError(
            f"XML sitemap exceeds {MAXIMUM_XML_SITEMAP_BYTES} bytes: {discovery_file}"
        )
    sitemap_parser = element_tree.XMLParser(target=SitemapDoctypeRejectingTreeBuilder())
    sitemap_root = element_tree.fromstring(sitemap_xml_bytes, parser=sitemap_parser)
    discovered_urls: list[str] = []
    for location_element in sitemap_root.iter():
        element_name = location_element.tag.rsplit("}", 1)[-1] if isinstance(location_element.tag, str) else ""
        if element_name == "loc" and location_element.text is not None:
            discovered_urls.append(location_element.text.strip())
    return discovered_urls


def read_html_links_urls(discovery_file: pathlib.Path) -> list[str]:
    """Reads anchor targets from a tolerant structured HTML parser."""
    link_parser = DocumentationLinkParser()
    link_parser.feed(discovery_file.read_text(encoding="utf-8"))
    link_parser.close()
    return link_parser.discovered_urls


def load_seed_document_types(document_type_catalog: pathlib.Path | None = None) -> tuple[str, ...]:
    """Loads the manifest-owned document types that select implemented discovery readers."""
    catalog_path = document_type_catalog or DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG
    seed_document_types = tuple(
        catalog_path.read_text(encoding="utf-8").splitlines()
    )
    if not seed_document_types:
        raise ValueError("Documentation seed document type catalog has no records")
    if any(not is_canonical_seed_document_type(seed_document_type) for seed_document_type in seed_document_types):
        raise ValueError("Documentation seed document type catalog has invalid records")
    if len(set(seed_document_types)) != len(seed_document_types):
        raise ValueError("Documentation seed document type catalog has duplicate records")
    for seed_document_type in seed_document_types:
        seed_document_reader(seed_document_type)
    return seed_document_types


def is_canonical_seed_document_type(seed_document_type: str) -> bool:
    """Accepts only lower-case ASCII words separated by single hyphens."""
    seed_document_type_segments = seed_document_type.split("-")
    return bool(seed_document_type_segments) and all(
        seed_document_type_segment
        and all(character.isascii() and (character.islower() or character.isdigit())
                for character in seed_document_type_segment)
        for seed_document_type_segment in seed_document_type_segments
    )


def seed_document_reader(seed_document_type: str) -> Callable[[pathlib.Path], list[str]]:
    """Resolves the catalog token to its convention-named structured reader."""
    reader_name = f"read_{seed_document_type.replace('-', '_')}_urls"
    seed_reader = globals().get(reader_name)
    if not callable(seed_reader):
        raise ValueError(f"Documentation seed document type has no reader: {seed_document_type}")
    return seed_reader


def seed_url_to_mirror_path(seed_url: str, cut_directories: int) -> str:
    """Projects a canonical HTML URL onto wget's no-host, cut-directory mirror path."""
    require_remote_url(seed_url, allow_http=False, require_trailing_slash=False)
    parsed_seed_url = urllib.parse.urlsplit(seed_url)
    decoded_path_segments = [
        urllib.parse.unquote(path_segment)
        for path_segment in parsed_seed_url.path.removeprefix("/").split("/")
    ]
    if parsed_seed_url.path.endswith("/"):
        decoded_path_segments[-1] = "index.html"
    if len(decoded_path_segments) <= cut_directories:
        raise ValueError(f"Seed URL has fewer path segments than --cut-directories: {seed_url}")
    mirror_path_segments = decoded_path_segments[cut_directories:]
    if any(
        not path_segment
        or path_segment in (".", "..")
        or "/" in path_segment
        or "\\" in path_segment
        or any(character.isspace() for character in path_segment)
        or has_ascii_control_character(path_segment)
        for path_segment in mirror_path_segments
    ):
        raise ValueError(f"Seed URL has an unsafe mirror path: {seed_url}")
    if not mirror_path_segments[-1].lower().endswith((".html", ".htm")):
        mirror_path_segments[-1] += ".html"
    return "/".join(mirror_path_segments)


def require_remote_url(remote_url: str, allow_http: bool, require_trailing_slash: bool) -> None:
    """Rejects remote URLs that carry credentials or non-path request components."""
    if (
        any(character.isspace() for character in remote_url)
        or has_ascii_control_character(remote_url)
        or "\\" in remote_url
        or "?" in remote_url
        or "#" in remote_url
    ):
        raise ValueError(f"Invalid remote URL: {remote_url}")
    try:
        parsed_remote_url = urllib.parse.urlsplit(remote_url)
        parsed_port = parsed_remote_url.port
    except ValueError as invalid_remote_url:
        raise ValueError(f"Invalid remote URL: {remote_url}") from invalid_remote_url
    allowed_schemes = {"https", "http"} if allow_http else {"https"}
    if (
        parsed_remote_url.scheme not in allowed_schemes
        or parsed_remote_url.hostname is None
        or parsed_remote_url.username is not None
        or parsed_remote_url.password is not None
        or not has_valid_remote_authority(parsed_remote_url)
        or (
            parsed_port is not None
            and not REMOTE_URL_MINIMUM_PORT <= parsed_port <= REMOTE_URL_MAXIMUM_PORT
        )
        or (require_trailing_slash and not parsed_remote_url.path.endswith("/"))
    ):
        raise ValueError(f"Invalid remote URL: {remote_url}")


def has_ascii_control_character(documentation_url_text: str) -> bool:
    """Detects the shared C0 and DEL controls prohibited by manifest consumers."""
    return any(
        ord(remote_character) <= DOCUMENTATION_URL_ASCII_CONTROL_MAXIMUM
        or ord(remote_character) == DOCUMENTATION_URL_ASCII_DELETE_CHARACTER
        for remote_character in documentation_url_text
    )


def has_valid_remote_authority(parsed_remote_url: urllib.parse.SplitResult) -> bool:
    """Accepts a DNS host or bracketed IPv6 literal with an optional non-empty port."""
    remote_hostname = parsed_remote_url.hostname
    if remote_hostname is None:
        return False

    remote_authority = parsed_remote_url.netloc
    if remote_authority.startswith("["):
        closing_bracket_index = remote_authority.find("]")
        if closing_bracket_index < 0:
            return False
        remote_port_suffix = remote_authority[closing_bracket_index + 1 :]
        if remote_port_suffix and (
            not remote_port_suffix.startswith(":") or not remote_port_suffix.removeprefix(":")
        ):
            return False
        try:
            ipaddress.IPv6Address(remote_hostname)
        except ipaddress.AddressValueError:
            return False
        return True

    if "[" in remote_authority or "]" in remote_authority:
        return False
    if remote_authority.count(":") > 1 or remote_authority.endswith(":"):
        return False
    return has_valid_remote_dns_labels(remote_hostname)


def has_valid_remote_dns_labels(remote_hostname: str) -> bool:
    """Accepts non-empty ASCII DNS labels that begin and end with an alphanumeric character."""
    remote_dns_labels = remote_hostname.split(".")
    return all(
        remote_dns_label
        and remote_dns_label[0] != "-"
        and remote_dns_label[-1] != "-"
        and all(
            remote_dns_character.isascii()
            and (remote_dns_character.isalnum() or remote_dns_character == "-")
            for remote_dns_character in remote_dns_label
        )
        for remote_dns_label in remote_dns_labels
    )


def require_url_prefix(url_prefix: str, allow_http: bool) -> None:
    """Rejects unsafe or non-base URL prefixes before canonical mapping."""
    require_remote_url(url_prefix, allow_http=allow_http, require_trailing_slash=True)


def require_discovery_url(discovery_url: str) -> None:
    """Rejects discovery origins that cannot safely resolve relative links."""
    require_remote_url(discovery_url, allow_http=False, require_trailing_slash=False)


def build_seed_urls(
    discovered_urls: list[str], discovery_url: str, source_prefix: str, canonical_prefix: str
) -> list[str]:
    """Maps only exact-prefix discovery URLs onto the canonical prefix."""
    require_discovery_url(discovery_url)
    require_url_prefix(source_prefix, allow_http=True)
    require_url_prefix(canonical_prefix, allow_http=False)
    seed_urls: set[str] = set()
    for discovered_url in discovered_urls:
        if (
            any(character.isspace() for character in discovered_url)
            or has_ascii_control_character(discovered_url)
        ):
            raise ValueError(f"Discovery URL contains prohibited characters: {discovered_url!r}")
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
        if (
            any(character.isspace() for character in decoded_source_path)
            or has_ascii_control_character(decoded_source_path)
        ):
            raise ValueError(f"Unsafe discovery URL path: {discovered_url}")
        source_path_segments = decoded_source_path.split("/")
        if any(path_segment in (".", "..") for path_segment in source_path_segments):
            raise ValueError(f"Unsafe discovery URL path: {discovered_url}")
        seed_urls.add(canonical_prefix + source_suffix)
    if not seed_urls:
        raise ValueError(f"Discovery produced no URLs beneath exact prefix {source_prefix}")
    return sorted(seed_urls)


def validate_remote_url_command(command_arguments: list[str]) -> int:
    """Validates one manifest URL for shell consumers without duplicating URL grammar."""
    validation_parser = argparse.ArgumentParser()
    validation_parser.add_argument("remote_url")
    validation_parser.add_argument("--allow-http", action="store_true")
    validation_parser.add_argument("--require-trailing-slash", action="store_true")
    validation_arguments = validation_parser.parse_args(command_arguments)
    require_remote_url(
        validation_arguments.remote_url,
        allow_http=validation_arguments.allow_http,
        require_trailing_slash=validation_arguments.require_trailing_slash,
    )
    return 0


def validate_seed_document_types_command(command_arguments: list[str]) -> int:
    """Validates the canonical seed document type catalog for non-Python consumers."""
    validation_parser = argparse.ArgumentParser()
    validation_parser.add_argument("--catalog", type=pathlib.Path, default=DOCUMENTATION_SEED_DOCUMENT_TYPE_CATALOG)
    validation_arguments = validation_parser.parse_args(command_arguments)
    load_seed_document_types(validation_arguments.catalog)
    return 0


def project_mirror_path_command(command_arguments: list[str]) -> int:
    """Projects one canonical seed URL onto its GNU Wget mirror path."""
    projection_parser = argparse.ArgumentParser()
    projection_parser.add_argument("seed_url")
    projection_parser.add_argument("cut_directories", type=int)
    projection_arguments = projection_parser.parse_args(command_arguments)
    if projection_arguments.cut_directories < 0:
        projection_parser.error("cut_directories cannot be negative")
    print(seed_url_to_mirror_path(projection_arguments.seed_url, projection_arguments.cut_directories))
    return 0


def main() -> int:
    if sys.argv[1:2] == ["--validate-seed-document-types"]:
        return validate_seed_document_types_command(sys.argv[2:])
    if sys.argv[1:2] == ["--validate-remote-url"]:
        return validate_remote_url_command(sys.argv[2:])
    if sys.argv[1:2] == ["--project-mirror-path"]:
        return project_mirror_path_command(sys.argv[2:])
    seed_document_types = load_seed_document_types()
    argument_parser = argparse.ArgumentParser()
    argument_parser.add_argument("--document-type", required=True, choices=seed_document_types)
    argument_parser.add_argument("--input", required=True, type=pathlib.Path)
    argument_parser.add_argument("--discovery-url", required=True)
    argument_parser.add_argument("--source-prefix", required=True)
    argument_parser.add_argument("--canonical-prefix", required=True)
    argument_parser.add_argument("--reject-regex", default="")
    argument_parser.add_argument("--output", required=True, type=pathlib.Path)
    argument_parser.add_argument("--mirror-path-output", required=True, type=pathlib.Path)
    argument_parser.add_argument("--cut-directories", required=True, type=int)
    parsed_arguments = argument_parser.parse_args()
    if parsed_arguments.cut_directories < 0:
        argument_parser.error("--cut-directories cannot be negative")

    discovered_urls = seed_document_reader(parsed_arguments.document_type)(parsed_arguments.input)
    seed_urls = build_seed_urls(
        discovered_urls,
        parsed_arguments.discovery_url,
        parsed_arguments.source_prefix,
        parsed_arguments.canonical_prefix,
    )
    if parsed_arguments.reject_regex:
        try:
            rejected_seed_url = re.compile(parsed_arguments.reject_regex)
        except re.error as invalid_reject_regex:
            raise ValueError("Documentation seed reject regex is invalid") from invalid_reject_regex
        seed_urls = [seed_url for seed_url in seed_urls if rejected_seed_url.search(seed_url) is None]
        if not seed_urls:
            raise ValueError("Documentation seed reject regex removed every discovered URL")
    mirror_paths = [
        seed_url_to_mirror_path(seed_url, parsed_arguments.cut_directories) for seed_url in seed_urls
    ]
    if len(set(mirror_paths)) != len(mirror_paths):
        raise ValueError("Structured discovery maps multiple URLs onto one mirror path")
    parsed_arguments.output.write_text("".join(f"{seed_url}\n" for seed_url in seed_urls), encoding="utf-8")
    parsed_arguments.mirror_path_output.write_text(
        "".join(f"{mirror_path}\n" for mirror_path in mirror_paths), encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
