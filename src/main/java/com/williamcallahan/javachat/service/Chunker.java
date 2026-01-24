package com.williamcallahan.javachat.service;

import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into token-aware chunks using the shared encoding registry.
 */
@Component
public class Chunker {
    private final Encoding encoding;

    /**
     * Initializes the tokenizer with the default CL100K encoding for consistent chunk sizes.
     */
    public Chunker() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    /**
     * Splits text into fixed-size token windows with optional overlap.
     */
    public List<String> chunkByTokens(String text, int maxTokens, int overlapTokens) {
        IntArrayList tokens = encoding.encode(text);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < tokens.size()) {
            int end = Math.min(start + maxTokens, tokens.size());
            IntArrayList window = new IntArrayList();
            for (int tokenIndex = start; tokenIndex < end; tokenIndex++) {
                window.add(tokens.get(tokenIndex));
            }
            String decoded = encoding.decode(window);
            chunks.add(decoded);
            if (end == tokens.size()) break;
            start = Math.max(0, end - overlapTokens);
        }
        return chunks;
    }

    /**
     * Truncates text to keep only the last maxTokens.
     * Useful for context window management.
     *
     * @param text the text to truncate (null-safe)
     * @param maxTokens the maximum number of tokens to retain
     * @return the truncated text containing only the last maxTokens, or empty string if text is null/empty
     */
    public String keepLastTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        IntArrayList tokens = encoding.encode(text);
        if (tokens.size() <= maxTokens) {
            return text;
        }
        IntArrayList lastTokens = new IntArrayList();
        for (int tokenOffset = tokens.size() - maxTokens; tokenOffset < tokens.size(); tokenOffset++) {
            lastTokens.add(tokens.get(tokenOffset));
        }
        return encoding.decode(lastTokens);
    }
}

