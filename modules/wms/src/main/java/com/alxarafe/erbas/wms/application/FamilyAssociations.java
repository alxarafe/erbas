package com.alxarafe.erbas.wms.application;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.alxarafe.erbas.wms.domain.StorageAttribute;

/** Evaluates complete commands against one pre-operation snapshot, before any mutation. */
public final class FamilyAssociations {
    private final Store store;

    public FamilyAssociations(Store store) { this.store = store; }

    public Result change(UUID familyId, Command command) {
        return store.mutate(familyId, command, snapshot -> evaluate(familyId, command, snapshot));
    }

    public Listing list(UUID familyId, BigInteger offset, int limit) { return store.list(familyId, offset, limit); }

    public static Result evaluate(UUID familyId, Command command, Snapshot before) {
        var issues = new ArrayList<Issue>();
        if (!before.familyExists()) issues.add(new Issue(Problem.FAMILY_MISSING, familyId, Part.ADD, -1));
        missing(command.add(), Part.ADD, before, issues);
        missing(command.remove(), Part.REMOVE, before, issues);
        if (!issues.isEmpty()) return Result.rejected(issues);

        if (command.kind() == Kind.CREATE && before.associated().contains(command.add().getFirst())) {
            issues.add(new Issue(Problem.ALREADY_ASSOCIATED, command.add().getFirst(), Part.ADD, 0));
        } else if (command.kind() == Kind.DELETE && !before.associated().contains(command.remove().getFirst())) {
            issues.add(new Issue(Problem.ASSOCIATION_MISSING, command.remove().getFirst(), Part.REMOVE, 0));
        } else if (command.kind() == Kind.PATCH) {
            conflicts(command.add(), Part.ADD, true, before, issues);
            conflicts(command.remove(), Part.REMOVE, false, before, issues);
        }
        if (!issues.isEmpty()) return Result.rejected(issues);

        var desired = new LinkedHashSet<>(before.associated());
        if (command.kind() == Kind.REPLACE) desired.clear();
        desired.addAll(command.add());
        desired.removeAll(command.remove());
        return new Result(List.of(), desired, command.kind() == Kind.CREATE ? before.attributes().get(command.add().getFirst()) : null);
    }

    private static void missing(List<UUID> ids, Part part, Snapshot before, List<Issue> issues) {
        for (int index = 0; index < ids.size(); index++) {
            UUID id = ids.get(index);
            if (!before.attributes().containsKey(id)) issues.add(new Issue(Problem.ATTRIBUTE_MISSING, id, part, index));
        }
    }

    private static void conflicts(List<UUID> ids, Part part, boolean adding, Snapshot before, List<Issue> issues) {
        for (int index = 0; index < ids.size(); index++) {
            UUID id = ids.get(index);
            if (before.associated().contains(id) == adding) {
                issues.add(new Issue(adding ? Problem.ALREADY_ASSOCIATED : Problem.NOT_ASSOCIATED, id, part, index));
            }
        }
    }

    public interface Store {
        /** Infrastructure serializes a family's mutations and commits the accepted plan atomically. */
        Result mutate(UUID familyId, Command command, Function<Snapshot, Result> evaluate);
        Listing list(UUID familyId, BigInteger offset, int limit);
    }

    public enum Kind { CREATE, REPLACE, PATCH, DELETE }
    public enum Part { ADD, REMOVE }
    public enum Problem { FAMILY_MISSING, ATTRIBUTE_MISSING, ASSOCIATION_MISSING, ALREADY_ASSOCIATED, NOT_ASSOCIATED }
    public record Command(Kind kind, List<UUID> add, List<UUID> remove) {
        public Command { add = List.copyOf(add); remove = List.copyOf(remove); }
    }
    public record Snapshot(boolean familyExists, Map<UUID, StorageAttribute> attributes, Set<UUID> associated) {
        public Snapshot { attributes = Map.copyOf(attributes); associated = Set.copyOf(associated); }
    }
    public record Issue(Problem problem, UUID id, Part part, int index) { }
    public record Result(List<Issue> issues, Set<UUID> desired, StorageAttribute created) {
        public Result { issues = List.copyOf(issues); desired = Set.copyOf(desired); }
        public static Result rejected(List<Issue> issues) { return new Result(issues, Set.of(), null); }
    }
    public record Listing(boolean familyExists, StorageAttributeCatalogue.Window window) { }
}
