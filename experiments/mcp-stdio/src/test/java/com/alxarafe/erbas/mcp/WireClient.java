package com.alxarafe.erbas.mcp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;

/** Independent wire client: raw JSON-RPC over a real child process, no MCP SDK client API. */
final class WireClient implements AutoCloseable {
    static final String PROTOCOL_VERSION = "2025-11-25";
    static final Duration WAIT = Duration.ofSeconds(20);
    private final Process process;
    private final BufferedWriter input;
    private final BufferedReader output;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final Future<String> errors;
    private int sequence;
    final double startupMillis;

    WireClient(String base, String token) throws Exception {
        long start = System.nanoTime();
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getenv().getOrDefault("ERBAS_MCP_TEST_ADAPTER_CP",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))), Main.class.getName());
        builder.environment().put(CoreHttp.BASE_URL_ENV, base);
        builder.environment().put(CoreHttp.TOKEN_ENV, token);
        process = builder.start();
        input = process.outputWriter(StandardCharsets.UTF_8);
        output = process.inputReader(StandardCharsets.UTF_8);
        errors = readers.submit(() -> new String(process.getErrorStream().readNBytes(CoreHttp.MAX_RESPONSE_BYTES), StandardCharsets.UTF_8));
        var initialized = request("initialize", Map.of("protocolVersion", PROTOCOL_VERSION,
                "clientInfo", Map.of("name", "independent-wire-client", "version", "0.1.0"), "capabilities", Map.of()));
        if (!PROTOCOL_VERSION.equals(initialized.path("result").path("protocolVersion").asString())) {
            close();
            throw new IllegalStateException("protocol_negotiation_failed");
        }
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        startupMillis = (System.nanoTime() - start) / 1_000_000d;
    }

    JsonNode request(String method, Object params) throws Exception {
        int id = ++sequence;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        while (true) {
            String line = readers.submit(output::readLine).get(WAIT.toNanos(), TimeUnit.NANOSECONDS);
            if (line == null) throw new IllegalStateException("mcp_process_ended");
            var response = CoreHttp.JSON.readTree(line);
            if (!"2.0".equals(response.path("jsonrpc").asString())) throw new IllegalStateException("stdout_not_mcp");
            if (response.path("id").asInt(-1) == id) return response;
        }
    }
    JsonNode list() throws Exception { return request("tools/list", Map.of()); }
    JsonNode call(Map<String, Object> input) throws Exception {
        return request("tools/call", Map.of("name", CoreHttp.TOOL, "arguments", input));
    }
    private void send(Object value) throws Exception {
        input.write(CoreHttp.JSON.writeValueAsString(value)); input.newLine(); input.flush();
    }
    public void close() throws Exception {
        input.close();
        if (!process.waitFor(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
        output.close();
        readers.shutdownNow();
    }
    String stderr() throws Exception { return errors.get(WAIT.toNanos(), TimeUnit.NANOSECONDS); }
}
