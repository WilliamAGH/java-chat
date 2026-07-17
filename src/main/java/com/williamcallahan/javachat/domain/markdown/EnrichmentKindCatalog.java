package com.williamcallahan.javachat.domain.markdown;

import com.williamcallahan.javachat.domain.text.UnicodeVisibleContent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Projects the canonical enrichment-kind manifest into validated domain values.
 *
 * <p>The manifest is the only owner of supported transport tokens and presentation metadata.
 * Every consumer derives its inventory from these rows instead of restating a fixed count or
 * token list.</p>
 */
public final class EnrichmentKindCatalog {

    private static final String MANIFEST_RESOURCE = "/enrichment-kinds.manifest";
    private static final String MANIFEST_DELIMITER_REGEX = "\\|";
    private static final int ENRICHMENT_KIND_COLUMN_COUNT = 3;
    private static final String SVG_OPENING_TAG = "<svg";
    private static final String SVG_CLOSING_TAG = "</svg>";

    private final List<EnrichmentPresentation> presentations;
    private final Map<String, EnrichmentPresentation> presentationsByToken;

    private EnrichmentKindCatalog(List<EnrichmentPresentation> presentations) {
        this.presentations = List.copyOf(presentations);
        Map<String, EnrichmentPresentation> presentationsByCanonicalToken = new LinkedHashMap<>();
        for (EnrichmentPresentation presentation : presentations) {
            presentationsByCanonicalToken.put(presentation.token(), presentation);
        }
        this.presentationsByToken = Map.copyOf(presentationsByCanonicalToken);
    }

    /** Loads and validates the classpath-owned enrichment-kind manifest. */
    public static EnrichmentKindCatalog load() {
        InputStream manifestStream = EnrichmentKindCatalog.class.getResourceAsStream(MANIFEST_RESOURCE);
        if (manifestStream == null) {
            throw new IllegalStateException("Canonical enrichment kind manifest is missing");
        }

        try {
            return parse(manifestStream);
        } catch (IOException manifestReadFailure) {
            throw new IllegalStateException(
                    "Canonical enrichment kind manifest could not be read", manifestReadFailure);
        }
    }

    /** Parses a manifest stream with the same strict grammar used for classpath loading. */
    public static EnrichmentKindCatalog parse(InputStream manifestStream) throws IOException {
        try (BufferedReader manifestReader =
                new BufferedReader(new InputStreamReader(manifestStream, StandardCharsets.UTF_8))) {
            return parse(manifestReader.lines().toList());
        }
    }

    /** Parses headerless manifest rows in their canonical order. */
    public static EnrichmentKindCatalog parse(List<String> manifestLines) {
        if (manifestLines.isEmpty()) {
            throw new IllegalStateException("Canonical enrichment kind manifest must contain at least one row");
        }

        List<EnrichmentPresentation> parsedPresentations = new ArrayList<>(manifestLines.size());
        Map<String, Integer> lineNumbersByToken = new LinkedHashMap<>();
        for (int lineIndex = 0; lineIndex < manifestLines.size(); lineIndex++) {
            int lineNumber = lineIndex + 1;
            String manifestLine = manifestLines.get(lineIndex);
            if (manifestLine == null || manifestLine.isEmpty()) {
                throw invalidLine(lineNumber, "cannot be blank");
            }

            EnrichmentPresentation presentation = parsePresentation(manifestLine, lineNumber);
            Integer firstLineNumber = lineNumbersByToken.putIfAbsent(presentation.token(), lineNumber);
            if (firstLineNumber != null) {
                throw invalidLine(lineNumber, "duplicates token " + presentation.token());
            }
            parsedPresentations.add(presentation);
        }
        return new EnrichmentKindCatalog(parsedPresentations);
    }

    /** Returns the canonical presentation matching an exact transport token. */
    public Optional<EnrichmentPresentation> find(String token) {
        return Optional.ofNullable(presentationsByToken.get(token));
    }

    /** Returns the canonical presentation matching an exact transport token. */
    public EnrichmentPresentation require(String token) {
        return find(token)
                .orElseThrow(
                        () -> new IllegalStateException("Canonical enrichment kind manifest lacks token " + token));
    }

    /** Returns every canonical presentation in manifest order. */
    public List<EnrichmentPresentation> all() {
        return presentations;
    }

    private static EnrichmentPresentation parsePresentation(String manifestLine, int lineNumber) {
        List<String> columns = List.of(manifestLine.split(MANIFEST_DELIMITER_REGEX, -1));
        if (columns.size() != ENRICHMENT_KIND_COLUMN_COUNT) {
            throw invalidLine(lineNumber, "has an invalid column count");
        }
        try {
            return new EnrichmentPresentation(columns.get(0), columns.get(1), columns.get(2));
        } catch (IllegalArgumentException invalidPresentation) {
            throw new IllegalStateException(
                    "Canonical enrichment kind manifest line " + lineNumber + " has an invalid field: "
                            + invalidPresentation.getMessage(),
                    invalidPresentation);
        }
    }

    private static IllegalStateException invalidLine(int lineNumber, String problem) {
        return new IllegalStateException("Canonical enrichment kind manifest line " + lineNumber + " " + problem);
    }

    /** Typed presentation metadata projected from one canonical manifest row. */
    public record EnrichmentPresentation(String token, String title, String iconHtml) {
        public EnrichmentPresentation {
            requireCanonicalToken(token);
            requireCanonicalTitle(title);
            requireSvgIcon(iconHtml);
        }

        private static void requireCanonicalToken(String token) {
            if (!UnicodeVisibleContent.hasVisibleContent(token) || !token.equals(token.strip())) {
                throw new IllegalArgumentException("token must be visible and have no surrounding whitespace");
            }
            for (int characterIndex = 0; characterIndex < token.length(); characterIndex++) {
                char tokenCharacter = token.charAt(characterIndex);
                boolean isLowercaseAsciiLetter = tokenCharacter >= 'a' && tokenCharacter <= 'z';
                if (!isLowercaseAsciiLetter && tokenCharacter != '-') {
                    throw new IllegalArgumentException("token must use lowercase ASCII letters and hyphens only");
                }
            }
        }

        private static void requireCanonicalTitle(String title) {
            if (!UnicodeVisibleContent.hasVisibleContent(title) || !title.equals(title.strip())) {
                throw new IllegalArgumentException("title must be visible and have no surrounding whitespace");
            }
        }

        private static void requireSvgIcon(String iconHtml) {
            if (!UnicodeVisibleContent.hasVisibleContent(iconHtml)
                    || !iconHtml.startsWith(SVG_OPENING_TAG)
                    || !iconHtml.endsWith(SVG_CLOSING_TAG)
                    || iconHtml.indexOf('\n') >= 0
                    || iconHtml.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("iconHtml must be a single-line SVG fragment");
            }
        }
    }
}
