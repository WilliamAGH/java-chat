package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.TooltipRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tooltips")
public class TooltipController {
    private final TooltipRegistry registry;
    public TooltipController(TooltipRegistry registry) { this.registry = registry; }

    @GetMapping("/list")
    public List<TooltipRegistry.TooltipDefinition> list() {
        return registry.list();
    }
}

