package com.lynra.kafkatower.controller;

import com.lynra.kafkatower.agents.core.specialist.FrontendModule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Publishes the set of {@link FrontendModule}s deployed in this build so the frontend shell can
 * render a menu and switch between them, instead of hard-coding one module's UI into the layout.
 * kafkatower-app is the one module that depends on every domain module, so — like
 * {@code SpecialistGuidanceInjector} does for specialists — it's the natural place to collect
 * {@code List<FrontendModule>} via constructor injection.
 */
@RestController
@RequestMapping("/api/ui")
public class MenuController {

    public record MenuEntry(String key, String label) {
    }

    private final List<MenuEntry> menu;

    public MenuController(List<FrontendModule> frontendModules) {
        this.menu = frontendModules.stream()
                .map(m -> new MenuEntry(m.key(), m.menuLabel()))
                .toList();
    }

    @GetMapping("/menu")
    public List<MenuEntry> menu() {
        return menu;
    }
}
