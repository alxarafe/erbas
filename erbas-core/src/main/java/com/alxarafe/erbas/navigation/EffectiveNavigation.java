package com.alxarafe.erbas.navigation;

import java.util.*;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.resources.*;
import org.springframework.context.MessageSource;

/** Rebuilds effective, localized presentation before any cache comparison. */
public final class EffectiveNavigation {
    private final NavigationRegistry registry;
    private final ResourceRegistry resources;
    private final ResourceRuntime runtime;
    private final ModuleStateService modules;
    private final MessageSource messages;
    private final NavigationContext context;
    public EffectiveNavigation(NavigationRegistry registry, ResourceRegistry resources, ResourceRuntime runtime,
            ModuleStateService modules, MessageSource messages, NavigationContext context) {
        this.registry = registry; this.resources = resources; this.runtime = runtime; this.modules = modules; this.messages = messages; this.context = context;
    }
    public Map<String, Object> resolve(UserIdentity principal, Locale locale) {
        var user = runtime.current(principal);
        String contextKey = context.key(user);
        if (contextKey == null || contextKey.isBlank()) throw new ResourceFailure(ResourceFailure.Code.resource_execution_unavailable);
        var items = new ArrayList<Map<String, Object>>();
        for (var contribution : registry.contributions()) {
            boolean enabled;
            try { enabled = modules.isEnabled(contribution.moduleId()); } catch (ModuleUnavailableException unavailable) { enabled = false; }
            if (enabled) items.addAll(project(user, contribution.items(), locale));
        }
        order(items);
        String version = ResourceMetadata.hash(ResourceJson.JSON.writeValueAsString(List.of(user.getName(), contextKey, locale.toLanguageTag(), items)));
        return Map.of("version", version, "items", items);
    }
    private List<Map<String, Object>> project(UserIdentity user, List<NavigationContribution.Entry> declarations, Locale locale) {
        var items = new ArrayList<Map<String, Object>>();
        for (var entry : declarations) {
            if (!entry.requiredPermissions().stream().allMatch(permission -> runtime.authorized(user, permission))) continue;
            var children = project(user, entry.children(), locale);
            if (entry.target() == null && children.isEmpty()) continue;
            if (entry.target() instanceof NavigationContribution.ResourceTarget target && !runtime.listable(user, resources.definition(target.resource()))) continue;
            String label = messages.getMessage(entry.labelKey(), null, locale);
            if (label.isBlank() || !ResourceJson.validText(label)) throw new IllegalStateException("Invalid navigation label");
            var item = new LinkedHashMap<String, Object>(); item.put("id", entry.id()); item.put("label", label); item.put("order", entry.order());
            if (entry.icon() != null) item.put("icon", entry.icon());
            if (entry.target() instanceof NavigationContribution.ResourceTarget target) item.put("target", Map.of("type", "RESOURCE", "resource", target.resource()));
            if (entry.target() instanceof NavigationContribution.RouteTarget target) item.put("target", Map.of("type", "ROUTE", "route", target.route()));
            item.put("children", children); items.add(item);
        }
        order(items); return items;
    }
    private void order(List<Map<String, Object>> items) {
        items.sort(Comparator.comparingInt((Map<String, Object> item) -> (Integer) item.get("order")).thenComparing(item -> (String) item.get("id")));
    }
}
