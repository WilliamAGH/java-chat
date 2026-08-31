package com.williamcallahan.javachat.domain.knowledge;

import java.util.List;
import java.util.Objects;

/** Returns every retrievable knowledge group with one authoritative total chunk count. */
public record KnowledgeInventory(List<KnowledgeGroup> groups, long totalChunks) {
    /** Derives the total from the exact group counts. */
    public KnowledgeInventory(List<KnowledgeGroup> groups) {
        this(groups, totalChunks(groups));
    }

    /** Validates that the supplied total exactly matches the group projection. */
    public KnowledgeInventory {
        groups = copyGroups(groups);
        if (totalChunks < 0 || totalChunks != totalChunks(groups)) {
            throw new IllegalArgumentException("totalChunks must equal the sum of group chunks");
        }
    }

    private static List<KnowledgeGroup> copyGroups(List<KnowledgeGroup> groups) {
        return List.copyOf(Objects.requireNonNull(groups, "groups"));
    }

    private static long totalChunks(List<KnowledgeGroup> groups) {
        long total = 0;
        for (KnowledgeGroup group : Objects.requireNonNull(groups, "groups")) {
            total = Math.addExact(
                    total, Objects.requireNonNull(group, "knowledge group").chunks());
        }
        return total;
    }
}
