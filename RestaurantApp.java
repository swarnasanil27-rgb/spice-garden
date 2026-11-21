import java.util.*;

// ===================== ENUMS (Module 5) =====================
enum Category {
    STARTER, MAIN_COURSE, DESSERT, DRINK
}

enum OrderStatus {
    PLACED, PAID, PREPARING, READY
}

// ===================== CUSTOM EXCEPTION (Module 4) =====================
class InvalidOrderException extends Exception {
    private static final long serialVersionUID = 1L;

    public InvalidOrderException(String message) {
        super(message);
    }
}

// ===================== INTERFACE (Module 3) =====================
interface Billable {
    double GST_RATE = 0.05;

    double getPrice();

    default double priceWithTax() {
        return round(getPrice() * (1 + GST_RATE));
    }

    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

// ===================== GENERIC CLASS (Module 5) =====================
class Pair<K, V> {
    private final K first;
    private final V second;

    public Pair(K first, V second) {
        this.first = first;
        this.second = second;
    }

    public K getFirst() {
        return first;
    }

    public V getSecond() {
        return second;
    }
}

// ===================== PEOPLE: INHERITANCE =====================
abstract class Person {
    private final String name;

    public Person(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Name cannot be empty");
        }

        this.name = name.trim();
    }

    public String getName() {
        return name;
    }
}

class Customer extends Person {
    public static final int MAX_TABLES = 10;

    private final int tableNumber;

    public Customer(String name, int tableNumber) {
        super(name);

        if (tableNumber < 1 || tableNumber > MAX_TABLES) {
            throw new IllegalArgumentException(
                "Table number must be between 1 and " + MAX_TABLES
            );
        }

        this.tableNumber = tableNumber;
    }

    public int getTableNumber() {
        return tableNumber;
    }
}

// ===================== MENU: ABSTRACTION + OVERRIDING =====================
abstract class MenuItem implements Billable {
    private static int count = 0;

    private final int id;
    private final String name;
    private final double price;
    private final Category category;

    public MenuItem(String name, double price, Category category) {

        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Item name cannot be empty");
        }

        if (!Double.isFinite(price) || price < 0) {
            throw new IllegalArgumentException("Invalid item price");
        }

        if (category == null) {
            throw new IllegalArgumentException("Category cannot be null");
        }

        this.id = ++count;
        this.name = name.trim();
        this.price = price;
        this.category = category;
    }

    public abstract String describe();

    @Override
    public double getPrice() {
        return price;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Category getCategory() {
        return category;
    }
}

class VegItem extends MenuItem {

    public VegItem(String name, double price, Category category) {
        super(name, price, category);
    }

    @Override
    public String describe() {
        return "[VEG]     " + getName();
    }
}

class NonVegItem extends MenuItem {
    private final int spiceLevel;

    public NonVegItem(String name, double price,
                      Category category, int spiceLevel) {

        super(name, price, category);

        if (spiceLevel < 1 || spiceLevel > 5) {
            throw new IllegalArgumentException(
                "Spice level must be between 1 and 5"
            );
        }

        this.spiceLevel = spiceLevel;
    }

    public int getSpiceLevel() {
        return spiceLevel;
    }

    @Override
    public String describe() {
        return "[NON-VEG] " + getName()
                + " (spice " + spiceLevel + "/5)";
    }
}

// ===================== PAYMENT: ABSTRACTION + POLYMORPHISM =====================
abstract class Payment {
    protected final double amount;

    public Payment(double amount) {

        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Invalid payment amount");
        }

        this.amount = amount;
    }

    public abstract void process();

    public abstract String getMode();
}

class CashPayment extends Payment {

    public CashPayment(double amount) {
        super(amount);
    }

    @Override
    public void process() {
        System.out.printf(
            "Please pay Rs.%.2f in cash at the counter.%n", amount
        );
    }

    @Override
    public String getMode() {
        return "CASH";
    }
}

class UpiPayment extends Payment {

    public UpiPayment(double amount) {
        super(amount);
    }

    @Override
    public void process() {
        System.out.printf(
            "Scan the QR code to pay Rs.%.2f via UPI.%n", amount
        );
    }

    @Override
    public String getMode() {
        return "UPI";
    }
}

class CardPayment extends Payment {

    public CardPayment(double amount) {
        super(amount);
    }

    @Override
    public void process() {
        System.out.printf(
            "Swipe your card to pay Rs.%.2f.%n", amount
        );
    }

    @Override
    public String getMode() {
        return "CARD";
    }
}

// ===================== ORDER: OVERLOADING + INNER CLASS =====================
class Order implements Billable {

    private static int counter = 1000;

    private final int orderId = ++counter;
    private final Customer customer;

    private final List<Pair<MenuItem, Integer>> lines =
            new ArrayList<>();

    private volatile OrderStatus status = OrderStatus.PLACED;
    private String paymentMode = "NOT PAID";

    public Order(Customer customer) {

        if (customer == null) {
            throw new IllegalArgumentException(
                "Customer cannot be null"
            );
        }

        this.customer = customer;
    }

    // METHOD OVERLOADING
    public void addItem(MenuItem item) {
        addItem(item, 1);
    }

    public void addItem(MenuItem item, int quantity) {

        if (status != OrderStatus.PLACED) {
            throw new IllegalStateException(
                "Cannot modify an order after payment"
            );
        }

        if (item == null) {
            throw new IllegalArgumentException("Invalid menu item");
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                "Quantity must be at least 1"
            );
        }

        lines.add(new Pair<>(item, quantity));
    }

    // SUBTOTAL
    @Override
    public double getPrice() {

        double sum = 0.0;

        for (Pair<MenuItem, Integer> line : lines) {
            sum += line.getFirst().getPrice()
                    * line.getSecond();
        }

        return sum;
    }

    public double total() {
        return priceWithTax();
    }

    public int getOrderId() {
        return orderId;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    // Read-only view of the order lines (used by the web server)
    public List<Pair<MenuItem, Integer>> getLines() {
        return Collections.unmodifiableList(new ArrayList<>(lines));
    }

    // DISPLAY CART
    public void showCart() {

        if (lines.isEmpty()) {
            System.out.println("Your order is empty.");
            return;
        }

        System.out.println("\n--- Your order so far ---");

        int number = 1;

        for (Pair<MenuItem, Integer> line : lines) {

            System.out.printf(
                "%d. %s x%d   Rs.%.2f%n",
                number++,
                line.getFirst().getName(),
                line.getSecond(),
                line.getFirst().getPrice()
                        * line.getSecond()
            );
        }

        System.out.printf(
            "Subtotal: Rs.%.2f%n", getPrice()
        );
    }

    // REMOVE ITEM
    public void removeItem(int lineNumber) {

        if (status != OrderStatus.PLACED) {
            throw new IllegalStateException(
                "Cannot modify this order"
            );
        }

        if (lineNumber < 1 || lineNumber > lines.size()) {
            throw new IllegalArgumentException(
                "No such line number: " + lineNumber
            );
        }

        lines.remove(lineNumber - 1);
    }

    // CANCEL ORDER
    public void cancelOrder() {

        if (status != OrderStatus.PLACED) {
            throw new IllegalStateException(
                "Only unpaid orders can be cancelled"
            );
        }

        lines.clear();
    }

    // CUSTOM EXCEPTION
    public void validateForBilling()
            throws InvalidOrderException {

        if (lines.isEmpty()) {
            throw new InvalidOrderException(
                "Please add at least one item before billing."
            );
        }
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus newStatus) {

        if (newStatus == null) {
            throw new IllegalArgumentException(
                "Invalid order status"
            );
        }

        status = newStatus;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getPaymentMode() {
        return paymentMode;
    }

    // PAYMENT
    public void setPayment(Payment payment) {

        if (payment == null) {
            throw new IllegalArgumentException(
                "Payment cannot be null"
            );
        }

        if (status != OrderStatus.PLACED || lines.isEmpty()) {
            throw new IllegalStateException(
                "Order is not ready for payment"
            );
        }

        paymentMode = payment.getMode();
        status = OrderStatus.PAID;
    }

    // INNER CLASS: BILL
    class Bill {

        void print() {

            System.out.println("\n========== BILL ==========");

            System.out.println(
                "Order #" + orderId
                + "   Customer: " + customer.getName()
                + "   Table: " + customer.getTableNumber()
            );

            System.out.println("--------------------------");

            for (Pair<MenuItem, Integer> line : lines) {

                MenuItem item = line.getFirst();

                System.out.printf(
                    "%-18s x%d   Rs.%.2f%n",
                    item.getName(),
                    line.getSecond(),
                    item.getPrice() * line.getSecond()
                );
            }

            System.out.println("--------------------------");

            System.out.printf(
                "Subtotal : Rs.%.2f%n", getPrice()
            );

            System.out.printf(
                "GST (5%%) : Rs.%.2f%n",
                total() - getPrice()
            );

            System.out.printf(
                "TOTAL    : Rs.%.2f%n", total()
            );
        }
    }
}

// ===================== MULTITHREADING (Module 4) =====================
class KitchenThread extends Thread {

    private final Order order;

    public KitchenThread(Order order) {
        this.order = order;
    }

    @Override
    public void run() {

        order.setStatus(OrderStatus.PREPARING);

        System.out.println(
            "Kitchen: preparing order #"
            + order.getOrderId() + "..."
        );

        try {

            Thread.sleep(2000);

            order.setStatus(OrderStatus.READY);

            System.out.println(
                "Kitchen: order #"
                + order.getOrderId() + " is ready!"
            );

        } catch (InterruptedException e) {

            System.out.println(
                "Kitchen preparation was interrupted."
            );

            Thread.currentThread().interrupt();
        }
    }
}

// ===================== RESTAURANT =====================
class Restaurant {

    private final Map<Integer, MenuItem> menu =
            new LinkedHashMap<>();

    private final List<Order> history =
            Collections.synchronizedList(new ArrayList<>());

    public void addToMenu(MenuItem item) {

        if (item == null) {
            throw new IllegalArgumentException(
                "Menu item cannot be null"
            );
        }

        menu.put(item.getId(), item);
    }

    public void addToHistory(Order order) {

        if (order != null) {
            history.add(order);
        }
    }

    // ORDER HISTORY
    public void showHistory() {

        System.out.println(
            "\n========== ORDER HISTORY =========="
        );

        if (history.isEmpty()) {
            System.out.println("No orders today.");
            return;
        }

        for (Order order : history) {

            System.out.printf(
                "Order #%d | %s | Table %d | Rs.%.2f "
                + "| Paid by %s | %s%n",

                order.getOrderId(),
                order.getCustomer().getName(),
                order.getCustomer().getTableNumber(),
                order.total(),
                order.getPaymentMode(),
                order.getStatus()
            );
        }
    }

    public MenuItem getItem(int id) {
        return menu.get(id);
    }

    public Collection<MenuItem> getMenu() {
        return Collections.unmodifiableCollection(menu.values());
    }

    public List<Order> getHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    // DISPLAY MENU
    public void showMenu() {

        System.out.println(
            "\n------------- MENU -------------"
        );

        for (Category category : Category.values()) {

            System.out.println("-- " + category + " --");

            for (MenuItem item : menu.values()) {

                if (item.getCategory() == category) {

                    System.out.printf(
                        "%d. %s  Rs.%.2f%n",
                        item.getId(),
                        item.describe(),
                        item.getPrice()
                    );
                }
            }
        }
    }
}

// ===================== MAIN =====================
public class RestaurantApp {

    public static void main(String[] args) {

        Restaurant restaurant = new Restaurant();

        restaurant.addToMenu(
            new VegItem("Paneer Tikka", 180, Category.STARTER)
        );

        restaurant.addToMenu(
            new NonVegItem("Chicken Wings", 220,
                    Category.STARTER, 4)
        );

        restaurant.addToMenu(
            new VegItem("Veg Biryani", 200, Category.MAIN_COURSE)
        );

        restaurant.addToMenu(
            new NonVegItem("Butter Chicken", 320,
                    Category.MAIN_COURSE, 3)
        );

        restaurant.addToMenu(
            new VegItem("Gulab Jamun", 80, Category.DESSERT)
        );

        restaurant.addToMenu(
            new VegItem("Masala Chai", 40, Category.DRINK)
        );

        try (Scanner scanner = new Scanner(System.in)) {

            System.out.println(
                "*** Welcome to Spice Garden ***"
            );

            String again = "y";

            while (again.equalsIgnoreCase("y")) {

                serveCustomer(restaurant, scanner);

                System.out.print(
                    "\nServe another customer? (y/n): "
                );

                if (!scanner.hasNextLine()) {
                    break;
                }

                again = scanner.nextLine().trim();
            }

            restaurant.showHistory();

            System.out.println(
                "\nRestaurant closed. Bye!"
            );
        }
    }

    // ===================== SERVE CUSTOMER =====================
    static void serveCustomer(
            Restaurant restaurant, Scanner scanner) {

        // STEP 1: CUSTOMER DETAILS

        Customer customer = null;

        while (customer == null) {

            try {

                System.out.print("Enter your name: ");

                if (!scanner.hasNextLine()) {
                    return;
                }

                String name = scanner.nextLine();

                System.out.print(
                    "Enter table number (1-"
                    + Customer.MAX_TABLES + "): "
                );

                if (!scanner.hasNextLine()) {
                    return;
                }

                int table = Integer.parseInt(
                    scanner.nextLine().trim()
                );

                customer = new Customer(name, table);

            } catch (NumberFormatException e) {

                System.out.println(
                    "Table number must be a number. Try again.\n"
                );

            } catch (IllegalArgumentException e) {

                System.out.println(
                    e.getMessage() + ". Try again.\n"
                );
            }
        }

        System.out.println(
            "\nHello " + customer.getName()
            + "! Table " + customer.getTableNumber()
            + " is ready."
        );

        // STEP 2: ORDERING

        Order order = new Order(customer);

        restaurant.showMenu();

        boolean ordering = true;

        while (ordering) {

            System.out.println(
                "\n--- What would you like to do? ---"
            );

            System.out.println(" 1. Add an item");
            System.out.println(" 2. View my order");
            System.out.println(" 3. Remove an item");
            System.out.println(" 4. Show menu again");
            System.out.println(" 5. Finish ordering and get bill");
            System.out.println(" 6. Cancel order");

            System.out.print("Your choice: ");

            if (!scanner.hasNextLine()) {
                order.cancelOrder();
                return;
            }

            try {

                int choice = Integer.parseInt(
                    scanner.nextLine().trim()
                );

                switch (choice) {

                    case 1: {

                        System.out.print("Enter item id: ");

                        if (!scanner.hasNextLine()) {
                            order.cancelOrder();
                            return;
                        }

                        int id = Integer.parseInt(
                            scanner.nextLine().trim()
                        );

                        MenuItem item = restaurant.getItem(id);

                        if (item == null) {
                            throw new IllegalArgumentException(
                                "No such item id: " + id
                            );
                        }

                        System.out.print("Quantity: ");

                        if (!scanner.hasNextLine()) {
                            order.cancelOrder();
                            return;
                        }

                        int quantity = Integer.parseInt(
                            scanner.nextLine().trim()
                        );

                        order.addItem(item, quantity);

                        System.out.println(
                            "Added " + quantity
                            + " x " + item.getName()
                        );

                        break;
                    }

                    case 2:

                        order.showCart();

                        break;

                    case 3:

                        order.showCart();

                        if (!order.isEmpty()) {

                            System.out.print(
                                "Enter the line number to remove: "
                            );

                            if (!scanner.hasNextLine()) {
                                order.cancelOrder();
                                return;
                            }

                            order.removeItem(
                                Integer.parseInt(
                                    scanner.nextLine().trim()
                                )
                            );

                            System.out.println("Item removed.");
                        }

                        break;

                    case 4:

                        restaurant.showMenu();

                        break;

                    case 5:

                        order.validateForBilling();

                        ordering = false;

                        break;

                    case 6:

                        order.cancelOrder();

                        System.out.println(
                            "Order cancelled. Goodbye, "
                            + customer.getName() + "!"
                        );

                        return;

                    default:

                        throw new IllegalArgumentException(
                            "Choose a number from 1 to 6"
                        );
                }

            } catch (NumberFormatException e) {

                System.out.println(
                    "Please enter numbers only."
                );

            } catch (InvalidOrderException e) {

                System.out.println(e.getMessage());

            } catch (IllegalArgumentException e) {

                System.out.println(
                    "Invalid input: " + e.getMessage()
                );
            }
        }

        // STEP 3: BILL

        Order.Bill bill = order.new Bill();

        bill.print();

        // STEP 4: PAYMENT

        Payment payment = null;

        while (payment == null) {

            System.out.println(
                "\nPayment options:\n"
                + " 1. Cash\n"
                + " 2. UPI\n"
                + " 3. Card"
            );

            System.out.print("Choose payment mode: ");

            if (!scanner.hasNextLine()) {
                return;
            }

            try {

                int choice = Integer.parseInt(
                    scanner.nextLine().trim()
                );

                double amount = order.total();

                switch (choice) {

                    case 1:

                        payment = new CashPayment(amount);

                        break;

                    case 2:

                        payment = new UpiPayment(amount);

                        break;

                    case 3:

                        payment = new CardPayment(amount);

                        break;

                    default:

                        throw new IllegalArgumentException(
                            "Choose 1, 2 or 3"
                        );
                }

            } catch (NumberFormatException e) {

                System.out.println(
                    "Please enter a valid number."
                );

            } catch (IllegalArgumentException e) {

                System.out.println(e.getMessage());
            }
        }

        payment.process();

        order.setPayment(payment);

        System.out.println(
            "\nBill paid! Order #"
            + order.getOrderId() + " confirmed."
        );

        // STEP 5: KITCHEN THREAD

        KitchenThread kitchen = new KitchenThread(order);

        kitchen.start();

        System.out.println(
            "Kitchen working? " + kitchen.isAlive()
        );

        try {

            kitchen.join();

        } catch (InterruptedException e) {

            System.out.println(
                "Main thread interrupted while waiting for the kitchen."
            );

            Thread.currentThread().interrupt();

            return;
        }

        restaurant.addToHistory(order);

        System.out.println(
            "Order status: " + order.getStatus()
        );

        System.out.println(
            "Thank you, " + customer.getName()
            + "! Enjoy your meal at table "
            + customer.getTableNumber() + "."
        );
    }
}