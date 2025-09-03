package com.williamcallahan.javachat.service;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class TooltipRegistry {
    public static class TooltipDefinition {
        private final String term;
        private final String definition;
        private final String link;

        public TooltipDefinition(String term, String definition, String link) {
            this.term = term; this.definition = definition; this.link = link;
        }
        public String getTerm() { return term; }
        public String getDefinition() { return definition; }
        public String getLink() { return link; }
    }

    private final Map<String, TooltipDefinition> glossary = new LinkedHashMap<>();

    public TooltipRegistry() {
        // Seed with top Java terms (expand as needed)
        add("primitive", "A basic value type like int, double, boolean stored directly (not an object).", "https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html");
        add("reference", "A value that points to an object on the heap rather than containing the value directly.", "https://docs.oracle.com/javase/specs/");
        add("variable", "A named storage location with a declared type that determines the kind of data it can hold.", "https://docs.oracle.com/javase/tutorial/java/nutsandbolts/variables.html");
        add("assignment", "The operation of storing a value into a variable using the = operator.", "https://docs.oracle.com/javase/tutorial/java/nutsandbolts/op1.html");
        add("autoboxing", "Automatic conversion between primitives and their wrapper types (e.g., int ↔ Integer).", "https://docs.oracle.com/javase/tutorial/java/data/autoboxing.html");
        add("immutable", "An object whose state cannot be changed after creation (e.g., String).", "https://docs.oracle.com/javase/tutorial/java/nutsandbolts/strings.html");
        add("record", "A special class that provides a compact syntax for immutable data carriers.", "https://docs.oracle.com/en/java/javase/24/language/records.html");
        add("optional", "A container object which may or may not contain a non-null value, used to avoid null checks.", "https://docs.oracle.com/javase/8/docs/api/java/util/Optional.html");
    }

    private void add(String term, String def, String link) {
        glossary.put(term.toLowerCase(Locale.ROOT), new TooltipDefinition(term, def, link));
    }

    public List<TooltipDefinition> list() {
        return new ArrayList<>(glossary.values());
    }
}

