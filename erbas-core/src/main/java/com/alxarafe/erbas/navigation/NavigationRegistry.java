package com.alxarafe.erbas.navigation;

import java.util.*;
import java.util.regex.Pattern;
import com.alxarafe.erbas.modularity.ModuleRegistry;
import com.alxarafe.erbas.resources.*;

public final class NavigationRegistry {
    private static final Pattern LOCAL_ROUTE = Pattern.compile("/(?:[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?)?");
    private final List<NavigationContribution> contributions;
    public NavigationRegistry(List<NavigationContribution.Source> sources, ModuleRegistry modules, ResourceRegistry resources) {
        var owners = modules.modules().stream().map(module -> module.descriptor().id()).toList();
        var all = new ArrayList<NavigationContribution>(); var identities = new HashSet<String>();
        for (var source : sources) for (var contribution : source.navigation()) {
            if (!owners.contains(contribution.moduleId())) throw new IllegalArgumentException("Unknown navigation owner");
            for (var item : contribution.items()) validate(item, identities, resources);
            all.add(contribution);
        }
        contributions = List.copyOf(all);
    }
    private void validate(NavigationContribution.Entry entry, Set<String> ids, ResourceRegistry resources) {
        if (entry.id() == null || entry.id().isBlank() || !ResourceJson.validText(entry.id()) || !ids.add(entry.id())
                || entry.labelKey() == null || entry.labelKey().isBlank() || entry.icon() != null && entry.icon().isBlank()
                || entry.requiredPermissions().stream().anyMatch(String::isBlank)
                || (entry.target() == null) == entry.children().isEmpty()) throw new IllegalArgumentException("Invalid navigation entry");
        if (entry.target() instanceof NavigationContribution.RouteTarget target
                && (target.route() == null || !LOCAL_ROUTE.matcher(target.route()).matches())) throw new IllegalArgumentException("Unsafe route");
        if (entry.target() instanceof NavigationContribution.ResourceTarget target) {
            try {
                if (!resources.definition(target.resource()).operations().containsKey(ResourceOperation.list)) throw new IllegalArgumentException("Resource is not list-capable");
            } catch (ResourceFailure invalid) { throw new IllegalArgumentException("Unknown navigation resource"); }
        }
        for (var child : entry.children()) validate(child, ids, resources);
    }
    public List<NavigationContribution> contributions() { return contributions; }
}
