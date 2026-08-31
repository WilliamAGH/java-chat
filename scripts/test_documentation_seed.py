#!/usr/bin/env python3
"""Verifies security boundaries owned by the structured documentation seed reader."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

DOCUMENTATION_SEED_SCRIPT_DIRECTORY = pathlib.Path(__file__).resolve().parent
DOCUMENTATION_SEED_SCRIPT_PATH = DOCUMENTATION_SEED_SCRIPT_DIRECTORY / "documentation_seed.py"
JAVADOC_SEED_SCRIPT_PATH = DOCUMENTATION_SEED_SCRIPT_DIRECTORY / "javadoc_seed.py"
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

JAVADOC_SEED_SPECIFICATION = importlib.util.spec_from_file_location(
    "javadoc_seed", JAVADOC_SEED_SCRIPT_PATH
)
if JAVADOC_SEED_SPECIFICATION is None or JAVADOC_SEED_SPECIFICATION.loader is None:
    raise ImportError(f"Cannot load Javadoc seed script: {JAVADOC_SEED_SCRIPT_PATH}")
javadoc_seed = importlib.util.module_from_spec(JAVADOC_SEED_SPECIFICATION)
sys.modules[JAVADOC_SEED_SPECIFICATION.name] = javadoc_seed
JAVADOC_SEED_SPECIFICATION.loader.exec_module(javadoc_seed)


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

    def test_prefers_canonical_directory_url_over_extensionless_alias(self) -> None:
        directory_url = "https://docs.example.invalid/reference/tutorial/"

        self.assertEqual(
            [directory_url],
            documentation_seed.prefer_directory_urls(
                [directory_url.removesuffix("/"), directory_url]
            ),
        )

    def test_projects_seed_file_in_one_canonical_batch(self) -> None:
        required_prefix = "https://docs.example.invalid/en/java/javase/25/docs/api/"
        with tempfile.TemporaryDirectory() as temporary_directory_name:
            temporary_directory = pathlib.Path(temporary_directory_name)
            seed_path = temporary_directory / "seed.txt"
            mirror_path = temporary_directory / "paths.txt"
            seed_path.write_text(
                required_prefix + "java.base/java/lang/String.html\n"
                + required_prefix
                + "java.base/java/util/List.html\n",
                encoding="utf-8",
            )

            exit_code = documentation_seed.project_mirror_paths_file_command(
                [
                    "--input",
                    str(seed_path),
                    "--output",
                    str(mirror_path),
                    "--required-prefix",
                    required_prefix,
                    "--cut-directories",
                    "5",
                ]
            )

            self.assertEqual(0, exit_code)
            self.assertEqual(
                "api/java.base/java/lang/String.html\napi/java.base/java/util/List.html\n",
                mirror_path.read_text(encoding="utf-8"),
            )

    def test_wraps_plain_text_as_escaped_html_without_parsing_markdown(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory_name:
            temporary_directory = pathlib.Path(temporary_directory_name)
            source_path = temporary_directory / "source.txt"
            wrapped_path = temporary_directory / "index.html"
            source_path.write_text("# API\n<script>alert('unsafe')</script>\n", encoding="utf-8")

            exit_code = documentation_seed.wrap_plain_text_html_command(
                [
                    "--input",
                    str(source_path),
                    "--output",
                    str(wrapped_path),
                    "--title",
                    "Porkbun API <v3.15>",
                ]
            )

            wrapped_html = wrapped_path.read_text(encoding="utf-8")
            self.assertEqual(0, exit_code)
            self.assertIn("<title>Porkbun API &lt;v3.15&gt;</title>", wrapped_html)
            self.assertIn("# API", wrapped_html)
            self.assertIn("&lt;script&gt;alert(&#x27;unsafe&#x27;)&lt;/script&gt;", wrapped_html)
            self.assertNotIn("<script>alert", wrapped_html)

    def test_rejects_oversized_plain_text_transport(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory_name:
            temporary_directory = pathlib.Path(temporary_directory_name)
            oversized_source_path = temporary_directory / "oversized.txt"
            wrapped_path = temporary_directory / "index.html"
            with oversized_source_path.open("wb") as oversized_source_stream:
                oversized_source_stream.truncate(
                    documentation_seed.MAXIMUM_PLAIN_TEXT_DOCUMENT_BYTES + 1
                )

            with self.assertRaisesRegex(ValueError, "transport limit"):
                documentation_seed.wrap_plain_text_html_command(
                    [
                        "--input",
                        str(oversized_source_path),
                        "--output",
                        str(wrapped_path),
                        "--title",
                        "Oversized",
                    ]
                )

    def test_plain_text_transport_fits_the_official_clerk_corpus(self) -> None:
        self.assertGreaterEqual(
            documentation_seed.MAXIMUM_PLAIN_TEXT_DOCUMENT_BYTES,
            27_775_297,
        )

    def test_matches_pcre_style_cloudflare_alias_regex(self) -> None:
        cloudflare_alias_regex = (
            r"^https://developers\.cloudflare\.com/"
            r"(?:ai/models/(?:@|%40)(?:cf|hf)/.*|ai-gateway/models/)$"
        )
        expected_status_by_candidate = {
            "https://developers.cloudflare.com/ai/models/@cf/meta/llama/": 0,
            "https://developers.cloudflare.com/ai/models/%40hf/example/": 0,
            "https://developers.cloudflare.com/ai-gateway/models/": 0,
            "https://developers.cloudflare.com/workers-ai/models/": 1,
        }

        for candidate_url, expected_status in expected_status_by_candidate.items():
            with self.subTest(candidate_url=candidate_url):
                self.assertEqual(
                    expected_status,
                    documentation_seed.matches_regex_command(
                        ["--regex", cloudflare_alias_regex, "--candidate", candidate_url]
                    ),
                )

    def test_rejects_invalid_portable_documentation_regex(self) -> None:
        with mock.patch("sys.stderr"):
            self.assertEqual(
                2,
                documentation_seed.matches_regex_command(
                    ["--regex", "(?invalid", "--candidate", "https://example.invalid/"]
                ),
            )

    def test_accepts_expected_single_shard_sitemap_index(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory_name:
            sitemap_index_path = pathlib.Path(temporary_directory_name) / "sitemap-index.xml"
            expected_sitemap_url = "https://docs.example.invalid/sitemap-0.xml"
            sitemap_index_path.write_text(
                '<?xml version="1.0"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                f"<sitemap><loc>{expected_sitemap_url}</loc></sitemap></sitemapindex>",
                encoding="utf-8",
            )

            exit_code = documentation_seed.validate_sitemap_index_command(
                [
                    "--input",
                    str(sitemap_index_path),
                    "--expected-sitemap-url",
                    expected_sitemap_url,
                ]
            )

            self.assertEqual(0, exit_code)

    def test_rejects_changed_sitemap_index_topology(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory_name:
            sitemap_index_path = pathlib.Path(temporary_directory_name) / "sitemap-index.xml"
            expected_sitemap_url = "https://docs.example.invalid/sitemap-0.xml"
            sitemap_index_path.write_text(
                '<?xml version="1.0"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                f"<sitemap><loc>{expected_sitemap_url}</loc></sitemap>"
                '<sitemap><loc>https://docs.example.invalid/sitemap-1.xml</loc></sitemap>'
                "</sitemapindex>",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "topology changed"):
                documentation_seed.validate_sitemap_index_command(
                    [
                        "--input",
                        str(sitemap_index_path),
                        "--expected-sitemap-url",
                        expected_sitemap_url,
                    ]
                )


class JavadocSeedTest(unittest.TestCase):
    """Verifies Javadoc seed generation includes only canonical type pages."""

    def test_excludes_class_use_pages_from_type_seeds(self) -> None:
        base_url = "https://docs.example.invalid/jdk/api/"
        oracle_index_fixture_by_url = {
            base_url + "package-search-index.js": (
                'packageSearchIndex = [{"m":"java.base","l":"java.util"}];updateSearchResults();'
            ),
            base_url + "type-search-index.js": (
                'typeSearchIndex = [{"p":"java.util","l":"List"}];updateSearchResults();'
            ),
            base_url + "index-files/index-1.html": '<a href="index-2.html">2</a>',
            base_url + "index.html": '<a href="overview-tree.html">Tree</a>',
        }

        def fetch_oracle_index(index_url: str) -> str:
            return oracle_index_fixture_by_url[index_url]

        with mock.patch.object(javadoc_seed, "fetch_text", side_effect=fetch_oracle_index):
            seed_urls = javadoc_seed.generate_seed_urls(base_url)

        self.assertIn(base_url + "java.base/java/util/List.html", seed_urls)
        self.assertFalse(any("/class-use/" in seed_url for seed_url in seed_urls))

    def test_uses_only_root_pages_published_for_each_governed_release(self) -> None:
        for java_release in (21, 24, 25):
            base_url = f"https://docs.oracle.com/en/java/javase/{java_release}/docs/api/"
            oracle_index_fixture_by_url = {
                base_url + "package-search-index.js": (
                    'packageSearchIndex = [{"m":"java.base","l":"java.util"}];updateSearchResults();'
                ),
                base_url + "type-search-index.js": (
                    'typeSearchIndex = [{"p":"java.util","l":"List"}];updateSearchResults();'
                ),
                base_url + "index-files/index-1.html": "",
            }

            with self.subTest(java_release=java_release):
                with mock.patch.object(
                    javadoc_seed,
                    "fetch_text",
                    side_effect=oracle_index_fixture_by_url.__getitem__,
                ):
                    seed_urls = javadoc_seed.generate_seed_urls(base_url)

                self.assertNotIn(base_url + "allclasses.html", seed_urls)
                release_specific_root_pages = (
                    "external-specs.html",
                    "restricted-list.html",
                    "search-tags.html",
                )
                for root_page in release_specific_root_pages:
                    if java_release == 21:
                        self.assertNotIn(base_url + root_page, seed_urls)
                    else:
                        self.assertIn(base_url + root_page, seed_urls)


if __name__ == "__main__":
    unittest.main()
