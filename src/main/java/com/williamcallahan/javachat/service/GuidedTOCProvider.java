package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.model.GuidedLesson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class GuidedTOCProvider {
    private static final Logger log = LoggerFactory.getLogger(GuidedTOCProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile List<GuidedLesson> cache = Collections.emptyList();

    public synchronized List<GuidedLesson> getTOC() {
        if (!cache.isEmpty()) return cache;
        try {
            ClassPathResource res = new ClassPathResource("guided/toc.json");
            try (InputStream in = res.getInputStream()) {
                cache = mapper.readValue(in, new TypeReference<List<GuidedLesson>>(){});
            }
        } catch (Exception e) {
            log.warn("Failed to load guided TOC: {}", e.getMessage());
            cache = Collections.emptyList();
        }
        return cache;
    }

    public Optional<GuidedLesson> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        return getTOC().stream().filter(l -> slug.equalsIgnoreCase(l.getSlug())).findFirst();
    }
}

