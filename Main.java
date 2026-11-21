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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Service layer: starts Java's built-in HTTP server and routes each request
 * to the restaurant (domain) classes defined in RestaurantApp.java.
 *
 * Endpoints:
 *   GET    /api/menu
 *   POST   /api/order    name, table [, item, quantity]  -> creates the order / adds an item
 *   GET    /api/order    orderId                          -> current status of an order
 *   DELETE /api/order    orderId [, line]                 -> removes a line, or cancels the order
 *   GET    /api/bill     orderId
 *   POST   /api/pay      orderId, mode
 *   GET    /api/history
 */
public class Main {

    static final Restaurant restaurant = new Restaurant();
    static final Map<Integer, Order> orders = new ConcurrentHashMap<>();

    @FunctionalInterface
    interface ApiHandler {
        void handle(HttpExchange ex) throws Exception;
    }

    public static void main(String[] args) throws IOException {

        seedMenu();

        int port = 8080;
        String env = System.getenv("PORT");
        if (env != null && !env.trim().isEmpty()) {
            port = Integer.parseInt(env.trim());
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/menu", ex -> api(ex, Main::handleMenu));
        server.createContext("/api/order", ex -> api(ex, Main::handleOrder));
        server.createContext("/api/bill", ex -> api(ex, Main::handleBill));
        server.createContext("/api/pay", ex -> api(ex, Main::handlePay));
        server.createContext("/api/history", ex -> api(ex, Main::handleHistory));
        server.createContext("/", Main::handleStatic);

        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Spice Garden server running on port " + port);
    }

    static void seedMenu() {
        restaurant.addToMenu(new VegItem("Paneer Tikka", 180, Category.STARTER));
        restaurant.addToMenu(new NonVegItem("Chicken Wings", 220, Category.STARTER, 4));
        restaurant.addToMenu(new VegItem("Veg Biryani", 200, Category.MAIN_COURSE));
        restaurant.addToMenu(new NonVegItem("Butter Chicken", 320, Category.MAIN_COURSE, 3));
        restaurant.addToMenu(new VegItem("Gulab Jamun", 80, Category.DESSERT));
        restaurant.addToMenu(new VegItem("Masala Chai", 40, Category.DRINK));
    }

    // ===================== ENDPOINTS =====================

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
             .append(",\"type\":").append(q(nonVeg ? "NON_VEG" : "VEG"));

            if (nonVeg) {
                b.append(",\"spice\":").append(((NonVegItem) item).getSpiceLevel());
            }

            b.append('}');
        }

        send(ex, 200, b.append(']').toString());
    }

    static void handleOrder(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod();
        Map<String, String> p = params(ex);

        if (method.equals("POST")) {

            Order order;
            int code = 200;

            if (isBlank(p.get("orderId"))) {
                // Validate the customer and create a new order
                String name = p.get("name");
                int table = toInt(p.get("table"), "Table number");
                order = new Order(new Customer(name, table));
                orders.put(order.getOrderId(), order);
                code = 201;
            } else {
                order = orderOf(p);
            }

            if (!isBlank(p.get("item"))) {
                int itemId = toInt(p.get("item"), "Item id");
                int quantity = isBlank(p.get("quantity")) ? 1 : toInt(p.get("quantity"), "Quantity");

                MenuItem item = restaurant.getItem(itemId);
                if (item == null) {
                    throw new IllegalArgumentException("No such item id: " + itemId);
                }

                synchronized (order) {
                    order.addItem(item, quantity);
                }
            }

            send(ex, code, billJson(order));

        } else if (method.equals("DELETE")) {

            Order order = orderOf(p);

            synchronized (order) {
                if (!isBlank(p.get("line"))) {
                    order.removeItem(toInt(p.get("line"), "Line number"));
                    send(ex, 200, billJson(order));
                } else {
                    order.cancelOrder();
                    orders.remove(order.getOrderId());
                    send(ex, 200, "{\"cancelled\":true}");
                }
            }

        } else if (method.equals("GET")) {
            send(ex, 200, billJson(orderOf(p)));

        } else {
            send(ex, 405, "{\"error\":\"Method not allowed\"}");
        }
    }

    static void handleBill(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");
        Order order = orderOf(params(ex));
        order.validateForBilling(); // InvalidOrderException for an empty order
        send(ex, 200, billJson(order));
    }

    static void handlePay(HttpExchange ex) throws Exception {
        requireMethod(ex, "POST");
        Map<String, String> p = params(ex);
        Order order = orderOf(p);

        synchronized (order) {
            order.validateForBilling();

            double amount = order.total();
            String mode = p.get("mode") == null ? "" : p.get("mode").trim().toUpperCase(Locale.ROOT);

            // Runtime polymorphism: the Payment reference calls the right process()
            Payment payment;
            switch (mode) {
                case "CASH": payment = new CashPayment(amount); break;
                case "UPI":  payment = new UpiPayment(amount);  break;
                case "CARD": payment = new CardPayment(amount); break;
                default:
                    throw new IllegalArgumentException("Choose CASH, UPI or CARD");
            }

            order.setPayment(payment);
            payment.process();

            // The kitchen runs on its own thread; a second thread records the
            // order in the history once the kitchen has finished.
            KitchenThread kitchen = new KitchenThread(order);
            kitchen.start();

            new Thread(() -> {
                try {
                    kitchen.join();
                    restaurant.addToHistory(order);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

            send(ex, 200, summaryJson(order, OrderStatus.PAID));
        }
    }

    static void handleHistory(HttpExchange ex) throws Exception {
        requireMethod(ex, "GET");

        StringBuilder b = new StringBuilder("[");
        boolean first = true;

        for (Order order : restaurant.getHistory()) {
            if (!first) b.append(',');
            first = false;
            b.append(summaryJson(order, order.getStatus()));
        }

        send(ex, 200, b.append(']').toString());
    }

    // ===================== STATIC FILES =====================

    static void handleStatic(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            sendBytes(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        Path base = Paths.get("").toAbsolutePath().normalize();
        Path file = base.resolve(path.substring(1)).normalize();

        // Only index.html and the public/ folder are served (never the source code)
        Path publicDir = base.resolve("public").normalize();
        boolean allowed = file.equals(base.resolve("index.html").normalize())
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

    // ===================== HELPERS =====================

    static void api(HttpExchange ex, ApiHandler handler) throws IOException {
        try {
            handler.handle(ex);
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

    static Order orderOf(Map<String, String> p) {
        int id = toInt(p.get("orderId"), "Order id");
        Order order = orders.get(id);
        if (order == null) {
            throw new IllegalArgumentException("Unknown order: " + id);
        }
        return order;
    }

    static int toInt(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a number");
        }
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        parse(ex.getRequestURI().getRawQuery(), m);
        byte[] body = ex.getRequestBody().readAllBytes();
        parse(new String(body, StandardCharsets.UTF_8), m);
        return m;
    }

    static void parse(String s, Map<String, String> m) {
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

    static String billJson(Order o) {
        double subtotal = o.getPrice();
        double total = o.total();
        double gst = Billable.round(total - subtotal);

        StringBuilder b = new StringBuilder();
        b.append("{\"orderId\":").append(o.getOrderId())
         .append(",\"name\":").append(q(o.getCustomer().getName()))
         .append(",\"table\":").append(o.getCustomer().getTableNumber())
         .append(",\"status\":").append(q(o.getStatus().name()))
         .append(",\"payment\":").append(q(o.getPaymentMode()))
         .append(",\"lines\":[");

        boolean first = true;
        for (Pair<MenuItem, Integer> line : o.getLines()) {
            if (!first) b.append(',');
            first = false;
            MenuItem item = line.getFirst();
            int qty = line.getSecond();
            b.append("{\"name\":").append(q(item.getName()))
             .append(",\"price\":").append(num(item.getPrice()))
             .append(",\"quantity\":").append(qty)
             .append(",\"lineTotal\":").append(num(item.getPrice() * qty))
             .append('}');
        }

        b.append("],\"subtotal\":").append(num(subtotal))
         .append(",\"gst\":").append(num(gst))
         .append(",\"total\":").append(num(total))
         .append('}');

        return b.toString();
    }

    static String summaryJson(Order o, OrderStatus status) {
        return "{\"id\":" + o.getOrderId()
             + ",\"name\":" + q(o.getCustomer().getName())
             + ",\"table\":" + o.getCustomer().getTableNumber()
             + ",\"total\":" + num(o.total())
             + ",\"mode\":" + q(o.getPaymentMode())
             + ",\"status\":" + q(status.name())
             + "}";
    }

    static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static String q(String s) {
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
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(code, head ? -1 : bytes.length);
        if (!head) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }
}
