package com.alxarafe.erbas.knowledge;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.ModuleRegistry;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;

/** Internal query-time authorization only; no document persistence or provider integration. */
public final class DocumentAuthorization {
    private final LongFunction<Optional<UserIdentity>> users;
    private final ModuleStateService states;
    private final List<FunctionalPermissionPolicy> permissions;
    private final DocumentVisibilityPolicy visibility;
    private final Set<String> owners;
    private final Map<String, List<DocumentAccessPolicy>> policies;

    public DocumentAuthorization(ModuleRegistry modules, ModuleStateService states,
            LongFunction<Optional<UserIdentity>> users, List<FunctionalPermissionPolicy> permissions,
            DocumentVisibilityPolicy visibility, Collection<DocumentAccessPolicy> policies) {
        this.states = Objects.requireNonNull(states);
        this.users = Objects.requireNonNull(users);
        this.permissions = List.copyOf(permissions);
        this.visibility = Objects.requireNonNull(visibility);
        owners = modules.modules().stream().map(module -> module.descriptor().id()).collect(Collectors.toUnmodifiableSet());
        // Snapshot trusted registration bindings, not mutable authorization decisions.
        var registrations = new java.util.HashMap<String, java.util.ArrayList<DocumentAccessPolicy>>();
        for (var policy : policies) {
            String owner = policy == null ? null : policy.module();
            if (!owners.contains(owner)) {
                throw new IllegalArgumentException("Document policy owner is unavailable");
            }
            registrations.computeIfAbsent(owner, key -> new java.util.ArrayList<>()).add(policy);
        }
        this.policies = registrations.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    public boolean allowed(long subject, DocumentReference document) {
        try {
            var user = users.apply(subject).filter(value -> value.userId() == subject && value.enabled()).orElse(null);
            if (user == null || document == null || !owners.contains(document.module())
                    || !states.isEnabled(document.module())) return false;
            if (!document.requiredPermissions().stream().allMatch(permission ->
                    permissions.stream().anyMatch(policy -> policy.granted(user, permission)))) return false;
            if (!visibility.visible(user, document)) return false;
            var restrictions = policies.getOrDefault(document.module(), List.of());
            if (document.modulePolicyRequired() && restrictions.isEmpty()) return false;
            return restrictions.stream().allMatch(policy -> policy.allowed(user, document));
        } catch (RuntimeException unavailableAuthority) {
            // A missing or failed authority is never permission to disclose knowledge.
            return false;
        }
    }

    /** Omit the entire edge, including endpoint identifiers, if either source is restricted. */
    public List<Relation> visibleRelations(long subject, Collection<Relation> relations) {
        return relations.stream().filter(relation -> relation != null
                && allowed(subject, relation.source()) && allowed(subject, relation.target())).toList();
    }

    public record Relation(DocumentReference source, DocumentReference target) {
        public Relation {
            Objects.requireNonNull(source);
            Objects.requireNonNull(target);
        }
    }
}
