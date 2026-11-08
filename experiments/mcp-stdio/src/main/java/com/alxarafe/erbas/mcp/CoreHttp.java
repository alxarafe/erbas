package com.alxarafe.erbas.mcp;

import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/** Fixed trusted origin and credential, with no redirects, proxy discovery or retries. */
final class CoreHttp implements AutoCloseable {
    static final String BASE_URL_ENV = "ERBAS_MCP_CORE_URL";
    static final String TOKEN_ENV = "ERBAS_MCP_TOKEN";
    static final String TOOL = "wms.uoms.list";
    static final String CAPABILITY_PATH = "/api/core/capabilities/" + TOOL;
    static final String INVOCATION_PATH = CAPABILITY_PATH + "/invocations";
    static final String HEALTH_PATH = "/health";
    static final String JSON_MEDIA_TYPE = "application/json";
    static final String CONTENT_TYPE = "Content-Type";
    static final String AUTHORIZATION = "Authorization";
    static final String INPUT = "input";
    static final String CODE = "code";
    static final String HTTP_STATUS = "httpStatus";
    static final String UNAVAILABLE = "core_unavailable";
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final URI base;
    private final String token;
    private final HttpClient client;

    CoreHttp(String url, String token) {
        base = URI.create(url);
        if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                || token == null || !token.matches("[A-Za-z0-9._~+/-]+=*")) {
            throw new IllegalArgumentException("invalid_mcp_configuration");
        }
        this.token = token;
        client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) { }
                }).build();
    }

    Reply request(String path, Object body) {
        try {
            var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT)
                    .header(AUTHORIZATION, "Bearer " + token).header("Accept", JSON_MEDIA_TYPE);
            if (body != null) builder.header(CONTENT_TYPE, JSON_MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            var pending = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
            HttpResponse<byte[]> response;
            try { response = pending.get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS); }
            finally { if (!pending.isDone()) pending.cancel(true); }
            var parsed = JSON.readTree(response.body());
            if (parsed == null || !parsed.isObject()) throw new IllegalStateException();
            return new Reply(response.statusCode(), parsed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(UNAVAILABLE);
        } catch (Exception failure) {
            // Never propagate provider URLs, response payloads or credentials into SDK errors.
            throw new IllegalStateException(UNAVAILABLE);
        }
    }

    record Reply(int status, tools.jackson.databind.JsonNode body) { }
    public void close() { client.close(); }
}
