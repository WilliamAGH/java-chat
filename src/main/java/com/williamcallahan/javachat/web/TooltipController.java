package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.TooltipRegistry;
import jakarta.annotation.security.PermitAll;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes tooltip glossary entries for the frontend.
 */
@RestController
@RequestMapping("/api/tooltips")
@PermitAll
@PreAuthorize("permitAll()")
public class TooltipController {
    private final TooltipRegistry registry;

    /**
     * Creates the tooltip controller backed by the glossary registry.
     */
    public TooltipController(TooltipRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns the current tooltip definitions in insertion order.
     */
    @GetMapping("/list")
    public List<TooltipRegistry.TooltipDefinition> list() {
        return registry.list();
    }
}
