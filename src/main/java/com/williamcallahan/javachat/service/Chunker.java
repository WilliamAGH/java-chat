package com.williamcallahan.javachat.service;

import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Chunker {
    private final Encoding encoding;

    public Chunker() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public List<String> chunkByTokens(String text, int maxTokens, int overlapTokens) {
        IntArrayList tokens = encoding.encode(text);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < tokens.size()) {
            int end = Math.min(start + maxTokens, tokens.size());
            IntArrayList window = new IntArrayList();
            for (int i = start; i < end; i++) {
                window.add(tokens.get(i));
            }
            String decoded = encoding.decode(window);
            chunks.add(decoded);
            if (end == tokens.size()) break;
            start = Math.max(0, end - overlapTokens);
        }
        return chunks;
    }
}


