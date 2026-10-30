package com.alxarafe.erbas.resources.validation;

import java.math.BigInteger;
import java.util.*;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.ConformanceModulesConfiguration;
import com.alxarafe.erbas.resources.*;
import com.alxarafe.erbas.navigation.NavigationContribution;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Isolated protocol probe owned by the existing conformance-only module composition. */
@Configuration(proxyBeanMethods = false)
@Profile(ConformanceModulesConfiguration.VALIDATION_PROFILE)
@ConditionalOnProperty(name = ConformanceModulesConfiguration.ENABLED_PROPERTY, havingValue = "true")
public class ResourceConformanceConfiguration {
    public static final String RESOURCE = "validation.catalogue";
    public static final String PERMISSION = "validation.catalogue.manage";
    public static final String ACTION = "touch";
    @Bean ResourceRegistry.Source conformanceResourceSource() { return () -> List.of(definition()); }
    @Bean FunctionalPermissionPolicy conformanceResourcePermission() {
        return (user, permission) -> user != null && user.enabled() && user.admin() && PERMISSION.equals(permission);
    }
    @Bean ProbeAdapter conformanceResourceAdapter() { return new ProbeAdapter(); }
    @Bean NavigationContribution.Source conformanceNavigationSource() {
        return () -> List.of(new NavigationContribution(ConformanceModulesConfiguration.MATH_ID, List.of(
                new NavigationContribution.Entry("validation", "navigation.validation", 10, null, null, List.of(
                        new NavigationContribution.Entry("validation.catalogue", "navigation.catalogue", 10, null,
                                new NavigationContribution.ResourceTarget(RESOURCE), List.of(), List.of())), List.of()))));
    }
    @Bean ResourceActionExecutor conformanceResourceAction(ProbeAdapter adapter) {
        return new ResourceActionExecutor() {
            public String resourceId() { return RESOURCE; }
            public String actionName() { return ACTION; }
            public Availability availability(UserIdentity user, List<Object> targets) {
                return new Availability(adapter.get(user, targets.getFirst()).isPresent(),
                        adapter.get(user, targets.getFirst()).isPresent() ? null : "record_absent");
            }
            public void execute(UserIdentity user, List<Object> targets, Map<String, Object> parameters) { }
        };
    }
    public static ResourceDefinition definition() {
        var operations = new EnumMap<ResourceOperation, String>(ResourceOperation.class);
        for (var operation : ResourceOperation.values()) operations.put(operation, PERMISSION);
        return new ResourceDefinition(RESOURCE, "1.0.0", ConformanceModulesConfiguration.MATH_ID, "id", List.of(
                field("id", ScalarType.UUID, false, false, true, true, null),
                field("code", ScalarType.STRING, false, true, false, true, null),
                field("name", ScalarType.STRING, false, true, false, false, null),
                field("active", ScalarType.BOOLEAN, false, false, false, false, ResourceJson.JSON.valueToTree(true)),
                field("note", ScalarType.TEXT, true, false, false, false, null)), operations, List.of(),
                List.of(new ResourceActionDefinition(ACTION, ResourceActionDefinition.Scope.ENTITY, false, PERMISSION, List.of(), List.of())),
                List.of(), null);
    }
    private static FieldDefinition field(String name, ScalarType type, boolean nullable, boolean required, boolean readOnly,
            boolean immutable, tools.jackson.databind.JsonNode defaultValue) {
        return new FieldDefinition(name, type, nullable, required, readOnly, immutable, defaultValue, FieldDefinition.Constraints.NONE,
                new FieldDefinition.Capabilities(true, true, type.text()), List.of(), List.of());
    }
    public static final class ProbeAdapter implements ResourceExecutionAdapter {
        private final Map<Object, Map<String, Object>> rows = new HashMap<>();
        public String resourceId() { return RESOURCE; }
        public synchronized ResourcePage list(UserIdentity user, ResourceQuery query) {
            var matches = rows.values().stream().filter(query::matches).sorted(query.comparator()).toList();
            var items = query.offset().compareTo(BigInteger.valueOf(matches.size())) >= 0 ? List.<Map<String, Object>>of()
                    : matches.subList(query.offset().intValueExact(), Math.min(matches.size(), query.offset().intValueExact() + query.limit()));
            return new ResourcePage(items, query.offset(), query.limit(), BigInteger.valueOf(matches.size()));
        }
        public synchronized Optional<Map<String, Object>> get(UserIdentity user, Object id) { return Optional.ofNullable(rows.get(id)); }
        public synchronized Map<String, Object> create(UserIdentity user, Map<String, Object> input) {
            if (rows.values().stream().anyMatch(row -> Objects.equals(row.get("code"), input.get("code"))))
                throw new ResourceFailure(ResourceFailure.Code.resource_conflict);
            var row = new LinkedHashMap<>(input); row.put("id", UUID.randomUUID()); row.putIfAbsent("note", null);
            var stored = Collections.unmodifiableMap(row); rows.put(row.get("id"), stored); return stored;
        }
        public synchronized Map<String, Object> update(UserIdentity user, Object id, Map<String, Object> merged) {
            if (!rows.containsKey(id)) throw new ResourceFailure(ResourceFailure.Code.resource_not_found);
            var stored = Collections.unmodifiableMap(new LinkedHashMap<>(merged)); rows.put(id, stored); return stored;
        }
        public synchronized boolean delete(UserIdentity user, Object id) { return rows.remove(id) != null; }
    }
}
