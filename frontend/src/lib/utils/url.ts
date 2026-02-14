/**
 * Unified URL validation, sanitization, and classification utilities.
 *
 * Provides XSS-safe URL handling for citations and external links throughout
 * the application. All URL processing should use these functions to ensure
 * consistent security and display behavior.
 */

/** Citation type for styling and icon selection. */
export type CitationType = "pdf" | "api-doc" | "repo" | "external" | "local" | "unknown";

/** URL protocol constants. */
const URL_SCHEME_HTTP = "http://";
const URL_SCHEME_HTTPS = "https://";
const LOCAL_PATH_PREFIX = "/";
const ANCHOR_SEPARATOR = "#";
const PDF_EXTENSION = ".pdf";

/** Fallback value for invalid or dangerous URLs. */
export const FALLBACK_LINK_TARGET = "#";

/** Fallback label when URL cannot be parsed for display. */
export const FALLBACK_SOURCE_LABEL = "Source";

/** Safe URL schemes - only http and https are allowed for external links. */
const SAFE_URL_SCHEMES = ["http:", "https:"] as const;

/** Domain patterns that indicate API documentation sources. */
const API_DOC_PATTERNS = ["docs.oracle.com", "javadoc", "/api/", "/docs/api/"] as const;

/** Domain patterns that indicate repository sources. */
const REPO_PATTERNS = ["github.com", "gitlab.com", "bitbucket.org"] as const;

/**
 * Checks if URL starts with http:// or https://.
 */
export function isHttpUrl(url: string): boolean {
  return url.startsWith(URL_SCHEME_HTTP) || url.startsWith(URL_SCHEME_HTTPS);
}

/**
 * Checks if URL contains any of the given patterns (case-sensitive).
 */
export function matchesAnyPattern(url: string, patterns: readonly string[]): boolean {
  return patterns.some((pattern) => url.includes(pattern));
}

/**
 * Validates that a URL uses a safe scheme (http/https) or is a relative path.
 * Rejects dangerous schemes like javascript:, data:, vbscript:, etc.
 *
 * @param url - URL to sanitize
 * @returns The original URL if safe, or FALLBACK_LINK_TARGET for dangerous schemes
 */
export function sanitizeUrl(url: string | undefined | null): string {
  if (!url) return FALLBACK_LINK_TARGET;
  const trimmedUrl = url.trim();
  if (!trimmedUrl) return FALLBACK_LINK_TARGET;

  // Allow relative paths (start with / but not // which is protocol-relative)
  if (trimmedUrl.startsWith(LOCAL_PATH_PREFIX) && !trimmedUrl.startsWith("//")) {
    return trimmedUrl;
  }

  // Check for safe schemes via URL parsing
  try {
    const parsedUrl = new URL(trimmedUrl);
    const scheme = parsedUrl.protocol.toLowerCase();
    if (SAFE_URL_SCHEMES.some((safe) => scheme === safe)) {
      return trimmedUrl;
    }
    // Parsed successfully but has dangerous scheme (javascript:, data:, etc.)
    return FALLBACK_LINK_TARGET;
  } catch {
    // URL parsing failed - might be a relative path or malformed
    // Block protocol-relative URLs (//example.com) that inherit page scheme
    if (trimmedUrl.startsWith("//")) {
      return FALLBACK_LINK_TARGET;
    }
    // Only allow if it doesn't look like a dangerous scheme
    const lowerUrl = trimmedUrl.toLowerCase();
    if (lowerUrl.includes(":") && !isHttpUrl(lowerUrl)) {
      return FALLBACK_LINK_TARGET;
    }
    return trimmedUrl;
  }
}

/**
 * Determines citation type from URL for icon and styling.
 *
 * @param url - URL to classify
 * @returns CitationType for styling decisions
 */
export function getCitationType(url: string | undefined | null): CitationType {
  if (!url) return "unknown";
  const lowerUrl = url.toLowerCase();

  if (lowerUrl.endsWith(PDF_EXTENSION)) return "pdf";
  if (isHttpUrl(lowerUrl)) {
    if (matchesAnyPattern(lowerUrl, API_DOC_PATTERNS)) return "api-doc";
    if (matchesAnyPattern(lowerUrl, REPO_PATTERNS)) return "repo";
    return "external";
  }
  if (lowerUrl.startsWith(LOCAL_PATH_PREFIX)) return "local";
  return "unknown";
}

/**
 * Extracts display-friendly domain or filename from URL.
 *
 * @param url - URL to extract display text from
 * @returns Human-readable source label (domain, filename, or fallback)
 */
export function getDisplaySource(url: string | undefined | null): string {
  if (!url) return FALLBACK_SOURCE_LABEL;

  const lowerUrl = url.toLowerCase();

  // PDF filenames - extract and clean the filename
  if (lowerUrl.endsWith(PDF_EXTENSION)) {
    const segments = url.split(LOCAL_PATH_PREFIX);
    const filename = segments[segments.length - 1];
    const cleanName = filename
      .replace(/\.pdf$/i, "")
      .replace(/[-_]/g, " ")
      .replace(/\s+/g, " ")
      .trim();
    return cleanName || "PDF Document";
  }

  // HTTP URLs - extract hostname
  if (isHttpUrl(url)) {
    try {
      const urlObj = new URL(url);
      return urlObj.hostname.replace(/^www\./, "");
    } catch {
      return FALLBACK_SOURCE_LABEL;
    }
  }

  // Local paths - extract last segment
  if (url.startsWith(LOCAL_PATH_PREFIX)) {
    const segments = url.split(LOCAL_PATH_PREFIX);
    return segments[segments.length - 1] || "Local Resource";
  }

  return FALLBACK_SOURCE_LABEL;
}

/**
 * Builds a full URL including anchor fragment if present.
 * Sanitizes the URL scheme to prevent XSS.
 *
 * @param baseUrl - Base URL
 * @param anchor - Optional anchor/fragment to append
 * @returns Safe URL with anchor, or FALLBACK_LINK_TARGET if unsafe
 */
export function buildFullUrl(baseUrl: string | undefined | null, anchor?: string): string {
  if (!baseUrl) return FALLBACK_LINK_TARGET;

  const safeUrl = sanitizeUrl(baseUrl);
  if (safeUrl === FALLBACK_LINK_TARGET) return FALLBACK_LINK_TARGET;

  if (anchor && !safeUrl.includes(ANCHOR_SEPARATOR)) {
    return `${safeUrl}${ANCHOR_SEPARATOR}${anchor}`;
  }
  return safeUrl;
}

/** Constraint for objects with a URL property (used in deduplication). */
interface HasUrl {
  url?: string | null;
}

/**
 * Deduplicates an array of objects by URL (case-insensitive).
 * Filters out objects with missing or invalid URL properties.
 *
 * @param citations - Array of objects with url property (null/undefined treated as empty)
 * @returns Deduplicated array preserving original order
 */
export function deduplicateCitations<T extends HasUrl>(
  citations: readonly T[] | null | undefined,
): T[] {
  if (!citations || citations.length === 0) {
    return [];
  }
  const seen = new Set<string>();
  return citations.filter((citation) => {
    if (!citation || typeof citation.url !== "string") return false;
    const key = citation.url.toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
