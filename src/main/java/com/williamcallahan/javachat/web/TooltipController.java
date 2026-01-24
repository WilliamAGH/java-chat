package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.TooltipRegistry;
import jakarta.annotation.security.PermitAll;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes tooltip glossary entries for the frontend.
 */
@RestController
@RequestMapping("/api/tooltips")
@PermitAll
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
