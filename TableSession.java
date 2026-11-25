import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an active or completed shared dining session at a table.
 * Multiple customers can join via join code / QR code and order independently
 * while sharing a consolidated running bill.
 */
public class TableSession implements Billable {

    public enum SessionStatus {
        ACTIVE,
        COMPLETED,
        CANCELLED
    }

    private final long sessionId;
    private final int tableNumber;
    private final String joinCode;
    private final Instant startedAt;
    private volatile Instant endedAt;
    private volatile SessionStatus status;
    private volatile String paymentMode;

    private final List<Customer> customers = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();

    public TableSession(long sessionId, int tableNumber, String joinCode, Instant startedAt) {
        this(sessionId, tableNumber, joinCode, startedAt, null, SessionStatus.ACTIVE, "NOT PAID");
    }

    public TableSession(
            long sessionId,
            int tableNumber,
            String joinCode,
            Instant startedAt,
            Instant endedAt,
            SessionStatus status,
            String paymentMode
    ) {
        if (tableNumber < 1 || tableNumber > Customer.MAX_TABLES) {
            throw new IllegalArgumentException("Invalid table number: " + tableNumber);
        }
        if (joinCode == null || joinCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Join code cannot be empty");
        }
        this.sessionId = sessionId;
        this.tableNumber = tableNumber;
        this.joinCode = joinCode.trim().toUpperCase();
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt is required");
        this.endedAt = endedAt;
        this.status = status == null ? SessionStatus.ACTIVE : status;
        this.paymentMode = paymentMode == null || paymentMode.isBlank() ? "NOT PAID" : paymentMode.trim();
    }

    public synchronized void addCustomer(Customer customer) {
        if (customer == null) {
            throw new IllegalArgumentException("Customer cannot be null");
        }
        for (Customer existing : customers) {
            if (existing.getCustomerId() > 0 && existing.getCustomerId() == customer.getCustomerId()) {
                return;
            }
        }
        customers.add(customer);
    }

    public synchronized void addOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order cannot be null");
        }
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).getOrderId() == order.getOrderId()) {
                orders.set(i, order);
                return;
            }
        }
        orders.add(order);
    }

    public synchronized boolean hasBillableItems() {
        for (Order order : orders) {
            if (order.getStatus() != OrderStatus.CANCELLED && !order.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public synchronized void validateForCheckout() throws InvalidOrderException {
        if (status != SessionStatus.ACTIVE) {
            throw new InvalidOrderException("This table session is already closed.");
        }
        if (!hasBillableItems()) {
            throw new InvalidOrderException("Please add at least one item before checkout.");
        }
    }

    public synchronized void completeCheckout(Payment payment) throws InvalidOrderException {
        validateForCheckout();
        if (payment == null) {
            throw new IllegalArgumentException("Payment cannot be null");
        }
        payment.process();
        this.paymentMode = payment.getMode();
        this.status = SessionStatus.COMPLETED;
        this.endedAt = Instant.now();
        for (Order order : orders) {
            if (order.getStatus() != OrderStatus.CANCELLED) {
                order.markSettled(payment.getMode());
            }
        }
    }

    @Override
    public synchronized double getPrice() {
        double sum = 0.0;
        for (Order order : orders) {
            if (order.getStatus() != OrderStatus.CANCELLED) {
                sum += order.getPrice();
            }
        }
        return Billable.round(sum);
    }

    public synchronized double getGst() {
        return Billable.round(getTotal() - getPrice());
    }

    public synchronized double getTotal() {
        return priceWithTax();
    }

    public synchronized int getTotalItemCount() {
        int count = 0;
        for (Order order : orders) {
            if (order.getStatus() != OrderStatus.CANCELLED) {
                for (Pair<MenuItem, Integer> line : order.getLines()) {
                    count += line.getSecond();
                }
            }
        }
        return count;
    }

    public synchronized Map<Long, List<Order>> getOrdersByCustomer() {
        Map<Long, List<Order>> grouped = new LinkedHashMap<>();
        for (Customer c : customers) {
            grouped.put(c.getCustomerId(), new ArrayList<>());
        }
        for (Order order : orders) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                continue;
            }
            long cid = order.getCustomer().getCustomerId();
            grouped.computeIfAbsent(cid, k -> new ArrayList<>()).add(order);
        }
        return grouped;
    }

    public long getDurationSeconds() {
        Instant end = endedAt != null ? endedAt : Instant.now();
        long secs = Duration.between(startedAt, end).getSeconds();
        return Math.max(0L, secs);
    }

    public long getSessionId() {
        return sessionId;
    }

    public int getTableNumber() {
        return tableNumber;
    }

    public String getJoinCode() {
        return joinCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public String getPaymentMode() {
        return paymentMode;
    }

    public synchronized List<Customer> getCustomers() {
        return Collections.unmodifiableList(new ArrayList<>(customers));
    }

    public synchronized List<Order> getOrders() {
        return Collections.unmodifiableList(new ArrayList<>(orders));
    }
}
