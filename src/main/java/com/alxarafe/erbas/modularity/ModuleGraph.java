package com.alxarafe.erbas.modularity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Validates registered modules and resolves dependencies before their consumers. */
public final class ModuleGraph {
    private ModuleGraph() {
    }

    public static List<ErbasModule> order(Collection<? extends ErbasModule> modules) {
        Map<String, ErbasModule> byId = new TreeMap<>();
        for (ErbasModule module : modules) {
            if (byId.putIfAbsent(module.id(), module) != null) {
                throw new IllegalArgumentException("Duplicate module id '" + module.id() + "'");
            }
        }
        for (ErbasModule module : byId.values()) {
            for (String dependency : module.dependencies()) {
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException("Module '" + module.id()
                            + "' depends on missing module '" + dependency + "'");
                }
            }
        }
        List<ErbasModule> ordered = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : byId.keySet()) {
            visit(id, byId, visiting, visited, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void visit(String id, Map<String, ErbasModule> modules, Set<String> visiting,
            Set<String> visited, List<ErbasModule> ordered) {
        if (visited.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("Module dependency cycle detected at '" + id + "'");
        }
        for (String dependency : modules.get(id).dependencies().stream().sorted().toList()) {
            visit(dependency, modules, visiting, visited, ordered);
        }
        visiting.remove(id);
        visited.add(id);
        ordered.add(modules.get(id));
    }
}
