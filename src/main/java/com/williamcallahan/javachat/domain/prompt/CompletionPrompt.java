package com.williamcallahan.javachat.domain.prompt;

import java.util.Objects;

/** Separates reusable task instructions from the current completion's untrusted input. */
public record CompletionPrompt(String instructions, String input) {
    /** Requires both boundary fields so instruction omission is explicit. */
    public CompletionPrompt {
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(input, "input");
    }
}
