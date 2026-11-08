package com.alxarafe.erbas.mcp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Independent classic SDK session. No Spring, application bootstrapping, JDBC or WMS classes. */
public final class Main {
    static final String NAME = "erbas-mcp-spike";
    static final String VERSION = "0.1.0";
    static final int MAX_INPUT_CHARS = 64 * 1024;

    public static void main(String[] args) {
        try (var core = new CoreHttp(System.getenv(CoreHttp.BASE_URL_ENV), System.getenv(CoreHttp.TOKEN_ENV))) {
            if (args.length == 1 && "--health".equals(args[0])) {
                if (core.request(CoreHttp.HEALTH_PATH, null).status() != java.net.HttpURLConnection.HTTP_OK) System.exit(1);
                return;
            }
            if (args.length != 0) throw new IllegalArgumentException();
            var done = new CountDownLatch(1);
            var mapper = McpJsonDefaults.getMapper();
            var tools = new UomTool(core);
            McpRequestHandler<Map<String, Object>> ping = (exchange, params) -> Mono.just(Map.of());
            McpRequestHandler<McpSchema.ListToolsResult> list = (exchange, params) -> Mono.fromCallable(tools::list)
                            .subscribeOn(Schedulers.boundedElastic()).onErrorMap(error -> UomTool.safeError());
            McpRequestHandler<McpSchema.CallToolResult> call = (exchange, params) -> Mono.fromCallable(() -> {
                        McpSchema.CallToolRequest request;
                        try { request = mapper.convertValue(params, new TypeRef<McpSchema.CallToolRequest>() { }); }
                        catch (RuntimeException invalid) { throw io.modelcontextprotocol.spec.McpError
                                .builder(McpSchema.ErrorCodes.INVALID_PARAMS).message("invalid_tool_request").build(); }
                        return tools.call(request);
                    }).subscribeOn(Schedulers.boundedElastic()).onErrorMap(error ->
                            error instanceof io.modelcontextprotocol.spec.McpError ? error : UomTool.safeError());
            Map<String, McpRequestHandler<?>> handlers = Map.of(McpSchema.METHOD_PING, ping,
                    McpSchema.METHOD_TOOLS_LIST, list, McpSchema.METHOD_TOOLS_CALL, call);
            var transport = new StdioServerTransportProvider(mapper, System.in, System.out, MAX_INPUT_CHARS);
            transport.setSessionFactory(wire -> new McpServerSession(UUID.randomUUID().toString(), CoreHttp.TIMEOUT, wire,
                    request -> Mono.just(new McpSchema.InitializeResult(ProtocolVersions.MCP_2025_11_25,
                            McpSchema.ServerCapabilities.builder().tools(false).build(),
                            McpSchema.Implementation.builder(NAME, VERSION).build(), null)),
                    handlers, Map.of(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (exchange, params) -> Mono.empty()),
                    () -> Mono.fromRunnable(done::countDown)));
            Runtime.getRuntime().addShutdownHook(new Thread(transport::close));
            done.await();
            transport.closeGracefully().block(CoreHttp.TIMEOUT);
        } catch (Exception invalid) {
            System.err.println("mcp_process_failed");
            System.exit(1);
        }
    }
}
