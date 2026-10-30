package com.alxarafe.erbas.capabilities;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.capabilities.http.CapabilityInvocationJson;
import tools.jackson.databind.json.JsonMapper;

/** Synchronous governance. Body access is deferred until the current effective lookup succeeds. */
public final class CapabilityInvocation {
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String NOT_FOUND = "capability_not_found";
    public static final String FAILED = "capability_execution_failed";
    public static final String UNAUTHORIZED = "unauthorized";
    private final CapabilityDiscovery discovery;
    private final CapabilityInvocationRegistry registry;
    private final CapabilityInvocationAuditSink audit;
    private final JsonMapper json = new JsonMapper();

    public CapabilityInvocation(CapabilityDiscovery discovery, CapabilityInvocationRegistry registry,
            CapabilityInvocationAuditSink audit) {
        this.discovery = discovery; this.registry = registry; this.audit = audit;
    }
    public Result invoke(UserIdentity user, String id, Supplier<String> body) {
        var invocationId = UUID.randomUUID().toString();
        var timestamp = Instant.now();
        long started = System.nanoTime();
        String module = null;
        Result result;
        try {
            if (user == null || !user.enabled()) result = Result.error(UNAUTHORIZED);
            else if (!Capability.validId(id)) result = Result.error(INVALID_REQUEST);
            else {
                var resolved = discovery.findEffective(user, id);
                if (resolved.isEmpty() || !Capability.READ.equals(resolved.orElseThrow().effect())) {
                    result = Result.error(NOT_FOUND);
                } else {
                    module = resolved.orElseThrow().module();
                    var context = new CapabilityInvocationContext(invocationId, null, user, id, module,
                            discovery.effectivePermissions(user));
                    var handler = registry.handler(id);
                    Object input;
                    try { input = handler.validateInput(CapabilityInvocationJson.input(body.get())); }
                    catch (CapabilityInvocationHandler.InvalidInput invalid) {
                        input = null;
                    }
                    if (input == null) result = Result.error(INVALID_REQUEST);
                    else {
                        var output = handler.execute(context, input);
                        if (!handler.validOutput(output, input)) result = Result.error(FAILED);
                        else {
                            // Materialize serialization within the failure boundary, before any HTTP 200.
                            var serialized = json.valueToTree(output);
                            if (serialized == null || !serialized.isObject()) result = Result.error(FAILED);
                            else result = new Result(new Envelope(invocationId, id, serialized), null);
                        }
                    }
                }
            }
        } catch (RuntimeException unexpected) { result = Result.error(FAILED); }
        var outcome = result.errorCode() == null ? CapabilityInvocationAuditSink.Outcome.completed
                : FAILED.equals(result.errorCode()) ? CapabilityInvocationAuditSink.Outcome.failed
                : CapabilityInvocationAuditSink.Outcome.denied;
        try {
            audit.record(new CapabilityInvocationAuditSink.Entry(invocationId, null, Capability.validId(id) ? id : null, module,
                    user == null || !user.enabled() ? null : user.getName(), timestamp, outcome,
                    Duration.ofNanos(System.nanoTime() - started), result.errorCode()));
        } catch (RuntimeException unavailableAudit) {
            return Result.error(FAILED);
        }
        return result;
    }
    public record Envelope(String invocationId, String capabilityId, Object output) { }
    public record Result(Envelope envelope, String errorCode) {
        static Result error(String code) { return new Result(null, code); }
    }
}
