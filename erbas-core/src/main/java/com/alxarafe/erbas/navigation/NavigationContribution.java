package com.alxarafe.erbas.navigation;

import java.util.List;

/** Curated module contribution; none of its requirements are public client authority. */
public record NavigationContribution(String moduleId, List<Entry> items) {
    public NavigationContribution { items = List.copyOf(items); }
    public sealed interface Target permits ResourceTarget, RouteTarget { }
    public record ResourceTarget(String resource) implements Target { }
    public record RouteTarget(String route) implements Target { }
    public record Entry(String id, String labelKey, int order, String icon, Target target,
            List<Entry> children, List<String> requiredPermissions) {
        public Entry { children = List.copyOf(children); requiredPermissions = List.copyOf(requiredPermissions); }
    }
    @FunctionalInterface public interface Source { List<NavigationContribution> navigation(); }
}
