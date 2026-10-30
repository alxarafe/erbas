package com.alxarafe.erbas.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import com.alxarafe.erbas.ai.http.AssistantJson;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.capabilities.Capability;
import com.alxarafe.erbas.capabilities.CapabilityDiscovery;
import com.alxarafe.erbas.capabilities.CapabilityInvocation;
import com.alxarafe.erbas.capabilities.http.CapabilityInvocationJson;
import com.alxarafe.erbas.modularity.ModuleDisabledException;
import com.alxarafe.erbas.modularity.ModuleStateService;
import com.alxarafe.erbas.modularity.ModuleUnavailableException;
import com.alxarafe.erbas.modularity.http.ModuleEndpointInterceptor;
import com.alxarafe.erbas.read.ReadPrimitiveRouter;
import tools.jackson.databind.json.JsonMapper;

/** AI governance only. CORE remains the sole authority for every business tool execution. */
public final class AssistantInvocation {
    public static final String INVALID_REQUEST = CapabilityInvocation.INVALID_REQUEST;
    public static final String UNAUTHORIZED = CapabilityInvocation.UNAUTHORIZED;
    public static final String FORBIDDEN = "forbidden";
    public static final String MODULE_DISABLED = ModuleEndpointInterceptor.DISABLED_CODE;
    public static final String UNAVAILABLE = "assistant_unavailable";
    public static final String FAILED = "assistant_execution_failed";
    public static final int MAX_TOOL_CALLS = 8;
    private final AiAssistantPermission permission;
    private final ModuleStateService states;
    private final CapabilityDiscovery discovery;
    private final CapabilityInvocation capabilities;
    private final ReadPrimitiveRouter primitives;
    private final Function<UserIdentity, UserIdentity> currentIdentity;
    private final AiAssistantProvider provider;
    private final AiAssistantAuditSink audit;
    private final JsonMapper json = new JsonMapper();

    public AssistantInvocation(AiAssistantPermission permission, ModuleStateService states, CapabilityDiscovery discovery,
            CapabilityInvocation capabilities, ReadPrimitiveRouter primitives, Function<UserIdentity, UserIdentity> currentIdentity,
            AiAssistantProvider provider, AiAssistantAuditSink audit) {
        this.permission = permission; this.states = states; this.discovery = discovery; this.capabilities = capabilities;
        this.primitives = primitives;
        this.currentIdentity = currentIdentity; this.provider = provider; this.audit = audit;
    }
    public Result invoke(UserIdentity user, Supplier<String> body) {
        String id = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        long started = System.nanoTime();
        var used = new ArrayList<AiAssistantAuditSink.ToolUsage>();
        Result result;
        try {
            if (user == null || !user.enabled()) result = Result.error(UNAUTHORIZED);
            else if (!permission.granted(user)) result = Result.error(FORBIDDEN);
            else {
                states.requireEnabled(AiModule.ID);
                String message = AssistantJson.message(body.get());
                var tools = new ArrayList<AiAssistantProvider.Tool>();
                discovery.effective(currentIdentity.apply(user)).stream().filter(capability -> Capability.READ.equals(capability.effect()))
                        .forEach(capability -> tools.add(new AiAssistantProvider.Tool(AiAssistantProvider.Kind.CAPABILITY,
                                capability.id(), capability.name(), capability.description(), json.valueToTree(capability.inputSchema()))));
                primitives.effective(user.userId()).forEach(primitive -> tools.add(new AiAssistantProvider.Tool(
                        AiAssistantProvider.Kind.PRIMITIVE, primitive.id(), primitive.name(), primitive.description(), primitive.inputSchema())));
                result = execute(id, user, message, tools, used);
            }
        } catch (ModuleDisabledException | ModuleUnavailableException disabled) { result = Result.error(MODULE_DISABLED); }
        catch (AssistantJson.InvalidRequest invalid) { result = Result.error(INVALID_REQUEST); }
        catch (RuntimeException failure) { result = Result.error(FAILED); }
        var outcome = result.errorCode() == null ? AiAssistantAuditSink.Outcome.completed
                : FAILED.equals(result.errorCode()) || UNAVAILABLE.equals(result.errorCode()) ? AiAssistantAuditSink.Outcome.failed
                : AiAssistantAuditSink.Outcome.denied;
        try {
            audit.record(new AiAssistantAuditSink.Entry(id, user == null || !user.enabled() ? null : user.getName(), timestamp,
                    outcome, Duration.ofNanos(System.nanoTime() - started), null, used, result.errorCode()));
        } catch (RuntimeException unavailableAudit) {
            if (result.errorCode() == null) return Result.error(FAILED);
        }
        return result;
    }
    private Result execute(String id, UserIdentity user, String message, List<AiAssistantProvider.Tool> tools,
            List<AiAssistantAuditSink.ToolUsage> used) {
        var results = new ArrayList<AiAssistantProvider.ToolResult>();
        var coreIds = new HashSet<String>();
        try {
            while (true) {
                var turn = provider.respond(message, List.copyOf(tools), List.copyOf(results));
                if (turn instanceof AiAssistantProvider.Answer answer) {
                    return AiText.validAnswer(answer.text()) ? new Result(new Envelope(id, answer.text(), used), null)
                            : Result.error(FAILED);
                }
                if (used.size() >= MAX_TOOL_CALLS) return Result.error(FAILED);
                AiAssistantAuditSink.ToolUsage usage;
                AiAssistantProvider.ToolResult toolResult;
                if (turn instanceof AiAssistantProvider.ToolCall call) {
                    // CORE-CAP-002 retains its own resolution, validation, execution and audit.
                    var execution = capabilities.invoke(currentIdentity.apply(user), call.capabilityId(), () -> json.writeValueAsString(
                            Collections.singletonMap(CapabilityInvocationJson.INPUT, call.input())));
                    if (execution.errorCode() != null || execution.envelope() == null) return Result.error(FAILED);
                    var envelope = execution.envelope();
                    usage = new AiAssistantAuditSink.CapabilityUsage(envelope.capabilityId(), envelope.invocationId());
                    toolResult = new AiAssistantProvider.ToolResult(AiAssistantProvider.Kind.CAPABILITY, envelope.capabilityId(),
                            json.valueToTree(envelope.output()));
                } else if (turn instanceof AiAssistantProvider.PrimitiveCall call) {
                    var execution = primitives.invoke(user.userId(), call.primitiveId(), call.input(),
                            new ReadPrimitiveRouter.Origin(id, null, used.size() + 1));
                    if (execution.failure() != null || execution.execution() == null) return Result.error(FAILED);
                    var envelope = execution.execution();
                    usage = new AiAssistantAuditSink.PrimitiveUsage(envelope.primitiveId(), envelope.invocationId());
                    toolResult = new AiAssistantProvider.ToolResult(AiAssistantProvider.Kind.PRIMITIVE, envelope.primitiveId(), envelope.output());
                } else return Result.error(FAILED);
                if (usage.invocationId() == null || usage.invocationId().isBlank() || id.equals(usage.invocationId())
                        || !coreIds.add(usage.invocationId())) return Result.error(FAILED);
                used.add(usage);
                results.add(toolResult);
            }
        } catch (AiAssistantProvider.Unavailable temporary) { return Result.error(UNAVAILABLE); }
        catch (RuntimeException failure) { return Result.error(FAILED); }
    }
    public record Envelope(String invocationId, String answer, List<AiAssistantAuditSink.ToolUsage> toolsUsed) {
        public Envelope { toolsUsed = List.copyOf(toolsUsed); }
    }
    public record Result(Envelope envelope, String errorCode) {
        static Result error(String code) { return new Result(null, code); }
    }
}
