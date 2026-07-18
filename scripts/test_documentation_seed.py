#!/usr/bin/env python3
"""Verifies security boundaries owned by the structured documentation seed reader."""

from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest

DOCUMENTATION_SEED_SCRIPT_DIRECTORY = pathlib.Path(__file__).resolve().parent
DOCUMENTATION_SEED_SCRIPT_PATH = DOCUMENTATION_SEED_SCRIPT_DIRECTORY / "documentation_seed.py"
INVALID_REMOTE_URLS_FIXTURE_PATH = (
    DOCUMENTATION_SEED_SCRIPT_DIRECTORY
    / "testdata"
    / "documentation_seed"
    / "invalid-remote-urls.txt"
)

DOCUMENTATION_SEED_SPECIFICATION = importlib.util.spec_from_file_location(
    "documentation_seed", DOCUMENTATION_SEED_SCRIPT_PATH
)
if DOCUMENTATION_SEED_SPECIFICATION is None or DOCUMENTATION_SEED_SPECIFICATION.loader is None:
    raise ImportError(f"Cannot load documentation seed script: {DOCUMENTATION_SEED_SCRIPT_PATH}")
documentation_seed = importlib.util.module_from_spec(DOCUMENTATION_SEED_SPECIFICATION)
DOCUMENTATION_SEED_SPECIFICATION.loader.exec_module(documentation_seed)


class DocumentationSeedSecurityTest(unittest.TestCase):
    """Verifies URL authority and XML input bounds before seed projection."""

    def test_rejects_shared_invalid_remote_url_fixtures(self) -> None:
        invalid_remote_urls = INVALID_REMOTE_URLS_FIXTURE_PATH.read_text(encoding="utf-8").splitlines()

        for invalid_remote_url in invalid_remote_urls:
            with self.subTest(invalid_remote_url=invalid_remote_url):
                with self.assertRaises(ValueError):
                    documentation_seed.require_remote_url(
                        invalid_remote_url,
                        allow_http=False,
                        require_trailing_slash=False,
                    )

    def test_accepts_valid_remote_authorities(self) -> None:
        valid_remote_urls = (
            "https://example.invalid:1/reference/",
            "https://example.invalid:65535/reference/",
            "https://[2001:db8::1]/reference/",
            "https://[2001:db8::1]:1/reference/",
            "https://[2001:db8::1]:65535/reference/",
        )

        for valid_remote_url in valid_remote_urls:
            with self.subTest(valid_remote_url=valid_remote_url):
                documentation_seed.require_remote_url(
                    valid_remote_url,
                    allow_http=False,
                    require_trailing_slash=True,
                )

    def test_rejects_ascii_control_characters_in_remote_urls(self) -> None:
        invalid_control_characters = ("\x01", "\x7f")

        for invalid_control_character in invalid_control_characters:
            invalid_remote_url = (
                "https://example.invalid/ref"
                + invalid_control_character
                + "erence/"
            )
            with self.subTest(invalid_remote_url=invalid_remote_url):
                with self.assertRaises(ValueError):
                    documentation_seed.require_remote_url(
                        invalid_remote_url,
                        allow_http=False,
                        require_trailing_slash=True,
                    )

    def test_rejects_ascii_control_characters_in_discovered_paths(self) -> None:
        invalid_discovered_urls = (
            "https://docs.example.invalid/reference/ref\x01erence.html",
            "https://docs.example.invalid/reference/ref%7Ference.html",
        )

        for invalid_discovered_url in invalid_discovered_urls:
            with self.subTest(invalid_discovered_url=invalid_discovered_url):
                with self.assertRaises(ValueError):
                    documentation_seed.build_seed_urls(
                        [invalid_discovered_url],
                        "https://docs.example.invalid/navigation.html",
                        "https://docs.example.invalid/reference/",
                        "https://docs.example.invalid/reference/",
                    )

    def test_rejects_xml_sitemap_above_protocol_byte_limit(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory_name:
            oversized_sitemap_path = pathlib.Path(temporary_directory_name) / "oversized-sitemap.xml"
            with oversized_sitemap_path.open("wb") as oversized_sitemap_stream:
                oversized_sitemap_stream.truncate(documentation_seed.MAXIMUM_XML_SITEMAP_BYTES + 1)

            with self.assertRaisesRegex(ValueError, "XML sitemap exceeds"):
                documentation_seed.read_xml_sitemap_urls(oversized_sitemap_path)


if __name__ == "__main__":
    unittest.main()
