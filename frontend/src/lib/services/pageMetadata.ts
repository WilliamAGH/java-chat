/**
 * Owns public SPA route semantics and the metadata projected into the browser document.
 *
 * The index bootstrap and in-app navigation consume this catalog so deep links, aliases,
 * trailing slashes, and document metadata remain synchronized.
 */
export type ApplicationView = "chat" | "learn";

const ROOT_APPLICATION_PATH = "/";
const CHAT_APPLICATION_PATH = "/chat";
const GUIDED_APPLICATION_PATH = "/guided";
const LEARN_APPLICATION_PATH = "/learn";
const DEFAULT_OPEN_GRAPH_IMAGE_PATH = "/og-image.png";
const DOCUMENT_DESCRIPTION_METADATA_NAME = "description";
const OPEN_GRAPH_TITLE_METADATA_PROPERTY = "og:title";
const OPEN_GRAPH_DESCRIPTION_METADATA_PROPERTY = "og:description";
const OPEN_GRAPH_URL_METADATA_PROPERTY = "og:url";
const OPEN_GRAPH_IMAGE_METADATA_PROPERTY = "og:image";
const TWITTER_TITLE_METADATA_NAME = "twitter:title";
const TWITTER_DESCRIPTION_METADATA_NAME = "twitter:description";
const TWITTER_IMAGE_METADATA_NAME = "twitter:image";
const CANONICAL_LINK_SELECTOR = 'link[data-seo="canonical"]';
const STRUCTURED_DATA_ELEMENT_ID = "java-chat-structured-data";
const STRUCTURED_DATA_CONTEXT = "https://schema.org";
const STRUCTURED_DATA_TYPE = "WebApplication";
const APPLICATION_NAME = "Java Chat";
const APPLICATION_CATEGORY = "EducationalApplication";
const APPLICATION_OPERATING_SYSTEM = "Web";

type PageMetadata = {
  readonly title: string;
  readonly description: string;
  readonly imagePath: string;
};

const DEFAULT_PAGE_METADATA: PageMetadata = {
  title: "Java Chat - AI-Powered Java Learning With Citations",
  description:
    "Learn Java faster with an AI tutor: streaming answers, code examples, and citations to official docs.",
  imagePath: DEFAULT_OPEN_GRAPH_IMAGE_PATH,
};

const CHAT_PAGE_METADATA: PageMetadata = {
  title: "Java Chat - Streaming Java Tutor With Citations",
  description:
    "Ask Java questions and get streaming answers with citations to official docs and practical examples.",
  imagePath: DEFAULT_OPEN_GRAPH_IMAGE_PATH,
};

const GUIDED_PAGE_METADATA: PageMetadata = {
  title: "Guided Java Learning - Java Chat",
  description: "Structured, step-by-step Java learning paths with examples and explanations.",
  imagePath: DEFAULT_OPEN_GRAPH_IMAGE_PATH,
};

const APPLICATION_ROUTE_BY_PATH = {
  [ROOT_APPLICATION_PATH]: {
    view: "chat",
    isCanonicalViewPath: true,
    pageMetadata: DEFAULT_PAGE_METADATA,
  },
  [CHAT_APPLICATION_PATH]: {
    view: "chat",
    isCanonicalViewPath: false,
    pageMetadata: CHAT_PAGE_METADATA,
  },
  [GUIDED_APPLICATION_PATH]: {
    view: "learn",
    isCanonicalViewPath: false,
    pageMetadata: GUIDED_PAGE_METADATA,
  },
  [LEARN_APPLICATION_PATH]: {
    view: "learn",
    isCanonicalViewPath: true,
    pageMetadata: GUIDED_PAGE_METADATA,
  },
} as const;

type ApplicationPath = keyof typeof APPLICATION_ROUTE_BY_PATH;
type ApplicationRoute = (typeof APPLICATION_ROUTE_BY_PATH)[ApplicationPath];

function normalizeApplicationPath(pathname: string): string {
  return pathname.length > 1 && pathname.endsWith("/") ? pathname.slice(0, -1) : pathname;
}

function isApplicationPath(pathname: string): pathname is ApplicationPath {
  return Object.hasOwn(APPLICATION_ROUTE_BY_PATH, pathname);
}

function applicationRouteForPath(pathname: string): ApplicationRoute {
  const normalizedPathname = normalizeApplicationPath(pathname);
  return isApplicationPath(normalizedPathname)
    ? APPLICATION_ROUTE_BY_PATH[normalizedPathname]
    : APPLICATION_ROUTE_BY_PATH[ROOT_APPLICATION_PATH];
}

function updateNamedMetadata(metadataName: string, metadataText: string): void {
  const metadataSelector = `meta[name="${metadataName}"]`;
  let documentMetadata = document.head.querySelector<HTMLMetaElement>(metadataSelector);
  if (!documentMetadata) {
    documentMetadata = document.createElement("meta");
    documentMetadata.setAttribute("name", metadataName);
    document.head.append(documentMetadata);
  }
  documentMetadata.setAttribute("content", metadataText);
}

function updatePropertyMetadata(metadataProperty: string, metadataText: string): void {
  const metadataSelector = `meta[property="${metadataProperty}"]`;
  let documentMetadata = document.head.querySelector<HTMLMetaElement>(metadataSelector);
  if (!documentMetadata) {
    documentMetadata = document.createElement("meta");
    documentMetadata.setAttribute("property", metadataProperty);
    document.head.append(documentMetadata);
  }
  documentMetadata.setAttribute("content", metadataText);
}

function updateCanonicalLink(canonicalUrl: string): void {
  let canonicalLink = document.head.querySelector<HTMLLinkElement>(CANONICAL_LINK_SELECTOR);
  if (!canonicalLink) {
    canonicalLink = document.createElement("link");
    canonicalLink.setAttribute("rel", "canonical");
    canonicalLink.setAttribute("data-seo", "canonical");
    document.head.append(canonicalLink);
  }
  canonicalLink.setAttribute("href", canonicalUrl);
}

function updateStructuredData(canonicalUrl: string, pageDescription: string): void {
  let structuredDataElement = document.getElementById(STRUCTURED_DATA_ELEMENT_ID);
  if (!structuredDataElement) {
    structuredDataElement = document.createElement("script");
    structuredDataElement.setAttribute("id", STRUCTURED_DATA_ELEMENT_ID);
    structuredDataElement.setAttribute("type", "application/ld+json");
    document.head.append(structuredDataElement);
  }
  structuredDataElement.textContent = JSON.stringify({
    "@context": STRUCTURED_DATA_CONTEXT,
    "@type": STRUCTURED_DATA_TYPE,
    name: APPLICATION_NAME,
    url: canonicalUrl,
    applicationCategory: APPLICATION_CATEGORY,
    operatingSystem: APPLICATION_OPERATING_SYSTEM,
    description: pageDescription,
  });
}

/** Resolves the SPA view that corresponds to a browser pathname. */
export function applicationViewForPath(pathname: string): ApplicationView {
  return applicationRouteForPath(pathname).view;
}

/** Resolves the canonical public path for a selected SPA view. */
export function canonicalPathForApplicationView(selectedView: ApplicationView): string {
  const canonicalRouteEntry = Object.entries(APPLICATION_ROUTE_BY_PATH).find(
    ([, applicationRoute]) =>
      applicationRoute.view === selectedView && applicationRoute.isCanonicalViewPath,
  );
  if (!canonicalRouteEntry) {
    throw new Error(`No canonical public path exists for application view: ${selectedView}`);
  }
  return canonicalRouteEntry[0];
}

/** Resolves the document metadata for a browser pathname. */
export function pageMetadataForPath(pathname: string): PageMetadata {
  return applicationRouteForPath(pathname).pageMetadata;
}

/** Resolves the canonical public URL for a browser pathname and its fallback route. */
export function canonicalUrlForPath(
  pathname: string,
  pageOrigin: string = globalThis.location.origin,
): string {
  const applicationRoute = applicationRouteForPath(pathname);
  const canonicalPath = canonicalPathForApplicationView(applicationRoute.view);
  return `${pageOrigin}${canonicalPath}`;
}

/** Synchronizes document title, canonical URL, social tags, and structured data with the active route. */
export function synchronizeDocumentMetadata(pathname: string = globalThis.location.pathname): void {
  const pageMetadata = pageMetadataForPath(pathname);
  const canonicalUrl = canonicalUrlForPath(pathname);
  const imageUrl = `${globalThis.location.origin}${pageMetadata.imagePath}`;

  document.title = pageMetadata.title;
  updateNamedMetadata(DOCUMENT_DESCRIPTION_METADATA_NAME, pageMetadata.description);
  updateCanonicalLink(canonicalUrl);
  updatePropertyMetadata(OPEN_GRAPH_TITLE_METADATA_PROPERTY, pageMetadata.title);
  updatePropertyMetadata(OPEN_GRAPH_DESCRIPTION_METADATA_PROPERTY, pageMetadata.description);
  updatePropertyMetadata(OPEN_GRAPH_URL_METADATA_PROPERTY, canonicalUrl);
  updatePropertyMetadata(OPEN_GRAPH_IMAGE_METADATA_PROPERTY, imageUrl);
  updateNamedMetadata(TWITTER_TITLE_METADATA_NAME, pageMetadata.title);
  updateNamedMetadata(TWITTER_DESCRIPTION_METADATA_NAME, pageMetadata.description);
  updateNamedMetadata(TWITTER_IMAGE_METADATA_NAME, imageUrl);
  updateStructuredData(canonicalUrl, pageMetadata.description);
}
