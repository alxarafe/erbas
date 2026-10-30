package com.alxarafe.erbas.read;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.modularity.ModuleUnavailableException;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Neutral READ governance. Neither discovery nor invocation trusts a captured principal snapshot. */
public final class ReadPrimitiveRouter {
    private final ReadPrimitiveRegistry registry;
    private final ModuleStateService states;
    private final LongFunction<Optional<UserIdentity>> users;
    private final List<FunctionalPermissionPolicy> policies;
    private final ReadPrimitiveAuditSink audit;
    private final JsonMapper json = new JsonMapper();
    public ReadPrimitiveRouter(ReadPrimitiveRegistry registry, ModuleStateService states,
            LongFunction<Optional<UserIdentity>> users, List<FunctionalPermissionPolicy> policies, ReadPrimitiveAuditSink audit) {
        this.registry = registry; this.states = states; this.users = users; this.policies = List.copyOf(policies); this.audit = audit;
    }
    public List<ReadPrimitive> effective(long subject) {
        var user = current(subject);
        if (user == null) return List.of();
        return registry.primitives().stream().filter(primitive -> authorized(user, primitive))
                .filter(primitive -> enabled(primitive.module())).toList();
    }
    public Result invoke(long subject, String primitiveId, JsonNode input, Origin origin) {
        String invocationId = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        long started = System.nanoTime();
        String module = null;
        UserIdentity user = null;
        Result result;
        try {
            user = current(subject);
            var binding = ReadPrimitive.validId(primitiveId) ? registry.find(primitiveId) : null;
            if (user == null) result = Result.failed(Failure.UNAUTHENTICATED);
            else if (!ReadPrimitive.validId(primitiveId)) result = Result.failed(Failure.INVALID_ID);
            else if (binding == null) result = Result.failed(Failure.UNKNOWN);
            else if (!authorized(user, binding.descriptor())) result = Result.failed(Failure.UNAUTHORIZED);
            else if (!enabled(binding.descriptor().module())) result = Result.failed(Failure.MODULE_DISABLED);
            else {
                module = binding.descriptor().module();
                var executor = binding.executor();
                Object parsed;
                try { parsed = executor.validateInput(input); }
                catch (ReadPrimitiveExecutor.InvalidInput invalid) { parsed = null; }
                if (parsed == null) result = Result.failed(Failure.INVALID_INPUT);
                else {
                    var output = executor.execute(new ReadPrimitiveExecutor.Context(invocationId, primitiveId, module, user, origin), parsed);
                    if (output == null || !executor.validOutput(output, parsed)) result = Result.failed(Failure.EXECUTION_FAILED);
                    else {
                        JsonNode serialized = json.valueToTree(output);
                        result = serialized == null || serialized.isNull() ? Result.failed(Failure.EXECUTION_FAILED)
                                : new Result(new Execution(invocationId, primitiveId, serialized), null);
                    }
                }
            }
        } catch (RuntimeException unexpected) { result = Result.failed(Failure.EXECUTION_FAILED); }
        var outcome = result.failure() == null ? ReadPrimitiveAuditSink.Outcome.completed
                : result.failure() == Failure.EXECUTION_FAILED ? ReadPrimitiveAuditSink.Outcome.failed : ReadPrimitiveAuditSink.Outcome.denied;
        try {
            audit.record(new ReadPrimitiveAuditSink.Entry(invocationId, ReadPrimitive.validId(primitiveId) ? primitiveId : null,
                    module, user == null ? null : user.getName(), origin, timestamp,
                    Duration.ofNanos(System.nanoTime() - started), outcome, result.failure()));
        } catch (RuntimeException unavailableAudit) { return Result.failed(Failure.EXECUTION_FAILED); }
        return result;
    }
    private UserIdentity current(long subject) {
        return users.apply(subject).filter(user -> user.userId() == subject && user.enabled()).orElse(null);
    }
    private boolean authorized(UserIdentity user, ReadPrimitive primitive) {
        return primitive.requiredPermissions().stream().allMatch(permission ->
                policies.stream().anyMatch(policy -> policy.granted(user, permission)));
    }
    private boolean enabled(String module) {
        try { return states.isEnabled(module); }
        catch (ModuleUnavailableException unavailable) { return false; }
    }
    public record Origin(String parentInvocationId, String correlationId, int sequence) { }
    public record Execution(String invocationId, String primitiveId, JsonNode output) { }
    public record Result(Execution execution, Failure failure) {
        static Result failed(Failure failure) { return new Result(null, failure); }
    }
    /** Internal diagnostics only, never new HTTP error codes. */
    public enum Failure { UNAUTHENTICATED, INVALID_ID, UNKNOWN, UNAUTHORIZED, MODULE_DISABLED, INVALID_INPUT, EXECUTION_FAILED }
}
