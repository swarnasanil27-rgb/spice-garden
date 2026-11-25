import java.time.Instant;
import java.util.Objects;

/**
 * Encapsulates validated customer feedback linked to a dining session.
 */
public class Feedback {

    private final long feedbackId;
    private final long sessionId;
    private final int tableNumber;
    private final String customerName;
    private final int rating;
    private final String category;
    private final String comment;
    private final Instant createdAt;

    public Feedback(
            long feedbackId,
            long sessionId,
            int tableNumber,
            String customerName,
            int rating,
            String category,
            String comment,
            Instant createdAt
    ) {
        if (customerName == null || customerName.trim().isEmpty()) {
            throw new IllegalArgumentException("Customer name is required for feedback");
        }
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5 stars");
        }
        if (tableNumber < 1 || tableNumber > Customer.MAX_TABLES) {
            throw new IllegalArgumentException("Invalid table number");
        }
        String cleanComment = comment == null ? "" : comment.trim();
        if (cleanComment.length() > 1000) {
            throw new IllegalArgumentException("Feedback comment cannot exceed 1000 characters");
        }
        String cleanCategory = (category == null || category.trim().isEmpty()) ? "Overall" : category.trim();

        this.feedbackId = feedbackId;
        this.sessionId = sessionId;
        this.tableNumber = tableNumber;
        this.customerName = customerName.trim();
        this.rating = rating;
        this.category = cleanCategory;
        this.comment = cleanComment;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
    }

    public long getFeedbackId() {
        return feedbackId;
    }

    public long getSessionId() {
        return sessionId;
    }

    public int getTableNumber() {
        return tableNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    public int getRating() {
        return rating;
    }

    public String getCategory() {
        return category;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
