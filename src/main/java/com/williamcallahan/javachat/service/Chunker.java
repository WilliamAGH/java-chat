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
}

