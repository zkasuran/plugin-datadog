package io.kestra.plugin.datadog;

import com.sun.net.httpserver.HttpServer;
import io.kestra.core.serializers.JacksonMapper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local stand-in for the Datadog API. It records every request and answers from a script: replies registered for a
 * "METHOD /path" are used one after the other, and the last one is repeated. A path without a reply gets {@code 200 {}}.
 */
public class MockDatadogServer {
    public record Received(String method, String path, String query, Map<String, String> headers, String body) {
        public Map<String, Object> json() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JacksonMapper.ofJson().readValue(body, Map.class);
            return map;
        }

        public List<?> jsonList() throws Exception {
            return JacksonMapper.ofJson().readValue(body, List.class);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private record Reply(int status, String body) {
    }

    public final List<Received> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Reply>> replies = new ConcurrentHashMap<>();
    private final Map<String, Reply> last = new ConcurrentHashMap<>();
    private final HttpServer server;

    public MockDatadogServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(), headers, body));

            Reply reply;
            synchronized (this) {
                Deque<Reply> queue = replies.get(key);
                if (queue != null && !queue.isEmpty()) {
                    reply = queue.poll();
                    last.put(key, reply);
                } else {
                    reply = last.getOrDefault(key, new Reply(200, "{}"));
                }
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (reply.status() == 204 || bytes.length == 0) {
                exchange.sendResponseHeaders(reply.status(), -1);
            } else {
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    public MockDatadogServer reply(String method, String path, int status, String body) {
        synchronized (this) {
            replies.computeIfAbsent(method + " " + path, k -> new ArrayDeque<>()).add(new Reply(status, body));
        }
        return this;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public Received only() {
        if (requests.size() != 1) {
            throw new AssertionError("expected exactly one request but got " + requests.size() + ": " + requests);
        }
        return requests.get(0);
    }

    public void stop() {
        server.stop(0);
    }
}
