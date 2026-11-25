import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages real-time Server-Sent Events (SSE) streams across customer phones
 * and the admin portal.
 */
public class SseHub {

    private static final class SseClient {
        final HttpExchange exchange;
        final OutputStream out;

        SseClient(HttpExchange exchange) {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
        }

        synchronized boolean sendRaw(String frame) {
            try {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                closeQuietly();
                return false;
            }
        }

        void closeQuietly() {
            try {
                exchange.close();
            } catch (Exception ignored) {
            }
        }
    }

    private final Map<String, CopyOnWriteArrayList<SseClient>> channels = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public SseHub() {
        heartbeat.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    public void subscribe(String channel, HttpExchange ex, String initialJson) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        SseClient client = new SseClient(ex);
        channels.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(client);

        if (initialJson != null) {
            if (!client.sendRaw(formatEvent("connected", initialJson))) {
                removeClient(channel, client);
            }
        }
    }

    public void broadcast(String channel, String eventName, String jsonPayload) {
        CopyOnWriteArrayList<SseClient> list = channels.get(channel);
        if (list == null || list.isEmpty()) return;

        String frame = formatEvent(eventName, jsonPayload);
        for (SseClient client : list) {
            if (!client.sendRaw(frame)) {
                list.remove(client);
            }
        }
    }

    public void broadcastSessionAndAll(long sessionId, String eventType, int tableNumber) {
        String payload = "{\"type\":\"" + eventType + "\",\"sessionId\":" + sessionId + ",\"tableNumber\":" + tableNumber + "}";
        broadcast("session:" + sessionId, "update", payload);
        broadcast("tables", "update", payload);
        broadcast("admin", "update", payload);
    }

    public void broadcastAdminAndTables(String eventType, int tableNumber) {
        String payload = "{\"type\":\"" + eventType + "\",\"tableNumber\":" + tableNumber + "}";
        broadcast("tables", "update", payload);
        broadcast("admin", "update", payload);
    }

    private void removeClient(String channel, SseClient client) {
        CopyOnWriteArrayList<SseClient> list = channels.get(channel);
        if (list != null) list.remove(client);
    }

    private void sendHeartbeats() {
        for (Map.Entry<String, CopyOnWriteArrayList<SseClient>> entry : channels.entrySet()) {
            CopyOnWriteArrayList<SseClient> list = entry.getValue();
            for (SseClient client : list) {
                if (!client.sendRaw(": ping\n\n")) {
                    list.remove(client);
                }
            }
        }
    }

    private static String formatEvent(String eventName, String data) {
        return "event: " + eventName + "\ndata: " + data + "\n\n";
    }
}
