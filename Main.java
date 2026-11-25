import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web server and API layer for Spice Garden.
 * Routes Customer Portal, Admin Portal, Database transactions, and real-time SSE streams.
 */
public class Main {

    static final Restaurant restaurant = new Restaurant();
    static final RestaurantRepository repository = new DatabaseManager();
    static final SseHub sseHub = new SseHub();

    private static final Map<String, AdminToken> adminTokens = new ConcurrentHashMap<>();
    private static final String ADMIN_USER = envOrDefault("ADMIN_USER", "admin");
    private static final String ADMIN_PASSWORD = envOrDefault("ADMIN_PASSWORD", "spicegarden2026");

    private static final class AdminToken {
        final AdminUser user;
        final Instant expiresAt;

        AdminToken(AdminUser user, Instant expiresAt) {
            this.user = user;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    @FunctionalInterface
    interface ApiHandler {
        void handle(HttpExchange ex) throws Exception;
    }

    public static void main(String[] args) throws IOException {
        repository.initSchemaAndSeed(restaurant);

        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.trim().isEmpty()) {
            port = Integer.parseInt(envPort.trim());
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Public & Customer Portal APIs
        server.createContext("/api/health", ex -> api(ex, Main::handleHealth));
        server.createContext("/api/menu", ex -> api(ex, Main::handleMenu));
        server.createContext("/api/tables", ex -> api(ex, Main::handleTables));
        server.createContext("/api/sessions/join", ex -> api(ex, Main::handleJoinSession));
        server.createContext("/api/sessions/current", ex -> api(ex, Main::handleCurrentSession));
        server.createContext("/api/sessions/order", ex -> api(ex, Main::handleSessionOrder));
        server.createContext("/api/sessions/send-kitchen", ex -> api(ex, Main::handleSendKitchen));
        server.createContext("/api/sessions/checkout", ex -> api(ex, Main::handleCheckout));
        server.createContext("/api/sessions", ex -> api(ex, Main::handleCreateSession));
        server.createContext("/api/feedback", ex -> api(ex, Main::handleSubmitFeedback));
        server.createContext("/api/reviews", ex -> api(ex, Main::handlePublicReviews));
        server.createContext("/api/events", Main::handleCustomerEvents);

        // Admin Portal APIs
        server.createContext("/api/admin/login", ex -> api(ex, Main::handleAdminLogin));
        server.createContext("/api/admin/logout", ex -> api(ex, Main::handleAdminLogout));
        server.createContext("/api/admin/me", ex -> api(ex, Main::handleAdminMe));
        server.createContext("/api/admin/dashboard", ex -> api(ex, Main::handleAdminDashboard));
        server.createContext("/api/admin/order-status", ex -> api(ex, Main::handleAdminOrderStatus));
        server.createContext("/api/admin/release-table", ex -> api(ex, Main::handleAdminReleaseTable));
        server.createContext("/api/admin/history", ex -> api(ex, Main::handleAdminHistory));
        server.createContext("/api/admin/feedback", ex -> api(ex, Main::handleAdminFeedback));
        server.createContext("/api/admin/events", Main::handleAdminEvents);

        // Static pages & assets
        server.createContext("/", Main::handleStatic);

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.println("Spice Garden server running on port " + port + " [" + repository.getEngineDescription() + "]");
    }

    // ===================== PUBLIC & CUSTOMER ENDPOINTS =====================

    static void handleHealth(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        send(ex, 200, "{\"status\":\"ok\",\"dbEngine\":" + q(repository.getEngineDescription()) + "}");
    }

    static void handleMenu(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (MenuItem item : restaurant.getMenu()) {
            if (!first) b.append(',');
            first = false;
            boolean nonVeg = item instanceof NonVegItem;
            b.append("{\"id\":").append(item.getId())
             .append(",\"name\":").append(q(item.getName()))
             .append(",\"price\":").append(num(item.getPrice()))
             .append(",\"category\":").append(q(item.getCategory().name()))
             .append(",\"type\":").append(q(nonVeg ? "NON_VEG" : "VEG"))
             .append(",\"description\":").append(q(item.describe()));
            if (nonVeg) {
                b.append(",\"spice\":").append(((NonVegItem) item).getSpiceLevel());
            }
            b.append('}');
        }
        send(ex, 200, b.append(']').toString());
    }

    static void handleTables(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        List<Table> tables = repository.listTables();
        List<TableSession> active = repository.listActiveSessions();
        Map<Long, TableSession> bySessionId = new HashMap<>();
        for (TableSession s : active) {
            bySessionId.put(s.getSessionId(), s);
        }

        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) b.append(',');
            Table t = tables.get(i);
            TableSession s = t.getActiveSessionId() != null ? bySessionId.get(t.getActiveSessionId()) : null;
            b.append("{\"tableNumber\":").append(t.getTableNumber())
             .append(",\"label\":").append(q(t.getLabel()))
             .append(",\"capacity\":").append(t.getCapacity())
             .append(",\"zone\":").append(q(t.getZone().name()))
             .append(",\"zoneLabel\":").append(q(t.getZone().getDisplayName()))
             .append(",\"status\":").append(q(t.getStatus().name()))
             .append(",\"guestCount\":").append(s != null ? s.getCustomers().size() : 0)
             .append(",\"durationSeconds\":").append(s != null ? s.getDurationSeconds() : 0)
             .append('}');
        }
        send(ex, 200, b.append(']').toString());
    }

    static void handleCreateSession(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        Map<String, String> p = params(ex);
        int tableNumber = toInt(p.get("tableNumber"), "Table number");
        String customerName = p.get("customerName");

        Pair<TableSession, Customer> created = repository.createTableSession(tableNumber, customerName);
        TableSession session = created.getFirst();
        Customer customer = created.getSecond();

        sseHub.broadcastSessionAndAll(session.getSessionId(), "table_booked", session.getTableNumber());
        send(ex, 201, "{\"session\":" + sessionJson(session) + ",\"customer\":" + customerJson(customer) + "}");
    }

    static void handleJoinSession(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        Map<String, String> p = params(ex);
        String joinCode = p.get("joinCode");
        String customerName = p.get("customerName");

        Pair<TableSession, Customer> joined = repository.joinTableSession(joinCode, customerName);
        TableSession session = joined.getFirst();
        Customer customer = joined.getSecond();

        sseHub.broadcastSessionAndAll(session.getSessionId(), "customer_joined", session.getTableNumber());
        send(ex, 200, "{\"session\":" + sessionJson(session) + ",\"customer\":" + customerJson(customer) + "}");
    }

    static void handleCurrentSession(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        Map<String, String> p = params(ex);
        TableSession session;
        if (!isBlank(p.get("sessionId"))) {
            session = repository.getSession(toLong(p.get("sessionId"), "Session id"));
        } else if (!isBlank(p.get("joinCode"))) {
            session = repository.getSessionByJoinCode(p.get("joinCode"));
        } else {
            throw new IllegalArgumentException("sessionId or joinCode is required");
        }
        send(ex, 200, sessionJson(session));
    }

    static void handleSessionOrder(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        Map<String, String> p = params(ex);

        if ("POST".equals(method)) {
            long sessionId = toLong(p.get("sessionId"), "Session id");
            long customerId = toLong(p.get("customerId"), "Customer id");
            int itemId = toInt(p.get("itemId"), "Item id");
            int quantity = isBlank(p.get("quantity")) ? 1 : toInt(p.get("quantity"), "Quantity");

            MenuItem item = restaurant.getItem(itemId);
            if (item == null) {
                throw new IllegalArgumentException("Unknown menu item: " + itemId);
            }

            TableSession updated = repository.addOrderItem(sessionId, customerId, item, quantity);
            sseHub.broadcastSessionAndAll(sessionId, "order_updated", updated.getTableNumber());
            send(ex, 200, sessionJson(updated));

        } else if ("DELETE".equals(method)) {
            long sessionId = toLong(p.get("sessionId"), "Session id");
            long customerId = toLong(p.get("customerId"), "Customer id");
            int orderId = toInt(p.get("orderId"), "Order id");
            int lineIndex = toInt(p.get("lineIndex"), "Line index");

            TableSession updated = repository.removeOrderLine(sessionId, customerId, orderId, lineIndex);
            sseHub.broadcastSessionAndAll(sessionId, "order_updated", updated.getTableNumber());
            send(ex, 200, sessionJson(updated));

        } else {
            throw new IllegalStateException("Use POST or DELETE for /api/sessions/order");
        }
    }

    static void handleSendKitchen(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        Map<String, String> p = params(ex);
        long sessionId = toLong(p.get("sessionId"), "Session id");
        long customerId = toLong(p.get("customerId"), "Customer id");
        Integer orderId = isBlank(p.get("orderId")) ? null : toInt(p.get("orderId"), "Order id");

        TableSession updated = repository.sendOrderToKitchen(sessionId, customerId, orderId);
        sseHub.broadcastSessionAndAll(sessionId, "kitchen_preparing", updated.getTableNumber());
        send(ex, 200, sessionJson(updated));
    }

    static void handleCheckout(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        Map<String, String> p = params(ex);
        long sessionId = toLong(p.get("sessionId"), "Session id");
        String mode = p.get("mode");

        TableSession completed = repository.checkoutSession(sessionId, mode);
        Payment payment = Payment.create(completed.getPaymentMode(), completed.getTotal());

        sseHub.broadcastSessionAndAll(sessionId, "table_released", completed.getTableNumber());
        send(ex, 200, "{\"session\":" + sessionJson(completed) + ",\"receiptNote\":" + q(payment.getReceiptNote()) + "}");
    }

    static void handleSubmitFeedback(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        Map<String, String> p = params(ex);
        long sessionId = toLong(p.get("sessionId"), "Session id");
        int tableNumber = toInt(p.get("tableNumber"), "Table number");
        String customerName = p.get("customerName");
        int rating = toInt(p.get("rating"), "Rating");
        String category = p.get("category");
        String comment = p.get("comment");

        Feedback saved = repository.saveFeedback(sessionId, tableNumber, customerName, rating, category, comment);
        sseHub.broadcast("admin", "update", "{\"type\":\"feedback_added\",\"sessionId\":" + sessionId + "}");
        send(ex, 201, feedbackJson(saved));
    }

    static void handlePublicReviews(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        List<Feedback> list = repository.listFeedback(4);
        int limit = Math.min(6, list.size());
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < limit; i++) {
            if (i > 0) b.append(',');
            b.append(feedbackJson(list.get(i)));
        }
        send(ex, 200, b.append(']').toString());
    }

    static void handleCustomerEvents(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendBytes(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, String> p = new HashMap<>();
        parseQuery(ex.getRequestURI().getRawQuery(), p);
        String channel;
        if (!isBlank(p.get("sessionId"))) {
            channel = "session:" + p.get("sessionId").trim();
        } else {
            channel = "tables";
        }
        sseHub.subscribe(channel, ex, "{\"channel\":" + q(channel) + "}");
    }

    // ===================== ADMIN ENDPOINTS =====================

    static void handleAdminLogin(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        Map<String, String> p = params(ex);
        String username = p.get("username");
        String password = p.get("password");

        if (username == null || password == null
                || !ADMIN_USER.equals(username.trim())
                || !ADMIN_PASSWORD.equals(password)) {
            send(ex, 401, "{\"error\":\"Invalid admin username or password\"}");
            return;
        }

        AdminUser admin = new AdminUser(ADMIN_USER, "Spice Garden Manager");
        String token = UUID.randomUUID().toString();
        adminTokens.put(token, new AdminToken(admin, Instant.now().plusSeconds(86400)));

        ex.getResponseHeaders().add("Set-Cookie", "sg_admin_token=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400");
        send(ex, 200, "{\"token\":" + q(token)
                + ",\"username\":" + q(admin.getUsername())
                + ",\"displayName\":" + q(admin.getName())
                + ",\"role\":" + q(admin.getRole()) + "}");
    }

    static void handleAdminLogout(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        String token = extractAdminToken(ex);
        if (token != null) adminTokens.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", "sg_admin_token=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        send(ex, 200, "{\"loggedOut\":true}");
    }

    static void handleAdminMe(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        AdminUser admin = requireAdmin(ex);
        if (admin == null) return;
        send(ex, 200, "{\"username\":" + q(admin.getUsername())
                + ",\"displayName\":" + q(admin.getName())
                + ",\"role\":" + q(admin.getRole()) + "}");
    }

    static void handleAdminDashboard(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        if (requireAdmin(ex) == null) return;

        List<Table> tables = repository.listTables();
        List<TableSession> activeSessions = repository.listActiveSessions();
        Map<Long, TableSession> activeById = new HashMap<>();
        double activeRevenue = 0.0;
        for (TableSession s : activeSessions) {
            activeById.put(s.getSessionId(), s);
            activeRevenue += s.getTotal();
        }

        List<TableSession> completed = repository.searchHistory(null, null, null, "COMPLETED", null, null);
        double completedRevenue = 0.0;
        for (TableSession s : completed) {
            completedRevenue += s.getTotal();
        }

        List<Feedback> feedbacks = repository.listFeedback(null);
        double avgRating = 0.0;
        if (!feedbacks.isEmpty()) {
            int sum = 0;
            for (Feedback f : feedbacks) sum += f.getRating();
            avgRating = Math.round(((double) sum / feedbacks.size()) * 10.0) / 10.0;
        }

        int occupiedCount = 0;
        for (Table t : tables) {
            if (t.isOccupied()) occupiedCount++;
        }

        StringBuilder b = new StringBuilder();
        b.append("{\"stats\":{")
         .append("\"occupiedCount\":").append(occupiedCount)
         .append(",\"availableCount\":").append(tables.size() - occupiedCount)
         .append(",\"activeRevenue\":").append(num(activeRevenue))
         .append(",\"completedRevenue\":").append(num(completedRevenue))
         .append(",\"completedCount\":").append(completed.size())
         .append(",\"avgRating\":").append(String.format(Locale.ROOT, "%.1f", avgRating))
         .append(",\"feedbackCount\":").append(feedbacks.size())
         .append(",\"dbEngine\":").append(q(repository.getEngineDescription()))
         .append("},\"tables\":[");

        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) b.append(',');
            Table t = tables.get(i);
            TableSession s = t.getActiveSessionId() != null ? activeById.get(t.getActiveSessionId()) : null;
            b.append("{\"tableNumber\":").append(t.getTableNumber())
             .append(",\"label\":").append(q(t.getLabel()))
             .append(",\"capacity\":").append(t.getCapacity())
             .append(",\"zone\":").append(q(t.getZone().name()))
             .append(",\"zoneLabel\":").append(q(t.getZone().getDisplayName()))
             .append(",\"status\":").append(q(t.getStatus().name()))
             .append(",\"activeSession\":").append(s != null ? sessionJson(s) : "null")
             .append('}');
        }
        b.append("]}");
        send(ex, 200, b.toString());
    }

    static void handleAdminOrderStatus(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        if (requireAdmin(ex) == null) return;
        Map<String, String> p = params(ex);
        int orderId = toInt(p.get("orderId"), "Order id");
        String statusRaw = p.get("status");
        if (isBlank(statusRaw)) throw new IllegalArgumentException("Status is required");

        OrderStatus next;
        try {
            next = OrderStatus.valueOf(statusRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid order status: " + statusRaw);
        }

        Order updated = repository.updateOrderStatus(orderId, next);
        sseHub.broadcastSessionAndAll(updated.getSessionId(), "kitchen_status_changed", updated.getCustomer().getTableNumber());
        send(ex, 200, orderJson(updated));
    }

    static void handleAdminReleaseTable(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        if (requireAdmin(ex) == null) return;
        Map<String, String> p = params(ex);
        int tableNumber = toInt(p.get("tableNumber"), "Table number");
        String mode = p.get("mode");

        TableSession closed = repository.adminReleaseTable(tableNumber, mode);
        if (closed != null) {
            sseHub.broadcastSessionAndAll(closed.getSessionId(), "table_released", tableNumber);
        } else {
            sseHub.broadcastAdminAndTables("table_released", tableNumber);
        }
        send(ex, 200, "{\"released\":true,\"tableNumber\":" + tableNumber + ",\"session\":" + (closed != null ? sessionJson(closed) : "null") + "}");
    }

    static void handleAdminHistory(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        if (requireAdmin(ex) == null) return;
        Map<String, String> p = params(ex);

        Integer table = isBlank(p.get("table")) ? null : toInt(p.get("table"), "Table");
        List<TableSession> sessions = repository.searchHistory(
                p.get("q"),
                table,
                p.get("mode"),
                p.get("status"),
                p.get("from"),
                p.get("to")
        );
        Map<Long, List<Feedback>> fbMap = repository.mapFeedbackBySession();

        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < sessions.size(); i++) {
            if (i > 0) b.append(',');
            TableSession s = sessions.get(i);
            List<Feedback> fbList = fbMap.getOrDefault(s.getSessionId(), List.of());
            b.append("{\"session\":").append(sessionJson(s)).append(",\"feedback\":[");
            for (int j = 0; j < fbList.size(); j++) {
                if (j > 0) b.append(',');
                b.append(feedbackJson(fbList.get(j)));
            }
            b.append("]}");
        }
        send(ex, 200, b.append(']').toString());
    }

    static void handleAdminFeedback(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        if (requireAdmin(ex) == null) return;
        Map<String, String> p = params(ex);
        Integer minRating = isBlank(p.get("minRating")) ? null : toInt(p.get("minRating"), "minRating");

        List<Feedback> list = repository.listFeedback(minRating);
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) b.append(',');
            b.append(feedbackJson(list.get(i)));
        }
        send(ex, 200, b.append(']').toString());
    }

    static void handleAdminEvents(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendBytes(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (authenticateAdmin(ex) == null) {
            send(ex, 401, "{\"error\":\"Admin authentication required\"}");
            ex.close();
            return;
        }
        sseHub.subscribe("admin", ex, "{\"channel\":\"admin\"}");
    }

    // ===================== STATIC FILES =====================

    static void handleStatic(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            sendBytes(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String path = ex.getRequestURI().getPath();
        if ("/".equals(path) || "/customer".equals(path)) path = "/index.html";
        else if ("/admin".equals(path) || "/admin/".equals(path)) path = "/admin.html";

        Path base = Paths.get("").toAbsolutePath().normalize();
        Path file = base.resolve(path.substring(1)).normalize();

        Path publicDir = base.resolve("public").normalize();
        boolean allowed = file.equals(base.resolve("index.html").normalize())
                || file.equals(base.resolve("admin.html").normalize())
                || file.startsWith(publicDir);

        if (!allowed || !Files.isRegularFile(file)) {
            sendBytes(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }

        sendBytes(ex, 200, contentType(file.getFileName().toString()), Files.readAllBytes(file));
    }

    static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    // ===================== AUTH & JSON HELPERS =====================

    static AdminUser requireAdmin(HttpExchange ex) throws IOException {
        AdminUser admin = authenticateAdmin(ex);
        if (admin == null) {
            send(ex, 401, "{\"error\":\"Admin authentication required\"}");
            return null;
        }
        return admin;
    }

    static AdminUser authenticateAdmin(HttpExchange ex) {
        String token = extractAdminToken(ex);
        if (token == null) return null;
        AdminToken entry = adminTokens.get(token);
        if (entry == null || entry.isExpired()) {
            adminTokens.remove(token);
            return null;
        }
        return entry.user;
    }

    static String extractAdminToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        Map<String, String> q = new HashMap<>();
        parseQuery(ex.getRequestURI().getRawQuery(), q);
        if (!isBlank(q.get("token"))) {
            return q.get("token").trim();
        }
        String cookieHeader = ex.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && "sg_admin_token".equals(kv[0].trim())) {
                    return kv[1].trim();
                }
            }
        }
        return null;
    }

    static void api(HttpExchange ex, ApiHandler handler) throws IOException {
        try {
            handler.handle(ex);
        } catch (TableOccupiedException e) {
            send(ex, 409, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (IllegalArgumentException | IllegalStateException | InvalidOrderException e) {
            send(ex, 400, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "{\"error\":\"Server error\"}");
        } finally {
            ex.close();
        }
    }

    static void requireMethod(HttpExchange ex, String method) {
        if (!ex.getRequestMethod().equals(method)) {
            throw new IllegalStateException("Use " + method + " for this endpoint");
        }
    }

    static int toInt(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number");
        }
    }

    static long toLong(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number");
        }
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String envOrDefault(String key, String fallback) {
        String val = System.getenv(key);
        return (val == null || val.trim().isEmpty()) ? fallback : val.trim();
    }

    static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        parseQuery(ex.getRequestURI().getRawQuery(), m);
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length > 0) {
            String raw = new String(body, StandardCharsets.UTF_8).trim();
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if ((ct != null && ct.toLowerCase(Locale.ROOT).contains("application/json")) || raw.startsWith("{")) {
                parseFlatJson(raw, m);
            } else {
                parseQuery(raw, m);
            }
        }
        return m;
    }

    static void parseQuery(String s, Map<String, String> m) {
        if (s == null || s.isEmpty()) return;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                  URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
    }

    private static final Pattern JSON_PAIR = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|(-?\\d+(?:\\.\\d+)?)|true|false|null)");

    static void parseFlatJson(String json, Map<String, String> m) {
        Matcher matcher = JSON_PAIR.matcher(json);
        while (matcher.find()) {
            String key = matcher.group(1);
            String strVal = matcher.group(2);
            String numVal = matcher.group(3);
            if (strVal != null) {
                m.put(key, unescapeJson(strVal));
            } else if (numVal != null) {
                m.put(key, numVal);
            }
        }
    }

    static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    default: out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static String sessionJson(TableSession s) {
        double subtotal = s.getPrice();
        double gst = s.getGst();
        double total = s.getTotal();

        Map<Long, List<Order>> byCustomer = s.getOrdersByCustomer();
        StringBuilder b = new StringBuilder();
        b.append("{\"sessionId\":").append(s.getSessionId())
         .append(",\"tableNumber\":").append(s.getTableNumber())
         .append(",\"joinCode\":").append(q(s.getJoinCode()))
         .append(",\"status\":").append(q(s.getStatus().name()))
         .append(",\"paymentMode\":").append(q(s.getPaymentMode()))
         .append(",\"startedAt\":").append(q(s.getStartedAt().toString()))
         .append(",\"endedAt\":").append(s.getEndedAt() == null ? "null" : q(s.getEndedAt().toString()))
         .append(",\"durationSeconds\":").append(s.getDurationSeconds())
         .append(",\"itemCount\":").append(s.getTotalItemCount())
         .append(",\"subtotal\":").append(num(subtotal))
         .append(",\"gst\":").append(num(gst))
         .append(",\"total\":").append(num(total))
         .append(",\"customers\":[");

        List<Customer> customers = s.getCustomers();
        for (int i = 0; i < customers.size(); i++) {
            if (i > 0) b.append(',');
            Customer c = customers.get(i);
            List<Order> custOrders = byCustomer.getOrDefault(c.getCustomerId(), List.of());
            double custSubtotal = 0.0;
            for (Order o : custOrders) custSubtotal += o.getPrice();
            b.append("{\"customerId\":").append(c.getCustomerId())
             .append(",\"name\":").append(q(c.getName()))
             .append(",\"joinedAt\":").append(q(c.getJoinedAt().toString()))
             .append(",\"subtotal\":").append(num(custSubtotal))
             .append(",\"orders\":[");
            for (int j = 0; j < custOrders.size(); j++) {
                if (j > 0) b.append(',');
                b.append(orderJson(custOrders.get(j)));
            }
            b.append("]}");
        }

        b.append("],\"orders\":[");
        List<Order> allOrders = s.getOrders();
        for (int i = 0; i < allOrders.size(); i++) {
            if (i > 0) b.append(',');
            b.append(orderJson(allOrders.get(i)));
        }

        // Consolidated item summary across the entire table
        Map<String, ConsolidatedLine> consolidated = new LinkedHashMap<>();
        for (Order o : allOrders) {
            if (o.getStatus() == OrderStatus.CANCELLED) continue;
            for (Pair<MenuItem, Integer> line : o.getLines()) {
                MenuItem mi = line.getFirst();
                int qty = line.getSecond();
                consolidated.compute(mi.getName(), (k, existing) -> {
                    if (existing == null) return new ConsolidatedLine(mi.getName(), mi.getPrice(), qty);
                    existing.quantity += qty;
                    return existing;
                });
            }
        }
        b.append("],\"consolidatedLines\":[");
        boolean firstLine = true;
        for (ConsolidatedLine cl : consolidated.values()) {
            if (!firstLine) b.append(',');
            firstLine = false;
            b.append("{\"name\":").append(q(cl.name))
             .append(",\"price\":").append(num(cl.unitPrice))
             .append(",\"quantity\":").append(cl.quantity)
             .append(",\"lineTotal\":").append(num(cl.unitPrice * cl.quantity))
             .append('}');
        }
        b.append("]}");
        return b.toString();
    }

    private static final class ConsolidatedLine {
        final String name;
        final double unitPrice;
        int quantity;

        ConsolidatedLine(String name, double unitPrice, int quantity) {
            this.name = name;
            this.unitPrice = unitPrice;
            this.quantity = quantity;
        }
    }

    static String customerJson(Customer c) {
        return "{\"customerId\":" + c.getCustomerId()
                + ",\"name\":" + q(c.getName())
                + ",\"tableNumber\":" + c.getTableNumber()
                + ",\"sessionId\":" + c.getSessionId()
                + ",\"joinedAt\":" + q(c.getJoinedAt().toString())
                + "}";
    }

    static String orderJson(Order o) {
        double subtotal = o.getPrice();
        double total = o.total();
        double gst = Billable.round(total - subtotal);

        StringBuilder b = new StringBuilder();
        b.append("{\"orderId\":").append(o.getOrderId())
         .append(",\"sessionId\":").append(o.getSessionId())
         .append(",\"customerId\":").append(o.getCustomer().getCustomerId())
         .append(",\"customerName\":").append(q(o.getCustomer().getName()))
         .append(",\"tableNumber\":").append(o.getCustomer().getTableNumber())
         .append(",\"status\":").append(q(o.getStatus().name()))
         .append(",\"paymentMode\":").append(q(o.getPaymentMode()))
         .append(",\"createdAt\":").append(q(o.getCreatedAt().toString()))
         .append(",\"subtotal\":").append(num(subtotal))
         .append(",\"gst\":").append(num(gst))
         .append(",\"total\":").append(num(total))
         .append(",\"lines\":[");

        List<Pair<MenuItem, Integer>> lines = o.getLines();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append(',');
            MenuItem item = lines.get(i).getFirst();
            int qty = lines.get(i).getSecond();
            b.append("{\"lineIndex\":").append(i)
             .append(",\"itemId\":").append(item.getId())
             .append(",\"name\":").append(q(item.getName()))
             .append(",\"price\":").append(num(item.getPrice()))
             .append(",\"quantity\":").append(qty)
             .append(",\"lineTotal\":").append(num(item.getPrice() * qty))
             .append('}');
        }
        b.append("]}");
        return b.toString();
    }

    static String feedbackJson(Feedback f) {
        return "{\"feedbackId\":" + f.getFeedbackId()
                + ",\"sessionId\":" + f.getSessionId()
                + ",\"tableNumber\":" + f.getTableNumber()
                + ",\"customerName\":" + q(f.getCustomerName())
                + ",\"rating\":" + f.getRating()
                + ",\"category\":" + q(f.getCategory())
                + ",\"comment\":" + q(f.getComment())
                + ",\"createdAt\":" + q(f.getCreatedAt().toString())
                + "}";
    }

    static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    static void send(HttpExchange ex, int code, String json) throws IOException {
        sendBytes(ex, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    static void sendBytes(HttpExchange ex, int code, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(code, head ? -1 : bytes.length);
        if (!head) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }
}
