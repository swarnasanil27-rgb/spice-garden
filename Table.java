import java.util.Objects;

/**
 * Represents a physical dining table in Spice Garden.
 * Encapsulates table identity, zone, seating capacity, and occupancy state.
 */
public class Table {

    public enum TableStatus {
        AVAILABLE,
        OCCUPIED
    }

    public enum TableZone {
        WINDOW_ALCOVE("Window Alcove"),
        GARDEN_COURTYARD("Garden Courtyard"),
        MAIN_HALL("Main Dining Hall"),
        CORNER_LOUNGE("Corner Lounge");

        private final String displayName;

        TableZone(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private final int tableNumber;
    private final String label;
    private final int capacity;
    private final TableZone zone;
    private volatile TableStatus status;
    private volatile Long activeSessionId;

    public Table(int tableNumber, int capacity, TableZone zone) {
        this(tableNumber, capacity, zone, TableStatus.AVAILABLE, null);
    }

    public Table(int tableNumber, int capacity, TableZone zone, TableStatus status, Long activeSessionId) {
        if (tableNumber < 1 || tableNumber > Customer.MAX_TABLES) {
            throw new IllegalArgumentException("Table number must be between 1 and " + Customer.MAX_TABLES);
        }
        if (capacity < 1 || capacity > 12) {
            throw new IllegalArgumentException("Table capacity must be between 1 and 12");
        }
        this.tableNumber = tableNumber;
        this.label = String.format("T-%02d", tableNumber);
        this.capacity = capacity;
        this.zone = Objects.requireNonNull(zone, "Table zone is required");
        this.status = status == null ? TableStatus.AVAILABLE : status;
        this.activeSessionId = activeSessionId;
    }

    public synchronized void occupy(long sessionId) {
        if (this.status == TableStatus.OCCUPIED) {
            throw new IllegalStateException("Table " + tableNumber + " is already occupied");
        }
        this.status = TableStatus.OCCUPIED;
        this.activeSessionId = sessionId;
    }

    public synchronized void release() {
        this.status = TableStatus.AVAILABLE;
        this.activeSessionId = null;
    }

    public int getTableNumber() {
        return tableNumber;
    }

    public String getLabel() {
        return label;
    }

    public int getCapacity() {
        return capacity;
    }

    public TableZone getZone() {
        return zone;
    }

    public TableStatus getStatus() {
        return status;
    }

    public Long getActiveSessionId() {
        return activeSessionId;
    }

    public boolean isAvailable() {
        return status == TableStatus.AVAILABLE;
    }

    public boolean isOccupied() {
        return status == TableStatus.OCCUPIED;
    }
}
