import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Exception thrown when a customer attempts to book an already occupied table.
 */
class TableOccupiedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public TableOccupiedException(String message) {
        super(message);
    }
}

/**
 * Abstraction for Spice Garden persistence operations.
 */
interface RestaurantRepository {
    String getEngineDescription();
    void initSchemaAndSeed(Restaurant restaurant);
    List<Table> listTables();
    Pair<TableSession, Customer> createTableSession(int tableNumber, String customerName);
    Pair<TableSession, Customer> joinTableSession(String joinCode, String customerName);
    TableSession getSession(long sessionId);
    TableSession getSessionByJoinCode(String joinCode);
    List<TableSession> listActiveSessions();
    TableSession addOrderItem(long sessionId, long customerId, MenuItem item, int quantity);
    TableSession removeOrderLine(long sessionId, long customerId, int orderId, int lineIndex);
    TableSession sendOrderToKitchen(long sessionId, long customerId, Integer orderId);
    Order updateOrderStatus(int orderId, OrderStatus newStatus);
    TableSession checkoutSession(long sessionId, String paymentMode) throws InvalidOrderException;
    TableSession adminReleaseTable(int tableNumber, String paymentMode) throws InvalidOrderException;
    Feedback saveFeedback(long sessionId, int tableNumber, String customerName, int rating, String category, String comment);
    List<Feedback> listFeedback(Integer minRating);
    List<TableSession> searchHistory(String query, Integer tableNumber, String paymentMode, String status, String fromDate, String toDate);
    Map<Long, List<Feedback>> mapFeedbackBySession();
}

/**
 * JDBC implementation supporting MySQL (via MYSQL_URL / DB_URL / MYSQLHOST env vars)
 * with automatic persistent file-backed H2 in MySQL compatibility mode when no external
 * MySQL server is configured.
 */
public class DatabaseManager implements RestaurantRepository {

    private static final String CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final Random random = new Random();

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String engineDescription;

    public DatabaseManager() {
        String envUrl = firstNonBlank(
                System.getenv("DB_URL"),
                System.getenv("MYSQL_URL"),
                System.getenv("DATABASE_URL")
        );
        String mysqlHost = System.getenv("MYSQLHOST");

        if (envUrl != null && envUrl.startsWith("jdbc:mysql:")) {
            this.jdbcUrl = envUrl;
            this.dbUser = firstNonBlank(System.getenv("DB_USER"), System.getenv("MYSQLUSER"), "root");
            this.dbPassword = firstNonEmpty(System.getenv("DB_PASSWORD"), System.getenv("MYSQLPASSWORD"));
            this.engineDescription = "MYSQL";
        } else if (mysqlHost != null && !mysqlHost.isBlank()) {
            String port = firstNonBlank(System.getenv("MYSQLPORT"), "3306");
            String db = firstNonBlank(System.getenv("MYSQLDATABASE"), "spice_garden");
            this.jdbcUrl = "jdbc:mysql://" + mysqlHost.trim() + ":" + port.trim() + "/" + db.trim()
                    + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            this.dbUser = firstNonBlank(System.getenv("MYSQLUSER"), System.getenv("DB_USER"), "root");
            this.dbPassword = firstNonEmpty(System.getenv("MYSQLPASSWORD"), System.getenv("DB_PASSWORD"));
            this.engineDescription = "MYSQL";
        } else {
            String dataDir = firstNonBlank(System.getenv("DATA_DIR"), "./data");
            this.jdbcUrl = "jdbc:h2:file:" + dataDir + "/spicegarden;MODE=MySQL;DATABASE_TO_LOWER=TRUE";
            this.dbUser = "sa";
            this.dbPassword = "";
            this.engineDescription = "H2_MYSQL_PERSISTENT";
        }

        loadDriver();
    }

    private void loadDriver() {
        try {
            if ("MYSQL".equals(engineDescription)) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } else {
                Class.forName("org.h2.Driver");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("JDBC driver not found for " + engineDescription, e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
    }

    @Override
    public String getEngineDescription() {
        return engineDescription;
    }

    @Override
    public void initSchemaAndSeed(Restaurant restaurant) {
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS menu_items (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(120) NOT NULL,
                    price DECIMAL(10,2) NOT NULL,
                    category VARCHAR(40) NOT NULL,
                    item_type VARCHAR(20) NOT NULL,
                    spice_level INT DEFAULT 1
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS dining_tables (
                    table_number INT PRIMARY KEY,
                    zone VARCHAR(40) NOT NULL,
                    capacity INT NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
                    active_session_id BIGINT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS table_sessions (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    table_number INT NOT NULL,
                    join_code VARCHAR(16) NOT NULL UNIQUE,
                    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                    started_at TIMESTAMP NOT NULL,
                    ended_at TIMESTAMP NULL,
                    subtotal DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                    gst DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                    total DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                    payment_mode VARCHAR(30) NOT NULL DEFAULT 'NOT PAID'
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS session_customers (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    session_id BIGINT NOT NULL,
                    customer_name VARCHAR(100) NOT NULL,
                    joined_at TIMESTAMP NOT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    session_id BIGINT NOT NULL,
                    customer_id BIGINT NOT NULL,
                    customer_name VARCHAR(100) NOT NULL,
                    table_number INT NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'PLACED',
                    subtotal DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                    gst DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                    total DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                    payment_mode VARCHAR(30) NOT NULL DEFAULT 'NOT PAID',
                    created_at TIMESTAMP NOT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS order_items (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    order_id INT NOT NULL,
                    menu_item_id INT NOT NULL,
                    item_name VARCHAR(120) NOT NULL,
                    unit_price DECIMAL(10,2) NOT NULL,
                    quantity INT NOT NULL,
                    line_total DECIMAL(10,2) NOT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS feedback (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    session_id BIGINT NOT NULL,
                    table_number INT NOT NULL,
                    customer_name VARCHAR(100) NOT NULL,
                    rating INT NOT NULL,
                    category VARCHAR(40) NOT NULL,
                    comment VARCHAR(1000) NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
            """);

            seedTablesIfEmpty(conn);
            seedAndLoadMenu(conn, restaurant);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize database (" + engineDescription + "): " + e.getMessage(), e);
        }
    }

    private void seedTablesIfEmpty(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM dining_tables")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        Object[][] defaults = {
                {1, Table.TableZone.WINDOW_ALCOVE.name(), 2},
                {2, Table.TableZone.WINDOW_ALCOVE.name(), 2},
                {3, Table.TableZone.WINDOW_ALCOVE.name(), 4},
                {4, Table.TableZone.GARDEN_COURTYARD.name(), 4},
                {5, Table.TableZone.GARDEN_COURTYARD.name(), 4},
                {6, Table.TableZone.GARDEN_COURTYARD.name(), 6},
                {7, Table.TableZone.MAIN_HALL.name(), 4},
                {8, Table.TableZone.MAIN_HALL.name(), 4},
                {9, Table.TableZone.CORNER_LOUNGE.name(), 6},
                {10, Table.TableZone.CORNER_LOUNGE.name(), 6}
        };

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO dining_tables (table_number, zone, capacity, status, active_session_id) VALUES (?, ?, ?, 'AVAILABLE', NULL)")) {
            for (Object[] row : defaults) {
                ps.setInt(1, (Integer) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setInt(3, (Integer) row[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void seedAndLoadMenu(Connection conn, Restaurant restaurant) throws SQLException {
        int count = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM menu_items")) {
            if (rs.next()) count = rs.getInt(1);
        }

        if (count == 0) {
            List<MenuItem> seed = List.of(
                    new VegItem("Paneer Tikka", 180, Category.STARTER),
                    new NonVegItem("Chicken Wings", 220, Category.STARTER, 4),
                    new VegItem("Veg Biryani", 200, Category.MAIN_COURSE),
                    new NonVegItem("Butter Chicken", 320, Category.MAIN_COURSE, 3),
                    new VegItem("Gulab Jamun", 80, Category.DESSERT),
                    new VegItem("Masala Chai", 40, Category.DRINK)
            );
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO menu_items (name, price, category, item_type, spice_level) VALUES (?, ?, ?, ?, ?)")) {
                for (MenuItem item : seed) {
                    boolean nonVeg = item instanceof NonVegItem;
                    ps.setString(1, item.getName());
                    ps.setDouble(2, item.getPrice());
                    ps.setString(3, item.getCategory().name());
                    ps.setString(4, nonVeg ? "NON_VEG" : "VEG");
                    ps.setInt(5, nonVeg ? ((NonVegItem) item).getSpiceLevel() : 1);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name, price, category, item_type, spice_level FROM menu_items ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                double price = rs.getDouble("price");
                Category cat = Category.valueOf(rs.getString("category"));
                String type = rs.getString("item_type");
                int spice = rs.getInt("spice_level");
                MenuItem item = "NON_VEG".equals(type)
                        ? new NonVegItem(id, name, price, cat, Math.max(1, Math.min(5, spice)))
                        : new VegItem(id, name, price, cat);
                restaurant.addToMenu(item);
            }
        }
    }

    @Override
    public List<Table> listTables() {
        List<Table> tables = new ArrayList<>();
        String sql = "SELECT table_number, zone, capacity, status, active_session_id FROM dining_tables ORDER BY table_number";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Long sid = rs.getObject("active_session_id") == null ? null : rs.getLong("active_session_id");
                tables.add(new Table(
                        rs.getInt("table_number"),
                        rs.getInt("capacity"),
                        Table.TableZone.valueOf(rs.getString("zone")),
                        Table.TableStatus.valueOf(rs.getString("status")),
                        sid
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list tables", e);
        }
        return tables;
    }

    @Override
    public Pair<TableSession, Customer> createTableSession(int tableNumber, String customerName) {
        Customer validated = new Customer(customerName, tableNumber);
        Instant now = Instant.now();

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                // Atomic conditional update prevents concurrent double-booking of the same table
                int updated;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE dining_tables SET status = 'OCCUPIED' WHERE table_number = ? AND status = 'AVAILABLE'")) {
                    ps.setInt(1, tableNumber);
                    updated = ps.executeUpdate();
                }

                if (updated == 0) {
                    boolean exists = tableExists(conn, tableNumber);
                    conn.rollback();
                    if (!exists) {
                        throw new IllegalArgumentException("Unknown table number: " + tableNumber);
                    }
                    throw new TableOccupiedException("Table " + tableNumber + " is already occupied. Enter its join code to join.");
                }

                String joinCode = generateUniqueJoinCode(conn, tableNumber);
                long sessionId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO table_sessions (table_number, join_code, status, started_at) VALUES (?, ?, 'ACTIVE', ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, tableNumber);
                    ps.setString(2, joinCode);
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        sessionId = keys.getLong(1);
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE dining_tables SET active_session_id = ? WHERE table_number = ?")) {
                    ps.setLong(1, sessionId);
                    ps.setInt(2, tableNumber);
                    ps.executeUpdate();
                }

                long customerId = insertCustomer(conn, sessionId, validated.getName(), now);
                conn.commit();

                TableSession session = loadSessionInternal(conn, sessionId);
                Customer customer = new Customer(customerId, validated.getName(), tableNumber, sessionId, now);
                return new Pair<>(session, customer);
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("Failed to book table", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error while booking table", e);
        }
    }

    @Override
    public Pair<TableSession, Customer> joinTableSession(String joinCode, String customerName) {
        if (joinCode == null || joinCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Join code is required");
        }
        String cleanCode = joinCode.trim().toUpperCase(Locale.ROOT);
        String cleanName = new Customer(customerName, 1).getName();
        Instant now = Instant.now();

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                long sessionId;
                int tableNumber;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, table_number FROM table_sessions WHERE UPPER(join_code) = ? AND status = 'ACTIVE'")) {
                    ps.setString(1, cleanCode);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("No active table session matches code: " + cleanCode);
                        }
                        sessionId = rs.getLong("id");
                        tableNumber = rs.getInt("table_number");
                    }
                }

                long customerId = insertCustomer(conn, sessionId, cleanName, now);
                conn.commit();

                TableSession session = loadSessionInternal(conn, sessionId);
                Customer customer = new Customer(customerId, cleanName, tableNumber, sessionId, now);
                return new Pair<>(session, customer);
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("Failed to join table session", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error while joining table", e);
        }
    }

    @Override
    public TableSession getSession(long sessionId) {
        try (Connection conn = openConnection()) {
            return loadSessionInternal(conn, sessionId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load session " + sessionId, e);
        }
    }

    @Override
    public TableSession getSessionByJoinCode(String joinCode) {
        if (joinCode == null || joinCode.isBlank()) {
            throw new IllegalArgumentException("Join code is required");
        }
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM table_sessions WHERE UPPER(join_code) = ?")) {
            ps.setString(1, joinCode.trim().toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Unknown join code: " + joinCode);
                }
                return loadSessionInternal(conn, rs.getLong("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load session by join code", e);
        }
    }

    @Override
    public List<TableSession> listActiveSessions() {
        List<TableSession> list = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM table_sessions WHERE status = 'ACTIVE' ORDER BY table_number")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(loadSessionInternal(conn, rs.getLong("id")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list active sessions", e);
        }
        return list;
    }

    @Override
    public TableSession addOrderItem(long sessionId, long customerId, MenuItem item, int quantity) {
        if (item == null) throw new IllegalArgumentException("Invalid menu item");
        if (quantity < 1 || quantity > 50) throw new IllegalArgumentException("Quantity must be between 1 and 50");

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                SessionHeader header = lockActiveSession(conn, sessionId);
                Customer customer = loadCustomerInSession(conn, sessionId, customerId, header.tableNumber);

                // Find customer's open PLACED order or create a new one
                Integer orderId = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM orders WHERE session_id = ? AND customer_id = ? AND status = 'PLACED' ORDER BY id DESC LIMIT 1")) {
                    ps.setLong(1, sessionId);
                    ps.setLong(2, customerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            orderId = rs.getInt("id");
                        }
                    }
                }

                if (orderId == null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO orders (session_id, customer_id, customer_name, table_number, status, created_at) VALUES (?, ?, ?, ?, 'PLACED', ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setLong(1, sessionId);
                        ps.setLong(2, customerId);
                        ps.setString(3, customer.getName());
                        ps.setInt(4, header.tableNumber);
                        ps.setTimestamp(5, Timestamp.from(Instant.now()));
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            keys.next();
                            orderId = keys.getInt(1);
                        }
                    }
                }

                // Merge with existing line for same menu item inside this PLACED order
                Long existingLineId = null;
                int existingQty = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, quantity FROM order_items WHERE order_id = ? AND menu_item_id = ? LIMIT 1")) {
                    ps.setInt(1, orderId);
                    ps.setInt(2, item.getId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            existingLineId = rs.getLong("id");
                            existingQty = rs.getInt("quantity");
                        }
                    }
                }

                if (existingLineId != null) {
                    int newQty = existingQty + quantity;
                    double lineTotal = Billable.round(item.getPrice() * newQty);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE order_items SET quantity = ?, line_total = ? WHERE id = ?")) {
                        ps.setInt(1, newQty);
                        ps.setDouble(2, lineTotal);
                        ps.setLong(3, existingLineId);
                        ps.executeUpdate();
                    }
                } else {
                    double lineTotal = Billable.round(item.getPrice() * quantity);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO order_items (order_id, menu_item_id, item_name, unit_price, quantity, line_total) VALUES (?, ?, ?, ?, ?, ?)")) {
                        ps.setInt(1, orderId);
                        ps.setInt(2, item.getId());
                        ps.setString(3, item.getName());
                        ps.setDouble(4, item.getPrice());
                        ps.setInt(5, quantity);
                        ps.setDouble(6, lineTotal);
                        ps.executeUpdate();
                    }
                }

                recalculateTotals(conn, orderId, sessionId);
                conn.commit();
                return loadSessionInternal(conn, sessionId);
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("Failed to add item", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error while adding item", e);
        }
    }

    @Override
    public TableSession removeOrderLine(long sessionId, long customerId, int orderId, int lineIndex) {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                lockActiveSession(conn, sessionId);

                String orderStatus;
                long orderOwnerId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT customer_id, status FROM orders WHERE id = ? AND session_id = ?")) {
                    ps.setInt(1, orderId);
                    ps.setLong(2, sessionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("Order not found in this table session");
                        }
                        orderOwnerId = rs.getLong("customer_id");
                        orderStatus = rs.getString("status");
                    }
                }

                if (orderOwnerId != customerId) {
                    throw new IllegalStateException("You can only remove items from your own order");
                }
                if (!"PLACED".equals(orderStatus)) {
                    throw new IllegalStateException("Items already sent to the kitchen cannot be removed");
                }

                List<Long> lineIds = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM order_items WHERE order_id = ? ORDER BY id")) {
                    ps.setInt(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) lineIds.add(rs.getLong("id"));
                    }
                }

                if (lineIndex < 0 || lineIndex >= lineIds.size()) {
                    throw new IllegalArgumentException("Invalid item line number");
                }

                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM order_items WHERE id = ?")) {
                    ps.setLong(1, lineIds.get(lineIndex));
                    ps.executeUpdate();
                }

                if (lineIds.size() == 1) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM orders WHERE id = ?")) {
                        ps.setInt(1, orderId);
                        ps.executeUpdate();
                    }
                } else {
                    recalculateOrderTotals(conn, orderId);
                }

                recalculateSessionTotals(conn, sessionId);
                conn.commit();
                return loadSessionInternal(conn, sessionId);
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("Failed to remove item", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error while removing item", e);
        }
    }

    @Override
    public TableSession sendOrderToKitchen(long sessionId, long customerId, Integer orderId) {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                lockActiveSession(conn, sessionId);
                int updated;
                if (orderId != null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE orders SET status = 'PREPARING' WHERE id = ? AND session_id = ? AND customer_id = ? AND status = 'PLACED'")) {
                        ps.setInt(1, orderId);
                        ps.setLong(2, sessionId);
                        ps.setLong(3, customerId);
                        updated = ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE orders SET status = 'PREPARING' WHERE session_id = ? AND customer_id = ? AND status = 'PLACED'")) {
                        ps.setLong(1, sessionId);
                        ps.setLong(2, customerId);
                        updated = ps.executeUpdate();
                    }
                }
                if (updated == 0) {
                    throw new IllegalStateException("No pending items to send to the kitchen");
                }
                conn.commit();
                return loadSessionInternal(conn, sessionId);
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("Failed to send order to kitchen", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error sending to kitchen", e);
        }
    }

    @Override
    public Order updateOrderStatus(int orderId, OrderStatus newStatus) {
        if (newStatus == null) throw new IllegalArgumentException("Status is required");
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE orders SET status = ? WHERE id = ?")) {
                    ps.setString(1, newStatus.name());
                    ps.setInt(2, orderId);
                    if (ps.executeUpdate() == 0) {
                        throw new IllegalArgumentException("Unknown order: " + orderId);
                    }
                }
                Order order = loadSingleOrder(conn, orderId);
                conn.commit();
                return order;
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("Failed to update order status", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error updating order status", e);
        }
    }

    @Override
    public TableSession checkoutSession(long sessionId, String paymentMode) throws InvalidOrderException {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                SessionHeader header = lockActiveSession(conn, sessionId);
                recalculateSessionTotals(conn, sessionId);

                TableSession current = loadSessionInternal(conn, sessionId);
                current.validateForCheckout();

                Payment payment = Payment.create(paymentMode, current.getTotal());
                payment.process();

                Instant endedAt = Instant.now();
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE table_sessions SET status = 'COMPLETED', ended_at = ?, payment_mode = ?, subtotal = ?, gst = ?, total = ? WHERE id = ?")) {
                    ps.setTimestamp(1, Timestamp.from(endedAt));
                    ps.setString(2, payment.getMode());
                    ps.setDouble(3, current.getPrice());
                    ps.setDouble(4, current.getGst());
                    ps.setDouble(5, current.getTotal());
                    ps.setLong(6, sessionId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE orders SET payment_mode = ?, status = CASE WHEN status = 'PLACED' THEN 'PAID' ELSE status END WHERE session_id = ? AND status <> 'CANCELLED'")) {
                    ps.setString(1, payment.getMode());
                    ps.setLong(2, sessionId);
                    ps.executeUpdate();
                }

                // Automatically release table upon checkout
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE dining_tables SET status = 'AVAILABLE', active_session_id = NULL WHERE table_number = ?")) {
                    ps.setInt(1, header.tableNumber);
                    ps.executeUpdate();
                }

                conn.commit();
                return loadSessionInternal(conn, sessionId);
            } catch (InvalidOrderException e) {
                conn.rollback();
                throw e;
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("Failed to complete checkout", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error during checkout", e);
        }
    }

    @Override
    public TableSession adminReleaseTable(int tableNumber, String paymentMode) throws InvalidOrderException {
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                Long activeSessionId = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT active_session_id FROM dining_tables WHERE table_number = ?")) {
                    ps.setInt(1, tableNumber);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("Unknown table number: " + tableNumber);
                        }
                        if (rs.getObject("active_session_id") != null) {
                            activeSessionId = rs.getLong("active_session_id");
                        }
                    }
                }

                if (activeSessionId == null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE dining_tables SET status = 'AVAILABLE', active_session_id = NULL WHERE table_number = ?")) {
                        ps.setInt(1, tableNumber);
                        ps.executeUpdate();
                    }
                    conn.commit();
                    return null;
                }

                TableSession current = loadSessionInternal(conn, activeSessionId);
                Instant endedAt = Instant.now();

                if (current.hasBillableItems()) {
                    String mode = (paymentMode == null || paymentMode.isBlank()) ? "CASH" : paymentMode;
                    Payment payment = Payment.create(mode, current.getTotal());
                    payment.process();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE table_sessions SET status = 'COMPLETED', ended_at = ?, payment_mode = ?, subtotal = ?, gst = ?, total = ? WHERE id = ?")) {
                        ps.setTimestamp(1, Timestamp.from(endedAt));
                        ps.setString(2, payment.getMode());
                        ps.setDouble(3, current.getPrice());
                        ps.setDouble(4, current.getGst());
                        ps.setDouble(5, current.getTotal());
                        ps.setLong(6, activeSessionId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE orders SET payment_mode = ?, status = CASE WHEN status = 'PLACED' THEN 'PAID' ELSE status END WHERE session_id = ? AND status <> 'CANCELLED'")) {
                        ps.setString(1, payment.getMode());
                        ps.setLong(2, activeSessionId);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE table_sessions SET status = 'CANCELLED', ended_at = ? WHERE id = ?")) {
                        ps.setTimestamp(1, Timestamp.from(endedAt));
                        ps.setLong(2, activeSessionId);
                        ps.executeUpdate();
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE dining_tables SET status = 'AVAILABLE', active_session_id = NULL WHERE table_number = ?")) {
                    ps.setInt(1, tableNumber);
                    ps.executeUpdate();
                }

                conn.commit();
                return loadSessionInternal(conn, activeSessionId);
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof InvalidOrderException) throw (InvalidOrderException) e;
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("Failed to release table", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error releasing table", e);
        }
    }

    @Override
    public Feedback saveFeedback(long sessionId, int tableNumber, String customerName, int rating, String category, String comment) {
        Instant now = Instant.now();
        Feedback validated = new Feedback(0L, sessionId, tableNumber, customerName, rating, category, comment, now);

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                long id;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO feedback (session_id, table_number, customer_name, rating, category, comment, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, validated.getSessionId());
                    ps.setInt(2, validated.getTableNumber());
                    ps.setString(3, validated.getCustomerName());
                    ps.setInt(4, validated.getRating());
                    ps.setString(5, validated.getCategory());
                    ps.setString(6, validated.getComment());
                    ps.setTimestamp(7, Timestamp.from(now));
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        id = keys.getLong(1);
                    }
                }
                conn.commit();
                return new Feedback(id, validated.getSessionId(), validated.getTableNumber(),
                        validated.getCustomerName(), validated.getRating(), validated.getCategory(),
                        validated.getComment(), now);
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("Failed to save feedback", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error saving feedback", e);
        }
    }

    @Override
    public List<Feedback> listFeedback(Integer minRating) {
        List<Feedback> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, session_id, table_number, customer_name, rating, category, comment, created_at FROM feedback");
        if (minRating != null) {
            sql.append(" WHERE rating >= ?");
        }
        sql.append(" ORDER BY created_at DESC, id DESC");

        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (minRating != null) ps.setInt(1, minRating);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapFeedback(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list feedback", e);
        }
        return list;
    }

    @Override
    public Map<Long, List<Feedback>> mapFeedbackBySession() {
        Map<Long, List<Feedback>> map = new HashMap<>();
        for (Feedback fb : listFeedback(null)) {
            map.computeIfAbsent(fb.getSessionId(), k -> new ArrayList<>()).add(fb);
        }
        return map;
    }

    @Override
    public List<TableSession> searchHistory(
            String query,
            Integer tableNumber,
            String paymentMode,
            String status,
            String fromDate,
            String toDate
    ) {
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ts.id FROM table_sessions ts WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status.trim())) {
            sql.append(" AND ts.status = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        } else {
            sql.append(" AND ts.status IN ('COMPLETED', 'CANCELLED')");
        }

        if (tableNumber != null) {
            sql.append(" AND ts.table_number = ?");
            params.add(tableNumber);
        }

        if (paymentMode != null && !paymentMode.isBlank() && !"ALL".equalsIgnoreCase(paymentMode.trim())) {
            sql.append(" AND UPPER(ts.payment_mode) = ?");
            params.add(paymentMode.trim().toUpperCase(Locale.ROOT));
        }

        if (fromDate != null && !fromDate.isBlank()) {
            Instant from = LocalDate.parse(fromDate.trim()).atStartOfDay().toInstant(ZoneOffset.UTC);
            sql.append(" AND ts.started_at >= ?");
            params.add(Timestamp.from(from));
        }

        if (toDate != null && !toDate.isBlank()) {
            Instant to = LocalDate.parse(toDate.trim()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            sql.append(" AND ts.started_at < ?");
            params.add(Timestamp.from(to));
        }

        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append("""
                 AND (
                    LOWER(ts.join_code) LIKE ?
                    OR CAST(ts.id AS CHAR) LIKE ?
                    OR EXISTS (SELECT 1 FROM session_customers sc WHERE sc.session_id = ts.id AND LOWER(sc.customer_name) LIKE ?)
                    OR EXISTS (SELECT 1 FROM orders o JOIN order_items oi ON oi.order_id = o.id WHERE o.session_id = ts.id AND LOWER(oi.item_name) LIKE ?)
                )
            """);
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }

        sql.append(" ORDER BY ts.id DESC LIMIT 200");

        List<TableSession> results = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer) ps.setInt(i + 1, (Integer) p);
                else if (p instanceof Timestamp) ps.setTimestamp(i + 1, (Timestamp) p);
                else ps.setString(i + 1, String.valueOf(p));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(loadSessionInternal(conn, rs.getLong("id")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to search order history", e);
        }
        return results;
    }

    // ===================== INTERNAL HELPERS =====================

    private static class SessionHeader {
        final long sessionId;
        final int tableNumber;
        final String status;

        SessionHeader(long sessionId, int tableNumber, String status) {
            this.sessionId = sessionId;
            this.tableNumber = tableNumber;
            this.status = status;
        }
    }

    private SessionHeader lockActiveSession(Connection conn, long sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, table_number, status FROM table_sessions WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Table session not found: " + sessionId);
                }
                String status = rs.getString("status");
                if (!"ACTIVE".equals(status)) {
                    throw new IllegalStateException("This table session is already " + status.toLowerCase(Locale.ROOT));
                }
                return new SessionHeader(rs.getLong("id"), rs.getInt("table_number"), status);
            }
        }
    }

    private boolean tableExists(Connection conn, int tableNumber) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM dining_tables WHERE table_number = ?")) {
            ps.setInt(1, tableNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String generateUniqueJoinCode(Connection conn, int tableNumber) throws SQLException {
        for (int attempt = 0; attempt < 25; attempt++) {
            StringBuilder sb = new StringBuilder("T").append(tableNumber).append('-');
            for (int i = 0; i < 4; i++) {
                sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
            String candidate = sb.toString();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM table_sessions WHERE join_code = ?")) {
                ps.setString(1, candidate);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return candidate;
                }
            }
        }
        return "T" + tableNumber + "-" + System.currentTimeMillis() % 100000;
    }

    private long insertCustomer(Connection conn, long sessionId, String name, Instant joinedAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO session_customers (session_id, customer_name, joined_at) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sessionId);
            ps.setString(2, name);
            ps.setTimestamp(3, Timestamp.from(joinedAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private Customer loadCustomerInSession(Connection conn, long sessionId, long customerId, int tableNumber) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, customer_name, joined_at FROM session_customers WHERE id = ? AND session_id = ?")) {
            ps.setLong(1, customerId);
            ps.setLong(2, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Guest is not registered in this table session");
                }
                return new Customer(
                        rs.getLong("id"),
                        rs.getString("customer_name"),
                        tableNumber,
                        sessionId,
                        rs.getTimestamp("joined_at").toInstant()
                );
            }
        }
    }

    private void recalculateTotals(Connection conn, int orderId, long sessionId) throws SQLException {
        recalculateOrderTotals(conn, orderId);
        recalculateSessionTotals(conn, sessionId);
    }

    private void recalculateOrderTotals(Connection conn, int orderId) throws SQLException {
        double subtotal = 0.0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(line_total), 0) FROM order_items WHERE order_id = ?")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) subtotal = Billable.round(rs.getDouble(1));
            }
        }
        double total = Billable.round(subtotal * (1.0 + Billable.GST_RATE));
        double gst = Billable.round(total - subtotal);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE orders SET subtotal = ?, gst = ?, total = ? WHERE id = ?")) {
            ps.setDouble(1, subtotal);
            ps.setDouble(2, gst);
            ps.setDouble(3, total);
            ps.setInt(4, orderId);
            ps.executeUpdate();
        }
    }

    private void recalculateSessionTotals(Connection conn, long sessionId) throws SQLException {
        double subtotal = 0.0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(subtotal), 0) FROM orders WHERE session_id = ? AND status <> 'CANCELLED'")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) subtotal = Billable.round(rs.getDouble(1));
            }
        }
        double total = Billable.round(subtotal * (1.0 + Billable.GST_RATE));
        double gst = Billable.round(total - subtotal);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE table_sessions SET subtotal = ?, gst = ?, total = ? WHERE id = ?")) {
            ps.setDouble(1, subtotal);
            ps.setDouble(2, gst);
            ps.setDouble(3, total);
            ps.setLong(4, sessionId);
            ps.executeUpdate();
        }
    }

    private TableSession loadSessionInternal(Connection conn, long sessionId) throws SQLException {
        TableSession session;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, table_number, join_code, status, started_at, ended_at, payment_mode FROM table_sessions WHERE id = ?")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Unknown table session: " + sessionId);
                }
                Timestamp endedTs = rs.getTimestamp("ended_at");
                session = new TableSession(
                        rs.getLong("id"),
                        rs.getInt("table_number"),
                        rs.getString("join_code"),
                        rs.getTimestamp("started_at").toInstant(),
                        endedTs == null ? null : endedTs.toInstant(),
                        TableSession.SessionStatus.valueOf(rs.getString("status")),
                        rs.getString("payment_mode")
                );
            }
        }

        Map<Long, Customer> customerMap = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, customer_name, joined_at FROM session_customers WHERE session_id = ? ORDER BY id")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Customer c = new Customer(
                            rs.getLong("id"),
                            rs.getString("customer_name"),
                            session.getTableNumber(),
                            sessionId,
                            rs.getTimestamp("joined_at").toInstant()
                    );
                    customerMap.put(c.getCustomerId(), c);
                    session.addCustomer(c);
                }
            }
        }

        Map<Integer, Order> orderMap = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, customer_id, customer_name, table_number, status, payment_mode, created_at FROM orders WHERE session_id = ? ORDER BY id")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long cid = rs.getLong("customer_id");
                    Customer c = customerMap.get(cid);
                    if (c == null) {
                        c = new Customer(cid, rs.getString("customer_name"), rs.getInt("table_number"), sessionId, rs.getTimestamp("created_at").toInstant());
                    }
                    Order order = new Order(
                            rs.getInt("id"),
                            sessionId,
                            c,
                            OrderStatus.valueOf(rs.getString("status")),
                            rs.getString("payment_mode"),
                            rs.getTimestamp("created_at").toInstant()
                    );
                    orderMap.put(order.getOrderId(), order);
                    session.addOrder(order);
                }
            }
        }

        if (!orderMap.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT oi.order_id, oi.menu_item_id, oi.item_name, oi.unit_price, oi.quantity, COALESCE(mi.category, 'MAIN_COURSE') AS category, COALESCE(mi.item_type, 'VEG') AS item_type, COALESCE(mi.spice_level, 1) AS spice_level "
                    + "FROM order_items oi LEFT JOIN menu_items mi ON oi.menu_item_id = mi.id "
                    + "JOIN orders o ON oi.order_id = o.id WHERE o.session_id = ? ORDER BY oi.id")) {
                ps.setLong(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Order order = orderMap.get(rs.getInt("order_id"));
                        if (order == null) continue;
                        int menuId = rs.getInt("menu_item_id");
                        String name = rs.getString("item_name");
                        double price = rs.getDouble("unit_price");
                        Category cat = Category.valueOf(rs.getString("category"));
                        String type = rs.getString("item_type");
                        int spice = rs.getInt("spice_level");
                        MenuItem item = "NON_VEG".equals(type)
                                ? new NonVegItem(menuId, name, price, cat, Math.max(1, Math.min(5, spice)))
                                : new VegItem(menuId, name, price, cat);
                        order.addLineRaw(item, rs.getInt("quantity"));
                    }
                }
            }
        }

        return session;
    }

    private Order loadSingleOrder(Connection conn, int orderId) throws SQLException {
        long sessionId;
        try (PreparedStatement ps = conn.prepareStatement("SELECT session_id FROM orders WHERE id = ?")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Unknown order: " + orderId);
                sessionId = rs.getLong("session_id");
            }
        }
        TableSession s = loadSessionInternal(conn, sessionId);
        for (Order o : s.getOrders()) {
            if (o.getOrderId() == orderId) return o;
        }
        throw new IllegalArgumentException("Unknown order: " + orderId);
    }

    private Feedback mapFeedback(ResultSet rs) throws SQLException {
        return new Feedback(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getInt("table_number"),
                rs.getString("customer_name"),
                rs.getInt("rating"),
                rs.getString("category"),
                rs.getString("comment"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null) return v;
        }
        return "";
    }
}
